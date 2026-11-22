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

@Composable
fun DiagScreen(vm: DashViewModel, onClose: () -> Unit) {
    val log by vm.log.collectAsState()
    val supported by vm.supported.collectAsState()
    val values by vm.values.collectAsState()
    val conn by vm.conn.collectAsState()
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    Row(Modifier.fillMaxSize().background(C.Bg).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.4f).fillMaxHeight()) {
            Text("Log", color = C.Value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth()
                    .background(C.Tile, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                state = listState,
            ) {
                items(log) { Text(it, color = C.Label, fontSize = 13.sp, fontFamily = Fonts.Mono) }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    cmd, { cmd = it },
                    singleLine = true,
                    textStyle = TextStyle(color = C.Value, fontSize = 18.sp, fontFamily = Fonts.Mono),
                    cursorBrush = SolidColor(C.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.sendRaw(cmd); cmd = "" }),
                    modifier = Modifier.weight(1f)
                        .background(C.Tile, RoundedCornerShape(10.dp))
                        .border(1.dp, C.Stroke, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (cmd.isEmpty()) Text("ELM command, e.g. 0105 or ATSH7E0", color = C.Dim, fontSize = 18.sp, fontFamily = Fonts.Mono)
                        inner()
                    },
                )
                Chip("Send") { vm.sendRaw(cmd); cmd = "" }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Probe Ford PIDs") { vm.probeFord() }
                Spacer(Modifier.weight(1f))
                Chip("Back to dash", C.Label, onClose)
            }
            Text("Link: ${conn.link} ${conn.device ?: ""} · ${conn.detail}", color = C.Label, fontSize = 14.sp)
            Text("Standard PIDs the car reports", color = C.Value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (supported.isEmpty()) "— (connect to the car first)"
                else supported.sorted().joinToString("\n") { code ->
                    val n = Pids.name(code)
                    "%02X  %s".format(code, n ?: "")
                },
                color = C.Label, fontSize = 13.sp, fontFamily = Fonts.Mono,
            )
            Text("Live values", color = C.Value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                values.entries.sortedBy { it.key }.joinToString("\n") { "%-14s %.2f".format(it.key, it.value) },
                color = C.Label, fontSize = 13.sp, fontFamily = Fonts.Mono,
            )
            Text(
                "Console sends raw ELM327 commands. Stick to reads (01xx, 22xxxx, AT…). " +
                    "The probe only sends mode-22 reads.",
                color = C.Dim, fontSize = 12.sp,
            )
        }
    }
}
