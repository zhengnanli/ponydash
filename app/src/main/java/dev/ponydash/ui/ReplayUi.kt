package dev.ponydash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.window.Dialog
import dev.ponydash.DashViewModel
import dev.ponydash.ReplayState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReplayDialog(vm: DashViewModel, onDismiss: () -> Unit) {
    val recs by vm.recordings.collectAsState()
    LaunchedEffect(Unit) { vm.refreshRecordings() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(C.Tile, RoundedCornerShape(20.dp))
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Replay a drive", color = C.Value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Plays a recording back through the dashboard, Matrix and G-meter. " +
                    "Nothing is sent to the car, and the replay is not recorded.",
                color = C.Label, fontSize = 14.sp,
            )
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (recs.isEmpty()) {
                    Text("No recordings yet. Drive with the adapter connected, or push a CSV into Android/data/dev.ponydash/files/replay/.", color = C.Dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
                recs.forEach { r ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { vm.startReplay(r); onDismiss() }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(r.name, color = C.Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(r.detail, color = C.Dim, fontSize = 13.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Close", C.Label, onDismiss)
            }
        }
    }
}

/** Transport controls along the bottom while a replay runs. */
@Composable
fun ReplayBar(vm: DashViewModel, st: ReplayState, modifier: Modifier = Modifier) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(
        modifier
            .fillMaxWidth()
            .background(C.Tile.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
            .border(1.dp, C.Stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("REPLAY", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (st.loading) {
            Text("loading ${st.name}…", color = C.Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        } else {
            Chip(if (st.playing) "Pause" else "Play", C.Value) { vm.replayToggle() }
            Text(clock.format(Date(st.pos)), color = C.Value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val span = (st.end - st.start).coerceAtLeast(1)
            Slider(
                value = dragging ?: ((st.pos - st.start).toFloat() / span),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.replaySeek(st.start + (it * span).toLong()) }
                    dragging = null
                },
                colors = SliderDefaults.colors(thumbColor = C.Accent, activeTrackColor = C.Accent, inactiveTrackColor = C.Stroke),
                modifier = Modifier.weight(1f),
            )
            Text(clock.format(Date(st.end)), color = C.Dim, fontSize = 13.sp)
            for (x in listOf(1, 4, 16)) {
                Text(
                    "$x×",
                    color = if (st.speed == x) C.Bg else C.Label,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(if (st.speed == x) C.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .border(1.dp, C.Stroke, RoundedCornerShape(12.dp))
                        .clickableNoRipple { vm.replaySpeed(x) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Chip("Exit", C.Bad) { vm.stopReplay() }
    }
}
