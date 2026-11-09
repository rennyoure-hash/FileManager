package com.example.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class RootProviderType(val displayName: String, val serviceDir: String, val postFsDataDir: String) {
    MAGISK("Magisk", "/data/adb/service.d", "/data/adb/post-fs-data.d"),
    KERNELSU("KernelSU", "/data/adb/service.d", "/data/adb/post-fs-data.d"),
    APATCH("APatch", "/data/adb/service.d", "/data/adb/post-fs-data.d"),
    GENERIC_SU("Generic Superuser (su)", "/data/local/userinit.d", "/data/local/userinit.d"),
    NONE("Non-Root (Read-Only Fallback)", "", "")
}

data class RootProviderInfo(
    val provider: RootProviderType,
    val versionString: String,
    val suPath: String?,
    val isRootGranted: Boolean
)

data class PosixPermissionMatrix(
    val ownerRead: Boolean = true,
    val ownerWrite: Boolean = true,
    val ownerExec: Boolean = false,
    val groupRead: Boolean = true,
    val groupWrite: Boolean = false,
    val groupExec: Boolean = false,
    val otherRead: Boolean = true,
    val otherWrite: Boolean = false,
    val otherExec: Boolean = false,
    val setUid: Boolean = false,
    val setGid: Boolean = false,
    val sticky: Boolean = false
) {
    fun toOctalString(): String {
        val special = (if (setUid) 4 else 0) + (if (setGid) 2 else 0) + (if (sticky) 1 else 0)
        val u = (if (ownerRead) 4 else 0) + (if (ownerWrite) 2 else 0) + (if (ownerExec) 1 else 0)
        val g = (if (groupRead) 4 else 0) + (if (groupWrite) 2 else 0) + (if (groupExec) 1 else 0)
        val o = (if (otherRead) 4 else 0) + (if (otherWrite) 2 else 0) + (if (otherExec) 1 else 0)
        return "$special$u$g$o"
    }

    fun toSymbolicString(): String {
        val u = "${if (ownerRead) "r" else "-"}${if (ownerWrite) "w" else "-"}${if (ownerExec) "x" else "-"}"
        val g = "${if (groupRead) "r" else "-"}${if (groupWrite) "w" else "-"}${if (groupExec) "x" else "-"}"
        val o = "${if (otherRead) "r" else "-"}${if (otherWrite) "w" else "-"}${if (otherExec) "x" else "-"}"
        return "$u$g$o"
    }

    companion object {
        fun fromOctal(octal: String): PosixPermissionMatrix {
            val clean = octal.trim().padStart(4, '0').takeLast(4)
            val s = clean[0].digitToIntOrNull() ?: 0
            val u = clean[1].digitToIntOrNull() ?: 6
            val g = clean[2].digitToIntOrNull() ?: 4
            val o = clean[3].digitToIntOrNull() ?: 4
            return PosixPermissionMatrix(
                ownerRead = (u and 4) != 0,
                ownerWrite = (u and 2) != 0,
                ownerExec = (u and 1) != 0,
                groupRead = (g and 4) != 0,
                groupWrite = (g and 2) != 0,
                groupExec = (g and 1) != 0,
                otherRead = (o and 4) != 0,
                otherWrite = (o and 2) != 0,
                otherExec = (o and 1) != 0,
                setUid = (s and 4) != 0,
                setGid = (s and 2) != 0,
                sticky = (s and 1) != 0
            )
        }
    }
}

data class FileSecurityMetadata(
    val path: String,
    val octalMode: String,
    val owner: String,
    val group: String,
    val selinuxContext: String,
    val isImmutableAttr: Boolean,
    val rawAttributes: String,
    val symlinkTarget: String?,
    val isBrokenSymlink: Boolean
)

data class RunningProcessItem(
    val pid: Int,
    val user: String,
    val rssKb: Long,
    val name: String
)

data class BuildPropEntry(
    val key: String,
    val value: String,
    val comment: String = ""
)

/**
 * Fase 2 & Fase 8b.7: All root operations in SystemRootToolkit strictly route through
 * [RootManager.executeCommand] so they are verified against [MountAnalyzer.ALLOWED_ROOT_COMMANDS].
 * Direct `Shell.cmd(...)` calls outside `RootManager` are completely eliminated.
 */
object SystemRootToolkit {

    private val _safeReadOnlyMode = MutableStateFlow(false)
    val safeReadOnlyMode: StateFlow<Boolean> = _safeReadOnlyMode.asStateFlow()

    private val _providerInfo = MutableStateFlow(
        RootProviderInfo(RootProviderType.NONE, "Unknown", null, false)
    )
    val providerInfo: StateFlow<RootProviderInfo> = _providerInfo.asStateFlow()

    fun setSafeReadOnlyMode(enabled: Boolean) {
        _safeReadOnlyMode.value = enabled
    }

    /**
     * Detects active root provider (Magisk / KernelSU / APatch / Generic su) or non-root fallback
     * via [RootManager] without invoking `su` directly.
     */
    suspend fun detectRootProvider(): RootProviderInfo = withContext(Dispatchers.IO) {
        val access = RootManager.requestRootAccess()
        val suPath = access.suPath ?: RootManager.findSuBinary()
        val granted = access.status == RootStatus.GRANTED

        if (!granted) {
            val info = RootProviderInfo(
                provider = RootProviderType.NONE,
                versionString = if (suPath != null) "su ditemukan ($suPath) - izin belum diberikan" else "Non-Root (Mode Biasa)",
                suPath = suPath,
                isRootGranted = false
            )
            _providerInfo.value = info
            return@withContext info
        }

        val dirCheck = RootManager.executeCommand(
            "ls -d '/data/adb/magisk' '/data/adb/ksud' '/data/adb/ap' 2>/dev/null",
            reportErrorToUi = false
        )
        val combined = (dirCheck.stdout + listOfNotNull(suPath)).joinToString("\n")
        val lower = combined.lowercase()

        val provider = when {
            lower.contains("magisk") || combined.contains("/data/adb/magisk") -> RootProviderType.MAGISK
            lower.contains("kernelsu") || combined.contains("/data/adb/ksud") -> RootProviderType.KERNELSU
            lower.contains("apatch") || combined.contains("/data/adb/ap") -> RootProviderType.APATCH
            else -> RootProviderType.GENERIC_SU
        }

        val info = RootProviderInfo(
            provider = provider,
            versionString = "${provider.displayName} (${access.uidInfo.ifEmpty { "uid=0" }})",
            suPath = suPath,
            isRootGranted = true
        )
        _providerInfo.value = info
        info
    }

    /**
     * Inspects permissions, owner:group, SELinux context (`ls -Z`), attributes (`lsattr`), and symlink target
     * exclusively via [RootManager.executeCommand].
     */
    suspend fun inspectFileSecurity(path: String): FileSecurityMetadata = withContext(Dispatchers.IO) {
        val escaped = MountAnalyzer.escapeShellPath(path)
        val statRes = RootManager.executeCommand("stat -c '%a|%U|%G' $escaped 2>/dev/null", reportErrorToUi = false)
        val statParts = statRes.stdout.firstOrNull()?.split("|").orEmpty()
        val octal = statParts.getOrNull(0)?.trim().orEmpty().ifEmpty { "0644" }
        val owner = statParts.getOrNull(1)?.trim().orEmpty().ifEmpty { "root" }
        val group = statParts.getOrNull(2)?.trim().orEmpty().ifEmpty { "root" }

        val selinuxRes = RootManager.executeCommand("ls -dZ $escaped 2>/dev/null", reportErrorToUi = false)
        val selinuxLine = selinuxRes.stdout.firstOrNull()?.trim().orEmpty()
        val selinuxCtx = selinuxLine.split(Regex("\\s+")).firstOrNull { it.contains(":") } ?: "u:object_r:unlabeled:s0"

        val attrRes = RootManager.executeCommand("lsattr -d $escaped 2>/dev/null", reportErrorToUi = false)
        val attrFlags = attrRes.stdout.firstOrNull()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
        val isImmutable = attrFlags.contains('i')

        val linkRes = RootManager.executeCommand("readlink $escaped 2>/dev/null", reportErrorToUi = false)
        val symlinkTarget = linkRes.stdout.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val isBroken = if (symlinkTarget != null) {
            val derefStat = RootManager.executeCommand("stat -L $escaped 2>/dev/null", reportErrorToUi = false)
            !derefStat.isSuccess
        } else {
            false
        }

        FileSecurityMetadata(
            path = path,
            octalMode = octal,
            owner = owner,
            group = group,
            selinuxContext = selinuxCtx,
            isImmutableAttr = isImmutable,
            rawAttributes = attrFlags,
            symlinkTarget = symlinkTarget,
            isBrokenSymlink = isBroken
        )
    }

    suspend fun updateSelinuxContext(path: String, newContext: String?, restoreDefault: Boolean): RootCommandResult {
        if (_safeReadOnlyMode.value) {
            return RootCommandResult(1, emptyList(), listOf("Mode Aman (Global Read-Only) aktif — modifikasi SELinux diblokir"))
        }
        val escapedPath = MountAnalyzer.escapeShellPath(path)
        return RootManager.withWritableMount(path) {
            if (restoreDefault || newContext.isNullOrBlank()) {
                RootManager.executeCommand("restorecon -FR $escapedPath")
            } else {
                val escapedCtx = MountAnalyzer.escapeShellPath(newContext.trim())
                RootManager.executeCommand("chcon $escapedCtx $escapedPath")
            }
        }
    }

    suspend fun setImmutableAttribute(path: String, immutable: Boolean): RootCommandResult {
        if (_safeReadOnlyMode.value) {
            return RootCommandResult(1, emptyList(), listOf("Mode Aman (Global Read-Only) aktif — chattr diblokir"))
        }
        val escapedPath = MountAnalyzer.escapeShellPath(path)
        val flag = if (immutable) "+i" else "-i"
        return RootManager.withWritableMount(path) {
            RootManager.executeCommand("chattr $flag $escapedPath")
        }
    }

    suspend fun createSymlink(targetPath: String, linkPath: String): RootCommandResult {
        if (_safeReadOnlyMode.value) {
            return RootCommandResult(1, emptyList(), listOf("Mode Aman (Global Read-Only) aktif — pembuatan symlink diblokir"))
        }
        val escapedTarget = MountAnalyzer.escapeShellPath(targetPath)
        val escapedLink = MountAnalyzer.escapeShellPath(linkPath)
        return RootManager.withWritableMount(linkPath) {
            RootManager.executeCommand("ln -sf $escapedTarget $escapedLink")
        }
    }

    suspend fun readBuildProp(path: String = "/system/build.prop"): List<BuildPropEntry> = withContext(Dispatchers.IO) {
        val escaped = MountAnalyzer.escapeShellPath(path)
        val lines = try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                f.readLines()
            } else {
                RootManager.executeCommand("cat $escaped 2>/dev/null", reportErrorToUi = false).stdout
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val entries = mutableListOf<BuildPropEntry>()
        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx <= 0) continue
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (isValidBuildPropKey(key)) {
                entries.add(BuildPropEntry(key = key, value = value))
            }
        }
        entries
    }

    fun isValidBuildPropKey(key: String): Boolean {
        if (key.isBlank() || key.length > 128) return false
        return key.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    }

    suspend fun saveBuildProp(
        entries: List<BuildPropEntry>,
        targetPath: String = "/system/build.prop"
    ): Pair<RootCommandResult, String?> = withContext(Dispatchers.IO) {
        if (_safeReadOnlyMode.value) {
            return@withContext RootCommandResult(
                1,
                emptyList(),
                listOf("Mode Aman (Global Read-Only) aktif — penyimpanan build.prop diblokir")
            ) to null
        }

        for (entry in entries) {
            if (!isValidBuildPropKey(entry.key)) {
                return@withContext RootCommandResult(
                    1,
                    emptyList(),
                    listOf("Key build.prop tidak valid: '${entry.key}'")
                ) to null
            }
        }

        val backupRes = RootManager.backupSystemItemToStorage(targetPath)
        val backupStatus: String? = if (backupRes.isSuccess) "/sdcard/Download/MTExplorer_Backup" else null
        val tempFile = File.createTempFile("build_prop_edit", ".prop")
        try {
            val content = buildString {
                appendLine("# Generated & validated by MT Explorer Root Toolkit")
                for (entry in entries) {
                    appendLine("${entry.key}=${entry.value}")
                }
            }
            tempFile.writeText(content)
            val escapedTmp = MountAnalyzer.escapeShellPath(tempFile.absolutePath)
            val escapedTarget = MountAnalyzer.escapeShellPath(targetPath)

            val res = RootManager.withWritableMount(targetPath) {
                RootManager.executeCommand(
                    "cp -f $escapedTmp $escapedTarget && chmod 0644 $escapedTarget && chown root:root $escapedTarget && restorecon $escapedTarget"
                )
            }
            res to backupStatus
        } finally {
            tempFile.delete()
        }
    }

    suspend fun listRunningProcesses(): List<RunningProcessItem> = withContext(Dispatchers.IO) {
        val res = RootManager.executeCommand(
            "ps -A -o PID,USER,RSS,NAME 2>/dev/null || ps -o PID,USER,RSS,NAME 2>/dev/null",
            reportErrorToUi = false
        )
        val list = mutableListOf<RunningProcessItem>()
        val ws = Regex("\\s+")
        for (line in res.stdout.drop(1)) {
            val parts = line.trim().split(ws, limit = 4)
            if (parts.size < 4) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val user = parts[1]
            val rss = parts[2].toLongOrNull() ?: 0L
            val name = parts[3]
            list.add(RunningProcessItem(pid = pid, user = user, rssKb = rss, name = name))
        }
        list.sortedByDescending { it.rssKb }
    }

    suspend fun killProcess(pid: Int, signal: Int = 9): RootCommandResult {
        if (_safeReadOnlyMode.value) {
            return RootCommandResult(1, emptyList(), listOf("Mode Aman aktif — kill process diblokir"))
        }
        return RootManager.executeCommand("kill -$signal $pid")
    }

    suspend fun setPackageEnabledState(packageName: String, enabled: Boolean): RootCommandResult {
        if (_safeReadOnlyMode.value) {
            return RootCommandResult(1, emptyList(), listOf("Mode Aman aktif — freeze/unfreeze paket diblokir"))
        }
        val cleanPkg = packageName.trim()
        if (!cleanPkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
            return RootCommandResult(1, emptyList(), listOf("Nama paket tidak valid: $packageName"))
        }
        val escapedPkg = MountAnalyzer.escapeShellPath(cleanPkg)
        val cmd = if (enabled) {
            "pm enable --user 0 $escapedPkg"
        } else {
            "pm disable-user --user 0 $escapedPkg"
        }
        return RootManager.executeCommand(cmd)
    }

    suspend fun readSystemLogs(source: String = "logcat", maxLines: Int = 300, filterQuery: String = ""): List<String> =
        withContext(Dispatchers.IO) {
            val n = maxLines.coerceIn(50, 2000)
            val cmd = if (source == "dmesg") {
                "dmesg | tail -n $n"
            } else {
                "logcat -d -t $n"
            }
            val res = RootManager.executeCommand(cmd, reportErrorToUi = false)
            val lines = if (res.stdout.isNotEmpty()) res.stdout else res.stderr
            if (filterQuery.isBlank()) {
                lines
            } else {
                val q = filterQuery.trim().lowercase()
                lines.filter { it.lowercase().contains(q) }
            }
        }

    suspend fun listBootScripts(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val provider = _providerInfo.value.provider
        val dirs = listOf(provider.serviceDir, provider.postFsDataDir).filter { it.isNotEmpty() }.distinct()
        val result = mutableListOf<Pair<String, String>>()
        for (dir in dirs) {
            val escapedDir = MountAnalyzer.escapeShellPath(dir)
            val res = RootManager.executeCommand("ls -1 $escapedDir 2>/dev/null", reportErrorToUi = false)
            for (name in res.stdout) {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    result.add(dir to "$dir/$trimmed")
                }
            }
        }
        result
    }

    suspend fun saveBootScript(scriptPath: String, content: String): RootCommandResult = withContext(Dispatchers.IO) {
        if (_safeReadOnlyMode.value) {
            return@withContext RootCommandResult(1, emptyList(), listOf("Mode Aman aktif — boot script tidak disimpan"))
        }
        val tmp = File.createTempFile("boot_script", ".sh")
        try {
            tmp.writeText(content)
            val escapedTmp = MountAnalyzer.escapeShellPath(tmp.absolutePath)
            val escapedTarget = MountAnalyzer.escapeShellPath(scriptPath)
            val parentDir = File(scriptPath).parent ?: "/data/adb/service.d"
            val escapedParent = MountAnalyzer.escapeShellPath(parentDir)
            RootManager.executeCommand(
                "mkdir -p $escapedParent && cp -f $escapedTmp $escapedTarget && chmod 0755 $escapedTarget && chown root:root $escapedTarget"
            )
        } finally {
            tmp.delete()
        }
    }
}
