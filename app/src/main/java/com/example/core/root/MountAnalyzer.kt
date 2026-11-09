package com.example.core.root

import java.io.File
import java.nio.file.Files

enum class PartitionBadge(val label: String) {
    RW("RW"),
    RO("RO"),
    RO_PERMANENT("RO-permanen")
}

data class MountEntry(
    val device: String,
    val mountPoint: String,
    val fsType: String,
    val options: List<String>
) {
    val isPermanentReadOnly: Boolean
        get() = MountAnalyzer.isPermanentReadOnlyFs(fsType)

    val isRw: Boolean
        get() = !isPermanentReadOnly && options.contains("rw") && !options.contains("ro")

    val isRo: Boolean
        get() = isPermanentReadOnly || options.contains("ro") || !options.contains("rw")

    val badge: PartitionBadge
        get() = when {
            isPermanentReadOnly -> PartitionBadge.RO_PERMANENT
            isRw -> PartitionBadge.RW
            else -> PartitionBadge.RO
        }
}

enum class PreWriteAction {
    PROCEED_ALREADY_RW,
    REMOUNT_RW_REQUIRED,
    BLOCK_PERMANENT_RO
}

data class PreWriteDecision(
    val action: PreWriteAction,
    val mountEntry: MountEntry?,
    val message: String = ""
)

data class RemountFailureDiagnosis(
    val mountPoint: String,
    val fsType: String,
    val currentOptions: String,
    val isDynamicPartition: Boolean,
    val dynamicPartitionDetail: String,
    val isDmVerityActive: Boolean,
    val verityDetail: String,
    val mountStderr: String,
    val causes: List<String>,
    val magiskOverlayInfo: String = "Pada Android 10+ dengan Dynamic Partitions / dm-verity / EROFS, modifikasi langsung ke partisi sistem tidak disarankan. Gunakan mekanisme systemless seperti Magisk Module (\$MODDIR/system) atau KernelSU OverlayFS untuk menimpa file sistem secara aman tanpa merusak integritas partisi."
)

data class ToctouSnapshot(
    val requestedPath: String,
    val canonicalPath: String,
    val isSymlink: Boolean,
    val exists: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

object MountAnalyzer {

    private val PERMANENT_RO_FSTYPES = setOf(
        "erofs",
        "squashfs",
        "iso9660"
    )

    // Fase 8.1 & 8c.1: Critical root folder denylist
    private val BLOCKED_DIRECT_DELETE_PATHS = setOf(
        "/",
        "/system",
        "/system_ext",
        "/data",
        "/vendor",
        "/boot",
        "/product",
        "/dev",
        "/proc",
        "/sys"
    )

    // Fase 8c.1: Paths where recursive chmod -R, chown -R, or rm -r/-rf are strictly forbidden
    private val BLOCKED_RECURSIVE_PATHS = setOf(
        "/",
        "/system",
        "/system_ext",
        "/data",
        "/data/data",
        "/data/user",
        "/data/user/0",
        "/data/app",
        "/data/adb",
        "/vendor",
        "/boot",
        "/product",
        "/dev",
        "/proc",
        "/sys",
        "/sdcard",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0"
    )

    private val CRITICAL_SYSTEM_PARTITIONS = listOf(
        "/system",
        "/system_ext",
        "/vendor",
        "/product",
        "/boot",
        "/dev",
        "/proc",
        "/sys"
    )

    private val SENSITIVE_PREFIXES = listOf(
        "/system",
        "/vendor",
        "/product",
        "/boot",
        "/dev/block"
    )

    private val SYSTEM_BACKUP_PREFIXES = listOf(
        "/system",
        "/vendor",
        "/product",
        "/boot"
    )

    /**
     * Fase 8b.7 & 8c.1: Strict Root Command Allowlist.
     * Interpreters and high-risk meta-binaries (`sh`, `bash`, `su`, `busybox`, `toybox`,
     * `env`, `xargs`, `awk`, `sed`, `[`, `test`) are strictly EXCLUDED.
     * Every command with flags (`find`, `tar`, `cp`, `mv`, `rm`, `chmod`, `chown`, `ln`, `pm`, `am`, `mount`, `dd`)
     * is validated via an explicit per-command WHITELIST of flags/subcommands in [validateRootCommandAllowlist].
     */
    val ALLOWED_ROOT_COMMANDS: Set<String> = setOf(
        "id",
        "cat",
        "ls",
        "stat",
        "readlink",
        "realpath",
        "cp",
        "mv",
        "rm",
        "mkdir",
        "touch",
        "chmod",
        "chown",
        "chcon",
        "restorecon",
        "lsattr",
        "chattr",
        "ln",
        "mount",
        "getprop",
        "ps",
        "kill",
        "pm",
        "am",
        "logcat",
        "dmesg",
        "tail",
        "find",
        "tar",
        "fstrim",
        "dd"
    )

    /**
     * Explicitly banned interpreters & multiplexers for clear audit trail.
     */
    val FORBIDDEN_INTERPRETERS_AND_META_BINARIES: Set<String> = setOf(
        "sh",
        "bash",
        "zsh",
        "su",
        "busybox",
        "toybox",
        "env",
        "xargs",
        "awk",
        "sed",
        "perl",
        "python",
        "python3",
        "[",
        "test",
        "eval",
        "exec"
    )

    // Fase 8c.1: Explicit WHITELIST of allowed flags per command (anything outside these sets is rejected)
    val ALLOWED_CP_FLAGS: Set<String> = setOf("-r", "-f", "-rf", "-fr", "-p", "-a")
    val ALLOWED_MV_FLAGS: Set<String> = setOf("-f")
    val ALLOWED_RM_FLAGS: Set<String> = setOf("-f", "-r", "-rf", "-fr", "-d")
    val ALLOWED_CHMOD_FLAGS: Set<String> = setOf("-R")
    val ALLOWED_CHOWN_FLAGS: Set<String> = setOf("-R")
    val ALLOWED_LN_FLAGS: Set<String> = setOf("-s", "-f", "-sf", "-fs")
    val ALLOWED_FIND_FLAGS: Set<String> = setOf(
        "-name",
        "-iname",
        "-type",
        "-maxdepth",
        "-mindepth",
        "-size",
        "-mtime",
        "-print"
    )
    val ALLOWED_TAR_FLAGS: Set<String> = setOf(
        "-t",
        "-x",
        "-c",
        "-z",
        "-f",
        "-v",
        "-tf",
        "-ztf",
        "-tzf",
        "-xzf",
        "-xf",
        "-czf",
        "-cf",
        "-C"
    )
    val ALLOWED_PM_SUBCOMMANDS: Set<String> = setOf("enable", "disable-user")
    val ALLOWED_AM_SUBCOMMANDS: Set<String> = setOf("force-stop")

    // Allowed directory prefixes for `ln -s` (both target and link must resolve inside one of these)
    val ALLOWED_SYMLINK_PREFIXES: List<String> = listOf(
        "/sdcard/",
        "/storage/emulated/0/",
        "/data/local/tmp/",
        "/data/data/",
        "/data/user/0/"
    )

    private val OCTAL_MODE_REGEX = Regex("^[0-7]{3,4}$")
    private val OWNER_GROUP_REGEX = Regex("^[a-zA-Z0-9_.-]+(:[a-zA-Z0-9_.-]+)?$")
    private val VALID_PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    private val ALLOWED_DD_INPUT_PARTITIONS = setOf(
        "/dev/block/by-name/boot",
        "/dev/block/by-name/boot_a",
        "/dev/block/by-name/boot_b",
        "/dev/block/by-name/recovery",
        "/dev/block/by-name/init_boot",
        "/dev/block/by-name/init_boot_a",
        "/dev/block/by-name/init_boot_b"
    )

    fun isPermanentReadOnlyFs(fsType: String): Boolean {
        return PERMANENT_RO_FSTYPES.contains(fsType.trim().lowercase())
    }

    fun parseProcMounts(rawText: String): List<MountEntry> {
        return parseProcMounts(rawText.lines())
    }

    fun parseProcMounts(lines: List<String>): List<MountEntry> {
        val result = mutableListOf<MountEntry>()
        val whitespaceRegex = Regex("\\s+")
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(whitespaceRegex)
            if (parts.size < 4) continue

            val device = unescapeMountField(parts[0])
            val mountPoint = normalizePath(unescapeMountField(parts[1]))
            val fsType = parts[2].trim()
            val options = parts[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }

            result.add(
                MountEntry(
                    device = device,
                    mountPoint = mountPoint,
                    fsType = fsType,
                    options = options
                )
            )
        }
        return result
    }

    fun unescapeMountField(raw: String): String {
        return raw
            .replace("\\040", " ")
            .replace("\\011", "\t")
            .replace("\\012", "\n")
            .replace("\\134", "\\")
    }

    fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val collapsed = trimmed.replace(Regex("/+"), "/")
        return if (collapsed.length > 1 && collapsed.endsWith("/")) {
            collapsed.removeSuffix("/")
        } else {
            collapsed
        }
    }

    fun resolveCanonicalSafe(path: String): String {
        val norm = normalizePath(path)
        return try {
            normalizePath(File(norm).canonicalPath)
        } catch (_: Throwable) {
            norm
        }
    }

    fun findMountForPath(targetPath: String, mounts: List<MountEntry>): MountEntry? {
        if (mounts.isEmpty()) return null
        val normTarget = normalizePath(targetPath)

        var bestMatch: MountEntry? = null
        var bestLength = -1

        for (entry in mounts) {
            val mp = normalizePath(entry.mountPoint)
            val matches = if (mp == "/") {
                normTarget.startsWith("/")
            } else {
                normTarget == mp || normTarget.startsWith("$mp/")
            }

            if (matches && mp.length >= bestLength) {
                bestMatch = entry
                bestLength = mp.length
            }
        }
        return bestMatch
    }

    fun evaluatePreWriteDecision(mountEntry: MountEntry?): PreWriteDecision {
        if (mountEntry == null) {
            return PreWriteDecision(
                action = PreWriteAction.PROCEED_ALREADY_RW,
                mountEntry = null,
                message = "Mountpoint tidak ditemukan, lanjut tulis langsung"
            )
        }

        if (isPermanentReadOnlyFs(mountEntry.fsType)) {
            return PreWriteDecision(
                action = PreWriteAction.BLOCK_PERMANENT_RO,
                mountEntry = mountEntry,
                message = "Partisi ini read-only permanen (filesystem terkompresi)"
            )
        }

        if (mountEntry.isRw) {
            return PreWriteDecision(
                action = PreWriteAction.PROCEED_ALREADY_RW,
                mountEntry = mountEntry,
                message = "Mountpoint ${mountEntry.mountPoint} sudah RW"
            )
        }

        return PreWriteDecision(
            action = PreWriteAction.REMOUNT_RW_REQUIRED,
            mountEntry = mountEntry,
            message = "Mountpoint ${mountEntry.mountPoint} (${mountEntry.fsType}) saat ini RO, perlu remount RW"
        )
    }

    /**
     * Escapes a path with single quotes (`'` -> `'\''`) so special characters, spaces,
     * quotes, `$`, `;`, backticks, newlines, and Unicode cannot cause shell command injection.
     * Null bytes (`\u0000`) are stripped as POSIX paths cannot contain NUL.
     */
    fun escapeShellPath(path: String): String {
        val sanitized = path.replace("\u0000", "")
        return "'" + sanitized.replace("'", "'\\''") + "'"
    }

    /**
     * Builds a safe shell command from a base binary name (which MUST be in [ALLOWED_ROOT_COMMANDS])
     * and separate arguments, escaping each argument individually and validating per-command constraints.
     */
    fun buildSafeCommand(binary: String, vararg args: String): String {
        val cleanBin = binary.trim()
        require(ALLOWED_ROOT_COMMANDS.contains(cleanBin) && !FORBIDDEN_INTERPRETERS_AND_META_BINARIES.contains(cleanBin)) {
            "Perintah '$cleanBin' ditolak karena tidak ada di dalam Root Command Allowlist"
        }
        val built = if (args.isEmpty()) {
            cleanBin
        } else {
            val escapedArgs = args.joinToString(" ") { arg ->
                if (arg.startsWith("-") && arg.length <= 16 && arg.all { it.isLetterOrDigit() || it == '-' || it == ',' }) {
                    arg
                } else {
                    escapeShellPath(arg)
                }
            }
            "$cleanBin $escapedArgs"
        }
        validateRootCommandAllowlist(built).getOrThrow()
        return built
    }

    /**
     * Fase 8b.7 & Fase 8c.1:
     * Validates whether a command string complies with the Root Command Allowlist,
     * blocks interpreters (`sh`, `su`, `busybox`, `env`, `xargs`, `awk`), and enforces:
     * 1. Explicit per-command WHITELIST of flags for `find`, `tar`, `cp`, `mv`, `rm`, `chmod`, `chown`, `ln`.
     * 2. Canonical destination path validation (rejects critical root folders and `/dev/block`, `/proc`, `/sys`).
     * 3. Blocks recursive `chmod -R`, `chown -R`, and `rm -r`/`-rf`/`-fr` on critical system/data root paths.
     * 4. Explicit subcommand WHITELIST for `pm` (`enable --user 0 <pkg>`, `disable-user --user 0 <pkg>`)
     *    and `am` (`force-stop <pkg>`).
     * 5. Rejects `ln -s` / `ln -sf` when symlink target or link path resolves outside [ALLOWED_SYMLINK_PREFIXES].
     */
    fun validateRootCommandAllowlist(commandLine: String): Result<Unit> {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Perintah kosong tidak diizinkan"))
        }

        // Reject unquoted backticks or $() command substitution outside single quotes
        if (hasUnquotedCommandSubstitution(trimmed)) {
            return Result.failure(
                SecurityException("Security Allowlist Violation: Subshell/command substitution di luar kutip tunggal dilarang!")
            )
        }

        val segments = splitTopLevelShellSegments(trimmed)
        for (segment in segments) {
            val cleanSeg = segment.trim()
            if (cleanSeg.isEmpty()) continue

            val tokens = tokenizeShellSegment(cleanSeg)
            if (tokens.isEmpty()) continue

            val firstToken = tokens.first()
            val baseBinary = firstToken.substringAfterLast('/')

            if (FORBIDDEN_INTERPRETERS_AND_META_BINARIES.contains(baseBinary) ||
                FORBIDDEN_INTERPRETERS_AND_META_BINARIES.contains(firstToken)
            ) {
                return Result.failure(
                    SecurityException("Security Allowlist Violation: Interpreter/binary berisiko '$firstToken' dilarang keras!")
                )
            }

            if (firstToken != baseBinary || !ALLOWED_ROOT_COMMANDS.contains(firstToken)) {
                return Result.failure(
                    SecurityException("Security Allowlist Violation: Perintah '$firstToken' tidak ada di dalam ALLOWED_ROOT_COMMANDS")
                )
            }

            val rawArgs = tokens.drop(1).filterNot { it == "2>/dev/null" }

            when (firstToken) {
                "cp" -> {
                    val flags = mutableListOf<String>()
                    val paths = mutableListOf<String>()
                    for (arg in rawArgs) {
                        val u = unquoteSingle(arg)
                        if (u.startsWith("-")) flags.add(u) else paths.add(u)
                    }
                    for (flag in flags) {
                        if (!ALLOWED_CP_FLAGS.contains(flag)) {
                            return Result.failure(
                                SecurityException("Security Whitelist Violation: Flag cp '$flag' tidak ada di daftar putih ($ALLOWED_CP_FLAGS)!")
                            )
                        }
                    }
                    if (paths.size < 2) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: cp membutuhkan path sumber dan tujuan!")
                        )
                    }
                    val destCheck = validateDestinationPath(paths.last(), "cp")
                    if (destCheck.isFailure) return destCheck
                }

                "mv" -> {
                    val flags = mutableListOf<String>()
                    val paths = mutableListOf<String>()
                    for (arg in rawArgs) {
                        val u = unquoteSingle(arg)
                        if (u.startsWith("-")) flags.add(u) else paths.add(u)
                    }
                    for (flag in flags) {
                        if (!ALLOWED_MV_FLAGS.contains(flag)) {
                            return Result.failure(
                                SecurityException("Security Whitelist Violation: Flag mv '$flag' tidak ada di daftar putih ($ALLOWED_MV_FLAGS)!")
                            )
                        }
                    }
                    if (paths.size < 2) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: mv membutuhkan path sumber dan tujuan!")
                        )
                    }
                    for (src in paths.dropLast(1)) {
                        if (isBlockedDirectDeletePath(src)) {
                            return Result.failure(
                                SecurityException("Security Policy Violation: Memindahkan folder kritis '$src' dilarang!")
                            )
                        }
                    }
                    val destCheck = validateDestinationPath(paths.last(), "mv")
                    if (destCheck.isFailure) return destCheck
                }

                "rm" -> {
                    val flags = mutableListOf<String>()
                    val paths = mutableListOf<String>()
                    for (arg in rawArgs) {
                        val u = unquoteSingle(arg)
                        if (u.startsWith("-")) flags.add(u) else paths.add(u)
                    }
                    for (flag in flags) {
                        if (!ALLOWED_RM_FLAGS.contains(flag)) {
                            return Result.failure(
                                SecurityException("Security Whitelist Violation: Flag rm '$flag' tidak ada di daftar putih ($ALLOWED_RM_FLAGS)!")
                            )
                        }
                    }
                    if (paths.isEmpty()) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: rm membutuhkan minimal satu target path!")
                        )
                    }
                    val isRecursive = flags.any { it.contains('r') || it.contains('R') }
                    for (target in paths) {
                        if (isBlockedDirectDeletePath(target)) {
                            return Result.failure(
                                SecurityException("Security Policy Violation: Menghapus partisi/folder kritis '$target' dilarang keras!")
                            )
                        }
                        if (isRecursive && isBlockedRecursiveOperationPath(target, allowAppDataPkgDir = false)) {
                            return Result.failure(
                                SecurityException("Security Policy Violation: rm rekursif pada path kritis '$target' dilarang keras!")
                            )
                        }
                    }
                }

                "chmod" -> {
                    val flags = mutableListOf<String>()
                    val nonFlags = mutableListOf<String>()
                    for (arg in rawArgs) {
                        val u = unquoteSingle(arg)
                        if (u.startsWith("-")) flags.add(u) else nonFlags.add(u)
                    }
                    for (flag in flags) {
                        if (!ALLOWED_CHMOD_FLAGS.contains(flag)) {
                            return Result.failure(
                                SecurityException("Security Whitelist Violation: Flag chmod '$flag' tidak ada di daftar putih ($ALLOWED_CHMOD_FLAGS)!")
                            )
                        }
                    }
                    if (nonFlags.size != 2) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: chmod membutuhkan <mode_octal> dan <target_path>!")
                        )
                    }
                    val mode = nonFlags[0]
                    val targetPath = nonFlags[1]
                    if (!OCTAL_MODE_REGEX.matches(mode)) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Mode chmod '$mode' harus berupa oktal 3-4 digit (0-7)!")
                        )
                    }
                    val destCheck = validateDestinationPath(targetPath, "chmod")
                    if (destCheck.isFailure) return destCheck
                    val isRecursive = flags.contains("-R")
                    if (isRecursive && isBlockedRecursiveOperationPath(targetPath, allowAppDataPkgDir = true)) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: chmod -R rekursif pada path kritis '$targetPath' dilarang keras!")
                        )
                    }
                }

                "chown" -> {
                    val flags = mutableListOf<String>()
                    val nonFlags = mutableListOf<String>()
                    for (arg in rawArgs) {
                        val u = unquoteSingle(arg)
                        if (u.startsWith("-")) flags.add(u) else nonFlags.add(u)
                    }
                    for (flag in flags) {
                        if (!ALLOWED_CHOWN_FLAGS.contains(flag)) {
                            return Result.failure(
                                SecurityException("Security Whitelist Violation: Flag chown '$flag' tidak ada di daftar putih ($ALLOWED_CHOWN_FLAGS)!")
                            )
                        }
                    }
                    if (nonFlags.size != 2) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: chown membutuhkan <owner:group> dan <target_path>!")
                        )
                    }
                    val ownerGroup = nonFlags[0]
                    val targetPath = nonFlags[1]
                    if (!OWNER_GROUP_REGEX.matches(ownerGroup)) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Format owner:group chown '$ownerGroup' tidak valid!")
                        )
                    }
                    val destCheck = validateDestinationPath(targetPath, "chown")
                    if (destCheck.isFailure) return destCheck
                    val isRecursive = flags.contains("-R")
                    if (isRecursive && isBlockedRecursiveOperationPath(targetPath, allowAppDataPkgDir = true)) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: chown -R rekursif pada path kritis '$targetPath' dilarang keras!")
                        )
                    }
                }

                "ln" -> {
                    val flags = mutableListOf<String>()
                    val paths = mutableListOf<String>()
                    for (arg in rawArgs) {
                        val u = unquoteSingle(arg)
                        if (u.startsWith("-")) flags.add(u) else paths.add(u)
                    }
                    if (flags.isEmpty() || !flags.all { ALLOWED_LN_FLAGS.contains(it) } || !flags.any { it.contains('s') }) {
                        return Result.failure(
                            SecurityException("Security Whitelist Violation: ln hanya diizinkan dengan flag symlink di $ALLOWED_LN_FLAGS (hardlink dilarang)!")
                        )
                    }
                    if (paths.size != 2) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: ln membutuhkan tepat <target_path> dan <link_path>!")
                        )
                    }
                    val symlinkCheck = validateSymlinkPaths(paths[0], paths[1])
                    if (symlinkCheck.isFailure) return symlinkCheck
                }

                "find" -> {
                    if (rawArgs.isEmpty()) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: find membutuhkan path pencarian!")
                        )
                    }
                    val searchRoot = unquoteSingle(rawArgs.first())
                    if (!searchRoot.startsWith("/")) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Path pencarian find '$searchRoot' harus absolut!")
                        )
                    }
                    var i = 1
                    while (i < rawArgs.size) {
                        val token = unquoteSingle(rawArgs[i])
                        if (!token.startsWith("-")) {
                            return Result.failure(
                                SecurityException("Security Whitelist Violation: Token find '$token' tidak diharapkan tanpa flag mendahului!")
                            )
                        }
                        if (!ALLOWED_FIND_FLAGS.contains(token)) {
                            return Result.failure(
                                SecurityException("Security Whitelist Violation: Flag find '$token' ditolak (hanya $ALLOWED_FIND_FLAGS yang diizinkan)!")
                            )
                        }
                        when (token) {
                            "-print" -> i += 1
                            "-maxdepth", "-mindepth" -> {
                                val nextVal = rawArgs.getOrNull(i + 1)?.let { unquoteSingle(it) }
                                val depth = nextVal?.toIntOrNull()
                                if (depth == null || depth !in 0..16) {
                                    return Result.failure(
                                        SecurityException("Security Policy Violation: Nilai $token harus angka 0..16!")
                                    )
                                }
                                i += 2
                            }
                            "-type" -> {
                                val nextVal = rawArgs.getOrNull(i + 1)?.let { unquoteSingle(it) }
                                if (nextVal !in setOf("f", "d", "l")) {
                                    return Result.failure(
                                        SecurityException("Security Policy Violation: Nilai -type find hanya boleh f, d, atau l!")
                                    )
                                }
                                i += 2
                            }
                            "-name", "-iname", "-size", "-mtime" -> {
                                val nextVal = rawArgs.getOrNull(i + 1)?.let { unquoteSingle(it) }
                                if (nextVal.isNullOrEmpty() || nextVal.startsWith("-")) {
                                    return Result.failure(
                                        SecurityException("Security Policy Violation: Flag find '$token' membutuhkan argumen nilai!")
                                    )
                                }
                                i += 2
                            }
                            else -> i += 1
                        }
                    }
                }

                "tar" -> {
                    if (rawArgs.isEmpty()) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: tar membutuhkan argumen!")
                        )
                    }
                    var i = 0
                    while (i < rawArgs.size) {
                        val u = unquoteSingle(rawArgs[i])
                        if (u.startsWith("-")) {
                            if (!ALLOWED_TAR_FLAGS.contains(u)) {
                                return Result.failure(
                                    SecurityException("Security Whitelist Violation: Flag tar '$u' ditolak (hanya $ALLOWED_TAR_FLAGS yang diizinkan)!")
                                )
                            }
                            if (u == "-C") {
                                val targetDir = rawArgs.getOrNull(i + 1)?.let { unquoteSingle(it) }
                                if (targetDir.isNullOrEmpty()) {
                                    return Result.failure(
                                        SecurityException("Security Policy Violation: tar -C membutuhkan direktori tujuan!")
                                    )
                                }
                                val canonicalDest = resolveCanonicalSafe(targetDir)
                                // Allow /data/data for app restore or non-critical user storage paths
                                if (canonicalDest != "/data/data" && isBlockedDirectDeletePath(canonicalDest)) {
                                    return Result.failure(
                                        SecurityException("Security Policy Violation: Direktori tujuan tar -C '$canonicalDest' dilarang!")
                                    )
                                }
                                i += 2
                                continue
                            }
                        } else {
                            // Path argument to tar: must not contain '..'
                            if (u.split('/').contains("..")) {
                                return Result.failure(
                                    SecurityException("Security Policy Violation: Path tar '$u' mengandung path traversal '..'!")
                                )
                            }
                        }
                        i++
                    }
                }

                "pm" -> {
                    // Explicit subcommand whitelist:
                    // ONLY `pm enable --user 0 <pkg>` or `pm disable-user --user 0 <pkg>`
                    if (rawArgs.size != 4) {
                        return Result.failure(
                            SecurityException("Security Whitelist Violation: Format pm harus 'pm <enable|disable-user> --user 0 <package>'!")
                        )
                    }
                    val sub = unquoteSingle(rawArgs[0])
                    val userFlag = unquoteSingle(rawArgs[1])
                    val userId = unquoteSingle(rawArgs[2])
                    val pkg = unquoteSingle(rawArgs[3])
                    if (!ALLOWED_PM_SUBCOMMANDS.contains(sub) || userFlag != "--user" || userId != "0") {
                        return Result.failure(
                            SecurityException("Security Whitelist Violation: Subcommand pm '$sub' tidak ada di daftar putih ($ALLOWED_PM_SUBCOMMANDS --user 0)!")
                        )
                    }
                    if (!VALID_PACKAGE_REGEX.matches(pkg)) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Nama paket pm '$pkg' tidak valid!")
                        )
                    }
                }

                "am" -> {
                    // Explicit subcommand whitelist:
                    // ONLY `am force-stop <pkg>`
                    if (rawArgs.size != 2) {
                        return Result.failure(
                            SecurityException("Security Whitelist Violation: Format am harus 'am force-stop <package>'!")
                        )
                    }
                    val sub = unquoteSingle(rawArgs[0])
                    val pkg = unquoteSingle(rawArgs[1])
                    if (!ALLOWED_AM_SUBCOMMANDS.contains(sub)) {
                        return Result.failure(
                            SecurityException("Security Whitelist Violation: Subcommand am '$sub' tidak ada di daftar putih ($ALLOWED_AM_SUBCOMMANDS)!")
                        )
                    }
                    if (!VALID_PACKAGE_REGEX.matches(pkg)) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Nama paket am '$pkg' tidak valid!")
                        )
                    }
                }

                "mount" -> {
                    // Only permit `mount -o rw,remount <mp>` or `mount -o ro,remount <mp>`
                    if (rawArgs.size != 3 || rawArgs[0] != "-o" ||
                        (rawArgs[1] != "rw,remount" && rawArgs[1] != "ro,remount")
                    ) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Perintah mount hanya diizinkan untuk '-o rw,remount <mp>' atau '-o ro,remount <mp>'!")
                        )
                    }
                    val mp = normalizePath(unquoteSingle(rawArgs[2]))
                    if (!mp.startsWith("/")) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Mountpoint '$mp' tidak valid!")
                        )
                    }
                }

                "dd" -> {
                    var inputFile: String? = null
                    var outputFile: String? = null
                    for (arg in rawArgs) {
                        val unquoted = unquoteSingle(arg)
                        when {
                            unquoted.startsWith("if=") -> inputFile = normalizePath(unquoted.removePrefix("if="))
                            unquoted.startsWith("of=") -> outputFile = normalizePath(unquoted.removePrefix("of="))
                            unquoted.startsWith("bs=") || unquoted.startsWith("count=") -> Unit
                            else -> return Result.failure(
                                SecurityException("Security Policy Violation: Argumen dd '$unquoted' tidak diizinkan!")
                            )
                        }
                    }
                    if (inputFile == null || outputFile == null) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: dd wajib menyertakan if= dan of= yang eksplisit!")
                        )
                    }
                    if (outputFile.startsWith("/dev") ||
                        outputFile.startsWith("/system") ||
                        outputFile.startsWith("/vendor") ||
                        outputFile.startsWith("/boot") ||
                        outputFile.startsWith("/product") ||
                        outputFile.startsWith("/proc") ||
                        outputFile.startsWith("/sys")
                    ) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Menulis langsung ke blok/partisi sistem (dd of=$outputFile) dilarang keras!")
                        )
                    }
                    if (!ALLOWED_DD_INPUT_PARTITIONS.contains(inputFile)) {
                        return Result.failure(
                            SecurityException("Security Policy Violation: Sumber dd hanya diizinkan dari partisi boot/recovery read-only ($inputFile ditolak)!")
                        )
                    }
                }
            }
        }
        return Result.success(Unit)
    }

    /**
     * Fase 8c.1: Validates that a destination path (canonicalized) is not a root critical folder,
     * does not contain traversal segments (`..`), and does not write into `/dev/block`, `/proc`, or `/sys`.
     */
    fun validateDestinationPath(rawDestPath: String, commandName: String): Result<Unit> {
        val norm = normalizePath(rawDestPath)
        if (!norm.startsWith("/")) {
            return Result.failure(
                SecurityException("Security Policy Violation ($commandName): Path tujuan '$rawDestPath' harus path absolut!")
            )
        }
        if (rawDestPath.split('/').contains("..")) {
            return Result.failure(
                SecurityException("Security Policy Violation ($commandName): Path tujuan '$rawDestPath' mengandung '..' (path traversal)!")
            )
        }
        val canonical = resolveCanonicalSafe(norm)
        if (BLOCKED_DIRECT_DELETE_PATHS.contains(norm) || BLOCKED_DIRECT_DELETE_PATHS.contains(canonical)) {
            return Result.failure(
                SecurityException("Security Policy Violation ($commandName): Path tujuan '$canonical' adalah folder kritis root yang dilindungi!")
            )
        }
        if (canonical == "/dev" || canonical.startsWith("/dev/block") ||
            canonical == "/proc" || canonical.startsWith("/proc/") ||
            canonical == "/sys" || canonical.startsWith("/sys/")
        ) {
            return Result.failure(
                SecurityException("Security Policy Violation ($commandName): Menulis/memodifikasi '$canonical' dilarang keras!")
            )
        }
        return Result.success(Unit)
    }

    /**
     * Fase 8c.1: Determines whether a recursive operation (`rm -rf`, `chmod -R`, `chown -R`)
     * is blocked on [rawPath].
     * - Always blocks any path in [BLOCKED_RECURSIVE_PATHS] (`/`, `/system`, `/vendor`, `/data`, `/data/data`, `/sdcard`, etc.).
     * - Always blocks any path inside `/system`, `/system_ext`, `/vendor`, `/product`, `/boot`, `/dev`, `/proc`, `/sys`
     *   for recursive operations (prevents `chmod -R 0777 /system/bin` or `rm -rf /system/priv-app`).
     * - Allows `/data/data/<valid_pkg>` when [allowAppDataPkgDir] is `true` (used for restoring app data ownership).
     */
    fun isBlockedRecursiveOperationPath(rawPath: String, allowAppDataPkgDir: Boolean): Boolean {
        val norm = normalizePath(rawPath)
        val canonical = resolveCanonicalSafe(norm)
        for (candidate in listOf(norm, canonical)) {
            if (BLOCKED_RECURSIVE_PATHS.contains(candidate)) return true
            if (CRITICAL_SYSTEM_PARTITIONS.any { part -> candidate == part || candidate.startsWith("$part/") }) {
                return true
            }
            if (candidate.startsWith("/data/data/")) {
                val remainder = candidate.removePrefix("/data/data/")
                val pkg = remainder.substringBefore("/")
                if (!VALID_PACKAGE_REGEX.matches(pkg)) return true
                if (!allowAppDataPkgDir && !remainder.contains("/")) {
                    // Even for rm -rf, disallow wiping an entire package root `/data/data/<pkg>` directly via raw shell
                    return true
                }
            }
        }
        return false
    }

    /**
     * Fase 8c.1: Validates `ln -s` / `ln -sf` so neither [rawTarget] nor [rawLink] resolves outside
     * [ALLOWED_SYMLINK_PREFIXES], and cross-package symlinks inside `/data/data` are rejected.
     */
    fun validateSymlinkPaths(rawTarget: String, rawLink: String): Result<Unit> {
        if (rawTarget.split('/').contains("..") || rawLink.split('/').contains("..")) {
            return Result.failure(
                SecurityException("Security Policy Violation (ln): Path symlink tidak boleh mengandung '..'!")
            )
        }
        val normTarget = normalizePath(rawTarget)
        val normLink = normalizePath(rawLink)
        val canonTarget = resolveCanonicalSafe(normTarget)
        val canonLink = resolveCanonicalSafe(normLink)

        val targetAllowed = ALLOWED_SYMLINK_PREFIXES.any { prefix ->
            normTarget.startsWith(prefix) && canonTarget.startsWith(prefix)
        }
        val linkAllowed = ALLOWED_SYMLINK_PREFIXES.any { prefix ->
            normLink.startsWith(prefix) && canonLink.startsWith(prefix)
        }
        if (!targetAllowed || !linkAllowed) {
            return Result.failure(
                SecurityException(
                    "Security Policy Violation (ln): Symlink ditolak karena target ('$canonTarget') atau link ('$canonLink') berada di luar folder yang diizinkan ($ALLOWED_SYMLINK_PREFIXES)!"
                )
            )
        }

        // Prevent cross-app symlink attacks inside /data/data or /data/user/0
        val targetPkg = extractAppDataPackage(canonTarget)
        val linkPkg = extractAppDataPackage(canonLink)
        if ((targetPkg != null || linkPkg != null) && targetPkg != linkPkg) {
            return Result.failure(
                SecurityException(
                    "Security Policy Violation (ln): Symlink lintas sandbox aplikasi (/data/data/$targetPkg -> /data/data/$linkPkg) dilarang keras!"
                )
            )
        }

        return Result.success(Unit)
    }

    private fun extractAppDataPackage(path: String): String? {
        for (prefix in listOf("/data/data/", "/data/user/0/")) {
            if (path.startsWith(prefix)) {
                return path.removePrefix(prefix).substringBefore('/').takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun unquoteSingle(token: String): String {
        val t = token.trim()
        return if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) {
            t.substring(1, t.length - 1).replace("'\\''", "'")
        } else {
            t
        }
    }

    private fun hasUnquotedCommandSubstitution(cmd: String): Boolean {
        var inSingleQuote = false
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (c == '\'') {
                inSingleQuote = !inSingleQuote
            } else if (!inSingleQuote) {
                if (c == '`') return true
                if (c == '$' && i + 1 < cmd.length && cmd[i + 1] == '(') return true
            }
            i++
        }
        return false
    }

    private fun tokenizeShellSegment(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingleQuote = false
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '\'') {
                inSingleQuote = !inSingleQuote
                sb.append(c)
                i++
            } else if (!inSingleQuote && c.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                }
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }

    private fun splitTopLevelShellSegments(cmd: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingleQuote = false
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (c == '\'') {
                inSingleQuote = !inSingleQuote
                sb.append(c)
                i++
            } else if (!inSingleQuote && i + 1 < cmd.length &&
                ((c == '&' && cmd[i + 1] == '&') || (c == '|' && cmd[i + 1] == '|'))
            ) {
                result.add(sb.toString())
                sb.setLength(0)
                i += 2
            } else if (!inSingleQuote && (c == '|' || c == ';')) {
                result.add(sb.toString())
                sb.setLength(0)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }

    /**
     * Fase 8.1: Captures a canonical path snapshot before a root operation and verifies it right before execution
     * to prevent TOCTOU (Time-of-Check to Time-of-Use) and symlink swap attacks.
     */
    fun captureToctouSnapshot(path: String): ToctouSnapshot {
        val file = File(path)
        val isSymlink = try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            false
        }
        val canonical = try {
            file.canonicalPath
        } catch (_: Throwable) {
            normalizePath(path)
        }
        return ToctouSnapshot(
            requestedPath = normalizePath(path),
            canonicalPath = normalizePath(canonical),
            isSymlink = isSymlink,
            exists = file.exists(),
            sizeBytes = if (file.exists() && file.isFile) file.length() else 0L,
            lastModified = if (file.exists()) file.lastModified() else 0L
        )
    }

    /**
     * Verifies that the target file has not been swapped with a symlink or redirected to a different
     * canonical path between the initial check and the actual root execution.
     */
    fun verifyToctouSnapshot(
        initial: ToctouSnapshot,
        allowSymlinkTarget: Boolean = false
    ): Result<ToctouSnapshot> {
        val current = captureToctouSnapshot(initial.requestedPath)
        if (!allowSymlinkTarget && current.isSymlink) {
            return Result.failure(
                SecurityException("TOCTOU / Symlink Guard: Target '${initial.requestedPath}' adalah symlink yang tidak diizinkan.")
            )
        }
        if (initial.canonicalPath != current.canonicalPath) {
            return Result.failure(
                SecurityException(
                    "TOCTOU Violation: Canonical path berubah dari '${initial.canonicalPath}' menjadi '${current.canonicalPath}'!"
                )
            )
        }
        if (isBlockedDirectDeletePath(current.canonicalPath)) {
            return Result.failure(
                SecurityException("Denylist Violation: Resolusi canonical path mengarah ke partisi kritis '${current.canonicalPath}'!")
            )
        }
        return Result.success(current)
    }

    fun isBlockedDirectDeletePath(path: String): Boolean {
        val norm = normalizePath(path)
        if (BLOCKED_DIRECT_DELETE_PATHS.contains(norm)) return true
        val canonical = resolveCanonicalSafe(norm)
        return BLOCKED_DIRECT_DELETE_PATHS.contains(canonical)
    }

    fun isSensitiveWritePath(path: String, ownPackageName: String? = null): Boolean {
        val norm = normalizePath(path)
        for (prefix in SENSITIVE_PREFIXES) {
            if (norm == prefix || norm.startsWith("$prefix/")) {
                return true
            }
        }
        for (dataPrefix in listOf("/data/data", "/data/user/0")) {
            if (norm == dataPrefix) return true
            if (norm.startsWith("$dataPrefix/")) {
                val remainder = norm.removePrefix("$dataPrefix/")
                val pkg = remainder.substringBefore("/")
                if (ownPackageName.isNullOrEmpty() || pkg != ownPackageName) {
                    return true
                }
            }
        }
        return false
    }

    fun isSystemAreaForBackup(path: String): Boolean {
        val norm = normalizePath(path)
        return SYSTEM_BACKUP_PREFIXES.any { prefix ->
            norm == prefix || norm.startsWith("$prefix/")
        }
    }
}
