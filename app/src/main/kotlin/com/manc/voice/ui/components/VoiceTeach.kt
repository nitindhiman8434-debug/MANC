package com.manc.voice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manc.voice.Controller
import kotlinx.coroutines.delay

/**
 * The 5-second voice recording, used by first-run setup and by "Re-teach my voice".
 * It always shows that the microphone reacts, counts down in the open, says how much
 * of the speaker's voice was heard, and ends with a plain result. It never navigates
 * on its own.
 */
@Composable
fun VoiceTeach(ready: Boolean, notReadyHint: String, enrolled: Boolean) {
    val meters by Controller.meters.collectAsState()
    val result by Controller.enrollResult.collectAsState()
    var counting by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }

    LaunchedEffect(counting) {
        if (counting) {
            Controller.setEnrollResult(Controller.EnrollResult.NONE)
            Controller.enrollRequested = true
            for (s in 5 downTo 1) { secondsLeft = s; delay(1000) }
            Controller.enrollFinishRequested = true
            counting = false
        }
    }

    if (!ready) {
        Text(notReadyHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    Text(
        "Hold the phone the way you do on a call, or wear your earbuds. Tap the button, then talk at your normal volume for 5 seconds. Any language works. For example:",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "“Hello, this is my voice. I am teaching my phone to recognise me, so that on every call only my voice goes through.”",
        style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic
    )
    Spacer(Modifier.height(8.dp))
    // proof that the microphone is alive before and during the recording
    LevelBar("Microphone (should move when you talk)", meters.inputRms.coerceIn(0f, 1f) * 4f)

    if (counting) {
        Spacer(Modifier.height(8.dp))
        Text("Keep talking… $secondsLeft", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text("Your voice heard so far: ${"%.1f".format(meters.enrollVoicedMs / 1000f)} s",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Spacer(Modifier.height(10.dp))
        Button(onClick = { counting = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (enrolled) "Record again (5 seconds)" else "Start recording (5 seconds)")
        }
    }

    Spacer(Modifier.height(8.dp))
    when {
        counting -> {}
        result == Controller.EnrollResult.SAVED ->
            Text("Saved. From now on the mic opens only for this voice.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        result == Controller.EnrollResult.TOO_LITTLE_SPEECH ->
            Text("I could not hear enough of your voice, so nothing was saved. Check that the microphone bar moves when you talk, then record again and keep talking for the whole 5 seconds.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        enrolled ->
            Text("A voice is already saved. Record again only if the mic does not open for you on calls.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
