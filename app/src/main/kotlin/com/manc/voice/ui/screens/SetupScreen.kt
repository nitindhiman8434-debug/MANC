package com.manc.voice.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.manc.voice.Controller
import com.manc.voice.shizuku.ShizukuManager
import com.manc.voice.ui.components.SectionCard
import kotlinx.coroutines.delay

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

/**
 * Guided first-run: three checks, each auto-detected and auto-advanced where
 * Android allows it. The only unavoidable manual part is Shizuku (install once,
 * start it, allow once).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit, micGranted: Boolean, onRequestMic: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val shz by ShizukuManager.state.collectAsState()
    val run by Controller.run.collectAsState()
    val enrolled by Controller.enrolled.collectAsState()
    var tick by remember { mutableStateOf(0) }
    val shizukuInstalled = remember(tick, shz) { isInstalled(ctx, SHIZUKU_PKG) }

    // keep state live while the user hops to Play Store / Shizuku and back
    LaunchedEffect(Unit) { while (true) { delay(1500); ShizukuManager.refresh(ctx); tick++ } }
    // auto actions: ask for mic once, ask Shizuku permission as soon as it is possible
    LaunchedEffect(Unit) { if (!micGranted) onRequestMic() }
    LaunchedEffect(shz) { if (shz == ShizukuManager.State.NEED_PERMISSION) ShizukuManager.requestPermission() }

    var counting by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    LaunchedEffect(counting) {
        if (counting) {
            Controller.enrollRequested = true
            for (s in 5 downTo 1) { secondsLeft = s; delay(1000) }
            Controller.enrollFinishRequested = true
            counting = false
        }
    }
    val bridgeOk = shz == ShizukuManager.State.CONNECTED
    val allDone = micGranted && bridgeOk && enrolled
    LaunchedEffect(allDone) { if (allDone) { delay(600); onDone() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Get ready") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Later") }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Three quick checks. Everything that can be automatic is.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            StepCard(1, "Microphone & notifications", micGranted,
                hint = if (micGranted) "Allowed" else "Needed to hear you and to show the live status",
                action = if (micGranted) null else "Allow" to onRequestMic)

            StepCard(2, "Shizuku bridge", bridgeOk,
                hint = when {
                    bridgeOk -> "Connected"
                    !shizukuInstalled -> "One-time helper that lets MANC replace your microphone inside apps. Free, from the Play Store."
                    shz == ShizukuManager.State.UNAVAILABLE -> "Open Shizuku and tap Start (Wireless debugging). After a reboot you start it once more."
                    shz == ShizukuManager.State.NEED_PERMISSION -> "Tap Allow in the Shizuku dialog."
                    shz == ShizukuManager.State.CONNECTING -> "Connecting..."
                    else -> "Could not connect. Open Shizuku and make sure it is running."
                },
                action = when {
                    bridgeOk -> null
                    !shizukuInstalled -> "Install Shizuku" to { openPlayStore(ctx) }
                    shz == ShizukuManager.State.UNAVAILABLE || shz == ShizukuManager.State.ERROR -> "Open Shizuku" to { openShizuku(ctx) }
                    shz == ShizukuManager.State.NEED_PERMISSION -> "Grant" to { ShizukuManager.requestPermission() }
                    else -> null
                })

            StepCard(3, "Teach it your voice (5 s)", enrolled,
                hint = when {
                    enrolled -> "Done. The mic opens only for you."
                    run != Controller.Run.RUNNING -> "Becomes available as soon as the bridge connects."
                    counting -> "Keep talking normally... $secondsLeft"
                    else -> "Read any sentence normally in a quiet spot."
                },
                action = if (!enrolled && run == Controller.Run.RUNNING && !counting) "Record" to { counting = true } else null)

            if (counting) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            if (allDone) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth().height(56.dp)) { Text("Ready — protecting your voice") }
            }
        }
    }
}

@Composable
private fun StepCard(n: Int, title: String, done: Boolean, hint: String, action: Pair<String, () -> Unit>?) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓" else "$n", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, act) ->
            Spacer(Modifier.height(10.dp))
            Button(onClick = act, Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}

private fun isInstalled(ctx: Context, pkg: String): Boolean =
    try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

private fun openPlayStore(ctx: Context) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}

private fun openShizuku(ctx: Context) {
    try { ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (_: Exception) {}
}
