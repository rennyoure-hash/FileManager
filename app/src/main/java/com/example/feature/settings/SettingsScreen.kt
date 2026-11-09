package com.example.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(Unit) {
        theme = settingsManager.getTheme()
        density = settingsManager.getDensity()
        showHidden = settingsManager.getShowHidden()
        enableTrash = settingsManager.getEnableTrash()
        remoteEnabled = settingsManager.getRemoteMgmtEnabled()
        language = settingsManager.getLanguage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
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
                        }
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
                        }
                    )
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
                        Text("HTTP remote management")
                        Text("Access files from browser (off by default)", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = remoteEnabled,
                        onCheckedChange = {
                            remoteEnabled = it
                            scope.launch { settingsManager.setRemoteMgmtEnabled(it) }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
