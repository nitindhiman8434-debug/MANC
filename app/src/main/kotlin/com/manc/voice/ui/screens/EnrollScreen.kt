package com.manc.voice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manc.voice.Controller
import com.manc.voice.bridge.BridgeHub
import com.manc.voice.service.VoiceService
import com.manc.voice.ui.components.SectionCard
import com.manc.voice.ui.components.VoiceTeach

/** "Re-teach my voice": the same recording as in first-run setup, with a way back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollScreen(onBack: () -> Unit) {
    val run by Controller.run.collectAsState()
    val status by Controller.status.collectAsState()
    val enrolled by Controller.enrolled.collectAsState()
    val bridgeOk by BridgeHub.connected.collectAsState()
    val ctx = LocalContext.current
    // the recording needs the pipeline: run it while this screen is open
    LaunchedEffect(Unit) { Controller.screenNeedsPipeline = true; VoiceService.evaluate(ctx) }
    DisposableEffect(Unit) { onDispose { Controller.screenNeedsPipeline = false; VoiceService.evaluate(ctx) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Teach my voice") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            SectionCard {
                VoiceTeach(
                    ready = bridgeOk && run == Controller.Run.RUNNING,
                    notReadyHint = when {
                        !bridgeOk -> "Not connected yet. Go back and open “Setup details”."
                        status.isNotBlank() -> status
                        else -> "Opening the microphone…"
                    },
                    enrolled = enrolled
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
        }
    }
}
