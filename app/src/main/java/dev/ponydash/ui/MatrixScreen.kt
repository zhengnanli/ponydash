package dev.ponydash.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ponydash.DashViewModel
import dev.ponydash.Link
import dev.ponydash.cToF
import dev.ponydash.kmhToMph
import dev.ponydash.kpaToPsi
import dev.ponydash.obd.Channel
import dev.ponydash.obd.Kind
import dev.ponydash.obd.Pids
import dev.ponydash.sensors.GForce
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.delay

private object M {
    val Bg = Color(0xFF020805)
    val Green = Color(0xFF00FF66)
    val Mid = Color(0xFF00B347)
    val Dim = Color(0xFF0B5E2E)
    val Faint = Color(0xFF06351A)
    val Cell = Color(0xCC03110A)
    val Mono = FontFamily.Monospace
    val glow = Shadow(Color(0xFF00FF66), Offset.Zero, 14f)
}

/** Convert a stored (metric) value for display; returns value, unit, decimals. */
private fun display(ch: Channel, v: Double, metric: Boolean): Triple<Double, String, Int> = when (ch.kind) {
    Kind.TEMP -> if (metric) Triple(v, "°C", 0) else Triple(cToF(v), "°F", 0)
    Kind.PRESSURE -> if (metric) Triple(v, "kPa", 0) else Triple(kpaToPsi(v), "psi", 1)
    Kind.SPEED -> if (metric) Triple(v, "km/h", 0) else Triple(kmhToMph(v), "mph", 0)
    Kind.DISTANCE -> if (metric) Triple(v, "km", ch.decimals) else Triple(v * 0.621371, "mi", ch.decimals)
    Kind.PLAIN -> Triple(v, ch.unit, ch.decimals)
}

private fun fmt(v: Double, d: Int) = "%.${d}f".format(v)

@Composable
fun MatrixScreen(vm: DashViewModel, onClose: () -> Unit) {
    val values by vm.values.collectAsState()
    val hist by vm.history.collectAsState()
    val ext by vm.extremes.collectAsState()
    val traffic by vm.traffic.collectAsState()
    val rate by vm.pollRate.collectAsState()
    val conn by vm.conn.collectAsState()
    val metric by vm.metric.collectAsState()

    val shown = Pids.channels.filter { it.key in values }

    Box(Modifier.fillMaxSize().background(M.Bg)) {
        DigitalRain(Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PONYDASH://OBD",
                        style = TextStyle(color = M.Green, fontSize = 26.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold, shadow = M.glow),
                    )
                    Spacer(Modifier.width(18.dp))
                    Text(
                        "${shown.size} ch · %.1f req/s · ${conn.link.name.lowercase()} ${conn.device ?: ""}".format(rate),
                        color = M.Mid, fontSize = 15.sp, fontFamily = M.Mono,
                    )
                    Spacer(Modifier.weight(1f))
                    MChip("reset min/max", vm::resetExtremes)
                    Spacer(Modifier.width(10.dp))
                    MChip("[ dash ]", onClose)
                }
                Spacer(Modifier.height(12.dp))
                if (shown.isEmpty()) {
                    Text("waiting for data…", color = M.Mid, fontSize = 18.sp, fontFamily = M.Mono)
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(205.dp),
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.key }) { ch ->
                        MatrixCell(ch, values[ch.key], hist[ch.key], ext[ch.key], metric)
                    }
                }
            }
            Column(Modifier.width(330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val g by vm.gmeter.state.collectAsState()
                GPanel(g, vm.gmeter::calibrate, Modifier.fillMaxWidth())
                Terminal(traffic, conn.link, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun MatrixCell(ch: Channel, v: Double?, hist: List<Float>?, mm: Pair<Double, Double>?, metric: Boolean) {
    Column(
        Modifier
            .background(M.Cell, RoundedCornerShape(6.dp))
            .border(1.dp, M.Faint, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(ch.label.uppercase(), color = M.Mid, fontSize = 12.sp, fontFamily = M.Mono, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            val (dv, unit, dec) = if (v != null) display(ch, v, metric) else Triple(0.0, "", 0)
            Text(
                if (v != null) fmt(dv, dec) else "----",
                style = TextStyle(color = M.Green, fontSize = 30.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold, shadow = M.glow),
            )
            Text(" $unit", color = M.Mid, fontSize = 14.sp, fontFamily = M.Mono, modifier = Modifier.padding(bottom = 5.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val h = hist ?: return@Canvas
            if (h.size < 2) return@Canvas
            val lo = h.min()
            val hi = h.max()
            val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
            val path = Path()
            h.forEachIndexed { i, y ->
                val x = size.width * i / (120 - 1).coerceAtLeast(1)
                val py = size.height - (y - lo) / span * size.height
                if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
            }
            drawPath(path, M.Green.copy(alpha = 0.85f), style = Stroke(2f))
        }
        val mmText = mm?.let { (lo, hi) ->
            val a = display(ch, lo, metric)
            val b = display(ch, hi, metric)
            "min ${fmt(a.first, a.third)}  max ${fmt(b.first, b.third)}"
        } ?: " "
        Text(mmText, color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono, maxLines = 1)
    }
}

@Composable
private fun Terminal(lines: List<String>, link: Link, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Column(
        modifier
            .background(Color(0xE6010603), RoundedCornerShape(6.dp))
            .border(1.dp, M.Dim, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Text("ELM327 TRAFFIC", color = M.Mid, fontSize = 13.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold)
        Text(
            if (link == Link.SIM) "simulated" else "live · ATSH7E0 · CAN 11/500",
            color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(lines) { l ->
                Text(
                    l,
                    color = if (l.startsWith(">")) M.Mid else M.Green,
                    fontSize = 13.sp,
                    fontFamily = M.Mono,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = M.Green,
        fontSize = 14.sp,
        fontFamily = M.Mono,
        modifier = Modifier
            .border(1.dp, M.Mid, RoundedCornerShape(4.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Low-key digital rain behind the data, ~20 fps. */
@Composable
private fun DigitalRain(modifier: Modifier) {
    val glyphs = remember { "0123456789ABCDEFｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜ".toCharArray() }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(50); tick.longValue++ } }
    val state = remember { RainState() }
    val paint = remember {
        Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = true; color = M.Green.toArgb() }
    }
    Canvas(modifier) {
        tick.longValue // read to redraw each tick
        val cell = 26f
        paint.textSize = cell * 0.9f
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        state.ensure(cols, rows)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            for (x in 0 until cols) {
                state.heads[x] += state.speeds[x]
                if (state.heads[x] - 18 > rows) {
                    state.heads[x] = -Random.nextInt(0, rows).toFloat()
                    state.speeds[x] = Random.nextFloat() * 0.5f + 0.25f
                }
                val head = state.heads[x].toInt()
                for (k in 0 until 18) {
                    val y = head - k
                    if (y < 0 || y >= rows) continue
                    if (Random.nextInt(40) == 0) state.chars[x][y] = glyphs[Random.nextInt(glyphs.size)]
                    val a = if (k == 0) 0.55f else 0.28f * (1f - k / 18f)
                    paint.alpha = (a * 255).toInt()
                    nc.drawText(state.chars[x][y].toString(), x * cell, (y + 1) * cell, paint)
                }
            }
        }
    }
}

private class RainState {
    var cols = 0
    var heads = FloatArray(0)
    var speeds = FloatArray(0)
    var chars: Array<CharArray> = emptyArray()

    fun ensure(c: Int, r: Int) {
        if (c == cols && chars.isNotEmpty() && chars[0].size == r) return
        cols = c
        heads = FloatArray(c) { -Random.nextInt(0, r).toFloat() }
        speeds = FloatArray(c) { Random.nextFloat() * 0.5f + 0.25f }
        val g = "0123456789ABCDEF"
        chars = Array(c) { CharArray(r) { g[Random.nextInt(g.length)] } }
    }
}

/** Friction circle + scrolling lat/lon traces, in Matrix green. */
@Composable
private fun GPanel(g: GForce, onZero: () -> Unit, modifier: Modifier) {
    val lat = Color(0xFF00FF66)
    val lon = Color(0xFF7CFFCB)
    Column(
        modifier
            .background(Color(0xE6010603), RoundedCornerShape(6.dp))
            .border(1.dp, M.Dim, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("G-FORCE", color = M.Mid, fontSize = 13.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (g.calibrated) "[re-zero]" else "[zero]",
                color = M.Green, fontSize = 12.sp, fontFamily = M.Mono,
                modifier = Modifier.clickableNoRipple(onZero),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(130.dp).height(130.dp)) {
                val r = size.minDimension / 2
                val c = Offset(size.width / 2, size.height / 2)
                for (ring in listOf(0.25f, 0.5f, 0.75f, 1f)) drawCircle(M.Faint, r * ring, c, style = Stroke(1.5f))
                drawLine(M.Faint, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
                drawLine(M.Faint, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
                fun pt(la: Float, lo: Float) = Offset(c.x + la.coerceIn(-1.2f, 1.2f) * r, c.y - lo.coerceIn(-1.2f, 1.2f) * r)
                g.trail.forEachIndexed { i, (la, lo) ->
                    drawCircle(M.Green.copy(alpha = 0.1f + 0.5f * (i + 1f) / g.trail.size), 3f, pt(la, lo))
                }
                drawCircle(M.Green, 7f, pt(g.lat, g.lon))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "%.2f g".format(sqrt(g.lat * g.lat + g.lon * g.lon)),
                    style = TextStyle(color = M.Green, fontSize = 28.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold, shadow = M.glow),
                )
                Text("lat %+.2f".format(g.lat), color = lat, fontSize = 13.sp, fontFamily = M.Mono)
                Text("lon %+.2f".format(g.lon), color = lon, fontSize = 13.sp, fontFamily = M.Mono)
                Spacer(Modifier.height(6.dp))
                Text("peak lat %.2f".format(g.peakLat), color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono)
                Text("peak acc %.2f".format(g.peakAccel), color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono)
                Text("peak brk %.2f".format(g.peakBrake), color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono)
            }
        }
        Spacer(Modifier.height(8.dp))
        // time graph: ±1 g, newest on the right, ~12 s window
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val mid = size.height / 2
            val scale = size.height / 2 / 1.0f
            for (y in listOf(-0.5f, 0f, 0.5f)) {
                drawLine(M.Faint, Offset(0f, mid - y * scale), Offset(size.width, mid - y * scale), if (y == 0f) 1.5f else 1f)
            }
            val h = g.history
            if (h.size >= 2) {
                val dx = size.width / (300 - 1)
                val x0 = size.width - (h.size - 1) * dx
                val pLat = Path()
                val pLon = Path()
                h.forEachIndexed { i, (la, lo) ->
                    val x = x0 + i * dx
                    val yl = mid - la.coerceIn(-1f, 1f) * scale
                    val yo = mid - lo.coerceIn(-1f, 1f) * scale
                    if (i == 0) { pLat.moveTo(x, yl); pLon.moveTo(x, yo) } else { pLat.lineTo(x, yl); pLon.lineTo(x, yo) }
                }
                drawPath(pLon, lon.copy(alpha = 0.8f), style = Stroke(2f))
                drawPath(pLat, lat, style = Stroke(2f))
            }
        }
        Row {
            Text("— lat", color = lat, fontSize = 11.sp, fontFamily = M.Mono)
            Spacer(Modifier.width(10.dp))
            Text("— lon", color = lon, fontSize = 11.sp, fontFamily = M.Mono)
            Spacer(Modifier.weight(1f))
            Text("±1 g · 12 s", color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono)
        }
    }
}
