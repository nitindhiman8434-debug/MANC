package com.manc.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.manc.voice.service.VoiceService
import com.manc.voice.data.Prefs
import com.manc.voice.shizuku.ShizukuManager
import com.manc.voice.ui.screens.*
import com.manc.voice.ui.theme.MancTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(applicationContext)
        // restore persisted settings
        val snap = Prefs.loadBlocking(applicationContext)
        snap.strength?.let { Controller.config.strength = it }
        snap.voiceprint?.let { Controller.enrolledProfile = it; Controller.setEnrolled(true) }
        Controller.autoStart = snap.autoStart

        setContent {
            MancTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                val shz by ShizukuManager.state.collectAsState()
                val run by Controller.run.collectAsState()
                // Just works: once the bridge is connected and permissions are already granted,
                // start cleaning automatically (no extra taps).
                LaunchedEffect(shz) {
                    if (shz == ShizukuManager.State.CONNECTED && Controller.autoStart &&
                        run == Controller.Run.STOPPED && hasMicPermission()) startService()
                }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            onSetup = { screen = Screen.SETUP },
                            onApps = { screen = Screen.APPS },
                            onEnroll = { screen = Screen.ENROLL },
                            onCellular = { screen = Screen.CELLULAR },
                            onStart = { ensureMicThen { startService() } },
                            onStop = { stopService() }
                        )
                        Screen.SETUP -> SetupScreen(onBack = { screen = Screen.HOME })
                        Screen.APPS -> AppsScreen(onBack = { screen = Screen.HOME })
                        Screen.ENROLL -> EnrollScreen(onBack = { screen = Screen.HOME })
                        Screen.CELLULAR -> CellularScreen(onBack = { screen = Screen.HOME })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuManager.refresh(applicationContext)
    }

    private var pendingAction: (() -> Unit)? = null
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingAction?.invoke(); pendingAction = null
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun ensureMicThen(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) action() else { pendingAction = action; micLauncher.launch(needed.toTypedArray()) }
    }

    private fun startService() {
        val i = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun stopService() {
        startService(Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_STOP })
    }

    enum class Screen { HOME, SETUP, APPS, ENROLL, CELLULAR }
}
