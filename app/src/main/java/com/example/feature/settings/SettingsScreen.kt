package com.example.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.root.RootAccessResult
import com.example.core.root.RootManager
import com.example.core.root.RootStatus
import com.example.core.settings.AppTheme
import com.example.core.settings.ListDensity
import com.example.core.settings.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(AppTheme.SYSTEM) }
    var density by remember { mutableStateOf(ListDensity.COMFORTABLE) }
    var showHidden by remember { mutableStateOf(false) }
    var enableTrash by remember { mutableStateOf(true) }
    var remoteEnabled by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf("system") }

    var rootExplorerEnabled by remember { mutableStateOf(false) }
    var mountSystemRw by remember { mutableStateOf(false) }
    var isRequestingRoot by remember { mutableStateOf(false) }
    var rootStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingFallbackResult by remember { mutableStateOf<RootAccessResult?>(null) }
    val rootStatus by RootManager.rootStatus.collectAsState()

    LaunchedEffect(Unit) {
        theme = settingsManager.getTheme()
        density = settingsManager.getDensity()
        showHidden = settingsManager.getShowHidden()
        enableTrash = settingsManager.getEnableTrash()
        remoteEnabled = settingsManager.getRemoteMgmtEnabled()
        language = settingsManager.getLanguage()
        rootExplorerEnabled = settingsManager.getRootExplorerEnabled()
        mountSystemRw = settingsManager.getMountSystemRw()
        if (rootExplorerEnabled && rootStatus == RootStatus.UNKNOWN) {
            val res = RootManager.requestRootAccess()
            rootStatusMessage = res.message
        }
    }

    if (pendingFallbackResult != null) {
        val result = pendingFallbackResult!!
        AlertDialog(
            onDismissRequest = { pendingFallbackResult = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Superuser Permission Notice") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = result.message.ifEmpty {
                            "Superuser (su) access could not be acquired on this device."
                        }
                    )
                    Text(
                        text = "Would you like to enable Root Explorer in Read-Only System mode to browse accessible system partitions (/, /system), or keep it disabled?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingFallbackResult = null
                        rootExplorerEnabled = true
                        rootStatusMessage = "Enabled (Read-only system access: ${result.message})"
                        scope.launch {
                            settingsManager.setRootExplorerEnabled(true)
                        }
                    },
                    modifier = Modifier.testTag("btn_root_enable_readonly")
                ) {
                    Text("Enable Read-Only")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingFallbackResult = null
                        rootExplorerEnabled = false
                        scope.launch {
                            settingsManager.setRootExplorerEnabled(false)
                        }
                    },
                    modifier = Modifier.testTag("btn_root_cancel")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            item {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            item {
                Text("Theme")
                Row {
                    AppTheme.entries.forEach { t ->
                        FilterChip(
                            selected = theme == t,
                            onClick = {
                                theme = t
                                scope.launch { settingsManager.setTheme(t) }
                            },
                            label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            item {
                Text("List Density")
                Row {
                    ListDensity.entries.forEach { d ->
                        FilterChip(
                            selected = density == d,
                            onClick = {
                                density = d
                                scope.launch { settingsManager.setDensity(d) }
                            },
                            label = { Text(d.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            item {
                Text("Language")
                Row {
                    listOf("system", "en", "id").forEach { lang ->
                        FilterChip(
                            selected = language == lang,
                            onClick = {
                                language = lang
                                scope.launch { settingsManager.setLanguage(lang) }
                            },
                            label = { Text(lang.uppercase()) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                Text("Files", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show hidden files")
                        Text("Show files starting with '.'", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = showHidden,
                        onCheckedChange = {
                            showHidden = it
                            scope.launch { settingsManager.setShowHidden(it) }
                        },
                        modifier = Modifier.testTag("switch_show_hidden")
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable recycle bin")
                        Text("Move deleted files to trash first", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = enableTrash,
                        onCheckedChange = {
                            enableTrash = it
                            scope.launch { settingsManager.setEnableTrash(it) }
                        },
                        modifier = Modifier.testTag("switch_enable_trash")
                    )
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text("Root & System Access", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                tint = if (rootExplorerEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(28.dp)
                                    .padding(end = 8.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Root Explorer",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Request superuser (su) permissions for system-level file access (/data, /system, /)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isRequestingRoot) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(horizontal = 4.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Switch(
                                    checked = rootExplorerEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            isRequestingRoot = true
                                            rootStatusMessage = "Requesting superuser permission (su)..."
                                            scope.launch {
                                                val result = RootManager.requestRootAccess()
                                                isRequestingRoot = false
                                                if (result.status == RootStatus.GRANTED) {
                                                    rootExplorerEnabled = true
                                                    rootStatusMessage = result.message
                                                    settingsManager.setRootExplorerEnabled(true)
                                                } else {
                                                    rootStatusMessage = result.message
                                                    pendingFallbackResult = result
                                                }
                                            }
                                        } else {
                                            rootExplorerEnabled = false
                                            mountSystemRw = false
                                            rootStatusMessage = "Root Explorer disabled"
                                            scope.launch {
                                                settingsManager.setRootExplorerEnabled(false)
                                                settingsManager.setMountSystemRw(false)
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("switch_root_explorer")
                                )
                            }
                        }

                        if (rootStatusMessage != null || rootExplorerEnabled) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val isGranted = rootStatus == RootStatus.GRANTED
                                Icon(
                                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = rootStatusMessage ?: when (rootStatus) {
                                        RootStatus.GRANTED -> "Superuser access active (uid=0)"
                                        RootStatus.CHECKING -> "Verifying superuser status..."
                                        RootStatus.DENIED -> "Superuser permission denied"
                                        RootStatus.NOT_AVAILABLE -> "su binary not found (Read-only system mode)"
                                        RootStatus.UNKNOWN -> "Root Explorer enabled"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("text_root_status")
                                )
                                if (rootExplorerEnabled && !isRequestingRoot) {
                                    TextButton(
                                        onClick = {
                                            isRequestingRoot = true
                                            scope.launch {
                                                val res = RootManager.requestRootAccess()
                                                isRequestingRoot = false
                                                rootStatusMessage = res.message
                                            }
                                        },
                                        modifier = Modifier.testTag("btn_verify_root")
                                    ) {
                                        Text("Re-check su", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        if (rootExplorerEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "Mount /system Read-Write",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Remount system partitions as RW for modifying system files",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = mountSystemRw,
                                    onCheckedChange = { writable ->
                                        mountSystemRw = writable
                                        scope.launch {
                                            settingsManager.setMountSystemRw(writable)
                                            val remountRes = RootManager.remountSystem(writable)
                                            rootStatusMessage = if (remountRes.isSuccess) {
                                                if (writable) "/system mounted as Read-Write (RW)"
                                                else "/system mounted as Read-Only (RO)"
                                            } else {
                                                "Remount failed: ${remountRes.errorText.ifEmpty { "Superuser required" }}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("switch_mount_system_rw")
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text("Remote Management", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("HTTP remote management", color = androidx.compose.ui.graphics.Color.Gray)
                        Text(
                            "Not yet implemented in this build. See README roadmap.",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color.Gray
                        )
                    }
                    Switch(
                        checked = false,
                        enabled = false,
                        onCheckedChange = {}
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
