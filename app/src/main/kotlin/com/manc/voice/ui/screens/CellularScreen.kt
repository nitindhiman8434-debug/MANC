package com.manc.voice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.manc.voice.bridge.BridgeHub
import com.manc.voice.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellularScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var verdict by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Cellular Lab (beta)") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            SectionCard("Why a lab?") {
                Text("Regular phone-network (cellular) calls run through the modem. Only some phones let " +
                        "software touch that audio. This check asks your device, during whatever state it is " +
                        "in now, whether the cellular path is possible. Run it again during an active call for " +
                        "the most accurate answer.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        try { BridgeHub.bridge?.probeCellular() } catch (e: Exception) { "{\"verdict\":\"PROBE_FAILED\",\"error\":\"${e.message}\"}" }
                    }
                    report = r
                    verdict = try { JSONObject(r ?: "{}").optString("verdict", "UNKNOWN") } catch (e: Exception) { "UNKNOWN" }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Checking..." else "Run device check")
            }
            Spacer(Modifier.height(12.dp))
            if (verdict.isNotEmpty()) {
                val friendly = when (verdict) {
                    "LIKELY_SUPPORTED" -> "Good news: this device looks able to clean cellular calls. Start cleaning in Cellular mode during a call."
                    "NOT_SUPPORTED_ON_THIS_DEVICE" -> "This device does not expose cellular audio to software. Cellular cleaning will not work here; use App calls instead."
                    "UNKNOWN" -> "Could not tell yet. Try again during an active cellular call."
                    else -> "Check failed. Finish setup first so the bridge is connected."
                }
                SectionCard("Result: $verdict") { Text(friendly, style = MaterialTheme.typography.bodyMedium) }
            }
            report?.let {
                SectionCard("Raw report") {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
