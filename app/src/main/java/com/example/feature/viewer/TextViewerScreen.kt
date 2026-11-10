package com.example.feature.viewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(
    file: File,
    onBack: () -> Unit
) {
    var content by remember { mutableStateOf("") }
    var editedContent by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                content = if (file.length() > 5 * 1024 * 1024) {
                    file.bufferedReader().readText().take(5 * 1024 * 1024) + "\n\n... [truncated large file]"
                } else {
                    file.readText()
                }
                editedContent = content
            } catch (e: Exception) {
                content = "Error reading file: ${e.message}"
                editedContent = content
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, fontSize = 16.sp, maxLines = 1)
                        Text(
                            "${file.length().toString()} bytes${if (isEditing) " • editing" else ""}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(
                            onClick = {
                                // Save
                                try {
                                    file.writeText(editedContent)
                                    content = editedContent
                                    saved = true
                                } catch (_: Exception) {}
                            }
                        ) {
                            Icon(Icons.Filled.Save, "Save")
                        }
                    }
                    IconButton(onClick = { isEditing = !isEditing }) {
                        Icon(
                            if (isEditing) Icons.Filled.Visibility else Icons.Filled.Edit,
                            if (isEditing) "View" else "Edit"
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Search bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Find...") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showReplace = !showReplace }) {
                    Text("Replace")
                }
            }

            if (showReplace) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        placeholder = { Text("Replacement...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (searchQuery.isNotEmpty()) {
                                editedContent = editedContent.replace(searchQuery, replacement)
                                content = editedContent
                            }
                        }
                    ) { Text("Replace All") }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (isEditing) {
                OutlinedTextField(
                    value = editedContent,
                    onValueChange = { editedContent = it },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
            } else {
                // Rendered content with simple highlight
                val lines = remember(content, searchQuery) {
                    content.lines().map { line ->
                        if (searchQuery.isNotBlank()) {
                            line.replace(searchQuery, ">>$searchQuery<<")
                        } else line
                    }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = lines.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            if (saved) {
                Row(Modifier.padding(12.dp)) {
                    Text("Saved ✓", color = Color(0xFF4CAF50), fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexEditorScreen(
    file: File,
    onBack: () -> Unit
) {
    var bytes by remember { mutableStateOf<ByteArray>(ByteArray(0)) }
    var isLoading by remember { mutableStateOf(true) }
    var offset by remember { mutableIntStateOf(0) }
    val pageSize = 512

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                bytes = file.readBytes().take(100 * 1024).toByteArray()
            } catch (_: Exception) {
                bytes = ByteArray(0)
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hex Editor — ${file.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Offset: 0x${offset.toString(16).uppercase()}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (offset > 0) offset = (offset - pageSize).coerceAtLeast(0) }) { Text("Prev") }
                TextButton(onClick = { if (offset + pageSize < bytes.size) offset += pageSize }) { Text("Next") }
            }
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val slice = bytes.slice(offset until (offset + pageSize).coerceAtMost(bytes.size))
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    itemsIndexed(slice.chunked(16)) { rowIdx, rowBytes ->
                        val rowOffset = offset + rowIdx * 16
                        Row(
                            Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                String.format("0x%08X: ", rowOffset),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF888888)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = rowBytes.joinToString(" ") { "%02X".format(it) },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = rowBytes.joinToString("") { if (it.toInt() in 32..126) it.toInt().toChar().toString() else "." },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                }
            }
        }
    }
}
