package dev.ponydash

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/** A recording that can be replayed: the rolling recorder, a saved hour, or a CSV pushed into files/replay/. */
class Recording(val name: String, val detail: String, val sortKey: Long, val open: () -> List<InputStream>)

/** A loaded recording: one row per 100 ms sample, NaN where the car sent nothing. */
class ReplayData(val name: String, val t: LongArray, val keys: List<String>, val cols: Array<FloatArray>) {
    val size get() = t.size

    /** Boost for recordings made before it was its own column. */
    private val boostCol: FloatArray? by lazy {
        val m = col(dev.ponydash.obd.K.MAP) ?: return@lazy null
        val b = col(dev.ponydash.obd.K.BARO)
        FloatArray(size) { i -> m[i] - (b?.get(i)?.takeIf { !it.isNaN() } ?: 101.3f) }
    }
    fun col(key: String): FloatArray? = keys.indexOf(key).takeIf { it >= 0 }?.let { cols[it] }

    /** Index of the last row at or before [ms]. */
    fun indexAt(ms: Long): Int {
        var lo = 0
        var hi = t.size - 1
        if (ms <= t[0]) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (t[mid] <= ms) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun row(i: Int): Map<String, Double> {
        val m = HashMap<String, Double>(keys.size)
        for (k in keys.indices) {
            val v = cols[k][i]
            if (!v.isNaN() && keys[k] != "g_lat" && keys[k] != "g_lon") m[keys[k]] = v.toDouble()
        }
        return m
    }

    /** The [spanMs] before [now], for the zoomed plot. */
    fun series(key: String, now: Long, spanMs: Long): Series {
        val c = col(key) ?: (if (key == dev.ponydash.obd.K.BOOST) boostCol else null) ?: return Series.EMPTY
        val end = indexAt(now)
        var start = indexAt(now - spanMs)
        if (t[start] < now - spanMs && start < end) start++
        val n = end - start + 1
        return Series(LongArray(n) { t[start + it] }, FloatArray(n) { c[start + it] }, now)
    }

    companion object {
        /** Parse recorder CSV text (one or more segments; repeated headers are skipped). */
        fun parse(name: String, streams: List<InputStream>): ReplayData {
            var keys: List<String>? = null
            var epochCol = -1
            var linkCol = -1
            val times = ArrayList<Long>(40_000)
            val rows = ArrayList<FloatArray>(40_000)
            val links = ArrayList<Boolean>(40_000)
            for (s in streams) s.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    if (line.startsWith("time,")) {
                        val h = line.split(',')
                        if (keys == null) {
                            epochCol = h.indexOf("epoch_ms")
                            linkCol = h.indexOf("link")
                            keys = h.drop(3).map { it.substringBefore('[') }
                        }
                        continue
                    }
                    val k = keys ?: continue
                    val f = line.split(',')
                    if (f.size < 3 + k.size) continue
                    val t = f[epochCol].toLongOrNull() ?: continue
                    times += t
                    links += f[linkCol] != "sim"
                    rows += FloatArray(k.size) { i -> f[3 + i].toFloatOrNull() ?: Float.NaN }
                }
            }
            val k = keys ?: error("not a PonyDash recording")
            // Prefer the real car: drop simulator rows when there is live data.
            val keep = if (links.any { it }) links.indices.filter { links[it] } else links.indices.toList()
            val order = keep.sortedBy { times[it] }
            if (order.isEmpty()) error("recording is empty")
            val t = LongArray(order.size) { times[order[it]] }
            val cols = Array(k.size) { c -> FloatArray(order.size) { rows[order[it]][c] } }
            return ReplayData(name, t, k, cols)
        }

        fun list(context: Context, flightDir: File): List<Recording> {
            val out = ArrayList<Recording>()
            val segs = flightDir.listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.name }.orEmpty()
            if (segs.isNotEmpty()) {
                val mb = segs.sumOf { it.length() } / 1e6
                out += Recording("Last hour (rolling recorder)", "${segs.size} segments · %.1f MB".format(mb), Long.MAX_VALUE) {
                    segs.map { it.inputStream() }
                }
            }
            val pushed = File(context.getExternalFilesDir(null), "replay").apply { mkdirs() }
            pushed.listFiles { f -> f.name.endsWith(".csv") }?.forEach { f ->
                out += Recording(f.name.removeSuffix(".csv"), "replay folder · %.1f MB".format(f.length() / 1e6), f.lastModified()) {
                    listOf(f.inputStream())
                }
            }
            // "save hour" exports this app wrote to Download/PonyDash/
            val cr = context.contentResolver
            val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED)
            runCatching {
                cr.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("%PonyDash%", "%.csv"), null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uri: Uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                        val nm = c.getString(1).removeSuffix(".csv")
                        out += Recording(nm, "saved hour · %.1f MB".format(c.getLong(2) / 1e6), c.getLong(3) * 1000) {
                            listOf(cr.openInputStream(uri) ?: error("cannot open $nm"))
                        }
                    }
                }
            }
            return out.sortedByDescending { it.sortKey }
        }
    }
}

data class ReplayState(
    val name: String,
    val start: Long,
    val end: Long,
    val pos: Long,
    val playing: Boolean,
    val speed: Int,
    val loading: Boolean = false,
)
