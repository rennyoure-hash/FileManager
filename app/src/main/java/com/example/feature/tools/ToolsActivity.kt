package com.example.feature.tools

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.apps.InstalledAppsManager
import com.example.core.settings.SettingsManager
import com.example.core.storage.RecycleBin
import com.example.ui.theme.MTExplorerTheme
import java.io.File

open class ToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fase 8b.6: Enforce FLAG_SECURE across ToolsActivity (Root Toolkit, Terminal, Vault, Network Credentials, Security Center)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        val tool = intent.getStringExtra("tool") ?: defaultTool()
        setContent {
            MTExplorerTheme {
                ToolsHub(onBack = { finish() }, initialTool = tool)
            }
        }
    }

    protected open fun defaultTool(): String = "HUB"
}

/**
 * Fase 8b.6: Dedicated TerminalActivity with `FLAG_SECURE` enforced in `onCreate`.
 */
class TerminalActivity : ToolsActivity() {
    override fun defaultTool(): String = "TERMINAL"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHub(onBack: () -> Unit, initialTool: String) {
    var currentTool by remember { mutableStateOf(if (initialTool == "HUB") "HUB" else initialTool) }
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val recycleBin = remember { RecycleBin(File(context.filesDir, "trash")) }
    val settingsManager = remember { SettingsManager(context) }
    val appsManager = remember { InstalledAppsManager(context.packageManager) }

    // Fase 8b.6: Ensure FLAG_SECURE stays active on all sensitive Tools screens (Terminal, Vault, Network Credentials, Root Toolkit, Security Center)
    DisposableEffect(currentTool) {
        val isSensitiveScreen = currentTool in setOf("ROOT_TOOLKIT", "NETWORK", "SECURITY_CENTER", "TERMINAL", "SETTINGS")
        if (isSensitiveScreen) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools — $currentTool") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentTool == "HUB") onBack() else currentTool = "HUB"
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (currentTool) {
                "HUB" -> ToolsHubList(onSelect = { currentTool = it })
                "SECURITY_CENTER" -> SecurityAndDiagnosticsScreen(onBack = { currentTool = "HUB" })
                "ROOT_TOOLKIT" -> RootSystemToolkitScreen(onBack = { currentTool = "HUB" })
                "RECYCLE_BIN" -> RecycleBinScreen(recycleBin = recycleBin, onBack = { currentTool = "HUB" })
                "CHECK" -> ChecksumScreen(file = File("/storage/emulated/0"), onBack = { currentTool = "HUB" })
                "DUPLICATES" -> DuplicateFinderScreen(root = File("/storage/emulated/0"), onBack = { currentTool = "HUB" })
                "ANALYZER" -> StorageAnalyzerScreen(root = File("/storage/emulated/0"), onBack = { currentTool = "HUB" })
                "APPS" -> InstalledAppsScreen(manager = appsManager, cacheDir = File(context.cacheDir, "apks"), onBack = { currentTool = "HUB" })
                "INJECT" -> InjectProviderScreen(onBack = { currentTool = "HUB" })
                "TERMINAL" -> TerminalScreen(onBack = { currentTool = "HUB" })
                "SETTINGS" -> com.example.feature.settings.SettingsScreen(settingsManager = settingsManager, onBack = { currentTool = "HUB" })
                "NETWORK" -> com.example.feature.network.NetworkStorageScreen(
                    profiles = emptyList(),
                    onBack = { currentTool = "HUB" },
                    onAddProfile = {},
                    onConnect = {}
                )
            }
        }
    }
}

@Composable
fun ToolsHubList(onSelect: (String) -> Unit) {
    val items = listOf(
        "SECURITY_CENTER" to "Security & Diagnostics (HMAC Log Chain, Integrity Watch, VT Keystore, Partisi RO)",
        "ROOT_TOOLKIT" to "Root & Sistem (Mount, Op Log & Undo, build.prop, Process, Logcat)",
        "DUPLICATES" to "Smart Duplicate Cleaner (4-Stage + Keep Policy + Recycle Bin)",
        "CHECK" to "Checksum & Verifikasi Hash (MD5 / SHA-1 / SHA-256 / SHA-512)",
        "RECYCLE_BIN" to "Recycle Bin",
        "ANALYZER" to "Storage Analyzer & Treemap",
        "APPS" to "Installed Apps & APK Extractor",
        "INJECT" to "Inject Documents Provider (patch APK, browse /data/data tanpa root)",
        "TERMINAL" to "Root Terminal (FLAG_SECURE)",
        "NETWORK" to "Network Storage, Local Server & Vault (FLAG_SECURE)",
        "SETTINGS" to "Settings"
    )
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(items.size) { idx ->
            val (key, label) = items[idx]
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onClick = { onSelect(key) }
            ) {
                ListItem(headlineContent = { Text(label) })
            }
        }
    }
}
