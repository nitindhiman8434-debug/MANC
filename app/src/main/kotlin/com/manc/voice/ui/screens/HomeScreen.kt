package com.manc.voice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.manc.voice.data.Prefs
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manc.voice.Controller
import com.manc.voice.shizuku.ShizukuManager
import com.manc.voice.ui.components.LevelBar
import com.manc.voice.ui.components.SectionCard
import com.manc.voice.ui.components.StatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSetup: () -> Unit,
    onApps: () -> Unit,
    onEnroll: () -> Unit,
    onCellular: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val run by Controller.run.collectAsState()
    val meters by Controller.meters.collectAsState()
    val status by Controller.status.collectAsState()
    val target by Controller.targetPackage.collectAsState()
    val enrolled by Controller.enrolled.collectAsState()
    val shz by ShizukuManager.state.collectAsState()
    val sinks by Controller.sinks.collectAsState()
    val ctx = LocalContext.current

    var strength by remember { mutableStateOf(Controller.config.strength) }
    var voiceLock by remember { mutableStateOf(Controller.config.voiceLockEnabled) }
    var denoise by remember { mutableStateOf(Controller.config.denoiseEnabled) }

    val running = run == Controller.Run.RUNNING || run == Controller.Run.STARTING

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text("MANC Voice", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Clean outgoing voice for any call", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        // Big start/stop
        Button(
            onClick = { if (running) onStop() else onStart() },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (running) "Stop" else "Start cleaning", style = MaterialTheme.typography.titleMedium)
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Live") {
            LevelBar("Your input", meters.inputRms.coerceIn(0f, 1f) * 4f)
            LevelBar("Sent to the other side", meters.outputRms.coerceIn(0f, 1f) * 4f,
                tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Voice match: ${(meters.ownerSimilarity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium)
                Text(if (meters.gate > 0.5f) "Mic OPEN" else "Muted",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (meters.gate > 0.5f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (meters.usingOnnxEmbedder) Text("Using neural voiceprint", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }

        SectionCard("Where it cleans") {
            Text("Everywhere, automatically: every app that uses your microphone gets the cleaned voice, " +
                    "and cellular calls too on phones that allow it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (running && sinks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Active now: $sinks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                Controller.setMode(Controller.Mode.VOIP); onApps()
            }) { Text(target?.let { "Advanced: limited to $it (tap to change)" } ?: "Advanced: limit to one app") }
            if (target != null) {
                TextButton(onClick = { Controller.setTarget(null); Controller.setMode(Controller.Mode.AUTO) }) {
                    Text("Back to everywhere")
                }
            }
        }

        SectionCard("Controls") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Only my voice (voice-lock)", Modifier.weight(1f))
                Switch(checked = voiceLock, onCheckedChange = { voiceLock = it; Controller.config.voiceLockEnabled = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Noise removal", Modifier.weight(1f))
                Switch(checked = denoise, onCheckedChange = { denoise = it; Controller.config.denoiseEnabled = it })
            }
            Spacer(Modifier.height(6.dp))
            Text("Strength", style = MaterialTheme.typography.labelMedium)
            Slider(value = strength, onValueChange = { strength = it; Controller.config.strength = it }, onValueChangeFinished = { Prefs.saveStrengthAsync(ctx, strength) }, valueRange = 0f..1f)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onEnroll, Modifier.fillMaxWidth()) {
                Text(if (enrolled) "Re-teach my voice" else "Teach the app my voice")
            }
        }

        SectionCard("Setup") {
            StatusDot(shz == ShizukuManager.State.CONNECTED,
                when (shz) {
                    ShizukuManager.State.CONNECTED -> "Shizuku bridge connected"
                    ShizukuManager.State.NEED_PERMISSION -> "Shizuku needs permission"
                    ShizukuManager.State.CONNECTING -> "Connecting to bridge..."
                    ShizukuManager.State.UNAVAILABLE -> "Shizuku not running"
                    ShizukuManager.State.ERROR -> "Bridge error"
                })
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSetup, Modifier.fillMaxWidth()) { Text("Open setup") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onCellular, Modifier.fillMaxWidth()) { Text("Cellular Lab (device check)") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
