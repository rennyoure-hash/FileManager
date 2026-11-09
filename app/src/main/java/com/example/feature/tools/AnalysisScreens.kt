package com.example.feature.tools

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
import com.example.core.tools.ChecksumTool
import com.example.core.tools.DuplicateFinder
import com.example.core.tools.StorageAnalyzer
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecksumScreen(
    file: File,
    onBack: () -> Unit
) {
    var algorithm by remember { mutableStateOf("SHA-256") }
    var result by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checksum — ${file.name}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Algorithm:")
            Row {
                listOf("MD5", "SHA-1", "SHA-256", "SHA-512").forEach { algo ->
                    FilterChip(
                        selected = algorithm == algo,
                        onClick = { algorithm = algo },
                        label = { Text(algo) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        result = ChecksumTool.compute(file, algorithm)
                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) { Text("Compute") }
            Spacer(Modifier.height(16.dp))
            if (isLoading) CircularProgressIndicator()
            if (result.isNotEmpty()) {
                Text(result, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFinderScreen(
    root: File,
    onBack: () -> Unit
) {
    var groups by remember { mutableStateOf<List<com.example.core.tools.DuplicateGroup>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(root) {
        groups = DuplicateFinder().findDuplicates(root)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicate Finder") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No duplicates found") }
            } else {
                Text(
                    "${groups.size} duplicate groups, ${groups.sumOf { it.files.size }} files total",
                    modifier = Modifier.padding(16.dp)
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(groups) { group ->
                        ListItem(
                            headlineContent = { Text("SHA-256: ${group.checksum.take(16)}...", maxLines = 1) },
                            supportingContent = { Text("${group.files.size} copies") }
                        )
                        group.files.forEach { file ->
                            Text(
                                "  • ${file.absolutePath}",
                                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    root: File,
    onBack: () -> Unit
) {
    var usages by remember { mutableStateOf<List<com.example.core.tools.StorageUsage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val maxSize = remember(usages) { usages.maxOfOrNull { it.sizeBytes } ?: 1L }

    LaunchedEffect(root) {
        usages = StorageAnalyzer().analyzeDir(root)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage Analyzer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(usages) { usage ->
                        val percent = if (maxSize > 0) (usage.sizeBytes.toFloat() / maxSize.toFloat()) else 0f
                        ListItem(
                            headlineContent = { Text(usage.path, maxLines = 1) },
                            supportingContent = {
                                Column {
                                    Text(formatSize(usage.sizeBytes))
                                    LinearProgressIndicator(
                                        progress = { percent },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    )
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

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var b = bytes.toDouble()
    var i = 0
    while (b >= 1024 && i < units.size - 1) { b /= 1024; i++ }
    return "%.2f %s".format(b, units[i])
}
