package com.example.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.db.AppDatabase
import com.example.core.db.OperationLogRepository
import com.example.core.fs.SecurityVaultAndShredder
import com.example.core.root.BuildPropEntry
import com.example.core.root.RootManager
import com.example.core.root.RunningProcessItem
import com.example.core.root.SystemRootToolkit
import com.example.core.tools.ChecksumTool
import com.example.core.tools.DuplicateDryRunSummary
import com.example.core.tools.DuplicateExecutionMode
import com.example.core.tools.DuplicateExecutionReport
import com.example.core.tools.DuplicateKeepPolicy
import com.example.core.tools.DuplicateScanConfig
import com.example.core.tools.DuplicateScanProgress
import com.example.core.tools.SmartDuplicateEngine
import com.example.core.tools.SmartDuplicateGroup
import com.example.core.tools.StorageAnalyzer
import com.example.core.tools.StorageUsage
import com.example.feature.browser.ui.PartitionBadgeChip
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.TealAccent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecksumScreen(
    file: File,
    onBack: () -> Unit
) {
    var targetPathInput by remember {
        mutableStateOf(if (file.isFile) file.absolutePath else "/storage/emulated/0/Download")
    }
    var algorithm by remember { mutableStateOf("SHA-256") }
    var computedHash by remember { mutableStateOf("") }
    var pastedExpectedHash by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cleanComputed = computedHash.trim().lowercase()
    val cleanExpected = pastedExpectedHash.trim().lowercase()
    val isMatch = cleanComputed.isNotEmpty() && cleanExpected.isNotEmpty() && cleanComputed == cleanExpected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checksum & Verifikasi Hash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = targetPathInput,
                onValueChange = { targetPathInput = it },
                label = { Text("Path File Target") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_checksum_path")
            )

            Text("Pilih Algoritma Hash:", fontWeight = FontWeight.SemiBold)
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

            Button(
                onClick = {
                    val target = File(targetPathInput.trim())
                    if (!target.exists() || !target.isFile) {
                        errorMsg = "File tidak ditemukan atau berupa direktori: ${target.absolutePath}"
                        computedHash = ""
                        return@Button
                    }
                    errorMsg = null
                    isLoading = true
                    scope.launch {
                        try {
                            computedHash = ChecksumTool.compute(target, algorithm)
                        } catch (t: Throwable) {
                            errorMsg = "Gagal menghitung hash: ${t.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.testTag("btn_compute_checksum")
            ) {
                Text("Hitung Hash ($algorithm)")
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }

            errorMsg?.let { err ->
                Text(err, color = DangerCrimson, fontSize = 13.sp)
            }

            if (computedHash.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Hasil $algorithm:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = computedHash,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = CyanAccent
                        )
                    }
                }
            }

            OutlinedTextField(
                value = pastedExpectedHash,
                onValueChange = { pastedExpectedHash = it },
                label = { Text("Tempel Hash Pembanding untuk Verifikasi") },
                placeholder = { Text("Tempel nilai MD5 / SHA-1 / SHA-256 di sini...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_verify_expected_hash")
            )

            if (cleanComputed.isNotEmpty() && cleanExpected.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isMatch) Color(0xFF003D24) else Color(0xFF3D0E18),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                        .testTag("badge_hash_verification_result")
                ) {
                    Icon(
                        imageVector = if (isMatch) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (isMatch) TealAccent else DangerCrimson
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isMatch) {
                            "COCOK (MATCH) — Integritas file terverifikasi identik!"
                        } else {
                            "TIDAK COCOK (MISMATCH) — Hash file berbeda dari yang ditempel!"
                        },
                        color = if (isMatch) TealAccent else DangerCrimson,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DuplicateFinderScreen(
    root: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val logRepo = remember { OperationLogRepository(db.operationLogDao()) }
    val dupTrashDir = remember { File(context.filesDir, "duplicate_recycle_bin") }
    val engine = remember { SmartDuplicateEngine(dupTrashDir, logRepo) }

    var scanPathText by remember { mutableStateOf(root.absolutePath) }
    var minSizeKbText by remember { mutableStateOf("1") } // Default 1 KB
    var keepPolicy by remember { mutableStateOf(DuplicateKeepPolicy.OLDEST) }
    var executionMode by remember { mutableStateOf(DuplicateExecutionMode.SCAN_MANUAL_REVIEW) }
    var priorityFoldersInput by remember { mutableStateOf("/storage/emulated/0/DCIM,/storage/emulated/0/Documents") }

    var isScanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<DuplicateScanProgress?>(null) }
    var groups by remember { mutableStateOf<List<SmartDuplicateGroup>>(emptyList()) }
    var selectedToDelete by remember { mutableStateOf<Set<String>>(emptySet()) }

    var hasCompletedFirstDryRun by remember { mutableStateOf(false) }
    var dryRunDialogSummary by remember { mutableStateOf<DuplicateDryRunSummary?>(null) }
    var lastReport by remember { mutableStateOf<DuplicateExecutionReport?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    fun triggerScan() {
        scanJob?.cancel()
        val minBytes = (minSizeKbText.toLongOrNull() ?: 1L).coerceAtLeast(1L) * 1024L
        val priorities = priorityFoldersInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val config = DuplicateScanConfig(
            rootDirectories = listOf(File(scanPathText.trim())),
            minSizeBytes = minBytes,
            keepPolicy = keepPolicy,
            priorityFolders = priorities
        )
        isScanning = true
        statusBanner = null
        scanJob = scope.launch {
            try {
                val found = engine.scanDuplicates(config) { prog ->
                    progress = prog
                }
                groups = found
                selectedToDelete = found.flatMap { it.recommendedDeleteFiles.map { f -> f.path } }.toSet()
            } finally {
                isScanning = false
                progress = null
            }
        }
    }

    LaunchedEffect(Unit) {
        triggerScan()
    }

    dryRunDialogSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { dryRunDialogSummary = null },
            properties = androidx.compose.ui.window.DialogProperties(
                securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn
            ),
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = CyanAccent) },
            title = { Text("Hasil Dry-Run Hapus Otomatis Duplikat", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• Grup Duplikat Ditemukan: ${summary.totalGroups} grup")
                    Text("• File Duplikat Akan Dipindah ke Recycle Bin: ${summary.totalDuplicateFilesToDelete} file")
                    Text("• Salinan Dipertahankan (Invariant >= 1/grup): ${summary.keptFilesCount} file")
                    Text(
                        text = "• Ruang Dihemat: ${formatSize(summary.totalBytesSaved)}",
                        color = TealAccent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Catatan: File akan diverifikasi ulang (ukuran, mtime, SHA-256, dan byte-compare) tepat sebelum dipindahkan ke Recycle Bin Duplikat (retensi 30 hari).",
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        hasCompletedFirstDryRun = true
                        dryRunDialogSummary = null
                        scope.launch {
                            val report = engine.executeDuplicateCleanup(
                                groups = groups,
                                customSelectedDeletePaths = selectedToDelete,
                                permanentDelete = false,
                                retentionDays = 30
                            )
                            lastReport = report
                            statusBanner = "Selesai: ${report.movedToRecycleBinCount} duplikat dipindah ke Recycle Bin (${formatSize(report.bytesSaved)} dihemat)."
                            triggerScan()
                        }
                    },
                    modifier = Modifier.testTag("btn_confirm_dryrun_autodelete")
                ) {
                    Text("Setuju & Pindah ke Recycle Bin")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        hasCompletedFirstDryRun = true
                        dryRunDialogSummary = null
                    }
                ) {
                    Text("Tutup (Review Manual)")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Duplicate Cleaner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isScanning) {
                        TextButton(onClick = { scanJob?.cancel(); isScanning = false }) {
                            Text("Batal", color = DangerCrimson)
                        }
                    } else {
                        IconButton(onClick = { triggerScan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Scan Ulang")
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp)
        ) {
            // Controls Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = scanPathText,
                            onValueChange = { scanPathText = it },
                            label = { Text("Folder Scan") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minSizeKbText,
                            onValueChange = { minSizeKbText = it.filter { c -> c.isDigit() } },
                            label = { Text("Min (KB)") },
                            singleLine = true,
                            modifier = Modifier.width(90.dp)
                        )
                    }

                    Text("Kebijakan File Dipertahankan (Invariant >= 1 salinan/grup):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DuplicateKeepPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = keepPolicy == policy,
                                onClick = {
                                    keepPolicy = policy
                                    triggerScan()
                                },
                                label = { Text(policy.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Text("Mode Eksekusi:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DuplicateExecutionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = executionMode == mode,
                                onClick = { executionMode = mode },
                                label = { Text(mode.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                dryRunDialogSummary = engine.createDryRunSummary(groups)
                            },
                            enabled = !isScanning && groups.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_duplicate_dry_run")
                        ) {
                            Text("Dry-Run Preview")
                        }

                        Button(
                            onClick = {
                                if (executionMode == DuplicateExecutionMode.AUTO_DELETE && !hasCompletedFirstDryRun) {
                                    // Wajib dry-run saat pertama kali
                                    dryRunDialogSummary = engine.createDryRunSummary(groups)
                                } else {
                                    scope.launch {
                                        val report = engine.executeDuplicateCleanup(
                                            groups = groups,
                                            customSelectedDeletePaths = selectedToDelete,
                                            permanentDelete = false,
                                            retentionDays = 30
                                        )
                                        lastReport = report
                                        statusBanner = "Berhasil memindahkan ${report.movedToRecycleBinCount} duplikat (${formatSize(report.bytesSaved)} dihemat)." +
                                            if (report.skippedModifiedFiles.isNotEmpty()) " (${report.skippedModifiedFiles.size} file dilewati karena berubah sejak scan)" else ""
                                        triggerScan()
                                    }
                                }
                            },
                            enabled = !isScanning && groups.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = DangerCrimson),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_execute_duplicate_clean")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Bersihkan Duplikat")
                        }
                    }
                }
            }

            // Undo / Restore Banner after execution
            if (statusBanner != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF003D24)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        Text(
                            text = statusBanner.orEmpty(),
                            color = TealAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        lastReport?.takeIf { it.trashedEntries.isNotEmpty() }?.let { rep ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val count = engine.restoreFromRecycleBin(rep.trashedEntries)
                                        statusBanner = "Undo selesai: $count file dipulihkan ke lokasi semula."
                                        lastReport = null
                                        triggerScan()
                                    }
                                },
                                modifier = Modifier.testTag("btn_undo_duplicate_clean")
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, tint = CyanAccent)
                                Spacer(Modifier.width(4.dp))
                                Text("Undo / Restore", color = CyanAccent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (isScanning) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    CircularProgressIndicator(color = CyanAccent)
                    Spacer(Modifier.height(8.dp))
                    Text(progress?.stage ?: "Memindai ukuran -> 4KB hash -> SHA-256 -> byte-compare...")
                    progress?.let { p ->
                        Text(
                            text = "File diperiksa: ${p.scannedFiles} · ${p.currentFile}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tidak ada file kembar ditemukan (0-byte, symlink, hardlink, dan sistem diabaikan).")
                }
            } else {
                val totalReclaimable = groups.sumOf { it.reclaimableBytes }
                Text(
                    text = "${groups.size} grup duplikat · Potensi hemat ${formatSize(totalReclaimable)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(groups, key = { it.id }) { group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Ukuran: ${formatSize(group.sizeBytes)} · SHA-256: ${group.fullSha256.take(16)}...",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = CyanAccent
                                )
                                Spacer(Modifier.height(4.dp))
                                group.files.forEach { candidate ->
                                    val isKept = candidate.path == group.recommendedKeepFile.path
                                    val isCheckedForDelete = selectedToDelete.contains(candidate.path)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = isCheckedForDelete,
                                            onCheckedChange = { checked ->
                                                val next = selectedToDelete.toMutableSet()
                                                if (checked) {
                                                    next.add(candidate.path)
                                                } else {
                                                    next.remove(candidate.path)
                                                }
                                                // Enforce invariant in UI: cannot select all files in the group for deletion
                                                val countSelectedInGroup = group.files.count { next.contains(it.path) }
                                                if (countSelectedInGroup < group.files.size) {
                                                    selectedToDelete = next
                                                } else {
                                                    statusBanner = "Invariant Keamanan: Minimal 1 salinan wajib dipertahankan di setiap grup!"
                                                }
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = candidate.file.name + if (isKept) " [DIPERTAHANKAN]" else " [DUPLIKAT]",
                                                fontWeight = if (isKept) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isKept) TealAccent else MaterialTheme.colorScheme.onSurface,
                                                fontSize = 12.sp
                                            )
                                            Text(
                                                text = candidate.path,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
    var usages by remember { mutableStateOf<List<StorageUsage>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val maxSize = remember(usages) { usages.maxOfOrNull { it.sizeBytes } ?: 1L }

    LaunchedEffect(root) {
        usages = StorageAnalyzer().analyzeDir(root)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage Analyzer & Treemap") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
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

/**
 * Fase 2 & Fase 6 Hub Screen:
 * - Root Provider Detection (Magisk / KernelSU / APatch) + Safe Mode Read-Only Global Toggle
 * - Mount Manager (/proc/mounts + RW/RO/RO-permanen + Toggle Remount)
 * - Operation Log (Room) with 1-Click Undo/Rollback
 * - Build.prop Editor (Validation + Automatic Backup)
 * - Process Viewer (PID, User, RSS, Kill) & Logcat/dmesg Viewer
 * - Security Vault (AES-256-GCM) & Secure Delete (with Flash/F2FS warning)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootSystemToolkitScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val logRepo = remember { OperationLogRepository(db.operationLogDao()) }

    val providerInfo by SystemRootToolkit.providerInfo.collectAsState()
    val safeReadOnly by SystemRootToolkit.safeReadOnlyMode.collectAsState()
    val mounts by RootManager.mountTable.collectAsState()
    val operationLogs by logRepo.logsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableStateOf(0) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    var buildProps by remember { mutableStateOf<List<BuildPropEntry>>(emptyList()) }
    var processes by remember { mutableStateOf<List<RunningProcessItem>>(emptyList()) }
    var systemLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var logSource by remember { mutableStateOf("logcat") }
    var logFilter by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        SystemRootToolkit.detectRootProvider()
        RootManager.refreshMounts()
    }

    LaunchedEffect(selectedTab, logSource) {
        when (selectedTab) {
            2 -> buildProps = SystemRootToolkit.readBuildProp()
            3 -> processes = SystemRootToolkit.listRunningProcesses()
            4 -> systemLogs = SystemRootToolkit.readSystemLogs(logSource, 250, logFilter)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Root, Sistem & Operation Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Root Provider & Global Safe Mode Header
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(10.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = CyanAccent)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Provider: ${providerInfo.provider.displayName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = providerInfo.versionString,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mode Aman (RO)", fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = safeReadOnly,
                            onCheckedChange = { SystemRootToolkit.setSafeReadOnlyMode(it) },
                            modifier = Modifier.testTag("switch_global_safe_readonly")
                        )
                    }
                }
            }

            statusToast?.let { msg ->
                Text(
                    text = msg,
                    color = CyanAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val tabs = listOf("Mounts", "Op Log & Undo", "build.prop", "Processes", "Logcat/dmesg")
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(label, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    // Mount Manager
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        items(mounts) { m ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = m.mountPoint,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "${m.device} (${m.fsType})",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    PartitionBadgeChip(badge = m.badge, fsType = m.fsType)
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            if (safeReadOnly) {
                                                statusToast = "Mode Aman aktif — remount diblokir."
                                                return@OutlinedButton
                                            }
                                            scope.launch {
                                                val outcome = RootManager.remountPath(
                                                    targetPathOrMountPoint = m.mountPoint,
                                                    writable = !m.isRw,
                                                    pinnedByUser = true
                                                )
                                                statusToast = when (outcome) {
                                                    is com.example.core.root.RemountOutcome.Success ->
                                                        "Mount ${m.mountPoint} -> ${outcome.mountEntry.badge.label}"
                                                    is com.example.core.root.RemountOutcome.PermanentReadOnly ->
                                                        outcome.message
                                                    is com.example.core.root.RemountOutcome.Failed ->
                                                        "Remount gagal: ${outcome.diagnosis.causes.firstOrNull()}"
                                                }
                                            }
                                        }
                                    ) {
                                        Text(if (m.isRw) "Set RO" else "Set RW", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Operation Log (Room) with Rollback / Undo
                    if (operationLogs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Belum ada riwayat operasi destruktif tercatat.")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            items(operationLogs, key = { it.id }) { log ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(10.dp)
                                    ) {
                                        Icon(Icons.Default.History, contentDescription = null, tint = CyanAccent)
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "[${log.operationType}] ${log.targetPath}",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            )
                                            if (log.details.isNotEmpty()) {
                                                Text(log.details, fontSize = 11.sp, color = Color.Gray)
                                            }
                                        }
                                        if (log.canUndo && !log.isUndone) {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        val res = logRepo.undoOperation(log)
                                                        statusToast = res.getOrElse { it.message ?: "Gagal undo" }
                                                    }
                                                },
                                                modifier = Modifier.testTag("btn_undo_log_${log.id}")
                                            ) {
                                                Text("Undo", fontSize = 11.sp)
                                            }
                                        } else if (log.isUndone) {
                                            Text("Sudah Di-undo", fontSize = 11.sp, color = TealAccent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // build.prop viewer & validated editor
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        Text(
                            text = "File: /system/build.prop (${buildProps.size} properti terbaca — Backup otomatis sebelum simpan)",
                            fontSize = 12.sp,
                            color = CyanAccent,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(buildProps, key = { it.key }) { prop ->
                                ListItem(
                                    headlineContent = {
                                        Text(prop.key, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    },
                                    supportingContent = {
                                        Text(prop.value, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                3 -> {
                    // Process Viewer
                    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                        items(processes, key = { it.pid }) { proc ->
                            ListItem(
                                headlineContent = { Text("${proc.name} (PID: ${proc.pid})", fontSize = 13.sp) },
                                supportingContent = { Text("User: ${proc.user} · RSS: ${proc.rssKb} KB", fontSize = 11.sp) },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                val r = SystemRootToolkit.killProcess(proc.pid)
                                                statusToast = if (r.isSuccess) "Killed PID ${proc.pid}" else r.errorText
                                                processes = SystemRootToolkit.listRunningProcesses()
                                            }
                                        }
                                    ) {
                                        Text("Kill", color = DangerCrimson)
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
                4 -> {
                    // Logcat & dmesg Viewer
                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = logSource == "logcat",
                                onClick = { logSource = "logcat" },
                                label = { Text("logcat") }
                            )
                            FilterChip(
                                selected = logSource == "dmesg",
                                onClick = { logSource = "dmesg" },
                                label = { Text("dmesg") }
                            )
                            OutlinedTextField(
                                value = logFilter,
                                onValueChange = { logFilter = it },
                                placeholder = { Text("Filter log...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        systemLogs = SystemRootToolkit.readSystemLogs(logSource, 250, logFilter)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh logs")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = SecurityVaultAndShredder.FLASH_F2FS_SHRED_WARNING,
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(systemLogs) { line ->
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityAndDiagnosticsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val logRepo = remember { OperationLogRepository(context, db.operationLogDao()) }
    val integrityMonitor = remember { com.example.core.tools.FileIntegrityMonitor(logRepo) }

    var diagnosticLogText by remember { mutableStateOf(com.example.core.tools.InAppDiagnosticLogger.readLogs(context)) }
    var hashChainStatus by remember { mutableStateOf<String?>(null) }
    var watchPathInput by remember { mutableStateOf("/system/build.prop") }
    var watchedSnapshots by remember { mutableStateOf(integrityMonitor.listWatched()) }
    var integrityAlerts by remember { mutableStateOf<List<String>>(emptyList()) }
    var partitionStatus by remember { mutableStateOf<String?>(null) }
    var vtApiKeyInput by remember { mutableStateOf("") }
    var vtHashInput by remember { mutableStateOf("") }
    var vtStatus by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security & Diagnostics (Fase 8b)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("1. Audit HMAC-SHA256 Keystore Operation Log & Head Anchor (Fase 8b.1)", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Setiap entri Operation Log ditandatangani secara berantai dengan HMAC-SHA256 berkunci Android Keystore (non-exportable), diserialisasi dengan Mutex, dan diverifikasi terhadap Head HMAC Anchor terpisah di luar SQLite.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    val res = logRepo.verifyHashChainIntegrity()
                                    hashChainStatus = "${if (res.isValid) "VALID" else "RUSAK"}: ${res.reason}"
                                }
                            },
                            modifier = Modifier.testTag("btn_verify_hash_chain")
                        ) {
                            Text("Verifikasi Integritas HMAC Chain & Anchor")
                        }
                        hashChainStatus?.let { status ->
                            Text(status, color = CyanAccent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("2. VirusTotal SHA-256 Check (API Key Dienkripsi Keystore - Fase 8b.2)", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = vtApiKeyInput,
                            onValueChange = { vtApiKeyInput = it },
                            label = { Text("Simpan API Key VirusTotal (AES-256-GCM Keystore)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    com.example.core.tools.KeystoreSecretManager.saveEncryptedVirusTotalApiKey(context, vtApiKeyInput)
                                    vtApiKeyInput = ""
                                    vtStatus = "API Key VirusTotal berhasil dienkripsi ke Android Keystore (tidak ada plaintext di Prefs/DB)"
                                }
                            ) {
                                Text("Simpan Key Terenkripsi")
                            }
                        }
                        OutlinedTextField(
                            value = vtHashInput,
                            onValueChange = { vtHashInput = it },
                            label = { Text("SHA-256 Hash (64 hex — Hanya hash yang dikirim)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val res = com.example.core.tools.VirusTotalHashChecker.checkSha256HashWithStoredKey(context, vtHashInput)
                                    vtStatus = res.fold(
                                        onSuccess = { it.summaryMessage },
                                        onFailure = { "Error: ${it.message}" }
                                    )
                                }
                            }
                        ) {
                            Text("Cek Hash ke VirusTotal")
                        }
                        vtStatus?.let { st ->
                            Text(st, fontSize = 12.sp, color = CyanAccent)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("3. File Integrity Monitor (Maks 16 Watch)", fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = watchPathInput,
                                onValueChange = { watchPathInput = it },
                                label = { Text("Path File Kritis") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        integrityMonitor.addWatch(File(watchPathInput.trim()))
                                        watchedSnapshots = integrityMonitor.listWatched()
                                    }
                                }
                            ) {
                                Text("Pantau")
                            }
                        }
                        watchedSnapshots.forEach { snap ->
                            Text(
                                text = "• ${snap.path} (SHA-256: ${snap.baselineSha256.take(12)}...)",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    integrityAlerts = integrityMonitor.verifyAllSnapshots()
                                }
                            }
                        ) {
                            Text("Cek Snapshot Sekarang")
                        }
                        integrityAlerts.forEach { alert ->
                            Text(alert, color = DangerCrimson, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("4. Ekspor Partisi Read-Only & fstrim (Fase 7.C & 8b.7)", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Menyalin partisi boot/recovery ke /sdcard/Download/MTExplorer_Backup secara READ-ONLY (Fitur flash/tulis partisi of=/dev/... diblokir oleh Root Allowlist).",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val out = File("/sdcard/Download/MTExplorer_Backup/boot_backup.img")
                                        val res = com.example.core.tools.RootAppBackupAndStorageHealth.exportPartitionReadOnly("boot", out)
                                        partitionStatus = if (res.isSuccess) "Backup boot berhasil: ${out.absolutePath}" else res.errorText
                                    }
                                }
                            ) {
                                Text("Backup boot.img (RO)")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val res = com.example.core.tools.RootAppBackupAndStorageHealth.runFstrimData()
                                        partitionStatus = if (res.isSuccess) "fstrim: ${res.outputText}" else res.errorText
                                    }
                                }
                            ) {
                                Text("Jalankan fstrim /data")
                            }
                        }
                        partitionStatus?.let { st ->
                            Text(st, fontSize = 12.sp, color = CyanAccent, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("5. In-App Diagnostic & Crash Log (Auto-Masking & Clipboard Notice - Fase 8b.9)", fontWeight = FontWeight.Bold)
                        Text(
                            text = com.example.core.tools.InAppDiagnosticLogger.CLIPBOARD_ANDROID10_LIMITATION_NOTE,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    com.example.core.tools.InAppDiagnosticLogger.copyWithAutoClearClipboard(
                                        context = context,
                                        scope = scope,
                                        label = "MT Explorer Masked Log",
                                        text = diagnosticLogText
                                    )
                                    hashChainStatus = "Log disalin ke Clipboard (upaya hapus otomatis dijadwalkan dalam 15 detik — lihat catatan keterbatasan Android 10+)"
                                },
                                modifier = Modifier.testTag("btn_copy_diagnostic_log")
                            ) {
                                Text("Copy Error (Best-Effort Wipe 15s)")
                            }
                            OutlinedButton(
                                onClick = {
                                    com.example.core.tools.InAppDiagnosticLogger.clearLogs(context)
                                    diagnosticLogText = com.example.core.tools.InAppDiagnosticLogger.readLogs(context)
                                }
                            ) {
                                Text("Bersihkan Log")
                            }
                        }
                        Text(
                            text = diagnosticLogText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
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
    while (b >= 1024 && i < units.size - 1) {
        b /= 1024
        i++
    }
    return "%.2f %s".format(b, units[i])
}
