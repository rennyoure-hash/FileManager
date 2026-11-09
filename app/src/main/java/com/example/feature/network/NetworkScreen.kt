package com.example.feature.network

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.net.NetworkProfile
import com.example.core.net.NetworkProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkStorageScreen(
    profiles: List<NetworkProfile>,
    onBack: () -> Unit,
    onAddProfile: (NetworkProfile) -> Unit,
    onConnect: (NetworkProfile) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Storage") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { showAddDialog = true }) { Text("Add") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "Network storage (FTP / SFTP / SMB / WebDAV / S3) is registered for future support but not yet implemented in this build. You can save profiles for later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            if (profiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("No network profiles saved")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(profiles) { profile ->
                        ListItem(
                            headlineContent = { Text(profile.name) },
                            supportingContent = { Text("${profile.protocol} • ${profile.host}:${profile.port}${profile.basePath} — Connect pending implementation") },
                            trailingContent = {
                                TextButton(onClick = { /* disabled until implemented */ }) { Text("Coming soon") }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var protocol by remember { mutableStateOf(NetworkProtocol.FTP) }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var basePath by remember { mutableStateOf("/") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Network Profile") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row {
                        NetworkProtocol.entries.forEach { p ->
                            FilterChip(
                                selected = protocol == p,
                                onClick = { protocol = p },
                                label = { Text(p.name) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = basePath, onValueChange = { basePath = it }, label = { Text("Base Path") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank() && host.isNotBlank()) {
                            onAddProfile(
                                NetworkProfile(
                                    id = System.currentTimeMillis().toString(),
                                    name = name,
                                    protocol = protocol,
                                    host = host,
                                    port = port.toIntOrNull() ?: when (protocol) {
                                        NetworkProtocol.FTP -> 21
                                        NetworkProtocol.FTPS -> 21
                                        NetworkProtocol.SFTP -> 22
                                        NetworkProtocol.SMB -> 445
                                        NetworkProtocol.WEBDAV -> 80
                                        NetworkProtocol.S3 -> 443
                                    },
                                    username = username,
                                    password = password,
                                    basePath = basePath
                                )
                            )
                            showAddDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}
