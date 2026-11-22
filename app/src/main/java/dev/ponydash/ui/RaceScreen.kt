package dev.ponydash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ponydash.DashViewModel
import dev.ponydash.Gearbox
import dev.ponydash.RaceState
import dev.ponydash.cToF
import dev.ponydash.kmhToMph
import dev.ponydash.obd.K
import kotlinx.coroutines.delay

/** Health thresholds, °C unless noted. */
private enum class Lvl { OK, WARN, BAD, NONE }

private class Health(val label: String, val text: String, val lvl: Lvl, val alarm: String)

private fun lvlColor(l: Lvl) = when (l) {
    Lvl.OK -> C.Good
    Lvl.WARN -> C.Warn
    Lvl.BAD -> C.Bad
    Lvl.NONE -> C.Dim
}

/**
 * Race screen: what you can take in with a half-second glance at the limit.
 *
 *  - Shift lights across the top, flashing at the limiter.
 *  - The gear (estimated from rpm ÷ speed), huge, with speed and rpm under it,
 *    and where the rpm lands after the next upshift.
 *  - Pedal and braking bars plus boost with a peak-hold tick.
 *  - Automatic 0–100 km/h (0–60 mph) and 100–0 (60–0) timers with personal bests.
 *  - A health strip that stays quiet in green and turns into a red alarm
 *    naming the problem when oil, coolant, trans, charge air, knock, mixture or
 *    voltage leaves its safe range.
 */
@Composable
fun RaceScreen(vm: DashViewModel, onMatrix: () -> Unit, onDash: () -> Unit) {
    val v by vm.values.collectAsState()
    val r by vm.race.collectAsState()
    val g by vm.gmeter.state.collectAsState()
    val metric by vm.metric.collectAsState()
    val conn by vm.conn.collectAsState()
    val rpm = v[K.RPM]
    val noData = if (v.isNotEmpty()) null else when (conn.link) {
        dev.ponydash.Link.CONNECTING -> "Connecting to the adapter…"
        dev.ponydash.Link.RETRYING -> "No data · OBD: ${conn.detail}"
        else -> "No data · not connected"
    }

    // one blink clock for the limiter lights and alarms (only ticks while needed)
    // lean must persist 0.7 s: single-sample λ spikes during shifts are normal
    val leanClock = remember { longArrayOf(0L) }
    val leanNow = dev.ponydash.obd.leanUnderBoost(v)
    val tNow = System.currentTimeMillis()
    if (!leanNow) leanClock[0] = 0L else if (leanClock[0] == 0L) leanClock[0] = tNow
    val health = health(v, metric, leanNow && tNow - leanClock[0] >= 700)
    val alarm = health.filter { it.lvl == Lvl.BAD }
    val needBlink = (rpm ?: 0.0) >= Gearbox.LIMIT || alarm.isNotEmpty()
    var blink by remember { mutableStateOf(false) }
    LaunchedEffect(needBlink) {
        blink = false
        while (needBlink) { delay(130); blink = !blink }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val small = maxHeight < 700.dp
        val pad = if (small) 10.dp else 18.dp
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(if (small) 8.dp else 14.dp)) {
            ShiftLights(rpm, blink, Modifier.fillMaxWidth().height(if (small) 34.dp else 54.dp))

            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (small) 8.dp else 14.dp)) {
                Panel(Modifier.weight(0.8f).fillMaxHeight()) { Pedals(r, v, metric, small) }
                Panel(Modifier.weight(1.35f).fillMaxHeight(), border = if ((rpm ?: 0.0) >= Gearbox.SHIFT) C.Bad else null) {
                    GearBlock(r, v, metric, small)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (small) 8.dp else 14.dp)) {
                    val tu = if (metric) "0–100 km/h" else "0–60 mph"
                    val bu = if (metric) "100–0 km/h" else "60–0 mph"
                    val du = if (metric) "m" else "ft"
                    fun d(m: Double) = if (metric) "%.1f".format(m) else "%.0f".format(m * 3.28084)
                    Panel(Modifier.fillMaxWidth().weight(1f)) {
                        TimerCard(
                            tu,
                            live = r.launchLive?.let { "%.1f".format(it) },
                            last = r.launch.seconds?.let { "%.2f".format(it) }, unit = "s",
                            best = r.launchBest.seconds?.let { "%.2f s".format(it) },
                            fresh = r.launch.seconds != null && r.launch.at == r.launchBest.at && r.launchBest.at != 0L,
                            small = small,
                            onClearBest = vm::resetRaceBests,
                        )
                    }
                    Panel(Modifier.fillMaxWidth().weight(1f)) {
                        TimerCard(
                            bu,
                            live = r.brakeLive?.let { d(it) },
                            last = r.stop.meters?.let { d(it) }, unit = du,
                            best = r.stopBest.meters?.let { d(it) + " " + du },
                            fresh = r.stop.meters != null && r.stop.at == r.stopBest.at && r.stopBest.at != 0L,
                            small = small,
                            onClearBest = vm::resetRaceBests,
                        )
                    }
                    Panel(Modifier.fillMaxWidth().weight(0.75f)) {
                        GRow(g.lat, g.lon, g.peakLat, maxOf(g.peakBrake, g.peakAccel), small)
                    }
                }
            }

            HealthStrip(health, alarm, noData, blink, small, onMatrix, onDash, Modifier.fillMaxWidth().height(if (small) 62.dp else 84.dp))
        }
    }
}

@Composable
private fun Panel(modifier: Modifier, border: Color? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .background(C.Tile, RoundedCornerShape(16.dp))
            .border(if (border != null) 3.dp else 1.dp, border ?: C.Stroke, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) { content() }
}

/** "No reading" mark: a thin dash at [scale] of the number it stands in for, so it never reads as a solid bar. */
@Composable
private fun Missing(size: TextUnit, scale: Float = 0.55f, modifier: Modifier = Modifier) =
    Text("–", style = big(size * scale, C.Dim, FontWeight.Light), maxLines = 1, modifier = modifier)

private fun big(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Bold) =
    TextStyle(textMotion = Fonts.motion, fontFamily = Fonts.Sans, fontSize = size, lineHeight = size, color = color, fontWeight = weight)

/** 16 LEDs from LIGHTS_FROM to SHIFT: green, amber, red; all red at SHIFT; flashing past LIMIT. */
@Composable
private fun ShiftLights(rpm: Double?, blink: Boolean, modifier: Modifier) {
    val n = 16
    val good = C.Good
    val warn = C.Warn
    val bad = C.Bad
    val accent = C.Accent
    val off = C.Stroke
    Canvas(modifier) {
        val gap = size.height * 0.22f
        val w = (size.width - gap * (n - 1)) / n
        val x = rpm ?: 0.0
        val lit = ((x - Gearbox.LIGHTS_FROM) / (Gearbox.SHIFT - Gearbox.LIGHTS_FROM) * n).toInt().coerceIn(0, n)
        for (i in 0 until n) {
            val base = when {
                i < n * 0.45 -> good
                i < n * 0.75 -> warn
                else -> bad
            }
            val c = when {
                x >= Gearbox.LIMIT -> if (blink) accent else bad
                x >= Gearbox.SHIFT -> bad
                i < lit -> base
                else -> off.copy(alpha = 0.5f)
            }
            drawRoundRect(c, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(size.height * 0.3f))
        }
    }
}

@Composable
private fun GearBlock(r: RaceState, v: Map<String, Double>, metric: Boolean, small: Boolean) {
    val kmh = v[K.SPEED]
    val rpm = v[K.RPM]
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // size from height, but never wider than the panel (the Pad 3's panel is tall and narrow)
        val w = maxWidth
        val fullH = maxHeight
        val h = minOf(fullH, w * 1.15f)
        val density = LocalDensity.current
        fun sp(d: Dp) = with(density) { d.toSp() }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("GEAR", color = C.Label, fontSize = if (small) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text("est. from rpm ÷ speed", color = C.Dim, fontSize = if (small) 11.sp else 13.sp, maxLines = 1, softWrap = false)
            }
            val shown = r.gear ?: r.lastGear
            val gearText = when {
                kmh != null && kmh < 3 -> "–"
                shown == null -> "–"
                else -> shown.toString()
            }
            val gearColor = when {
                (rpm ?: 0.0) >= Gearbox.SHIFT -> C.Bad
                r.gear == null -> C.Dim
                else -> C.Value
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val gs = sp(minOf(fullH * 0.5f, w * 0.42f))
                if (gearText == "–") Missing(gs, 0.4f)
                else Text(gearText, style = big(gs, gearColor, FontWeight.ExtraBold), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            }
            Row {
                val sv = kmh?.let { if (metric) it else kmhToMph(it) }
                val ss = sp(minOf(h * 0.16f, w * 0.16f))
                if (sv == null) Missing(ss, modifier = Modifier.alignByBaseline())
                else Text("%.0f".format(sv), style = big(ss, C.Value), maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                Text(if (metric) " km/h" else " mph", color = C.Label, fontSize = sp(minOf(h * 0.06f, w * 0.06f)), maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
            }
            Text(rpm?.let { "%,.0f rpm".format(it) } ?: "– rpm", color = if (rpm == null) C.Dim else C.Label,
                fontSize = sp(minOf(h * 0.065f, w * 0.065f)), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            // where the needle lands after the next upshift: its own line, so it never collides with the rpm
            val g = r.gear
            val hint = if (g != null && g < 10 && kmh != null && (rpm ?: 0.0) > 3000) {
                val nth = when (g + 1) { 2 -> "2nd"; 3 -> "3rd"; else -> "${g + 1}th" }
                "▲ $nth → %,.0f rpm".format(Gearbox.rpmIn(g + 1, kmh))
            } else " "
            Text(hint, color = C.Dim, fontSize = sp(minOf(h * 0.045f, w * 0.045f)), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun Pedals(r: RaceState, v: Map<String, Double>, metric: Boolean, small: Boolean) {
    val boostKpa = v[K.BOOST]
    val maxKpa = 150.0
    fun b(kpa: Double) = if (metric) "%.2f".format(kpa / 100) else "%.1f".format(kpa * 0.145038)
    val bu = if (metric) "bar" else "psi"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(if (small) 10.dp else 14.dp)) {
            val live = v.isNotEmpty()
            VBar("THR", if (live) "%.0f".format(r.pedal * 100) else null, "%", r.pedal, C.Good, null, small, Modifier.weight(1f).fillMaxHeight())
            VBar("BRK", if (live) "%.1f".format(r.brake) else null, "g", r.brake, C.Bad, null, small, Modifier.weight(1f).fillMaxHeight())
            VBar(
                "BOOST",
                boostKpa?.let { b(it) }, bu,
                ((boostKpa ?: 0.0) / maxKpa).coerceIn(0.0, 1.0), C.Accent,
                (r.peakBoostKpa / maxKpa).coerceIn(0.0, 1.0).takeIf { it > 0 },
                small, Modifier.weight(1.2f).fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "peak boost ${b(r.peakBoostKpa)} $bu",
            color = C.Dim, fontSize = if (small) 12.sp else 14.sp, maxLines = 1,
        )
    }
}

@Composable
private fun VBar(label: String, value: String?, unit: String, frac: Double, color: Color, peak: Double?, small: Boolean, modifier: Modifier) {
    val track = C.Stroke
    val peakColor = C.Value
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (value == null) Missing(if (small) 17.sp else 22.sp, 1f)
        else Row {
            Text(value, style = big(if (small) 17.sp else 22.sp, C.Value), maxLines = 1, modifier = Modifier.alignByBaseline())
            Text(" $unit", color = C.Label, fontSize = if (small) 12.sp else 14.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val rad = CornerRadius(10f)
            drawRoundRect(track.copy(alpha = 0.45f), cornerRadius = rad)
            val h = (size.height * frac.toFloat()).coerceIn(0f, size.height)
            if (h > 1f) drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), rad)
            if (peak != null) {
                val y = size.height * (1 - peak.toFloat())
                drawRect(peakColor, Offset(0f, y - 2f), Size(size.width, 4f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = C.Label, fontSize = if (small) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TimerCard(title: String, live: String?, last: String?, unit: String, best: String?, fresh: Boolean, small: Boolean, onClearBest: () -> Unit) {
    // long-press only, so a bump in the car cannot wipe a best
    BoxWithConstraints(Modifier.fillMaxSize().combinedClickable(onLongClick = onClearBest, onClick = {})) {
        val density = LocalDensity.current
        // "18.47" plus its unit must fit the card's width as well as its height
        val vs = with(density) { minOf(maxHeight * 0.42f, maxWidth * 0.24f).toSp() }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.Label, fontSize = if (small) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (live != null) Text("● timing", color = C.Bad, fontSize = if (small) 12.sp else 14.sp, maxLines = 1)
                else if (fresh) Text("★ new best", color = C.Good, fontSize = if (small) 12.sp else 14.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                val shown = live ?: last
                if (shown == null) Missing(vs)
                else {
                    Text(shown, style = big(vs, if (live != null) C.Accent else C.Value), maxLines = 1, softWrap = false)
                    Text(" $unit", color = C.Label, fontSize = if (small) 14.sp else 18.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(best?.let { "best $it · hold to clear" } ?: "starts by itself", color = C.Dim, fontSize = if (small) 12.sp else 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun GRow(lat: Float, lon: Float, peakLat: Float, peakLon: Float, small: Boolean) {
    val dotC = C.Accent
    val ring = C.Stroke
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.fillMaxHeight().width(if (small) 56.dp else 80.dp)) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(ring, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawCircle(ring, r / 2, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            drawCircle(dotC, r * 0.16f, Offset(c.x + lat.coerceIn(-1.2f, 1.2f) / 1.2f * r, c.y - lon.coerceIn(-1.2f, 1.2f) / 1.2f * r))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("%.2f g".format(kotlin.math.sqrt(lat * lat + lon * lon)), style = big(if (small) 22.sp else 30.sp, C.Value))
            Text("peak lat %.2f · lon %.2f".format(peakLat, peakLon), color = C.Dim, fontSize = if (small) 11.sp else 13.sp, maxLines = 1)
        }
    }
}

private fun health(v: Map<String, Double>, metric: Boolean, lean: Boolean): List<Health> {
    fun t(c: Double?) = c?.let { if (metric) "%.0f°C".format(it) else "%.0f°F".format(cToF(it)) } ?: "—"
    fun temp(label: String, key: String, warn: Double, bad: Double, name: String): Health {
        val x = v[key]
        val l = when {
            x == null -> Lvl.NONE
            x >= bad -> Lvl.BAD
            x >= warn -> Lvl.WARN
            else -> Lvl.OK
        }
        return Health(label, t(x), l, "$name HOT ${t(x)}")
    }
    val out = ArrayList<Health>()
    if (v[K.OIL] != null) out += temp("OIL", K.OIL, 121.0, 129.0, "OIL")
    out += temp("COOLANT", K.COOLANT, 107.0, 113.0, "COOLANT")
    out += temp("TRANS", K.TRANS, 110.0, 120.0, "TRANS")
    val chargeKey = if (v[K.IAT_B] != null) K.IAT_B else K.IAT
    out += temp("CHARGE AIR", chargeKey, 55.0, 65.0, "CHARGE AIR")
    val lam = v[K.LAMBDA]?.takeIf { it > 0.5 }
    out += Health("λ", lam?.let { "%.2f".format(it) } ?: "—", if (lam == null) Lvl.NONE else if (lean) Lvl.BAD else Lvl.OK, "LEAN UNDER BOOST λ %.2f".format(lam ?: 0.0))
    val volts = v[K.VOLTS]
    out += Health("BATT", volts?.let { "%.1f V".format(it) } ?: "—",
        when { volts == null -> Lvl.NONE; volts < 11.8 -> Lvl.BAD; volts < 12.5 || volts > 15.2 -> Lvl.WARN; else -> Lvl.OK },
        "CHARGING %.1f V".format(volts ?: 0.0))
    val mil = (v[K.MIL] ?: 0.0) > 0
    if (mil) out += Health("MIL", "ON", Lvl.WARN, "CHECK ENGINE")
    return out
}

@Composable
private fun HealthStrip(
    items: List<Health>, alarm: List<Health>, noData: String?, blink: Boolean, small: Boolean,
    onMatrix: () -> Unit, onDash: () -> Unit, modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (small) 8.dp else 12.dp)) {
        if (noData != null) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(C.Tile, RoundedCornerShape(14.dp))
                    .border(2.dp, C.Warn, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(noData, color = C.Warn, fontSize = if (small) 17.sp else 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        } else if (alarm.isNotEmpty()) {
            // one thing is wrong: say what, in words, big
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (blink) C.Bad else C.Bad.copy(alpha = 0.75f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚠ " + alarm.joinToString("  ·  ") { it.alarm }, style = big(if (small) 24.sp else 34.sp, Color.White, FontWeight.ExtraBold), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
            }
        } else {
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .background(C.Tile, RoundedCornerShape(14.dp))
                    .border(1.dp, C.Stroke, RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (h in items) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(8.dp).height(8.dp).background(lvlColor(h.lvl), RoundedCornerShape(4.dp)))
                            Spacer(Modifier.width(5.dp))
                            Text(h.label, color = C.Label, fontSize = if (small) 11.sp else 13.sp, maxLines = 1)
                        }
                        if (h.lvl == Lvl.NONE) Missing(if (small) 19.sp else 26.sp, 0.8f)
                        else Text(h.text, style = big(if (small) 19.sp else 26.sp, if (h.lvl == Lvl.WARN) C.Warn else C.Value), maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.width(if (small) 84.dp else 110.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(if (small) 6.dp else 8.dp)) {
            NavChip("matrix", C.Matrix, onMatrix, small, Modifier.weight(1f))
            NavChip("dash", C.Label, onDash, small, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavChip(text: String, color: Color, onClick: () -> Unit, small: Boolean, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = if (small) 14.sp else 16.sp, fontFamily = Fonts.Mono, maxLines = 1, softWrap = false)
    }
}
