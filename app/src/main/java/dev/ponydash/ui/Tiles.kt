package dev.ponydash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ponydash.sensors.GForce
import kotlin.math.min

private val TileShape = RoundedCornerShape(18.dp)

/** Short screens (the 1024×600 onn tablet): tighter tiles and smaller type. */
val compact: Boolean
    @Composable get() = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 700

@Composable
fun Tile(title: String, modifier: Modifier = Modifier, alert: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(C.Tile, TileShape)
            .border(if (alert != null) 2.dp else 1.dp, alert ?: C.Stroke, TileShape)
            .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 8.dp else 14.dp),
    ) {
        Text(title.uppercase(), color = alert ?: C.Label, fontSize = if (compact) 12.sp else 14.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        content()
    }
}

/** Big number with unit and an optional sub-line. Shows "—" when there is no reading. */
@Composable
fun ValueTile(
    title: String,
    value: String?,
    unit: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    color: Color = C.Value,
    size: TextUnit = 44.sp,
) {
    val alert = color.takeIf { it == C.Bad || it == C.Warn }
    Tile(title, modifier, alert) {
        Spacer(Modifier.weight(1f))
        val k = if (compact) 0.72f else 1f
        Row(verticalAlignment = Alignment.Bottom) {
            if (value == null) Text("–", color = C.Dim, fontSize = size * k * 0.6f, fontWeight = FontWeight.Light, maxLines = 1)
            else Text(value, color = color, fontSize = size * k, fontWeight = FontWeight.Bold, maxLines = 1)
            if (value != null) Text(" $unit", color = C.Label, fontSize = 18.sp * k, maxLines = 1, modifier = Modifier.padding(bottom = (6 * k).dp))
        }
        if (sub != null) Text(sub, color = C.Dim, fontSize = if (compact) 11.sp else 13.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
    }
}

/** 240° arc gauge with the number in the middle. */
@Composable
fun GaugeTile(
    title: String,
    value: Double?,
    min: Double,
    max: Double,
    text: String?,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    val alert = color.takeIf { it == C.Bad || it == C.Warn }
    Tile(title, modifier, alert) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // Fit the number inside the ring: ~60% of the ring's inner width, at most 52 sp.
            val ring = min(maxWidth.value, maxHeight.value) - 12f
            val chars = (text ?: "—").length.coerceAtLeast(2)
            val valueSp = minOf(52f, ring * 0.62f / (chars * 0.62f), ring * 0.34f)
            val unitSp = minOf(16f, maxOf(11f, ring * 0.11f))
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val d = min(size.width, size.height)
                val tl = Offset((size.width - d) / 2, (size.height - d) / 2 + d * 0.06f)
                val stroke = Stroke(width = d * 0.075f, cap = StrokeCap.Round)
                drawArc(C.Track, 150f, 240f, false, tl, Size(d, d), style = stroke)
                if (value != null) {
                    val frac = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
                    drawArc(color, 150f, 240f * frac, false, tl, Size(d, d), style = stroke)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (text == null) {
                    Text("–", color = C.Dim, fontSize = (valueSp * 0.6f).sp, fontWeight = FontWeight.Light, maxLines = 1)
                } else {
                    Text(text, color = C.Value, fontSize = valueSp.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    Text(unit, color = C.Label, fontSize = unitSp.sp, maxLines = 1)
                }
            }
        }
        if (sub != null) Text(sub, color = C.Dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun GMeterTile(g: GForce, modifier: Modifier = Modifier, onCalibrate: () -> Unit) {
    Tile("G-meter", modifier) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).fillMaxSize().padding(6.dp)) {
                val r = min(size.width, size.height) / 2
                val c = Offset(size.width / 2, size.height / 2)
                val scale = r / 1.0f // ring edge = 1.0 g
                for (ring in listOf(0.25f, 0.5f, 0.75f, 1.0f)) {
                    drawCircle(C.Stroke, ring * scale, c, style = Stroke(1.5f))
                }
                drawLine(C.Stroke, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
                drawLine(C.Stroke, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
                // lat → x (right +), lon → y (accel = up)
                g.trail.forEachIndexed { i, (lat, lon) ->
                    val a = (i + 1f) / g.trail.size
                    drawCircle(C.Accent.copy(alpha = 0.15f + 0.45f * a), 4f,
                        Offset(c.x + lat.coerceIn(-1.2f, 1.2f) * scale, c.y - lon.coerceIn(-1.2f, 1.2f) * scale))
                }
                drawCircle(C.Warn, 11f, Offset(c.x + g.lat.coerceIn(-1.2f, 1.2f) * scale, c.y - g.lon.coerceIn(-1.2f, 1.2f) * scale))
            }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 6.dp)) {
                val tot = kotlin.math.sqrt(g.lat * g.lat + g.lon * g.lon)
                Text("%.2f g".format(tot), color = C.Value, fontSize = if (compact) 24.sp else 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("lat %+.2f".format(g.lat), color = C.Label, fontSize = if (compact) 13.sp else 15.sp)
                Text("lon %+.2f".format(g.lon), color = C.Label, fontSize = if (compact) 13.sp else 15.sp)
                Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                Text(
                    if (g.calibrated) "re-zero" else if (compact) "zero" else "tap to zero",
                    color = C.Accent,
                    fontSize = if (compact) 12.sp else 14.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .border(1.dp, C.Accent, RoundedCornerShape(8.dp))
                        .clickableNoRipple(onCalibrate)
                        .padding(horizontal = 10.dp, vertical = if (compact) 3.dp else 6.dp),
                )
            }
        }
    }
}
