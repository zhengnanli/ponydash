package dev.ponydash

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.ponydash.obd.Pids
import dev.ponydash.sensors.GForce
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Samples of one channel; [v] is in stored (metric) units, NaN where there was no reading. */
class Series(val t: LongArray, val v: FloatArray, val now: Long = System.currentTimeMillis()) {
    companion object {
        val EMPTY = Series(LongArray(0), FloatArray(0))
    }
}

/**
 * Rolling flight recorder for the last hour of driving.
 *
 * Every 100 ms while the car is sending data it writes one CSV row holding the
 * latest value of every channel plus the G-meter. Events (log lines and raw
 * ELM327 traffic) go to a matching .log file. Files are 5-minute segments in
 * `Android/data/dev.ponydash/files/flight/`; segments beyond one hour of
 * recorded time are deleted. Time with the car off does not count, so the last
 * hour of the previous drive survives until the next one replaces it.
 *
 * The last hour is also kept in memory at the full 10 Hz for the zoomed plots.
 */
class FlightRecorder(
    private val context: Context,
    scope: CoroutineScope,
    private val values: StateFlow<Map<String, Double>>,
    private val g: StateFlow<GForce>,
    private val conn: StateFlow<ConnState>,
) {
    companion object {
        const val KEEP_MS = 60 * 60_000L
        private const val SEG_MS = 5 * 60_000L
        /** No data for this long closes the segment, so idle time never counts toward the hour. */
        private const val GAP_MS = 30_000L
        private const val PERIOD_MS = 100L
        private const val RING_EVERY = 1
        private const val RING_MAX = (KEEP_MS / (PERIOD_MS * RING_EVERY)).toInt()
        private val NAME = Regex("""pd_(\d{8}_\d{6})\.csv""")
    }

    val dir = File(context.getExternalFilesDir(null), "flight").apply { mkdirs() }

    private val keys = Pids.channels.map { it.key } + listOf("g_lat", "g_lon")
    private val header = buildString {
        append("time,epoch_ms,link")
        for (c in Pids.channels) {
            append(',').append(c.key)
            if (c.unit.isNotEmpty()) append('[').append(c.unit).append(']')
        }
        append(",g_lat[g],g_lon[g]")
    }

    private val zone = ZoneId.systemDefault()
    private val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
    private val nameFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(zone)

    private val io = Dispatchers.IO.limitedParallelism(1)
    private val incoming = ConcurrentLinkedQueue<String>()
    /** Events seen while no segment is open; written at the top of the next one. */
    private val pending = ArrayDeque<String>()
    private val ring = ArrayDeque<Pair<Long, FloatArray>>()

    private var csv: BufferedWriter? = null
    private var evt: BufferedWriter? = null
    private var segName = ""
    private var segStart = 0L
    private var lastRow = 0L
    private var closedMs = 0L

    /** Milliseconds of driving currently on disk. */
    val storedMs = MutableStateFlow(0L)

    init {
        scope.launch(io) {
            try {
                closedMs = prune()
                storedMs.value = closedMs
                var n = 0L
                while (isActive) {
                    val t0 = System.currentTimeMillis()
                    try {
                        tick(t0, n % RING_EVERY == 0L, n % 10 == 0L)
                    } catch (e: Exception) {
                        android.util.Log.w("PonyDash", "recorder: ${e.message}")
                        closeSeg()
                    }
                    n++
                    delay((PERIOD_MS - (System.currentTimeMillis() - t0)).coerceAtLeast(10))
                }
            } finally {
                withContext(NonCancellable) { closeSeg() }
            }
        }
    }

    /** Queue a log line or traffic line; safe from any thread. */
    fun event(text: String) {
        incoming.add("${tsFmt.format(Instant.now())}  $text")
    }

    private fun tick(now: Long, toRing: Boolean, flush: Boolean) {
        val link = conn.value.link
        // The saved hour on disk is real driving only: sim or replay would push it
        // out. The in-memory ring behind the zoom plots follows whatever is on
        // screen, so the plots work in the simulator too. Replay has its own source.
        val toDisk = link != Link.REPLAY && link != Link.SIM
        val v = if (link == Link.REPLAY) emptyMap() else values.value
        if (v.isNotEmpty()) {
            val gf = g.value
            val last = keys.size - 1
            val row = FloatArray(keys.size) { i ->
                when (i) {
                    last - 1 -> gf.lat
                    last -> gf.lon
                    else -> v[keys[i]]?.toFloat() ?: Float.NaN
                }
            }
            if (toDisk) {
                if (csv == null || now - lastRow > GAP_MS || now - segStart > SEG_MS) rotate(now)
                val sb = StringBuilder(600)
                sb.append(tsFmt.format(Instant.ofEpochMilli(now))).append(',').append(now)
                    .append(',').append(link.name.lowercase())
                for (i in keys.indices) {
                    sb.append(',')
                    val d = if (i >= last - 1) row[i].toDouble() else v[keys[i]]
                    if (d != null && !d.isNaN()) sb.append(num(d))
                }
                csv!!.write(sb.toString())
                csv!!.newLine()
                lastRow = now
                storedMs.value = closedMs + (now - segStart)
            } else if (csv != null) {
                closeSeg() // left the car for sim; stop the disk segment but keep filling the ring
            }
            if (toRing) synchronized(ring) {
                ring.addLast(now to row)
                while (ring.size > RING_MAX || (ring.isNotEmpty() && ring.first().first < now - KEEP_MS)) ring.removeFirst()
            }
        } else if (csv != null && now - lastRow > GAP_MS) {
            closeSeg()
        }

        while (true) {
            val e = incoming.poll() ?: break
            val w = evt
            if (w != null) {
                w.write(e); w.newLine()
            } else {
                pending.addLast(e)
                while (pending.size > 400) pending.removeFirst()
            }
        }
        if (flush) {
            csv?.flush()
            evt?.flush()
        }
    }

    private fun num(d: Double): String {
        val r = Math.round(d * 10_000) / 10_000.0
        return if (r == Math.rint(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
    }

    private fun rotate(now: Long) {
        closeSeg()
        segStart = now
        lastRow = now
        val base = "pd_" + nameFmt.format(Instant.ofEpochMilli(now))
        segName = "$base.csv"
        csv = File(dir, segName).bufferedWriter().also { it.write(header); it.newLine() }
        evt = File(dir, "$base.log").bufferedWriter().also { w ->
            pending.forEach { w.write(it); w.newLine() }
            pending.clear()
        }
        closedMs = prune()
    }

    private fun closeSeg() {
        runCatching { csv?.close() }
        runCatching { evt?.close() }
        csv = null
        evt = null
        segName = ""
    }

    private fun segments(): List<File> =
        dir.listFiles { f -> NAME.matches(f.name) }?.sortedBy { it.name }.orEmpty()

    private fun startOf(f: File): Long? = NAME.matchEntire(f.name)?.groupValues?.get(1)?.let {
        runCatching { Instant.from(nameFmt.parse(it)).toEpochMilli() }.getOrNull()
    }

    /** Delete segments past the newest hour of recorded time; returns the kept duration, excluding the open one. */
    private fun prune(): Long {
        var acc = 0L
        var kept = 0L
        for (f in segments().asReversed()) {
            if (f.name == segName) continue
            val start = startOf(f) ?: continue
            val dur = (f.lastModified() - start).coerceIn(0, SEG_MS + GAP_MS)
            if (acc >= KEEP_MS) {
                f.delete()
                File(dir, f.name.removeSuffix(".csv") + ".log").delete()
            } else {
                kept += dur
            }
            acc += dur
        }
        return kept
    }

    /** The last [spanMs] of one channel from the in-memory 10 Hz buffer (no averaging). */
    fun series(key: String, spanMs: Long): Series {
        val i = keys.indexOf(key)
        if (i < 0) return Series.EMPTY
        val from = System.currentTimeMillis() - spanMs
        synchronized(ring) {
            val sel = ring.filter { it.first >= from }
            return Series(LongArray(sel.size) { sel[it].first }, FloatArray(sel.size) { sel[it].second[i] })
        }
    }

    /** Merge the recorded hour into Download/PonyDash/ as one CSV and one log. Returns a status line. */
    suspend fun exportLastHour(): String = withContext(io) {
        csv?.flush()
        evt?.flush()
        val files = segments()
        if (files.isEmpty()) return@withContext "Nothing recorded yet"
        val stamp = nameFmt.format(Instant.now())
        val r = context.contentResolver

        fun save(name: String, mime: String, body: (OutputStream) -> Unit) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PonyDash")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("could not create $name")
            r.openOutputStream(uri)!!.use(body)
            r.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }

        var rows = 0
        save("ponydash_$stamp.csv", "text/csv") { os ->
            val w = os.bufferedWriter()
            var hdr: String? = null
            for (f in files) f.bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, l ->
                    if (idx == 0) {
                        if (l != hdr) { w.write(l); w.newLine(); hdr = l }
                    } else if (l.isNotEmpty()) {
                        w.write(l); w.newLine(); rows++
                    }
                }
            }
            w.flush()
        }
        save("ponydash_${stamp}_events.txt", "text/plain") { os ->
            val w = os.bufferedWriter()
            for (f in files) {
                val log = File(dir, f.name.removeSuffix(".csv") + ".log")
                if (!log.exists()) continue
                w.write("# ---- ${log.name} ----"); w.newLine()
                log.bufferedReader().useLines { it.forEach { l -> w.write(l); w.newLine() } }
            }
            w.flush()
        }
        "Saved Download/PonyDash/ponydash_$stamp.csv ($rows rows, ${"%.0f".format(rows / 600.0)} min)"
    }
}
