package com.example.feature.network

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.core.net.NetworkProfile
import com.example.core.net.NetworkProtocol
import com.example.core.net.RemoteFileServer
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkStorageScreen(
    profiles: List<NetworkProfile>,
    onBack: () -> Unit,
    onAddProfile: (NetworkProfile) -> Unit,
    onConnect: (NetworkProfile) -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }

    // Fase 8b.6: Enforce FLAG_SECURE on Network Credentials & Vault screen
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }

    // Fase 8b.5 & 8c.5: Local Server serves ONLY a single dedicated share folder (`shared_drop`),
    // never the entire filesystem, with constant-time token validation and canonical path traversal protection.
    val dedicatedShareFolder = remember {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "shared_drop").apply { mkdirs() }
    }
    val localServer = remember {
        RemoteFileServer(
            port = 8080,
            sharedRootDir = dedicatedShareFolder,
            isReadOnlyDefault = true
        )
    }
    var isServerRunning by remember { mutableStateOf(localServer.isRunning) }
    var isReadOnlyMode by remember { mutableStateOf(localServer.isReadOnlyMode) }
    val activeClients by localServer.activeClients.collectAsState()
    var serverStatusMsg by remember {
        mutableStateOf("Server lokal nonaktif (Folder Share Tunggal: ${dedicatedShareFolder.absolutePath})")
    }

    DisposableEffect(localServer) {
        onDispose {
            localServer.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Storage & Local Server") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onClick = { showAddDialog = true }) { Text("Add Profile") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Fase 8b.5 & 8c.5: Hardened Local Server Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Server Lokal (Fase 8c.5 Hardened — Single Share Folder)", fontWeight = FontWeight.Bold)
                    Text(
                        text = "Folder Share: ${dedicatedShareFolder.absolutePath}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = serverStatusMsg,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!localServer.isTlsEnabled) {
                        Text(
                            text = RemoteFileServer.PLAINTEXT_HTTP_WARNING,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = RemoteFileServer.SELF_SIGNED_HTTPS_GUIDE,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isServerRunning) {
                                    localServer.stop()
                                    isServerRunning = false
                                    serverStatusMsg = "Server dihentikan"
                                } else {
                                    localServer.start(context)
                                    isServerRunning = true
                                    isReadOnlyMode = localServer.isReadOnlyMode
                                    serverStatusMsg = "Aktif di ${localServer.localUrl()} (Read-Only=${localServer.isReadOnlyMode})"
                                }
                            }
                        ) {
                            Text(if (isServerRunning) "Stop Server" else "Start Server (RO Default)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Read-Only", fontSize = 12.sp)
                            Spacer(Modifier.width(4.dp))
                            Switch(
                                checked = isReadOnlyMode,
                                onCheckedChange = {
                                    isReadOnlyMode = it
                                    localServer.isReadOnlyMode = it
                                }
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Klien Aktif: ${activeClients.size} (Rate limit: 60 req/menit/IP)", fontSize = 12.sp)
                        OutlinedButton(
                            onClick = {
                                val disconnected = localServer.disconnectAllClients()
                                serverStatusMsg = "Diputus $disconnected koneksi klien aktif"
                            }
                        ) {
                            Text("Putus Semua Klien", fontSize = 11.sp)
                        }
                    }

                    activeClients.forEach { client ->
                        Text(
                            text = "• IP: ${client.ipAddress} (${client.requestCount} req)",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Text(
                "Profil jaringan disimpan dengan password terenkripsi AES-256-GCM (Android Keystore) — tidak pernah disimpan dalam bentuk plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (profiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No network profiles saved")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(profiles) { profile ->
                        ListItem(
                            headlineContent = { Text(profile.name) },
                            supportingContent = {
                                Text("${profile.protocol} • ${profile.host}:${profile.port}${profile.basePath} (Keystore AES-GCM Encrypted)")
                            },
                            trailingContent = {
                                TextButton(onClick = { onConnect(profile) }) { Text("Connect") }
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
        var protocol by remember { mutableStateOf(NetworkProtocol.SFTP) }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var basePath by remember { mutableStateOf("/") }

        // Fase 8c.6: Enforce SecureFlagPolicy.SecureOn on credential input dialog window
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Add Network Profile (Keystore Encrypted)") },
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
                    if (protocol.isPlaintextWarningRequired) {
                        Text(
                            text = "Peringatan Keamanan: Protokol FTP mentransmisikan kredensial & data secara plaintext di jaringan! Gunakan SFTP atau FTPS.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (Encrypted in Keystore)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = basePath, onValueChange = { basePath = it }, label = { Text("Base Path") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank() && host.isNotBlank()) {
                            onAddProfile(
                                NetworkProfile.createWithEncryptedPassword(
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
                                    rawPassword = password,
                                    basePath = basePath
                                )
                            )
                            password = ""
                            showAddDialog = false
                        }
                    }
                ) { Text("Save Encrypted") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}
