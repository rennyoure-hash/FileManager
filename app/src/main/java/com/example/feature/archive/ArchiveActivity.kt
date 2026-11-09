package com.example.feature.archive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.archive.ArchiveManager
import com.example.ui.theme.MTExplorerTheme
import kotlinx.coroutines.launch
import java.io.File

class ArchiveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: return
        val file = File(path)
        setContent {
            MTExplorerTheme {
                ArchiveBrowser(file = file, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveBrowser(file: File, onBack: () -> Unit) {
    var entries by remember { mutableStateOf<List<com.example.core.fs.VEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var extractedMsg by remember { mutableStateOf<String?>(null) }
    val manager = remember { ArchiveManager() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file) {
        try {
            entries = when {
                file.name.endsWith(".zip", ignoreCase = true) -> manager.listZip(file)
                file.name.endsWith(".gz", ignoreCase = true) -> {
                    listOf(com.example.core.fs.VEntry(file.nameWithoutExtension, com.example.core.fs.VPath(file.nameWithoutExtension), false, 0L))
                } else -> manager.listZip(file) // fallback
            }
        } catch (e: Exception) {
            entries = emptyList()
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archive: ${file.name}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("Archive is empty or unsupported format")
                }
            } else {
                Row(Modifier.padding(12.dp)) {
                    Text("${entries.size} entries", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            scope.launch {
                                val target = File(file.parent, file.nameWithoutExtension + "_extracted")
                                val result = if (file.extension.equals("zip", ignoreCase = true)) {
                                    manager.extractZip(file, target)
                                } else if (file.extension.equals("gz", ignoreCase = true)) {
                                    listOfNotNull(File(target, file.nameWithoutExtension).also {
                                        if (manager.extractGzip(file, it)) it else null
                                    })
                                } else emptyList()
                                extractedMsg = "Extracted ${result.size} files to ${target.absolutePath}"
                            }
                        }
                    ) { Text("Extract All") }
                }
                if (extractedMsg != null) {
                    Text(extractedMsg!!, modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.path.normalized, maxLines = 1) },
                            supportingContent = { Text(if (entry.isDirectory) "Folder" else entry.formattedSize) },
                            leadingContent = {
                                Icon(
                                    if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Archive,
                                    null
                                )
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
