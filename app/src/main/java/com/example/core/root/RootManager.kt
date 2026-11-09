package com.example.core.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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

object RootManager {

    private const val PREFS_NAME = "mt_explorer_root_prefs"
    private const val KEY_ROOT_EXPLORER_ENABLED = "root_explorer_enabled"
    private const val KEY_MOUNT_SYSTEM_RW = "mount_system_rw"

    private val SU_BINARY_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/debug_ramdisk/su",
        "/apex/com.android.runtime/bin/su"
    )

    private val _rootStatus = MutableStateFlow(RootStatus.UNKNOWN)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private val _isRootExplorerEnabled = MutableStateFlow(false)
    val isRootExplorerEnabledFlow: StateFlow<Boolean> = _isRootExplorerEnabled.asStateFlow()

    /**
     * Checks if a `su` binary exists in known system paths or via `which su`.
     */
    fun findSuBinary(): String? {
        for (path in SU_BINARY_PATHS) {
            try {
                val file = File(path)
                if (file.exists()) {
                    return path
                }
            } catch (_: Throwable) {
            }
        }
        return try {
            val process = ProcessBuilder("which", "su").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor(2, TimeUnit.SECONDS)
            if (!output.isNullOrEmpty() && File(output).exists()) output else null
        } catch (_: Throwable) {
            null
        }
    }

    fun isDeviceRooted(): Boolean {
        return findSuBinary() != null
    }

    /**
     * Synchronously reads persisted Root Explorer state from SharedPreferences.
     */
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

    /**
     * Persists Root Explorer enabled state.
     */
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
     * Requests superuser permissions by invoking `su -c id` and verifying `uid=0`.
     */
    suspend fun requestRootAccess(timeoutSeconds: Long = 15L): RootAccessResult = withContext(Dispatchers.IO) {
        _rootStatus.value = RootStatus.CHECKING
        val suPath = findSuBinary()

        try {
            val cmd = suPath ?: "su"
            val process = ProcessBuilder(cmd, "-c", "id").start()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                _rootStatus.value = RootStatus.DENIED
                return@withContext RootAccessResult(
                    status = RootStatus.DENIED,
                    suPath = suPath,
                    message = "Superuser request timed out"
                )
            }

            val outText = stdoutReader.readText().trim()
            val errText = stderrReader.readText().trim()
            val exitCode = process.exitValue()

            if (exitCode == 0 && outText.contains("uid=0")) {
                _rootStatus.value = RootStatus.GRANTED
                RootAccessResult(
                    status = RootStatus.GRANTED,
                    uidInfo = outText,
                    suPath = suPath ?: "su",
                    message = "Superuser access granted ($outText)"
                )
            } else {
                _rootStatus.value = RootStatus.DENIED
                RootAccessResult(
                    status = RootStatus.DENIED,
                    uidInfo = outText,
                    suPath = suPath,
                    message = errText.ifEmpty { "Superuser access denied (exit code $exitCode)" }
                )
            }
        } catch (t: Throwable) {
            val status = if (suPath == null) RootStatus.NOT_AVAILABLE else RootStatus.DENIED
            _rootStatus.value = status
            RootAccessResult(
                status = status,
                suPath = suPath,
                message = if (suPath == null) {
                    "No su binary found on this device"
                } else {
                    t.localizedMessage ?: "Failed to execute su binary"
                }
            )
        }
    }

    /**
     * Executes a shell command with superuser privileges (`su -c <command>`).
     */
    suspend fun executeCommand(command: String, timeoutSeconds: Long = 15L): RootCommandResult = withContext(Dispatchers.IO) {
        try {
            val suCmd = findSuBinary() ?: "su"
            val process = ProcessBuilder(suCmd, "-c", command).start()

            val stdoutLines = mutableListOf<String>()
            val stderrLines = mutableListOf<String>()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stdoutLines.add(line!!)
                }
            }

            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stderrLines.add(line!!)
                }
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext RootCommandResult(
                    exitCode = -1,
                    stdout = stdoutLines,
                    stderr = stderrLines + "Command timed out"
                )
            }

            RootCommandResult(
                exitCode = process.exitValue(),
                stdout = stdoutLines,
                stderr = stderrLines
            )
        } catch (t: Throwable) {
            RootCommandResult(
                exitCode = -1,
                stdout = emptyList(),
                stderr = listOf(t.localizedMessage ?: "Failed to execute root command")
            )
        }
    }

    /**
     * Remounts `/system` and `/` partitions as read-write (rw) or read-only (ro).
     */
    suspend fun remountSystem(writable: Boolean): RootCommandResult {
        val mode = if (writable) "rw" else "ro"
        val cmd = "mount -o remount,$mode /system 2>/dev/null || mount -o remount,$mode /"
        return executeCommand(cmd)
    }

    /**
     * Safely quotes a file path for POSIX shell execution.
     */
    fun shellQuote(path: String): String {
        return "'" + path.replace("'", "'\\''") + "'"
    }
}
