package com.manc.voice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.manc.voice.Controller
import com.manc.voice.service.VoiceService
import androidx.compose.ui.platform.LocalContext
import com.manc.voice.ui.components.SectionCard
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollScreen(onBack: () -> Unit) {
    val run by Controller.run.collectAsState()
    val enrolled by Controller.enrolled.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Controller.forceActive = true; VoiceService.evaluate(ctx) }
    DisposableEffect(Unit) { onDispose { Controller.forceActive = false; VoiceService.evaluate(ctx) } }
    var counting by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }

    LaunchedEffect(counting) {
        if (counting) {
            Controller.enrollRequested = true
            for (s in 6 downTo 1) { secondsLeft = s; delay(1000) }
            Controller.enrollFinishRequested = true
            counting = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Teach my voice") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SectionCard("Voice-lock enrollment") {
                Text("The voice-lock opens the mic only for you. Read any sentence normally for ~5 seconds in a quiet spot.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (run != Controller.Run.RUNNING) {
                Text("Waiting for the bridge... (turn protection on and make sure Shizuku is running)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            if (counting) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Keep talking... $secondsLeft", style = MaterialTheme.typography.titleMedium)
            } else {
                Button(onClick = { counting = true }, enabled = run == Controller.Run.RUNNING,
                    modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Record 5 seconds")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(if (enrolled) "Voiceprint saved. Voice-lock is active." else "No voiceprint yet.",
                color = if (enrolled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
