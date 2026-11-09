package com.example.feature.tools

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.apps.InstalledAppsManager
import com.example.core.apps.InstalledApp
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(
    manager: InstalledAppsManager,
    cacheDir: File,
    onBack: () -> Unit
) {
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var includeSystem by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        isLoading = true
        apps = manager.listInstalledApps(includeSystem)
        isLoading = false
    }

    LaunchedEffect(includeSystem) {
        reload()
    }

    val displayed = remember(apps, filter) {
        if (filter.isBlank()) apps
        else apps.filter { it.appName.contains(filter, ignoreCase = true) || it.packageName.contains(filter, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Installed Apps (${displayed.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("System")
                        Checkbox(checked = includeSystem, onCheckedChange = { includeSystem = it })
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Search apps...") },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(displayed) { app ->
                        ListItem(
                            headlineContent = { Text(app.appName, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    "${app.packageName} • v${app.versionName} • ${formatSize(app.apkSizeBytes)}" +
                                    if (app.isSystemApp) " • System" else ""
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        scope.launch {
                                            manager.extractApk(app, cacheDir)
                                        }
                                    }) {
                                        Icon(Icons.Filled.FileDownload, "Extract APK")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkInfoScreen(
    app: InstalledApp,
    manager: InstalledAppsManager,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APK Info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            InfoRow("App Name", app.appName)
            InfoRow("Package", app.packageName)
            InfoRow("Version", app.versionName ?: "Unknown")
            InfoRow("Version Code", app.versionCode.toString())
            InfoRow("APK Path", app.sourceDir)
            InfoRow("APK Size", formatSize(app.apkSizeBytes))
            InfoRow("System App", if (app.isSystemApp) "Yes" else "No")
            InfoRow("Has Native Libs", if (app.hasNativeLibs) "Yes" else "No")
            InfoRow("First Install", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(app.firstInstallTime)))
            InfoRow("Last Update", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(app.lastUpdateTime)))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var b = bytes.toDouble()
    var i = 0
    while (b >= 1024 && i < units.size - 1) { b /= 1024; i++ }
    return "%.2f %s".format(b, units[i])
}
