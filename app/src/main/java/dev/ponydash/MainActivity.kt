package dev.ponydash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.ponydash.ui.ConnectDialog
import dev.ponydash.ui.DashScreen
import dev.ponydash.ui.DiagScreen
import dev.ponydash.ui.MatrixScreen
import dev.ponydash.ui.ReplayBar
import dev.ponydash.ui.ReplayDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: DashViewModel by viewModels()

    private val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lowRefreshRate()
        vm.setSystemDark(isSystemDark(resources.configuration))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = dev.ponydash.ui.Fonts.typography) {
                var showConnect by remember { mutableStateOf(false) }
                var showDiag by remember { mutableStateOf(false) }
                var showMatrix by remember { mutableStateOf(false) }
                var showReplay by remember { mutableStateOf(false) }
                val replay by vm.replay.collectAsState()
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                    vm.autoStart(res.values.all { it })
                }
                LaunchedEffect(Unit) {
                    if (hasPerms()) vm.autoStart(true) else launcher.launch(perms)
                }

                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(dev.ponydash.ui.C.Bg)) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                if (showDiag) {
                    DiagScreen(vm) { showDiag = false }
                } else if (showMatrix) {
                    MatrixScreen(vm) { showMatrix = false }
                } else {
                    DashScreen(
                        vm,
                        onOpenConnect = { if (hasPerms()) showConnect = true else launcher.launch(perms) },
                        onOpenDiag = { showDiag = true },
                        onOpenMatrix = { showMatrix = true },
                        onOpenReplay = { showReplay = true },
                    )
                }
                }
                replay?.let { ReplayBar(vm, it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
                }
                if (showConnect) ConnectDialog(vm) { showConnect = false }
                if (showReplay) ReplayDialog(vm) { showReplay = false }
            }
        }
    }

    /**
     * The screen is ~93% of this app's power. Data changes a few times a second,
     * so ask for the panel's slowest mode at or above 30 Hz instead of 120 Hz.
     */
    private fun lowRefreshRate() {
        val d = display ?: return
        val cur = d.mode
        val mode = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && it.refreshRate >= 29.5f }
            .minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

    private fun isSystemDark(c: android.content.res.Configuration) =
        (c.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** uiMode is in configChanges, so a light/dark switch re-themes without restarting the activity. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.setSystemDark(isSystemDark(newConfig))
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
