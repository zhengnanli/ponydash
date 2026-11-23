package dev.ponydash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.ponydash.DashViewModel
import dev.ponydash.Link
import dev.ponydash.obd.Pids

@Composable
fun ConnectDialog(vm: DashViewModel, onDismiss: () -> Unit) {
    val items by vm.scan.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val conn by vm.conn.collectAsState()
    LaunchedEffect(Unit) { vm.startScan() }

    Dialog(onDismissRequest = { vm.stopScan(); onDismiss() }) {
        Column(
            Modifier
                .width(560.dp)
                .background(C.Tile, RoundedCornerShape(20.dp))
                .border(1.dp, C.Stroke, RoundedCornerShape(20.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("OBD adapter", color = C.Value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Plug the iCar into the OBD port and turn the ignition on. " +
                    "Adapters whose name looks like OBD are listed first.",
                color = C.Label, fontSize = 14.sp,
            )
            Column(Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) {
                    Text(if (scanning) "Scanning…" else "Nothing found.", color = C.Dim, fontSize = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
                items.forEach { it ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { vm.connect(it.address, it.name); onDismiss() }
                            .padding(vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name, color = if (it.looksLikeObd) C.Accent else C.Value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(it.address, color = C.Dim, fontSize = 12.sp)
                        }
                        Text("${it.rssi} dBm", color = C.Dim, fontSize = 13.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(if (scanning) "Scanning…" else "Scan again") { if (!scanning) vm.startScan() }
                Chip("Simulator", C.Warn) { vm.stopScan(); vm.startSim(); onDismiss() }
                if (vm.savedDevice != null && conn.link != Link.SIM) {
                    Chip("Forget adapter", C.Bad) { vm.stopScan(); vm.forgetDevice(); onDismiss() }
                }
                Spacer(Modifier.weight(1f))
                Chip("Close", C.Label) { vm.stopScan(); onDismiss() }
            }
        }
    }
}
