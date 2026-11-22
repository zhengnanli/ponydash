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

class MainActivity : ComponentActivity() {
    private val vm: DashViewModel by viewModels()

    private val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lowRefreshRate()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var showConnect by remember { mutableStateOf(false) }
                var showDiag by remember { mutableStateOf(false) }
                var showMatrix by remember { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
                    vm.autoStart(res.values.all { it })
                }
                LaunchedEffect(Unit) {
                    if (hasPerms()) vm.autoStart(true) else launcher.launch(perms)
                }

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
                    )
                }
                if (showConnect) ConnectDialog(vm) { showConnect = false }
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
