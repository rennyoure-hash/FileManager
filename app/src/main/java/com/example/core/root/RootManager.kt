package com.example.core.root

import android.content.Context
import android.os.Environment
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RootStatus {
    UNKNOWN,
    CHECKING,
    GRANTED,
    DENIED,
    NOT_AVAILABLE
}

data class RootCommandResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val isSuccess: Boolean get() = exitCode == 0
    val outputText: String get() = stdout.joinToString("\n")
    val errorText: String get() = stderr.joinToString("\n")
}

data class RootAccessResult(
    val status: RootStatus,
    val uidInfo: String = "",
    val suPath: String? = null,
    val message: String = ""
)

sealed class RemountOutcome {
    data class Success(val mountEntry: MountEntry) : RemountOutcome()
    data class PermanentReadOnly(val mountEntry: MountEntry, val message: String) : RemountOutcome()
    data class Failed(val diagnosis: RemountFailureDiagnosis) : RemountOutcome()
}

private data class MountRefState(
    var activeWriters: Int = 0,
    var wasOriginallyRo: Boolean = false,
    var pinnedRwByUser: Boolean = false
)

object RootManager {

    private const val PREFS_NAME = "mt_explorer_root_prefs"
    private const val KEY_ROOT_EXPLORER_ENABLED = "root_explorer_enabled"
    private const val KEY_MOUNT_SYSTEM_RW = "mount_system_rw"
    const val COMMAND_TIMEOUT_SECONDS = 30L

    private val _rootStatus = MutableStateFlow(RootStatus.UNKNOWN)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private val _isRootGrantedAtStartup = MutableStateFlow<Boolean?>(null)
    val isRootGrantedAtStartup: StateFlow<Boolean?> = _isRootGrantedAtStartup.asStateFlow()

    private val _showRootUnavailableBanner = MutableStateFlow(false)
    val showRootUnavailableBanner: StateFlow<Boolean> = _showRootUnavailableBanner.asStateFlow()

    private val _isRootExplorerEnabled = MutableStateFlow(false)
    val isRootExplorerEnabledFlow: StateFlow<Boolean> = _isRootExplorerEnabled.asStateFlow()

    private val _mountTable = MutableStateFlow<List<MountEntry>>(emptyList())
    val mountTable: StateFlow<List<MountEntry>> = _mountTable.asStateFlow()

    private val _latestRemountFailure = MutableStateFlow<RemountFailureDiagnosis?>(null)
    val latestRemountFailure: StateFlow<RemountFailureDiagnosis?> = _latestRemountFailure.asStateFlow()

    private val _lastShellError = MutableStateFlow<String?>(null)
    val lastShellError: StateFlow<String?> = _lastShellError.asStateFlow()

    private val mountRefMutex = Mutex()
    private val mountRefMap = mutableMapOf<String, MountRefState>()

    /**
     * Configures libsu persistent shell with FLAG_MOUNT_MASTER and 30s timeout.
     */
    fun configurePersistentShell() {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(COMMAND_TIMEOUT_SECONDS)
        )
    }

    /**
     * Checks `Shell.isAppGrantedRoot()` at startup without crashing.
     * If false, runs in read-only mode and sets the "Root tidak tersedia" banner state.
     */
    suspend fun initializeAndCheckStartupRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        configurePersistentShell()
        val granted = try {
            Shell.isAppGrantedRoot() == true
        } catch (_: Throwable) {
            false
        }
        _isRootGrantedAtStartup.value = granted
        if (granted) {
            _rootStatus.value = RootStatus.GRANTED
            _showRootUnavailableBanner.value = false
        } else {
            _rootStatus.value = if (findSuBinary() == null) RootStatus.NOT_AVAILABLE else RootStatus.DENIED
            _showRootUnavailableBanner.value = true
        }
        refreshMounts()
        granted
    }

    fun dismissRootUnavailableBanner() {
        _showRootUnavailableBanner.value = false
    }

    fun clearRemountFailure() {
        _latestRemountFailure.value = null
    }

    fun clearLastShellError() {
        _lastShellError.value = null
    }

    fun findSuBinary(): String? {
        return Shell.findSuBinary()
    }

    fun isDeviceRooted(): Boolean {
        return findSuBinary() != null
    }

    fun isRootExplorerEnabled(context: Context): Boolean {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_ROOT_EXPLORER_ENABLED, false)
            _isRootExplorerEnabled.value = enabled
            enabled
        } catch (_: Throwable) {
            _isRootExplorerEnabled.value
        }
    }

    fun setRootExplorerEnabled(context: Context, enabled: Boolean) {
        _isRootExplorerEnabled.value = enabled
        if (!enabled) {
            _rootStatus.value = RootStatus.UNKNOWN
        }
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ROOT_EXPLORER_ENABLED, enabled).apply()
        } catch (_: Throwable) {
        }
    }

    fun isMountSystemRw(context: Context): Boolean {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_MOUNT_SYSTEM_RW, false)
        } catch (_: Throwable) {
            false
        }
    }

    fun setMountSystemRw(context: Context, writable: Boolean) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_MOUNT_SYSTEM_RW, writable).apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * Reads `/proc/mounts` via the persistent root shell (or falls back to reading `/proc/mounts` directly),
     * parses it, and updates the cached mount table.
     */
    suspend fun refreshMounts(): List<MountEntry> = withContext(Dispatchers.IO) {
        val lines: List<String> = try {
            if (Shell.isAppGrantedRoot() == true) {
                val res = executeCommand("cat '/proc/mounts'", reportErrorToUi = false)
                if (res.isSuccess && res.stdout.isNotEmpty()) {
                    res.stdout
                } else {
                    readProcMountsFallback()
                }
            } else {
                readProcMountsFallback()
            }
        } catch (_: Throwable) {
            readProcMountsFallback()
        }
        val parsed = MountAnalyzer.parseProcMounts(lines)
        _mountTable.value = parsed
        parsed
    }

    private fun readProcMountsFallback(): List<String> {
        return try {
            val file = File("/proc/mounts")
            if (file.exists() && file.canRead()) {
                file.readLines()
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Finds the mount entry for [targetPath] using longest-prefix match against cached `/proc/mounts`.
     */
    fun getMountForPath(targetPath: String): MountEntry? {
        val current = _mountTable.value
        val table = if (current.isNotEmpty()) {
            current
        } else {
            val fallback = MountAnalyzer.parseProcMounts(readProcMountsFallback())
            if (fallback.isNotEmpty()) {
                _mountTable.value = fallback
            }
            fallback
        }
        return MountAnalyzer.findMountForPath(targetPath, table)
    }

    /**
     * Requests/verifies superuser access using `libsu` (`Shell.isAppGrantedRoot()` + `executeCommand("id")`).
     */
    suspend fun requestRootAccess(timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS): RootAccessResult = withContext(Dispatchers.IO) {
        _rootStatus.value = RootStatus.CHECKING
        configurePersistentShell()
        val suPath = findSuBinary()

        try {
            val granted = Shell.isAppGrantedRoot() == true
            val res = executeCommand("id", timeoutSeconds = timeoutSeconds, reportErrorToUi = false)
            val outText = res.stdout.joinToString("\n").trim()
            val errText = res.stderr.joinToString("\n").trim()

            if (granted && res.isSuccess && outText.contains("uid=0")) {
                _rootStatus.value = RootStatus.GRANTED
                _showRootUnavailableBanner.value = false
                refreshMounts()
                RootAccessResult(
                    status = RootStatus.GRANTED,
                    uidInfo = outText,
                    suPath = suPath ?: "su",
                    message = "Superuser access granted ($outText)"
                )
            } else {
                val status = if (suPath == null) RootStatus.NOT_AVAILABLE else RootStatus.DENIED
                _rootStatus.value = status
                _showRootUnavailableBanner.value = true
                RootAccessResult(
                    status = status,
                    uidInfo = outText,
                    suPath = suPath,
                    message = errText.ifEmpty { "Root tidak tersedia" }
                )
            }
        } catch (t: Throwable) {
            val status = if (suPath == null) RootStatus.NOT_AVAILABLE else RootStatus.DENIED
            _rootStatus.value = status
            _showRootUnavailableBanner.value = true
            RootAccessResult(
                status = status,
                suPath = suPath,
                message = t.localizedMessage ?: "Root tidak tersedia"
            )
        }
    }

    /**
     * Executes a command via the persistent `libsu` shell, checking `code`, `out`, and `err`,
     * after enforcing the Fase 8 Root Command Allowlist.
     * Surfaces any `err` output to [lastShellError].
     */
    suspend fun executeCommand(
        command: String,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
        reportErrorToUi: Boolean = true
    ): RootCommandResult = withContext(Dispatchers.IO) {
        val allowlistCheck = MountAnalyzer.validateRootCommandAllowlist(command)
        if (allowlistCheck.isFailure) {
            val errMsg = allowlistCheck.exceptionOrNull()?.message ?: "Perintah ditolak oleh Root Command Allowlist"
            if (reportErrorToUi) {
                _lastShellError.value = errMsg
            }
            return@withContext RootCommandResult(-1, emptyList(), listOf(errMsg))
        }
        val res = Shell.cmd(command).exec()
        val cmdRes = RootCommandResult(
            exitCode = res.code,
            stdout = res.out,
            stderr = res.err
        )
        if (!cmdRes.isSuccess && reportErrorToUi) {
            val errMsg = cmdRes.errorText.ifEmpty { "Perintah root gagal (exit code ${cmdRes.exitCode})" }
            _lastShellError.value = errMsg
        }
        cmdRes
    }

    /**
     * Remounts the mountpoint covering [targetPathOrMountPoint] as `rw` or `ro`,
     * following the pre-write fstype rules and verifying `/proc/mounts` afterward.
     */
    suspend fun remountPath(
        targetPathOrMountPoint: String,
        writable: Boolean,
        pinnedByUser: Boolean = false
    ): RemountOutcome = withContext(Dispatchers.IO) {
        val mounts = if (_mountTable.value.isEmpty()) refreshMounts() else _mountTable.value
        val mountEntry = MountAnalyzer.findMountForPath(targetPathOrMountPoint, mounts)
            ?: MountEntry(
                device = "unknown",
                mountPoint = MountAnalyzer.normalizePath(targetPathOrMountPoint),
                fsType = "ext4",
                options = listOf("ro")
            )

        val mp = mountEntry.mountPoint

        if (writable) {
            val decision = MountAnalyzer.evaluatePreWriteDecision(mountEntry)
            if (decision.action == PreWriteAction.BLOCK_PERMANENT_RO) {
                val msg = "Partisi ini read-only permanen (filesystem terkompresi: ${mountEntry.fsType})"
                _lastShellError.value = msg
                return@withContext RemountOutcome.PermanentReadOnly(mountEntry, msg)
            }
            if (decision.action == PreWriteAction.PROCEED_ALREADY_RW) {
                if (pinnedByUser) {
                    mountRefMutex.withLock {
                        val state = mountRefMap.getOrPut(mp) { MountRefState() }
                        state.pinnedRwByUser = true
                    }
                }
                return@withContext RemountOutcome.Success(mountEntry)
            }
        }

        val mode = if (writable) "rw" else "ro"
        val escapedMp = MountAnalyzer.escapeShellPath(mp)
        val cmdResult = executeCommand("mount -o $mode,remount $escapedMp", reportErrorToUi = false)

        // Refresh /proc/mounts to verify actual mount state
        val updatedMounts = refreshMounts()
        val updatedEntry = MountAnalyzer.findMountForPath(mp, updatedMounts) ?: mountEntry

        val verified = if (writable) updatedEntry.isRw else updatedEntry.isRo
        if (cmdResult.isSuccess && verified) {
            mountRefMutex.withLock {
                val state = mountRefMap.getOrPut(mp) { MountRefState() }
                if (pinnedByUser) {
                    state.pinnedRwByUser = writable
                }
            }
            return@withContext RemountOutcome.Success(updatedEntry)
        } else {
            val diagnosis = diagnoseRemountFailure(
                mountEntry = updatedEntry,
                mountStderr = cmdResult.errorText
            )
            _latestRemountFailure.value = diagnosis
            return@withContext RemountOutcome.Failed(diagnosis)
        }
    }

    /**
     * Diagnoses why `mount -o rw,remount` failed by inspecting:
     * 1. Dynamic partitions (`/dev/block/mapper` & `getprop ro.boot.dynamic_partitions`)
     * 2. dm-verity / AVB (`getprop ro.boot.verifiedbootstate` & `getprop ro.boot.veritymode`)
     * 3. stderr from `mount`
     */
    suspend fun diagnoseRemountFailure(
        mountEntry: MountEntry,
        mountStderr: String
    ): RemountFailureDiagnosis = withContext(Dispatchers.IO) {
        val dynProp = executeCommand("getprop ro.boot.dynamic_partitions", reportErrorToUi = false).outputText.trim()
        val mapperCheck = executeCommand("ls /dev/block/mapper 2>/dev/null", reportErrorToUi = false)
        val hasMapper = mapperCheck.isSuccess && mapperCheck.stdout.isNotEmpty()
        val isDynamic = dynProp.equals("true", ignoreCase = true) || dynProp == "1" || hasMapper

        val vbState = executeCommand("getprop ro.boot.verifiedbootstate", reportErrorToUi = false).outputText.trim()
        val verityMode = executeCommand("getprop ro.boot.veritymode", reportErrorToUi = false).outputText.trim()
        val isVerityActive = verityMode.equals("enforcing", ignoreCase = true) ||
            verityMode.equals("eio", ignoreCase = true) ||
            vbState.equals("green", ignoreCase = true)

        val causes = mutableListOf<String>()
        if (MountAnalyzer.isPermanentReadOnlyFs(mountEntry.fsType)) {
            causes.add("Filesystem terkompresi (${mountEntry.fsType}) bersifat read-only permanen.")
        }
        if (isDynamic) {
            causes.add(
                "Dynamic Partitions aktif (ro.boot.dynamic_partitions=${dynProp.ifEmpty { "true" }}, /dev/block/mapper terdeteksi: ${if (hasMapper) "Ya" else "Tidak"}). Partisi logical memiliki ukuran tetap (shared blocks)."
            )
        }
        if (isVerityActive) {
            causes.add(
                "dm-verity / Android Verified Boot (AVB) aktif (verifiedbootstate=${vbState.ifEmpty { "unknown" }}, veritymode=${verityMode.ifEmpty { "unknown" }})."
            )
        }
        if (mountStderr.isNotBlank()) {
            causes.add("Output stderr mount: $mountStderr")
        }
        if (causes.isEmpty()) {
            causes.add("Kernel atau bootloader menolak perubahan flag mount ke RW pada ${mountEntry.mountPoint}.")
        }

        RemountFailureDiagnosis(
            mountPoint = mountEntry.mountPoint,
            fsType = mountEntry.fsType,
            currentOptions = mountEntry.options.joinToString(","),
            isDynamicPartition = isDynamic,
            dynamicPartitionDetail = "ro.boot.dynamic_partitions=${dynProp.ifEmpty { "n/a" }}, /dev/block/mapper=${if (hasMapper) "ada" else "tidak ada"}",
            isDmVerityActive = isVerityActive,
            verityDetail = "verifiedbootstate=${vbState.ifEmpty { "n/a" }}, veritymode=${verityMode.ifEmpty { "n/a" }}",
            mountStderr = mountStderr.ifEmpty { "(tidak ada stderr)" },
            causes = causes
        )
    }

    /**
     * Executes [block] while ensuring the mountpoint for [targetPath] is writable (`rw`),
     * using reference counting so parallel operations share the mount and the last operation
     * automatically restores `ro` in `finally` if the partition was originally `ro`.
     */
    suspend fun withWritableMount(
        targetPath: String,
        block: suspend () -> RootCommandResult
    ): RootCommandResult = withContext(Dispatchers.IO) {
        val mounts = if (_mountTable.value.isEmpty()) refreshMounts() else _mountTable.value
        val mountEntry = MountAnalyzer.findMountForPath(targetPath, mounts)
        val decision = MountAnalyzer.evaluatePreWriteDecision(mountEntry)

        if (decision.action == PreWriteAction.BLOCK_PERMANENT_RO) {
            val msg = decision.message.ifEmpty { "Partisi ini read-only permanen (filesystem terkompresi)" }
            _lastShellError.value = msg
            return@withContext RootCommandResult(-1, emptyList(), listOf(msg))
        }

        val mp = mountEntry?.mountPoint ?: "/"

        // Acquire write slot & remount RW if needed
        val acquireError: RootCommandResult? = mountRefMutex.withLock {
            val state = mountRefMap.getOrPut(mp) { MountRefState() }
            if (state.activeWriters == 0) {
                val currentMount = MountAnalyzer.findMountForPath(mp, _mountTable.value) ?: mountEntry
                val currentlyRo = currentMount?.isRo == true
                state.wasOriginallyRo = currentlyRo && !state.pinnedRwByUser

                if (currentlyRo) {
                    val escapedMp = MountAnalyzer.escapeShellPath(mp)
                    val remountCmd = executeCommand("mount -o rw,remount $escapedMp", reportErrorToUi = false)
                    val refreshed = refreshMounts()
                    val afterMount = MountAnalyzer.findMountForPath(mp, refreshed)
                    if (!remountCmd.isSuccess || afterMount?.isRw != true) {
                        val diag = diagnoseRemountFailure(
                            mountEntry = afterMount ?: currentMount ?: MountEntry("unknown", mp, "ext4", listOf("ro")),
                            mountStderr = remountCmd.stderr.joinToString("\n")
                        )
                        _latestRemountFailure.value = diag
                        val errMsg = diag.causes.joinToString("; ")
                        _lastShellError.value = errMsg
                        return@withLock RootCommandResult(
                            exitCode = if (remountCmd.exitCode != 0) remountCmd.exitCode else -1,
                            stdout = remountCmd.stdout,
                            stderr = listOf(errMsg)
                        )
                    }
                }
            }
            state.activeWriters += 1
            null
        }

        if (acquireError != null) {
            return@withContext acquireError
        }

        try {
            val result = block()
            if (!result.isSuccess) {
                val errMsg = result.errorText.ifEmpty { "Operasi tulis gagal (kode ${result.exitCode})" }
                _lastShellError.value = errMsg
            }
            result
        } finally {
            mountRefMutex.withLock {
                val state = mountRefMap[mp]
                if (state != null) {
                    state.activeWriters = (state.activeWriters - 1).coerceAtLeast(0)
                    if (state.activeWriters == 0 && state.wasOriginallyRo && !state.pinnedRwByUser) {
                        state.wasOriginallyRo = false
                        val escapedMp = MountAnalyzer.escapeShellPath(mp)
                        executeCommand("mount -o ro,remount $escapedMp", reportErrorToUi = false)
                        refreshMounts()
                    }
                }
            }
        }
    }

    /**
     * Legacy helper that delegates to [remountPath] on `/system`.
     */
    suspend fun remountSystem(writable: Boolean): RootCommandResult {
        return when (val outcome = remountPath("/system", writable, pinnedByUser = writable)) {
            is RemountOutcome.Success -> RootCommandResult(0, listOf("Mounted ${outcome.mountEntry.mountPoint}"), emptyList())
            is RemountOutcome.PermanentReadOnly -> RootCommandResult(-1, emptyList(), listOf(outcome.message))
            is RemountOutcome.Failed -> RootCommandResult(-1, emptyList(), outcome.diagnosis.causes)
        }
    }

    /**
     * Safely quotes a file path using single quotes (`'` -> `'\''`).
     */
    fun shellQuote(path: String): String {
        return MountAnalyzer.escapeShellPath(path)
    }

    // --- Root Write Operations (All escaped, TOCTOU-checked & wrapped in withWritableMount) ---

    suspend fun rootCopy(sourcePath: String, destPath: String): RootCommandResult {
        val snapSrc = MountAnalyzer.captureToctouSnapshot(sourcePath)
        val snapDst = MountAnalyzer.captureToctouSnapshot(destPath)
        return withWritableMount(snapDst.canonicalPath) {
            val verifySrc = MountAnalyzer.verifyToctouSnapshot(snapSrc, allowSymlinkTarget = true)
            val verifyDst = MountAnalyzer.verifyToctouSnapshot(snapDst, allowSymlinkTarget = false)
            if (verifySrc.isFailure || verifyDst.isFailure) {
                val err = (verifySrc.exceptionOrNull() ?: verifyDst.exceptionOrNull())?.message ?: "TOCTOU check gagal"
                _lastShellError.value = err
                return@withWritableMount RootCommandResult(-1, emptyList(), listOf(err))
            }
            val src = shellQuote(verifySrc.getOrThrow().canonicalPath)
            val dst = shellQuote(verifyDst.getOrThrow().canonicalPath)
            executeCommand("cp -rf $src $dst")
        }
    }

    suspend fun rootMove(sourcePath: String, destPath: String): RootCommandResult {
        if (MountAnalyzer.isBlockedDirectDeletePath(sourcePath)) {
            val msg = "Diblokir: Memindahkan partisi inti (${MountAnalyzer.normalizePath(sourcePath)}) dilarang keras."
            _lastShellError.value = msg
            return RootCommandResult(-1, emptyList(), listOf(msg))
        }
        val snapSrc = MountAnalyzer.captureToctouSnapshot(sourcePath)
        val snapDst = MountAnalyzer.captureToctouSnapshot(destPath)
        return withWritableMount(snapDst.canonicalPath) {
            withWritableMount(snapSrc.canonicalPath) {
                val verifySrc = MountAnalyzer.verifyToctouSnapshot(snapSrc, allowSymlinkTarget = true)
                val verifyDst = MountAnalyzer.verifyToctouSnapshot(snapDst, allowSymlinkTarget = false)
                if (verifySrc.isFailure || verifyDst.isFailure) {
                    val err = (verifySrc.exceptionOrNull() ?: verifyDst.exceptionOrNull())?.message ?: "TOCTOU check gagal"
                    _lastShellError.value = err
                    return@withWritableMount RootCommandResult(-1, emptyList(), listOf(err))
                }
                val src = shellQuote(verifySrc.getOrThrow().canonicalPath)
                val dst = shellQuote(verifyDst.getOrThrow().canonicalPath)
                executeCommand("mv -f $src $dst")
            }
        }
    }

    suspend fun rootRename(fromPath: String, toPath: String): RootCommandResult {
        return rootMove(fromPath, toPath)
    }

    suspend fun rootDelete(targetPath: String, recursive: Boolean = true): RootCommandResult {
        val snap = MountAnalyzer.captureToctouSnapshot(targetPath)
        if (MountAnalyzer.isBlockedDirectDeletePath(targetPath) || MountAnalyzer.isBlockedDirectDeletePath(snap.canonicalPath)) {
            val msg = "Diblokir: Penghapusan langsung pada partisi inti (${MountAnalyzer.normalizePath(targetPath)}) dilarang demi keamanan sistem. Hapus isi per item."
            _lastShellError.value = msg
            return RootCommandResult(-1, emptyList(), listOf(msg))
        }
        return withWritableMount(snap.canonicalPath) {
            val verified = MountAnalyzer.verifyToctouSnapshot(snap, allowSymlinkTarget = true)
            if (verified.isFailure) {
                val err = verified.exceptionOrNull()?.message ?: "TOCTOU verification gagal sebelum delete"
                _lastShellError.value = err
                return@withWritableMount RootCommandResult(-1, emptyList(), listOf(err))
            }
            // If it is a symlink itself, remove the link (`rm -f` on requestedPath), never follow into canonical target dir
            val actualTarget = if (snap.isSymlink) snap.requestedPath else verified.getOrThrow().canonicalPath
            val target = shellQuote(actualTarget)
            val flag = if (recursive && !snap.isSymlink) "-rf" else "-f"
            executeCommand("rm $flag $target")
        }
    }

    suspend fun rootMkdir(targetPath: String): RootCommandResult {
        val snap = MountAnalyzer.captureToctouSnapshot(targetPath)
        return withWritableMount(snap.canonicalPath) {
            val verified = MountAnalyzer.verifyToctouSnapshot(snap, allowSymlinkTarget = false)
            if (verified.isFailure) {
                val err = verified.exceptionOrNull()?.message ?: "TOCTOU check gagal"
                _lastShellError.value = err
                return@withWritableMount RootCommandResult(-1, emptyList(), listOf(err))
            }
            val target = shellQuote(verified.getOrThrow().canonicalPath)
            executeCommand("mkdir -p $target")
        }
    }

    suspend fun rootTouch(targetPath: String): RootCommandResult {
        val snap = MountAnalyzer.captureToctouSnapshot(targetPath)
        return withWritableMount(snap.canonicalPath) {
            val verified = MountAnalyzer.verifyToctouSnapshot(snap, allowSymlinkTarget = false)
            if (verified.isFailure) {
                val err = verified.exceptionOrNull()?.message ?: "TOCTOU check gagal"
                _lastShellError.value = err
                return@withWritableMount RootCommandResult(-1, emptyList(), listOf(err))
            }
            val target = shellQuote(verified.getOrThrow().canonicalPath)
            executeCommand("touch $target")
        }
    }

    suspend fun rootChmod(targetPath: String, modeOctal: String, recursive: Boolean = false): RootCommandResult {
        val safeMode = modeOctal.filter { it in '0'..'7' }.ifEmpty { "644" }
        val snap = MountAnalyzer.captureToctouSnapshot(targetPath)
        return withWritableMount(snap.canonicalPath) {
            val verified = MountAnalyzer.verifyToctouSnapshot(snap, allowSymlinkTarget = false)
            if (verified.isFailure) {
                val err = verified.exceptionOrNull()?.message ?: "TOCTOU / Symlink check gagal pada chmod"
                _lastShellError.value = err
                return@withWritableMount RootCommandResult(-1, emptyList(), listOf(err))
            }
            val target = shellQuote(verified.getOrThrow().canonicalPath)
            val flag = if (recursive) "-R " else ""
            executeCommand("chmod $flag$safeMode $target")
        }
    }

    suspend fun rootChown(targetPath: String, ownerGroup: String, recursive: Boolean = false): RootCommandResult {
        val safeOwner = ownerGroup.filter { it.isLetterOrDigit() || it == ':' || it == '_' || it == '.' }.ifEmpty { "root:root" }
        val snap = MountAnalyzer.captureToctouSnapshot(targetPath)
        return withWritableMount(snap.canonicalPath) {
            val verified = MountAnalyzer.verifyToctouSnapshot(snap, allowSymlinkTarget = false)
            if (verified.isFailure) {
                val err = verified.exceptionOrNull()?.message ?: "TOCTOU / Symlink check gagal pada chown"
                _lastShellError.value = err
                return@withWritableMount RootCommandResult(-1, emptyList(), listOf(err))
            }
            val target = shellQuote(verified.getOrThrow().canonicalPath)
            val flag = if (recursive) "-R " else ""
            executeCommand("chown $flag$safeOwner $target")
        }
    }

    /**
     * Backs up a system file/folder to `/sdcard/Download/MTExplorer_Backup/` before deletion.
     */
    suspend fun backupSystemItemToStorage(sourcePath: String): RootCommandResult = withContext(Dispatchers.IO) {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: "/sdcard/Download"
        val backupDir = "$downloadDir/MTExplorer_Backup"
        File(backupDir).mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = File(sourcePath).name.ifEmpty { "system_item" }
        val destPath = "$backupDir/${timestamp}_$baseName"
        val src = shellQuote(sourcePath)
        val dst = shellQuote(destPath)
        executeCommand("mkdir -p ${shellQuote(backupDir)} && cp -rf $src $dst")
    }
}
