package dev.ponydash.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** Matrix colours, all derived from the theme's Matrix hue (dark ground, or light ground for Daylight). */
private object M {
    private val light get() = C.palette.matrixLight
    private val ground get() = if (light) Color.White else Color.Black
    private val ink get() = if (light) lerp(C.Matrix, Color.Black, 0.25f) else C.Matrix
    private fun shade(f: Float) = lerp(ink, ground, f)
    val Bg get() = shade(0.96f)
    val Green get() = ink
    val Mid get() = shade(0.3f)
    val Dim get() = shade(if (light) 0.5f else 0.48f)
    val Faint get() = shade(if (light) 0.72f else 0.8f)
    val Cell get() = shade(if (light) 0.985f else 0.93f).copy(alpha = 0.93f)
    val Panel get() = shade(0.985f).copy(alpha = 0.92f)
    val Scrim get() = ground.copy(alpha = if (light) 0.6f else 0.85f)
    /** Second trace colour (longitudinal g). */
    val Alt get() = lerp(ink, if (light) Color.Black else Color.White, 0.45f)
    val RainAlpha get() = if (light) 0.6f else 0.75f
    val Mono get() = Fonts.Mono
    val glow get() = if (light) Shadow(Color.Transparent, Offset.Zero, 0f) else Shadow(C.Matrix, Offset.Zero, 14f)
}

/** Convert a stored (metric) value for display; returns value, unit, decimals. */
private fun display(ch: Channel, v: Double, metric: Boolean): Triple<Double, String, Int> = when (ch.kind) {
    Kind.TEMP -> if (metric) Triple(v, "°C", 0) else Triple(cToF(v), "°F", 0)
    // bar / psi everywhere in the app; fuel-rail pressure is ~50–200 bar, so whole numbers
    Kind.PRESSURE -> if (metric) Triple(v / 100, "bar", if (ch.key == "rail_kpa") 0 else 2)
                     else Triple(kpaToPsi(v), "psi", if (ch.key == "rail_kpa") 0 else 1)
    Kind.SPEED -> if (metric) Triple(v, "km/h", 0) else Triple(kmhToMph(v), "mph", 0)
    Kind.DISTANCE -> if (metric) Triple(v, "km", ch.decimals) else Triple(v * 0.621371, "mi", ch.decimals)
    Kind.PLAIN -> Triple(v, ch.unit, ch.decimals)
}

private fun fmt(v: Double, d: Int) = "%.${d}f".format(v)

@Composable
fun MatrixScreen(vm: DashViewModel, onClose: () -> Unit, onRace: () -> Unit) {
    val values by vm.values.collectAsState()
    val hist by vm.history.collectAsState()
    val ext by vm.extremes.collectAsState()
    val traffic by vm.traffic.collectAsState()
    val rate by vm.pollRate.collectAsState()
    val conn by vm.conn.collectAsState()
    val metric by vm.metric.collectAsState()
    val recMs by vm.recorder.storedMs.collectAsState()
    val exportMsg by vm.exportMsg.collectAsState()
    var zoom by remember { mutableStateOf<String?>(null) }

    val shown = Pids.channels.filter { it.key in values }

    BoxWithConstraints(Modifier.fillMaxSize().background(M.Bg)) {
        val narrow = maxWidth < 1150.dp
        DigitalRain(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(if (narrow) 10.dp else 16.dp)) {
            Row(
                Modifier.background(M.Bg.copy(alpha = 0.9f), RoundedCornerShape(6.dp)).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "PONYDASH://OBD",
                    style = TextStyle(textMotion = Fonts.motion, color = M.Green, fontSize = if (narrow) 18.sp else 26.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold, shadow = M.glow),
                    maxLines = 1, softWrap = false,
                )
                Spacer(Modifier.width(if (narrow) 10.dp else 18.dp))
                Text(
                    "${shown.size} ch · %.1f req/s · ${conn.link.name.lowercase()} ${conn.device ?: ""}".format(rate),
                    color = M.Mid, fontSize = if (narrow) 12.sp else 15.sp, fontFamily = M.Mono,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    exportMsg ?: "● rec ${recMs / 60_000}m",
                    color = if (exportMsg != null) M.Green else M.Mid, fontSize = 13.sp, fontFamily = M.Mono, maxLines = 1, softWrap = false,
                )
                Spacer(Modifier.width(8.dp))
                MChip(if (narrow) "save" else "save hour", vm::exportLastHour)
                Spacer(Modifier.width(8.dp))
                MChip(if (narrow) "reset" else "reset min/max", vm::resetExtremes)
                Spacer(Modifier.width(8.dp))
                MChip("[race]", onRace)
                Spacer(Modifier.width(8.dp))
                MChip("[dash]", onClose)
            }
            Spacer(Modifier.height(if (narrow) 8.dp else 12.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 14.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (shown.isEmpty()) {
                        Text(
                            when (conn.link) {
                                Link.CONNECTING -> "connecting to the adapter…"
                                Link.RETRYING -> "no data · obd: ${conn.detail}"
                                Link.IDLE -> "no data · not connected"
                                else -> "waiting for data…"
                            },
                            color = M.Green, fontSize = 18.sp, fontFamily = M.Mono,
                            modifier = Modifier.background(M.Bg.copy(alpha = 0.9f), RoundedCornerShape(6.dp)).border(1.dp, M.Dim, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    LazyVerticalGrid(
                        GridCells.Adaptive(if (narrow) 225.dp else 290.dp),
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(shown, key = { it.key }) { ch ->
                            MatrixCell(ch, values[ch.key], hist[ch.key], ext[ch.key], metric, narrow) { zoom = ch.key }
                        }
                    }
                }
                Column(Modifier.width(if (narrow) 250.dp else 330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val g by vm.gmeter.state.collectAsState()
                    GPanel(g, vm.gmeter::calibrate, narrow, Modifier.fillMaxWidth())
                    Terminal(traffic, conn.link, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
        zoom?.let { key -> Pids.channelByKey[key] }?.let { ch ->
            ZoomPanel(vm, ch, values[ch.key], metric) { zoom = null }
        }
    }
}

@Composable
private fun MatrixCell(ch: Channel, v: Double?, hist: List<Float>?, mm: Pair<Double, Double>?, metric: Boolean, narrow: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clickableNoRipple(onClick)
            .background(M.Cell, RoundedCornerShape(6.dp))
            .border(1.dp, M.Faint, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(ch.label.uppercase(), color = M.Mid, fontSize = if (narrow) 14.sp else 16.sp, fontFamily = M.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            val (dv, unit, dec) = if (v != null) display(ch, v, metric) else Triple(0.0, "", 0)
            Text(
                if (v != null) fmt(dv, dec) else "----",
                style = TextStyle(textMotion = Fonts.motion, color = M.Green, fontSize = if (narrow) 40.sp else 50.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold, shadow = M.glow),
                maxLines = 1,
            )
            Text(" $unit", color = M.Mid, fontSize = if (narrow) 17.sp else 20.sp, fontFamily = M.Mono, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(if (narrow) 34.dp else 44.dp)) {
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
            drawPath(path, M.Green.copy(alpha = 0.85f), style = Stroke(2.5f))
        }
        val mmText = mm?.let { (lo, hi) ->
            val a = display(ch, lo, metric)
            val b = display(ch, hi, metric)
            "min ${fmt(a.first, a.third)}  max ${fmt(b.first, b.third)}"
        } ?: " "
        Text(mmText, color = M.Dim, fontSize = if (narrow) 13.sp else 15.sp, fontFamily = M.Mono, maxLines = 1)
    }
}

@Composable
private fun Terminal(lines: List<String>, link: Link, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Column(
        modifier
            .background(M.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, M.Dim, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Text("ELM327 TRAFFIC", color = M.Mid, fontSize = 13.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold)
        Text(
            when (link) { Link.SIM -> "simulated"; Link.REPLAY -> "replay (traffic not recorded)"; else -> "live · ATSH7E0 · CAN 11/500" },
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
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(M.Bg, RoundedCornerShape(4.dp))
            .border(1.dp, M.Mid, RoundedCornerShape(4.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Low-key digital rain behind the data, ~10 fps (it was a noticeable share of the drawing cost at 20). */
@Composable
private fun DigitalRain(modifier: Modifier) {
    val glyphs = remember { "0123456789ABCDEFｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜ".toCharArray() }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(100); tick.longValue++ } }
    val state = remember { RainState() }
    val paint = remember {
        Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = true; color = M.Green.toArgb() }
    }
    Canvas(modifier) {
        tick.longValue // read to redraw each tick
        paint.color = M.Green.toArgb()
        val cell = 26f
        paint.textSize = cell * 0.9f
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        state.ensure(cols, rows)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            for (x in 0 until cols) {
                state.heads[x] += state.speeds[x] * 2
                if (state.heads[x] - 18 > rows) {
                    state.heads[x] = -Random.nextInt(0, rows).toFloat()
                    state.speeds[x] = Random.nextFloat() * 0.5f + 0.25f
                }
                val head = state.heads[x].toInt()
                for (k in 0 until 18) {
                    val y = head - k
                    if (y < 0 || y >= rows) continue
                    if (Random.nextInt(40) == 0) state.chars[x][y] = glyphs[Random.nextInt(glyphs.size)]
                    val a = (if (k == 0) 0.55f else 0.28f * (1f - k / 18f)) * M.RainAlpha
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
private fun GPanel(g: GForce, onZero: () -> Unit, narrow: Boolean, modifier: Modifier) {
    val lat = M.Green
    val lon = M.Alt
    Column(
        modifier
            .background(M.Panel, RoundedCornerShape(6.dp))
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
            Canvas(Modifier.width(if (narrow) 96.dp else 130.dp).height(if (narrow) 96.dp else 130.dp)) {
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
                    style = TextStyle(textMotion = Fonts.motion, color = M.Green, fontSize = if (narrow) 24.sp else 28.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold, shadow = M.glow),
                    maxLines = 1, softWrap = false,
                )
                Text("lat %+.2f".format(g.lat), color = lat, fontSize = 13.sp, fontFamily = M.Mono)
                Text("lon %+.2f".format(g.lon), color = lon, fontSize = 13.sp, fontFamily = M.Mono)
                Spacer(Modifier.height(6.dp))
                Text("peak lat %.2f".format(g.peakLat), color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono, maxLines = 1, softWrap = false)
                Text("peak acc %.2f".format(g.peakAccel), color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono, maxLines = 1, softWrap = false)
                Text("peak brk %.2f".format(g.peakBrake), color = M.Dim, fontSize = 11.sp, fontFamily = M.Mono, maxLines = 1, softWrap = false)
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

private val ZOOM_SPANS = listOf(
    Triple("1 min", 60_000L, 10_000L),
    Triple("5 min", 5 * 60_000L, 60_000L),
    Triple("15 min", 15 * 60_000L, 3 * 60_000L),
    Triple("60 min", 60 * 60_000L, 10 * 60_000L),
)

/** A round step near [raw]: 1, 2, 2.5 or 5 × 10ⁿ. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val p = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
    val f = raw / p
    return p * when {
        f <= 1 -> 1.0
        f <= 2 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5 -> 5.0
        else -> 10.0
    }
}

/** One channel, full screen, over the last 1–60 minutes from the flight recorder. */
@Composable
private fun ZoomPanel(vm: DashViewModel, ch: Channel, now: Double?, metric: Boolean, onClose: () -> Unit) {
    var span by remember { mutableStateOf(ZOOM_SPANS[1]) }
    var series by remember(ch.key) { mutableStateOf(dev.ponydash.Series.EMPTY) }
    LaunchedEffect(ch.key, span) {
        while (true) {
            series = vm.series(ch.key, span.second)
            delay(500)
        }
    }
    val unit = display(ch, 0.0, metric).second
    val dec = display(ch, 0.0, metric).third
    // display units, NaN kept as gaps
    val ys = FloatArray(series.v.size) { i ->
        val x = series.v[i]
        if (x.isNaN()) Float.NaN else display(ch, x.toDouble(), metric).first.toFloat()
    }
    val finite = ys.filter { !it.isNaN() }
    val lo = finite.minOrNull()
    val hi = finite.maxOrNull()
    val avg = if (finite.isEmpty()) null else finite.average()
    val labelPaint = remember {
        Paint().apply { typeface = Typeface.MONOSPACE; isAntiAlias = true; isSubpixelText = true; isLinearText = true; color = M.Mid.toArgb() }
    }

    val narrowZ = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 1150
    val controls: @Composable () -> Unit = {
        for (sp in ZOOM_SPANS) {
            Text(
                sp.first,
                color = if (sp == span) M.Bg else M.Green,
                fontSize = 15.sp,
                fontFamily = M.Mono,
                modifier = Modifier
                    .background(if (sp == span) M.Green else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(1.dp, M.Mid, RoundedCornerShape(4.dp))
                    .clickableNoRipple { span = sp }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        MChip("[ close ]", onClose)
    }
    Box(Modifier.fillMaxSize().background(M.Scrim).clickableNoRipple(onClose)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .background(M.Panel, RoundedCornerShape(8.dp))
                .border(1.dp, M.Mid, RoundedCornerShape(8.dp))
                .clickableNoRipple {}
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    Text(ch.label.uppercase(), color = M.Mid, fontSize = 16.sp, fontFamily = M.Mono)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            now?.let { fmt(display(ch, it, metric).first, dec) } ?: "----",
                            style = TextStyle(textMotion = Fonts.motion, color = M.Green, fontSize = 54.sp, fontFamily = M.Mono, fontWeight = FontWeight.Bold, shadow = M.glow),
                        )
                        Text(" $unit", color = M.Mid, fontSize = 20.sp, fontFamily = M.Mono, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.padding(bottom = 10.dp)) {
                    val s = { x: Double? -> x?.let { fmt(it, dec) } ?: "--" }
                    Text("min ${s(lo?.toDouble())}   avg ${s(avg)}   max ${s(hi?.toDouble())}", color = M.Green, fontSize = 16.sp, fontFamily = M.Mono)
                    Text("${finite.size} samples · 10 Hz raw · last ${span.first}", color = M.Dim, fontSize = 13.sp, fontFamily = M.Mono)
                }
                if (!narrowZ) {
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
                }
            }
            if (narrowZ) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                labelPaint.color = M.Mid.toArgb()
                labelPaint.textSize = 15.sp.toPx()
                val left = 86.dp.toPx()
                val bottom = size.height - 26.dp.toPx()
                val right = size.width - 8.dp.toPx()
                val top = 6.dp.toPx()
                val w = right - left
                val h = bottom - top

                // y range with a little headroom; flat signals get a ±1-unit band
                var yLo = (lo ?: 0f).toDouble()
                var yHi = (hi ?: 1f).toDouble()
                if (yHi - yLo < 1e-6) { yLo -= 1; yHi += 1 }
                val pad = (yHi - yLo) * 0.08
                yLo -= pad; yHi += pad
                val step = niceStep((yHi - yLo) / 7)
                fun yOf(v: Double) = (bottom - (v - yLo) / (yHi - yLo) * h).toFloat()

                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    labelPaint.textAlign = Paint.Align.RIGHT
                    var gy = kotlin.math.ceil(yLo / step) * step
                    val yDec = if (step >= 1) 0 else kotlin.math.ceil(-kotlin.math.log10(step)).toInt().coerceAtMost(4)
                    while (gy <= yHi) {
                        val y = yOf(gy)
                        drawLine(M.Faint, Offset(left, y), Offset(right, y), 1.5f)
                        nc.drawText(fmt(gy, yDec), left - 10.dp.toPx(), y + labelPaint.textSize / 3, labelPaint)
                        gy += step
                    }
                    // time axis: newest at the right edge
                    labelPaint.textAlign = Paint.Align.CENTER
                    val spanMs = span.second
                    var tk = 0L
                    while (tk <= spanMs) {
                        val x = right - w * tk / spanMs
                        drawLine(M.Faint, Offset(x, top), Offset(x, bottom), 1f)
                        val lbl = if (tk == 0L) "now" else if (tk % 60_000L == 0L) "-${tk / 60_000}m" else "-${tk / 1000}s"
                        nc.drawText(lbl, x.coerceIn(left + 20f, right - 20f), size.height - 4.dp.toPx(), labelPaint)
                        tk += span.third
                    }
                }
                drawLine(M.Dim, Offset(left, top), Offset(left, bottom), 2f)
                drawLine(M.Dim, Offset(left, bottom), Offset(right, bottom), 2f)

                val t = series.t
                if (t.isEmpty()) return@Canvas
                val tNow = series.now
                val path = Path()
                var pen = false
                var prevT = 0L
                var lastPt: Offset? = null
                for (i in t.indices) {
                    val v = ys[i]
                    if (v.isNaN()) { pen = false; continue }
                    val x = right - w * (tNow - t[i]).toFloat() / span.second
                    if (x < left) continue
                    val y = yOf(v.toDouble())
                    // break the line across recording gaps (car off, link drop)
                    if (!pen || t[i] - prevT > 2_000) path.moveTo(x, y) else path.lineTo(x, y)
                    pen = true
                    prevT = t[i]
                    lastPt = Offset(x, y)
                }
                drawPath(path, M.Green, style = Stroke(2.5f))
                lastPt?.let { drawCircle(M.Green, 6f, it) }
                avg?.let {
                    val y = yOf(it)
                    drawLine(M.Mid.copy(alpha = 0.6f), Offset(left, y), Offset(right, y), 1.5f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
                }
            }
        }
    }
}
