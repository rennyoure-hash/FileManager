package com.example.feature.tools

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.storage.RecycleBin
import com.example.core.storage.TrashedItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    recycleBin: RecycleBin,
    onBack: () -> Unit
) {
    var items by remember { mutableStateOf<List<TrashedItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        items = recycleBin.listTrashed()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recycle Bin (${items.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = {
                            recycleBin.emptyBin()
                            items = emptyList()
                        }) {
                            Text("Empty")
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Trash is empty")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items) { item ->
                        ListItem(
                            headlineContent = { Text(item.name, maxLines = 1) },
                            supportingContent = { Text("Original: ${item.originalPath}") },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        if (recycleBin.restore(item)) {
                                            items = recycleBin.listTrashed()
                                        }
                                    }) {
                                        Icon(Icons.Filled.Restore, "Restore")
                                    }
                                    IconButton(onClick = {
                                        if (recycleBin.permanentDelete(item)) {
                                            items = recycleBin.listTrashed()
                                        }
                                    }) {
                                        Icon(Icons.Filled.DeleteForever, "Delete forever")
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

@Composable
fun BatchRenameScreen(
    files: List<File>,
    onBack: () -> Unit,
    onDone: (Int) -> Unit
) {
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batch Rename (${files.size} files)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(value = prefix, onValueChange = { prefix = it }, label = { Text("Prefix") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = suffix, onValueChange = { suffix = it }, label = { Text("Suffix") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = find, onValueChange = { find = it }, label = { Text("Find") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = replace, onValueChange = { replace = it }, label = { Text("Replace") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            // Preview
            Text("Preview:", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(files.take(20)) { file ->
                    val newName = com.example.core.tools.BatchRenameRule(prefix, suffix, find, replace).let { rule ->
                        var name = file.name
                        if (rule.find.isNotEmpty()) name = name.replace(rule.find, rule.replace)
                        rule.prefix + name + rule.suffix
                    }
                    ListItem(
                        headlineContent = { Text("${file.name} → $newName", fontSize = MaterialTheme.typography.bodyMedium.fontSize) }
                    )
                }
            }

            Button(
                onClick = {
                    // Applied via ViewModel in real flow; here just callback
                    onDone(files.size)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Rename")
            }
        }
    }
}
