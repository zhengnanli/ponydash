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
fun DashScreen(vm: DashViewModel, onOpenConnect: () -> Unit, onOpenDiag: () -> Unit, onOpenMatrix: () -> Unit) {
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
        StatusBar(u.temp(v[K.AMBIENT]), u.tempUnit, conn.link, conn.device, conn.detail, onOpenConnect, onOpenDiag, vm::toggleUnits, onOpenMatrix)

        Row(Modifier.fillMaxWidth().weight(1.45f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (v[K.OIL] != null) {
                GaugeTile(
                    "Oil temp", oil, u.tc(40.0), u.tc(150.0), f0(oil), u.tempUnit,
                    tempColor(oil, u.tc(121.0), u.tc(129.0)), Modifier.weight(1f).fillMaxHeight(),
                )
            } else {
                val trans = u.temp(v[K.TRANS])
                GaugeTile(
                    "Trans fluid", trans, u.tc(20.0), u.tc(130.0), f0(trans), u.tempUnit,
                    tempColor(trans, u.tc(110.0), u.tc(120.0)), Modifier.weight(1f).fillMaxHeight(),
                    sub = if (trans == null && conn.link == Link.LIVE) "waiting for Ford PID" else null,
                )
            }
            GaugeTile(
                "Boost", boost, u.boostMin, u.boostMax, u.boostText(boost),
                if (boost != null && boost < 0) "${u.boostUnit} vac" else u.boostUnit,
                C.Accent, Modifier.weight(1f).fillMaxHeight(),
                sub = v[K.THROTTLE]?.let { "throttle %.0f%%".format(it) },
            )
            GMeterTile(g, Modifier.weight(1.25f).fillMaxHeight(), vm.gmeter::calibrate)
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueTile("Coolant", f0(cool), u.tempUnit, Modifier.weight(1f).fillMaxHeight(),
                color = tempColor(cool, u.tc(107.0), u.tc(113.0)))
            ValueTile("Intake air", f0(iat), u.tempUnit, Modifier.weight(1f).fillMaxHeight(),
                color = if ((iat ?: -99.0) > u.tc(60.0)) C.Warn else C.Value)
            ValueTile("Battery", f1(volts), "V", Modifier.weight(1f).fillMaxHeight(),
                color = if (volts != null && (volts < 12.0 || volts > 15.2)) C.Warn else C.Value)
            val knock = v[K.KNOCK]
            ValueTile("Knock retard", f1(knock), "°", Modifier.weight(1f).fillMaxHeight(),
                color = if ((knock ?: 0.0) >= 2.0) C.Warn else C.Value, sub = "Ford PID · scale unverified")
            ValueTile("RPM", f0(v[K.RPM]), "", Modifier.weight(1.1f).fillMaxHeight(),
                sub = v[K.SPEED]?.let { u.speed(it) })
        }

        Row(Modifier.fillMaxWidth().weight(0.85f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1.5f).fillMaxHeight().combinedClickable(onLongClick = vm::resetTrip, onClick = {})) {
                val mins = (trip.seconds / 60).toInt()
                ValueTile(
                    "Trip · hold to reset",
                    "%.1f".format(u.dist(trip.miles)), u.distUnit, Modifier.fillMaxSize(),
                    sub = u.economy(trip) + " · %d:%02d".format(mins / 60, mins % 60),
                    size = 36.sp,
                )
            }
            TiresTile(v, metric, Modifier.weight(1.4f).fillMaxHeight())
            ValueTile("Fuel", f0(v[K.FUEL]), "%", Modifier.weight(0.8f).fillMaxHeight(), size = 36.sp)
            val dtc = v[K.DTC]
            val mil = (v[K.MIL] ?: 0.0) > 0
            ValueTile(
                "Codes", dtc?.toInt()?.toString(), if (mil) "· check engine" else "",
                Modifier.weight(0.8f).fillMaxHeight(),
                color = if (dtc == null) C.Value else if (dtc > 0 || mil) C.Bad else C.Good, size = 36.sp,
            )
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
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1_000) } }
    val clock = remember { SimpleDateFormat("h:mm", Locale.US) }
    val ampm = remember { SimpleDateFormat("a", Locale.US) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(clock.format(now), color = C.Value, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(" " + ampm.format(now), color = C.Label, fontSize = 18.sp)
        if (ambient != null) Text("    %.0f%s outside".format(ambient, tempUnit), color = C.Label, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        val (dot, label) = when (link) {
            Link.LIVE -> C.Good to (device ?: "OBD")
            Link.SIM -> C.Warn to "Simulator"
            Link.CONNECTING -> C.Accent to "$detail…"
            Link.RETRYING -> C.Bad to "OBD: $detail"
            Link.IDLE -> C.Dim to "Not connected"
        }
        Row(
            Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onConnect)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = C.Label, fontSize = 15.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (tempUnit == "°C") "Metric" else "Imperial",
            color = C.Label,
            fontSize = 15.sp,
            modifier = Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onUnits)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Matrix",
            color = Color(0xFF00FF66),
            fontSize = 15.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .border(1.dp, Color(0xFF00B347), RoundedCornerShape(20.dp))
                .clickableNoRipple(onMatrix)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Diagnostics",
            color = C.Label,
            fontSize = 15.sp,
            modifier = Modifier
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .clickableNoRipple(onDiag)
                .padding(horizontal = 14.dp, vertical = 8.dp),
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
    fun t(k: String): String = v[k]?.let { if (metric) "%.0f".format(it) else "%.1f".format(it * 0.145038) } ?: "—"
    val all = listOf(K.TIRE_FL, K.TIRE_FR, K.TIRE_RL, K.TIRE_RR).mapNotNull { v[it] }
    // flag a tire 10 % below the average of the four
    val low = all.size == 4 && all.any { it < all.average() * 0.9 }
    Tile(if (metric) "Tires · kPa" else "Tires · psi", modifier, if (low) C.Warn else null) {
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(t(K.TIRE_FL), color = C.Value, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(t(K.TIRE_FR), color = C.Value, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text(t(K.TIRE_RL), color = C.Value, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(t(K.TIRE_RR), color = C.Value, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
    }
}
