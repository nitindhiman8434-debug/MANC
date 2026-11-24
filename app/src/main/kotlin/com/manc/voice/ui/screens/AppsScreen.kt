package com.manc.voice.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.manc.voice.Controller

data class AppRow(val label: String, val pkg: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val target by Controller.targetPackage.collectAsState()
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }

    LaunchedEffect(Unit) { apps = loadApps(ctx) }

    Scaffold(topBar = { TopAppBar(title = { Text("Pick calling app") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(apps) { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { Controller.setTarget(app.pkg); onBack() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.pkg, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (app.pkg == target) Text("Selected", color = MaterialTheme.colorScheme.secondary)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun loadApps(ctx: Context): List<AppRow> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(intent, 0)
    val priority = setOf(
        "com.whatsapp", "org.telegram.messenger", "com.discord", "us.zoom.videomeetings",
        "com.google.android.apps.tachyon", "com.microsoft.teams", "com.skype.raider",
        "com.facebook.orca", "com.viber.voip", "com.android.dialer", "com.google.android.dialer",
        "com.samsung.android.dialer"
    )
    val rows = resolved.mapNotNull { ri ->
        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
        val label = ri.loadLabel(pm).toString()
        AppRow(label, pkg)
    }.distinctBy { it.pkg }
    return rows.sortedWith(compareByDescending<AppRow> { it.pkg in priority }.thenBy { it.label.lowercase() })
}
