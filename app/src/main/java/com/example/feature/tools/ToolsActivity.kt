package com.example.feature.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.*
import com.example.core.settings.SettingsManager
import com.example.core.storage.RecycleBin
import com.example.core.apps.InstalledAppsManager
import com.example.ui.theme.MTExplorerTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

class ToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tool = intent.getStringExtra("tool") ?: "HUB"
        setContent {
            MTExplorerTheme {
                ToolsHub(onBack = { finish() }, initialTool = tool)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHub(onBack: () -> Unit, initialTool: String) {
    var currentTool by remember { mutableStateOf(if (initialTool == "HUB") "HUB" else initialTool) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val recycleBin = remember { RecycleBin(File(context.filesDir, "trash")) }
    val settingsManager = remember { SettingsManager(context) }
    val appsManager = remember { InstalledAppsManager(context.packageManager) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools — $currentTool") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentTool == "HUB") onBack() else currentTool = "HUB"
                    }) { Icon(Icons.Filled.ArrowBack, "Back") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (currentTool) {
                "HUB" -> ToolsHubList(onSelect = { currentTool = it })
                "RECYCLE_BIN" -> RecycleBinScreen(recycleBin = recycleBin, onBack = { currentTool = "HUB" })
                "CHECK" -> ChecksumScreen(file = File("/storage/emulated/0"), onBack = { currentTool = "HUB" })
                "DUPLICATES" -> DuplicateFinderScreen(root = File("/storage/emulated/0"), onBack = { currentTool = "HUB" })
                "ANALYZER" -> StorageAnalyzerScreen(root = File("/storage/emulated/0"), onBack = { currentTool = "HUB" })
                "APPS" -> InstalledAppsScreen(manager = appsManager, cacheDir = File(context.cacheDir, "apks"), onBack = { currentTool = "HUB" })
                "TERMINAL" -> TerminalScreen(onBack = { currentTool = "HUB" })
                "SETTINGS" -> com.example.feature.settings.SettingsScreen(settingsManager = settingsManager, onBack = { currentTool = "HUB" })
                "NETWORK" -> com.example.feature.network.NetworkStorageScreen(
                    profiles = emptyList(), onBack = { currentTool = "HUB" },
                    onAddProfile = {}, onConnect = {}
                )
            }
        }
    }
}

@Composable
fun ToolsHubList(onSelect: (String) -> Unit) {
    val items = listOf(
        "RECYCLE_BIN" to "Recycle Bin",
        "CHECK" to "Checksums",
        "DUPLICATES" to "Duplicate Finder",
        "ANALYZER" to "Storage Analyzer",
        "APPS" to "Installed Apps",
        "TERMINAL" to "Terminal",
        "SETTINGS" to "Settings",
        "NETWORK" to "Network Storage"
    )
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(items) { (key, label) ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onClick = { onSelect(key) }
            ) {
                ListItem(headlineContent = { Text(label) })
            }
        }
    }
}
