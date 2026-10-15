package kr.koseco.gandansurvey.v90

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kr.koseco.gandansurvey.v90.gnss.GnssForegroundService
import kr.koseco.gandansurvey.v90.ui.GandanSurveyMainApp
import kr.koseco.gandansurvey.v90.ui.GandanSurveyViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: GandanSurveyViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions granted or handled gracefully
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        startGnssForegroundService()

        setContent {
            val darkColors = darkColorScheme(
                primary = Color(0xFF38BDF8),
                secondary = Color(0xFF22C55E),
                tertiary = Color(0xFFFACC15),
                background = Color(0xFF0B0F19),
                surface = Color(0xFF131B2E),
                onPrimary = Color(0xFF0B0F19),
                onBackground = Color(0xFFF1F5F9),
                onSurface = Color(0xFFF1F5F9)
            )
            MaterialTheme(colorScheme = darkColors) {
                GandanSurveyMainApp(viewModel = viewModel)
            }
        }
    }

    /**
     * Hardware Volume Key Shortcuts:
     * - VOLUME_UP: Opens standard survey point dialog (same as tapping main Measure button)
     * - VOLUME_DOWN: Immediately re-measures using the previous point's code/epoch settings
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                viewModel.onVolumeUpPressed()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                viewModel.onVolumeDownPressed()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startGnssForegroundService() {
        try {
            val intent = Intent(this, GnssForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
        }
    }
}
