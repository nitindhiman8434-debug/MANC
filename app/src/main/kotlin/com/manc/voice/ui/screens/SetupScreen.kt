package com.manc.voice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manc.voice.shizuku.ShizukuManager
import com.manc.voice.ui.components.SectionCard
import com.manc.voice.ui.components.StatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val shz by ShizukuManager.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Setup") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            SectionCard("1. Shizuku") {
                Text("MANC needs a one-time developer-level bridge called Shizuku. It runs with " +
                        "shell access so the cleaned audio can replace your microphone inside calling apps. " +
                        "MANC never gets this access directly; the bridge does, and only while you run it.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                StatusDot(shz == ShizukuManager.State.CONNECTED, "State: $shz")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ShizukuManager.requestPermission() }) { Text("Grant Shizuku") }
                    OutlinedButton(onClick = { ShizukuManager.refresh(ctx) }) { Text("Refresh") }
                }
            }
            SectionCard("How to start Shizuku") {
                Text("• Install Shizuku from the Play Store or GitHub.\n" +
                        "• On Android 11+ you can start it on-device with Wireless debugging (no PC).\n" +
                        "• Open Shizuku, start the service, then come back and tap Grant.\n" +
                        "• After a reboot, start Shizuku once more (Android restriction).",
                    style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard("2. Microphone & notifications") {
                Text("When you first tap Start, Android asks for microphone and notification access. " +
                        "Both are required for a live, visible audio service.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
