package dev.ponydash.obd

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ELM327 command layer: send one text command, read until the '>' prompt.
 * Every command goes through one lock, so the poll loop and the diagnostics
 * console can share the adapter safely.
 */
class Elm327(private val t: ByteTransport, private val onTraffic: (String, String) -> Unit = { _, _ -> }) {
    private val lock = Mutex()

    suspend fun send(cmd: String, timeoutMs: Long = 2_500): String = lock.withLock {
        while (t.incoming.tryReceive().isSuccess) { /* drop stale bytes */ }
        t.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val buf = StringBuilder()
        val raw = withTimeoutOrNull(timeoutMs) {
            var i = -1
            while (i < 0) {
                buf.append(String(t.incoming.receive(), Charsets.US_ASCII))
                i = buf.indexOf(">")
            }
            buf.substring(0, i)
        } ?: throw IOException("Adapter timeout on '$cmd' (got '${buf.toString().trim()}')")
        clean(raw, cmd).also { onTraffic(cmd, it) }
    }

    private fun clean(raw: String, cmd: String): String =
        raw.replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != cmd && !it.startsWith("SEARCHING") }
            .joinToString("\n")
}

/** OBD session on top of ELM327: setup, supported-PID discovery, reads. */
class ObdSession(val elm: Elm327, private val log: (String) -> Unit) {
    var supported: Set<Int> = emptySet()
        private set
    private var countSuffix = ""

    suspend fun init() {
        runCatching { elm.send("ATZ", 5_000) }
        delay(500)
        log("Adapter: " + elm.send("ATI"))
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) elm.send(c)
        // 2025 Mustang: ISO 15765-4 CAN, 11-bit, 500 kbps. Address the PCM directly
        // so only one module answers each request.
        elm.send("ATSP6")
        elm.send("ATSH7E0")
        supported = querySupported()
        if (supported.isEmpty()) {
            log("PCM silent on CAN 11/500 — retrying with auto protocol + broadcast")
            elm.send("ATSH7DF")
            header = "7DF"
            elm.send("ATSP0")
            supported = querySupported()
        }
        if (supported.isEmpty()) throw IOException("No ECU answered — is the ignition on?")
        log("Protocol: " + elm.send("ATDP"))
        log("Supported mode-01 PIDs: " + supported.sorted().joinToString(" ") { "%02X".format(it) })

        // "010C1" = expect one reply: the adapter returns as soon as the PCM
        // answers instead of waiting out its timeout. Not every clone supports it.
        val r = runCatching { elm.send("010C1") }.getOrDefault("")
        if (parseMode01(r, 0x0C) != null) {
            countSuffix = "1"
            log("Fast replies enabled (response-count suffix)")
        }
    }

    private suspend fun querySupported(): Set<Int> {
        val out = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            val resp = runCatching { elm.send("01%02X".format(base), 6_000) }.getOrNull() ?: break
            val b = parseMode01(resp, base) ?: break
            if (b.size < 4) break
            val bits = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
            for (i in 0 until 32) if (bits and (1 shl (31 - i)) != 0) out += base + i + 1
            if (base + 0x20 !in out) break
            base += 0x20
        }
        return out
    }

    /** Header the adapter is currently addressing (ATSH). */
    var header = "7E0"

    suspend fun setHeader(h: String) {
        if (h == header) return
        elm.send("ATSH$h")
        header = h
    }

    suspend fun readFord(f: FordPid): Map<String, Double>? {
        setHeader(f.header)
        val r = elm.send("22${f.did}$countSuffix")
        val prefix = "62${f.did}"
        for (line in r.lines()) {
            val hex = line.filter { it.isLetterOrDigit() }.uppercase()
            if (!hex.startsWith(prefix)) continue
            val data = hex.substring(prefix.length)
            if (data.length < 4 || data.length % 2 != 0 || data.any { it !in "0123456789ABCDEF" }) continue
            val b = IntArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16) }
            return f.channels.map { it.key }.zip(f.decode(b)).toMap()
        }
        return null
    }

    suspend fun read(p: Pid): Map<String, Double>? {
        setHeader("7E0")
        val r = elm.send("01%02X%s".format(p.code, countSuffix))
        val b = parseMode01(r, p.code) ?: return null
        if (b.size < p.bytes) return null
        return p.decodeMap(b)
    }
}

/** Find "41 <pid> data..." in an ELM reply (spaces/headers off) and return the data bytes. */
fun parseMode01(resp: String, pid: Int): IntArray? {
    val prefix = "41%02X".format(pid)
    for (line in resp.lines()) {
        val hex = line.filter { it.isLetterOrDigit() }.uppercase()
        if (!hex.startsWith(prefix)) continue
        val data = hex.substring(4)
        if (data.length % 2 != 0 || data.any { it !in "0123456789ABCDEF" }) continue
        return IntArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16) }
    }
    return null
}
