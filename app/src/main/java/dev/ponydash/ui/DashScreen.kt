package dev.ponydash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ponydash.DashViewModel
import dev.ponydash.Link
import dev.ponydash.Trip
import dev.ponydash.boostPsi
import dev.ponydash.cToF
import dev.ponydash.kmhToMph
import dev.ponydash.obd.K
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

fun Modifier.clickableNoRipple(onClick: () -> Unit) =
    this.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

private fun f0(x: Double?) = x?.let { "%.0f".format(it) }
private fun f1(x: Double?) = x?.let { "%.1f".format(it) }

private fun tempColor(f: Double?, warn: Double, bad: Double) = when {
    f == null -> C.Value
    f >= bad -> C.Bad
    f >= warn -> C.Warn
    else -> C.Good
}

/** Display units. The car reports metric; imperial is converted. */
private class Units(val metric: Boolean) {
    fun temp(c: Double?) = c?.let { if (metric) it else cToF(it) }
    val tempUnit = if (metric) "°C" else "°F"
    /** Threshold given in °C, returned in display units. */
    fun tc(c: Double) = if (metric) c else cToF(c)
    fun boost(psi: Double?) = psi?.let { if (metric) it / 14.5038 else it }
    fun boostText(b: Double?) = b?.let { if (metric) "%.2f".format(it) else "%.1f".format(it) }
    val boostUnit = if (metric) "bar" else "psi"
    val boostMin = if (metric) -1.0 else -15.0
    val boostMax = if (metric) 1.7 else 25.0
    fun speed(kmh: Double) = if (metric) "%.0f km/h".format(kmh) else "%.0f mph".format(kmhToMph(kmh))
    fun dist(miles: Double) = if (metric) miles * 1.609344 else miles
    val distUnit = if (metric) "km" else "mi"
    fun economy(t: Trip): String {
        if (t.gallons <= 0.02 || t.miles <= 0.05) return if (metric) "— L/100 km" else "— mpg"
        return if (metric) "%.1f L/100 km".format(t.gallons * 3.78541 / (t.miles * 1.609344) * 100)
        else "%.1f mpg".format(t.miles / t.gallons)
    }
}

@Composable
fun DashScreen(vm: DashViewModel, onOpenConnect: () -> Unit, onOpenDiag: () -> Unit, onOpenMatrix: () -> Unit, onOpenReplay: () -> Unit, onOpenRace: () -> Unit) {
    val v by vm.values.collectAsState()
    val conn by vm.conn.collectAsState()
    val g by vm.gmeter.state.collectAsState()
    val trip by vm.trip.collectAsState()
    val supported by vm.supported.collectAsState()
    val metric by vm.metric.collectAsState()
    val u = Units(metric)

    val oil = u.temp(v[K.OIL])
    val cool = u.temp(v[K.COOLANT])
    val iat = u.temp(v[K.IAT])
    val boost = u.boost(boostPsi(v))
    val volts = v[K.VOLTS]
    
    Column(
        Modifier.fillMaxSize().background(C.Bg).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusBar(u.temp(v[K.AMBIENT]), u.tempUnit, conn.link, conn.device, conn.detail, onOpenConnect, onOpenDiag, vm::toggleUnits, onOpenMatrix, onOpenReplay, vm::cycleTheme, onOpenRace)

        // Row 1: what you read at the limit. RPM doubles as a shift light.
        Row(Modifier.fillMaxWidth().weight(1.5f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val rpm = v[K.RPM]
            GaugeTile(
                "RPM", rpm, 0.0, 7000.0, f0(rpm), v[K.SPEED]?.let { u.speed(it) } ?: "",
                when {
                    (rpm ?: 0.0) >= 6300 -> C.Bad
                    (rpm ?: 0.0) >= 5800 -> C.Warn
                    else -> C.Accent
                },
                Modifier.weight(1f).fillMaxHeight(),
            )
            GaugeTile(
                "Boost", boost, u.boostMin, u.boostMax, u.boostText(boost),
                if (boost != null && boost < 0) "${u.boostUnit} vac" else u.boostUnit,
                C.Accent, Modifier.weight(1f).fillMaxHeight(),
                sub = v["cmd_throttle"]?.let { "throttle %.0f%%".format(it) },
            )
            GMeterTile(g, Modifier.weight(1.25f).fillMaxHeight(), vm.gmeter::calibrate)
        }

        // Row 2: is the car healthy while you push it?
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (v[K.OIL] != null) {
                ValueTile("Oil", f0(oil), u.tempUnit, Modifier.weight(1f).fillMaxHeight(),
                    color = tempColor(oil, u.tc(121.0), u.tc(129.0)))
            } else {
                val trans = u.temp(v[K.TRANS])
                ValueTile("Trans fluid", f0(trans), u.tempUnit, Modifier.weight(1f).fillMaxHeight(),
                    color = tempColor(trans, u.tc(110.0), u.tc(120.0)),
                    sub = if (trans == null && conn.link == Link.LIVE) "waiting for Ford PID" else null)
            }
            ValueTile("Coolant", f0(cool), u.tempUnit, Modifier.weight(1f).fillMaxHeight(),
                color = tempColor(cool, u.tc(107.0), u.tc(113.0)))
            val charge = u.temp(v[K.IAT_B])
            ValueTile(if (charge != null) "Intake air B" else "Intake air", f0(charge ?: iat), u.tempUnit, Modifier.weight(1f).fillMaxHeight(),
                color = if ((charge ?: iat ?: -99.0) > u.tc(60.0)) C.Warn else C.Value)
            val knock = v[K.KNOCK]
            ValueTile("Knock retard", f1(knock), "°", Modifier.weight(1f).fillMaxHeight(),
                color = if ((knock ?: 0.0) >= 2.0) C.Warn else C.Value, sub = "scale unverified")
            val lam = v[K.LAMBDA]?.takeIf { it > 0.5 } // the O₂ sensor reads 0 until it is warm
            val lean = dev.ponydash.obd.leanUnderBoost(v)
            ValueTile("Air-fuel λ", lam?.let { "%.2f".format(it) }, "", Modifier.weight(1f).fillMaxHeight(),
                color = if (lean) C.Bad else C.Value,
                sub = if (lean) "lean under boost" else v[K.CMD_LAMBDA]?.let { "target %.2f".format(it) })
        }

        // Row 3: the rest.
        Row(Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TiresTile(v, metric, Modifier.weight(1.4f).fillMaxHeight())
            ValueTile("Fuel", f0(v[K.FUEL]), "%", Modifier.weight(0.8f).fillMaxHeight(), size = 32.sp)
            ValueTile("Battery", f1(volts), "V", Modifier.weight(0.8f).fillMaxHeight(), size = 32.sp,
                color = if (volts != null && (volts < 12.0 || volts > 15.2)) C.Warn else C.Value)
            val dtc = v[K.DTC]
            val mil = (v[K.MIL] ?: 0.0) > 0
            ValueTile(
                "Codes", dtc?.toInt()?.toString(), if (mil) "· check engine" else "",
                Modifier.weight(0.8f).fillMaxHeight(),
                color = if (dtc == null) C.Value else if (dtc > 0 || mil) C.Bad else C.Good, size = 32.sp,
            )
            Box(Modifier.weight(1.3f).fillMaxHeight().combinedClickable(onLongClick = vm::resetTrip, onClick = {})) {
                val mins = (trip.seconds / 60).toInt()
                ValueTile(
                    "Trip · hold to reset",
                    "%.1f".format(u.dist(trip.miles)), u.distUnit, Modifier.fillMaxSize(),
                    sub = u.economy(trip) + " · %d:%02d".format(mins / 60, mins % 60),
                    size = 32.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusBar(
    ambient: Double?,
    tempUnit: String,
    link: Link,
    device: String?,
    detail: String,
    onConnect: () -> Unit,
    onDiag: () -> Unit,
    onUnits: () -> Unit,
    onMatrix: () -> Unit,
    onReplay: () -> Unit,
    onTheme: () -> Unit,
    onRace: () -> Unit,
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1_000) } }
    val clock = remember { SimpleDateFormat("h:mm", Locale.US) }
    val ampm = remember { SimpleDateFormat("a", Locale.US) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val small = compact
        Text(clock.format(now), color = C.Value, fontSize = if (small) 24.sp else 30.sp, fontWeight = FontWeight.Bold)
        Text(" " + ampm.format(now), color = C.Label, fontSize = if (small) 14.sp else 18.sp)
        if (ambient != null) Text(if (small) "   %.0f%s".format(ambient, tempUnit) else "    %.0f%s outside".format(ambient, tempUnit), color = C.Label, fontSize = if (small) 14.sp else 18.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        val (dot, label) = when (link) {
            Link.LIVE -> C.Good to (device ?: "OBD")
            Link.SIM -> C.Warn to "Simulator"
            Link.CONNECTING -> C.Accent to "$detail…"
            Link.RETRYING -> C.Bad to "OBD: $detail"
            Link.IDLE -> C.Dim to "Not connected"
            Link.REPLAY -> C.Accent to "▶ ${device ?: "replay"}"
        }
        Row(
            Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onConnect)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = C.Label, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        }
        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        Text(
            if (tempUnit == "°C") "Metric" else "Imperial",
            color = C.Label,
            fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, softWrap = false,
            modifier = Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onUnits)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        )
        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        Text(
            "Matrix",
            color = C.Matrix,
            fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, softWrap = false,
            fontFamily = Fonts.Mono,
            modifier = Modifier
                .border(1.dp, C.Matrix.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .clickableNoRipple(onMatrix)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        )
        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        Text(
            "Race",
            color = C.Bad,
            fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .border(1.dp, C.Bad.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .clickableNoRipple(onRace)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        )
        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        Text(
            if (C.auto) (if (compact) "◐ ${C.palette.name}" else "Auto · ${C.palette.name}") else C.palette.name,
            color = C.Label,
            fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, softWrap = false,
            modifier = Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onTheme)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        )
        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        Text(
            "Replay",
            color = C.Label,
            fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, softWrap = false,
            modifier = Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onReplay)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        )
        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        Text(
            if (compact) "Diag" else "Diagnostics",
            color = C.Label,
            fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, softWrap = false,
            modifier = Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onDiag)
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
        )
    }
}

@Composable
fun Chip(text: String, color: Color = C.Accent, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun TiresTile(v: Map<String, Double>, metric: Boolean, modifier: Modifier) {
    fun t(k: String): String = v[k]?.let { if (metric) "%.2f".format(it / 100) else "%.1f".format(it * 0.145038) } ?: "–"
    val all = listOf(K.TIRE_FL, K.TIRE_FR, K.TIRE_RL, K.TIRE_RR).mapNotNull { v[it] }
    // flag a tire 10 % below the average of the four
    val low = all.size == 4 && all.any { it < all.average() * 0.9 }
    val ts = if (compact) 20.sp else 30.sp
    Tile(if (metric) "Tires · bar" else "Tires · psi", modifier, if (low) C.Warn else null) {
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(t(K.TIRE_FL), color = if (v[K.TIRE_FL] == null) C.Dim else C.Value, fontSize = ts, lineHeight = ts, fontWeight = if (v[K.TIRE_FL] == null) FontWeight.Light else FontWeight.Bold)
            Text(t(K.TIRE_FR), color = if (v[K.TIRE_FR] == null) C.Dim else C.Value, fontSize = ts, lineHeight = ts, fontWeight = if (v[K.TIRE_FR] == null) FontWeight.Light else FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(t(K.TIRE_RL), color = if (v[K.TIRE_RL] == null) C.Dim else C.Value, fontSize = ts, lineHeight = ts, fontWeight = if (v[K.TIRE_RL] == null) FontWeight.Light else FontWeight.Bold)
            Text(t(K.TIRE_RR), color = if (v[K.TIRE_RR] == null) C.Dim else C.Value, fontSize = ts, lineHeight = ts, fontWeight = if (v[K.TIRE_RR] == null) FontWeight.Light else FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
    }
}
