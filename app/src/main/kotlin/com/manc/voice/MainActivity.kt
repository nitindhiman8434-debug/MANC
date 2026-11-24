package com.manc.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.manc.voice.data.Prefs
import com.manc.voice.service.VoiceService
import com.manc.voice.shizuku.ShizukuManager
import com.manc.voice.ui.screens.*
import com.manc.voice.ui.theme.MancTheme

class MainActivity : ComponentActivity() {

    private val notifGrantedState = mutableStateOf(false)
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifGrantedState.value = hasNotifPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(applicationContext)
        // restore persisted settings
        val snap = Prefs.loadBlocking(applicationContext)
        snap.strength?.let { Controller.config.strength = it }
        snap.neuralWet?.let { Controller.config.neuralWet = it }
        snap.voiceprint?.let { Controller.enrolledProfile = it; Controller.setEnrolled(true) }
        snap.voiceprintNeural?.let { Controller.enrolledNeural = it }
        Controller.autoStart = snap.autoStart
        notifGrantedState.value = hasNotifPermission()
        // protection on by default: standby service, auto-activates on calls
        if (Controller.autoStart) VoiceService.start(this)

        setContent {
            MancTheme {
                var screen by remember {
                    mutableStateOf(if (Controller.enrolled.value) Screen.HOME else Screen.SETUP)
                }
                val notifGranted by notifGrantedState
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            onSetup = { screen = Screen.SETUP },
                            onApps = { screen = Screen.APPS },
                            onEnroll = { screen = Screen.ENROLL },
                            onCellular = { screen = Screen.CELLULAR },
                            onStart = { requestNotifIfNeeded(); VoiceService.start(this) },
                            onStop = { VoiceService.stop(this) }
                        )
                        Screen.SETUP -> SetupScreen(
                            onBack = { screen = Screen.HOME },
                            notifGranted = notifGranted,
                            onRequestNotif = { requestNotifIfNeeded() },
                            onDone = { screen = Screen.HOME }
                        )
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
        notifGrantedState.value = hasNotifPermission()
    }

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestNotifIfNeeded() {
        if (!hasNotifPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    enum class Screen { HOME, SETUP, APPS, ENROLL, CELLULAR }
}
