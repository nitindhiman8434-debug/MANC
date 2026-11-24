package com.manc.voice.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manc.voice.Controller
import com.manc.voice.adb.AdbLauncher
import com.manc.voice.adb.LaunchLogic.Step
import com.manc.voice.adb.PairingNotifier
import com.manc.voice.bridge.BridgeHub
import com.manc.voice.service.VoiceService
import com.manc.voice.shizuku.ShizukuManager
import com.manc.voice.ui.components.SectionCard
import kotlinx.coroutines.delay

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

/**
 * Guided first run. No companion app: MANC pairs once with the phone's own
 * wireless debugging and starts its helper itself. Every state is detected, so
 * the screen always shows exactly one thing to do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit, notifGranted: Boolean, onRequestNotif: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val bridgeOk by BridgeHub.connected.collectAsState()
    val step by AdbLauncher.step.collectAsState()
    val error by AdbLauncher.error.collectAsState()
    val pairingPort by AdbLauncher.pairingPort.collectAsState()
    val shz by ShizukuManager.state.collectAsState()
    val run by Controller.run.collectAsState()
    val enrolled by Controller.enrolled.collectAsState()
    var tick by remember { mutableStateOf(0) }
    var code by remember { mutableStateOf("") }
    val hasWirelessDebugging = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val shizukuInstalled = remember(tick, shz) { isInstalled(ctx, SHIZUKU_PKG) }

    // keep every state live while the user hops to Settings and back
    LaunchedEffect(Unit) {
        while (true) {
            ShizukuManager.refresh(ctx)
            BridgeHub.refresh()
            val s = AdbLauncher.evaluate(ctx)
            if (s == Step.READY_TO_LAUNCH) AdbLauncher.ensureRunning(ctx)
            if (BridgeHub.bridge == null && !AdbLauncher.isPaired(ctx)) AdbLauncher.startPairingWatch(ctx)
            tick++
            delay(1500)
        }
    }
    LaunchedEffect(Unit) { if (!notifGranted) onRequestNotif() }
    DisposableEffect(Unit) { onDispose { AdbLauncher.stopPairingWatch(); PairingNotifier.cancel(ctx) } }

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
    val allDone = bridgeOk && enrolled
    // the voice teach needs the pipeline: force it on while we are here and not yet enrolled
    LaunchedEffect(bridgeOk, enrolled) {
        Controller.forceActive = bridgeOk && !enrolled
        VoiceService.evaluate(ctx)
    }
    DisposableEffect(Unit) { onDispose { Controller.forceActive = false; VoiceService.evaluate(ctx) } }
    LaunchedEffect(allDone) { if (allDone) { delay(600); onDone() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Get ready") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Later") }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Done once. After this, MANC protects every call by itself, also after a restart.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            StepCard(1, "Notifications", notifGranted,
                hint = if (notifGranted) "Allowed" else "Used once to type the pairing code, and to show the standing-by status.",
                action = if (notifGranted) null else "Allow" to onRequestNotif)

            // ---- step 2: connect (pair once) ----
            SectionCard {
                StepTitle(2, "Connect (one time)", bridgeOk)
                Spacer(Modifier.height(6.dp))
                when {
                    bridgeOk -> Hint(if (BridgeHub.source == BridgeHub.Source.SHIZUKU) "Connected through Shizuku" else "Connected")

                    !hasWirelessDebugging -> {
                        Hint("This Android version has no wireless debugging, so MANC needs the free Shizuku helper app here.")
                        ShizukuButtons(ctx, shizukuInstalled, shz)
                    }

                    step == Step.NEED_DEV_OPTIONS -> {
                        Hint("Turn on Developer options: open About phone, then Software information, and tap “Build number” 7 times. Then come back here.")
                        FullButton("Open About phone") { open(ctx, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                    }

                    step == Step.NEED_WIFI -> {
                        Hint("Connect to Wi-Fi. Android allows this one-time step only on Wi-Fi; calls on mobile data are protected afterwards.")
                        FullButton("Open Wi-Fi settings") { open(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    }

                    step == Step.NEED_WIRELESS_DEBUGGING -> {
                        Hint(if (AdbLauncher.isPaired(ctx)) "Turn “Wireless debugging” on once so protection can restart."
                             else "Open Developer options and turn on “Wireless debugging”. Then tap its name and choose “Pair device with pairing code”.")
                        FullButton("Open Developer options") { openDeveloperOptions(ctx) }
                    }

                    step == Step.NEED_PAIRING -> {
                        Hint(if (pairingPort > 0)
                                "Pairing dialog found. Pull down the notification bar and type the 6 digits into the MANC notification. In split screen you can also type them below."
                             else
                                "In Developer options tap “Wireless debugging”, then “Pair device with pairing code”. A MANC notification will ask for the 6 digits.")
                        FullButton("Open Developer options") { openDeveloperOptions(ctx) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                                label = { Text("Pairing code") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(enabled = code.length == 6, onClick = { AdbLauncher.pair(ctx, code); code = "" }) { Text("Pair") }
                        }
                    }

                    else -> { // WORKING / READY_TO_LAUNCH
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Hint("Connecting…")
                        }
                        if (error.isNotEmpty() && step == Step.READY_TO_LAUNCH) {
                            Spacer(Modifier.height(8.dp))
                            FullButton("Try again") { AdbLauncher.ensureRunning(ctx, force = true) }
                        }
                    }
                }
                if (!bridgeOk && error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!bridgeOk && hasWirelessDebugging && shizukuInstalled) {
                    TextButton(onClick = {
                        if (shz == ShizukuManager.State.NEED_PERMISSION) ShizukuManager.requestPermission() else openShizuku(ctx)
                    }) { Text("Already use Shizuku? Use it instead") }
                }
            }

            StepCard(3, "Teach it your voice (5 s)", enrolled,
                hint = when {
                    enrolled -> "Done. The mic opens only for you."
                    run != Controller.Run.RUNNING -> "Becomes available as soon as step 2 is connected."
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
private fun StepTitle(n: Int, title: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "$n", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FullButton(label: String, onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun ShizukuButtons(ctx: Context, installed: Boolean, shz: ShizukuManager.State) {
    when {
        !installed -> FullButton("Install Shizuku") { openPlayStore(ctx) }
        shz == ShizukuManager.State.NEED_PERMISSION -> FullButton("Grant") { ShizukuManager.requestPermission() }
        else -> FullButton("Open Shizuku") { openShizuku(ctx) }
    }
}

@Composable
private fun StepCard(n: Int, title: String, done: Boolean, hint: String, action: Pair<String, () -> Unit>?) {
    SectionCard {
        StepTitle(n, title, done)
        Spacer(Modifier.height(6.dp))
        Hint(hint)
        action?.let { (label, act) -> FullButton(label, act) }
    }
}

private fun isInstalled(ctx: Context, pkg: String): Boolean =
    try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

/** Developer options, scrolled to and highlighting the Wireless debugging row where supported. */
private fun openDeveloperOptions(ctx: Context) {
    open(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
}

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
