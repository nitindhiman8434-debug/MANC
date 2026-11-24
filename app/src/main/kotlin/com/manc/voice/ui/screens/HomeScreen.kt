package com.manc.voice.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manc.voice.BuildConfig
import com.manc.voice.Controller
import com.manc.voice.data.Prefs
import com.manc.voice.service.VoiceService
import com.manc.voice.adb.AdbLauncher
import com.manc.voice.bridge.BridgeHub
import com.manc.voice.ui.components.LevelBar
import com.manc.voice.ui.components.SectionCard

/**
 * One screen, one button. Everything is on by default at full strength and cleans
 * everywhere; the rare knobs live under "Advanced".
 */
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
    val bridgeOk by BridgeHub.connected.collectAsState()
    val sinks by Controller.sinks.collectAsState()
    val inCall by Controller.inCall.collectAsState()
    val bridgeHint by Controller.bridgeHint.collectAsState()
    val ctx = LocalContext.current
    var runNow by remember { mutableStateOf(Controller.forceActive) }

    var strength by remember { mutableStateOf(Controller.config.strength) }
    var neuralWet by remember { mutableStateOf(Controller.config.neuralWet) }
    var voiceLock by remember { mutableStateOf(Controller.config.voiceLockEnabled) }
    var denoise by remember { mutableStateOf(Controller.config.denoiseEnabled) }
    var advanced by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }

    val running = run == Controller.Run.RUNNING || run == Controller.Run.STARTING
    val protectionOn = run != Controller.Run.STOPPED

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text("MANC Voice", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Only your voice reaches the other side", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        if (!bridgeOk || !enrolled) {
            // something is missing: one clear call to action, nothing else to decide
            SectionCard {
                Text(if (!bridgeOk) "Finish the one-time setup to start." else "Teach it your voice once so it opens only for you.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onSetup, Modifier.fillMaxWidth().height(52.dp)) { Text("Finish setup") }
            }
        }

        Button(
            onClick = { if (protectionOn) onStop() else onStart() },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (protectionOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (protectionOn) "Turn protection off" else "Turn protection on", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                running && inCall -> "In a call: protecting your voice" + (if (sinks.isNotEmpty()) " ($sinks)" else "")
                running -> "Running (test)"
                !protectionOn -> "Off. Calls go out unprocessed."
                bridgeHint.isNotEmpty() -> bridgeHint
                else -> "Standing by. Starts by itself when a call connects, stops when it ends."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (bridgeHint.isNotEmpty() && !running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard("Live") {
            LevelBar("You", meters.inputRms.coerceIn(0f, 1f) * 4f)
            LevelBar("What the other side hears", meters.outputRms.coerceIn(0f, 1f) * 4f,
                tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Voice match ${(meters.ownerSimilarity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Text(if (meters.gate > 0.5f) "Mic open" else "Muted",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (meters.gate > 0.5f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (meters.usingOnnxDenoiser || meters.usingOnnxEmbedder) {
                Text(buildString {
                    if (meters.usingOnnxDenoiser) append("Neural denoiser")
                    if (meters.usingOnnxDenoiser && meters.usingOnnxEmbedder) append(" + ")
                    if (meters.usingOnnxEmbedder) append("neural voiceprint")
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        // ---- Advanced (collapsed by default) ----
        Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { advanced = !advanced },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Advanced", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (advanced) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
                }
                if (advanced) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Run now without a call (test)", Modifier.weight(1f))
                        Switch(checked = runNow, onCheckedChange = { runNow = it; Controller.forceActive = it; VoiceService.evaluate(ctx) })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Only my voice (voice-lock)", Modifier.weight(1f))
                        Switch(checked = voiceLock, onCheckedChange = { voiceLock = it; Controller.config.voiceLockEnabled = it })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Noise removal", Modifier.weight(1f))
                        Switch(checked = denoise, onCheckedChange = { denoise = it; Controller.config.denoiseEnabled = it })
                    }
                    Text("Strength", style = MaterialTheme.typography.labelMedium)
                    Slider(value = strength, onValueChange = { strength = it; Controller.config.strength = it },
                        onValueChangeFinished = { Prefs.saveStrengthAsync(ctx, strength) }, valueRange = 0f..1f)
                    if (meters.usingOnnxDenoiser) {
                        Text("Neural depth (lower if your voice sounds thin)", style = MaterialTheme.typography.labelMedium)
                        Slider(value = neuralWet, onValueChange = { neuralWet = it; Controller.config.neuralWet = it },
                            onValueChangeFinished = { Prefs.saveNeuralWetAsync(ctx, neuralWet) }, valueRange = 0.3f..1f)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Cleans everywhere by default: every app that uses the microphone, plus cellular calls on phones that allow it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { Controller.setMode(Controller.Mode.VOIP); onApps() }) {
                        Text(target?.let { "Limited to $it (tap to change)" } ?: "Limit to one app")
                    }
                    if (target != null) {
                        TextButton(onClick = { Controller.setTarget(null); Controller.setMode(Controller.Mode.AUTO) }) { Text("Back to everywhere") }
                    }
                    TextButton(onClick = onEnroll) { Text(if (enrolled) "Re-teach my voice" else "Teach my voice") }
                    TextButton(onClick = onCellular) { Text("Cellular Lab (device check)") }
                    TextButton(onClick = onSetup) { Text("Setup details") }
                    TextButton(onClick = { diagnostics = !diagnostics }) {
                        Text(if (diagnostics) "Hide connection diagnostics" else "Connection diagnostics")
                    }
                    if (diagnostics) {
                        // one screenshot of this block is a complete failure report
                        Text(AdbLauncher.diagnostics(ctx), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { AdbLauncher.ensureRunning(ctx, force = true) }) { Text("Reconnect now") }
                    }
                    Text(
                        "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
