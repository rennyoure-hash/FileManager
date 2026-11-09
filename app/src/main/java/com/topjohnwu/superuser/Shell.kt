package com.topjohnwu.superuser

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Persistent root shell implementation compatible with topjohnwu's libsu API.
 * Maintains a single persistent `su` process with FLAG_MOUNT_MASTER (--mount-master)
 * and enforces per-command timeouts (default 30 seconds).
 */
class Shell private constructor(
    private val flags: Int,
    private val timeoutSeconds: Long
) {
    companion object {
        const val FLAG_NON_ROOT_SHELL = 1 shl 0
        const val FLAG_MOUNT_MASTER = 1 shl 1

        @Volatile
        var enableVerboseLogging: Boolean = false

        @Volatile
        private var defaultBuilder: Builder = Builder.create()
            .setFlags(FLAG_MOUNT_MASTER)
            .setTimeout(30L)

        @Volatile
        private var cachedShell: Shell? = null

        @Volatile
        private var rootGrantedOverride: Boolean? = null

        private val lock = Any()

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/debug_ramdisk/su",
            "/apex/com.android.runtime/bin/su"
        )

        @JvmStatic
        fun setDefaultBuilder(builder: Builder) {
            defaultBuilder = builder
        }

        @JvmStatic
        fun getShell(): Shell {
            cachedShell?.let { existing ->
                if (existing.isAlive) return existing
            }
            synchronized(lock) {
                cachedShell?.let { existing ->
                    if (existing.isAlive) return existing
                }
                val created = defaultBuilder.build()
                cachedShell = created
                return created
            }
        }

        @JvmStatic
        fun isAppGrantedRoot(): Boolean? {
            rootGrantedOverride?.let { return it }
            return try {
                val shell = getShell()
                shell.isRoot
            } catch (_: Throwable) {
                false
            }
        }

        @JvmStatic
        fun cmd(vararg commands: String): Job {
            return Job(getShell(), commands.toList())
        }

        internal fun findSuBinary(): String? {
            for (path in SU_PATHS) {
                try {
                    if (File(path).exists()) return path
                } catch (_: Throwable) {
                }
            }
            return try {
                val proc = ProcessBuilder("which", "su").redirectErrorStream(true).start()
                val line = proc.inputStream.bufferedReader().readLine()?.trim()
                proc.waitFor(2, TimeUnit.SECONDS)
                if (!line.isNullOrEmpty() && File(line).exists()) line else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    class Builder {
        private var flags: Int = FLAG_MOUNT_MASTER
        private var timeoutSeconds: Long = 30L

        companion object {
            @JvmStatic
            fun create(): Builder = Builder()
        }

        fun setFlags(flags: Int): Builder {
            this.flags = flags
            return this
        }

        fun setTimeout(timeoutSeconds: Long): Builder {
            this.timeoutSeconds = timeoutSeconds
            return this
        }

        fun build(): Shell {
            return Shell(flags, timeoutSeconds)
        }
    }

    class Result(
        val code: Int,
        val out: List<String>,
        val err: List<String>
    ) {
        val isSuccess: Boolean get() = code == 0

        companion object {
            const val JOB_NOT_EXECUTED = -1
        }
    }

    class Job(
        private val shell: Shell,
        private val commands: List<String>
    ) {
        fun exec(): Result {
            return shell.execCommands(commands)
        }
    }

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private val execLock = Any()
    private val ioExecutor = Executors.newCachedThreadPool()

    var isRoot: Boolean = false
        private set

    val isAlive: Boolean
        get() = process?.isAlive == true

    init {
        initPersistentProcess()
    }

    private fun initPersistentProcess() {
        val suBinary = findSuBinary()
        if (suBinary == null && (flags and FLAG_NON_ROOT_SHELL) == 0) {
            isRoot = false
            return
        }

        val candidates = mutableListOf<List<String>>()
        if ((flags and FLAG_NON_ROOT_SHELL) == 0) {
            val bin = suBinary ?: "su"
            if ((flags and FLAG_MOUNT_MASTER) != 0) {
                candidates.add(listOf(bin, "--mount-master"))
            }
            candidates.add(listOf(bin))
        } else {
            candidates.add(listOf("sh"))
        }

        for (cmdArgs in candidates) {
            try {
                val proc = ProcessBuilder(cmdArgs).start()
                process = proc
                stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
                stdoutReader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                stderrReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))

                val idCheck = execCommandsInternal(listOf("id"), 10L)
                if (idCheck.isSuccess && idCheck.out.any { it.contains("uid=0") }) {
                    isRoot = true
                    return
                } else {
                    closeQuietly()
                }
            } catch (_: Throwable) {
                closeQuietly()
            }
        }
        isRoot = false
    }

    internal fun execCommands(commands: List<String>): Result {
        if (commands.isEmpty()) {
            return Result(0, emptyList(), emptyList())
        }
        synchronized(execLock) {
            if (!isAlive) {
                initPersistentProcess()
            }
            if (!isAlive || !isRoot) {
                return Result(
                    code = Result.JOB_NOT_EXECUTED,
                    out = emptyList(),
                    err = listOf("Root shell is not available or permission was denied")
                )
            }
            return execCommandsInternal(commands, timeoutSeconds)
        }
    }

    private fun execCommandsInternal(commands: List<String>, timeoutSec: Long): Result {
        val writer = stdinWriter ?: return Result(Result.JOB_NOT_EXECUTED, emptyList(), listOf("Shell stdin closed"))
        val outReader = stdoutReader ?: return Result(Result.JOB_NOT_EXECUTED, emptyList(), listOf("Shell stdout closed"))
        val errReader = stderrReader ?: return Result(Result.JOB_NOT_EXECUTED, emptyList(), listOf("Shell stderr closed"))

        val token = "__LIBSU_EOF_${UUID.randomUUID().toString().replace("-", "")}__"
        val task = Callable {
            val outLines = mutableListOf<String>()
            val errLines = mutableListOf<String>()

            for (cmd in commands) {
                writer.write(cmd)
                writer.newLine()
            }
            // Write exit code and EOF markers on both stdout and stderr
            writer.write("__rc=$?")
            writer.newLine()
            writer.write("echo \"$token:\$__rc\"")
            writer.newLine()
            writer.write("echo \"$token\" >&2")
            writer.newLine()
            writer.flush()

            val errFuture = ioExecutor.submit(Callable {
                val lines = mutableListOf<String>()
                while (true) {
                    val line = errReader.readLine() ?: break
                    if (line.contains(token)) {
                        val before = line.substringBefore(token)
                        if (before.isNotEmpty()) lines.add(before)
                        break
                    }
                    lines.add(line)
                }
                lines
            })

            var exitCode = -1
            while (true) {
                val line = outReader.readLine() ?: break
                val markerIdx = line.indexOf("$token:")
                if (markerIdx >= 0) {
                    val before = line.substring(0, markerIdx)
                    if (before.isNotEmpty()) outLines.add(before)
                    val codeStr = line.substring(markerIdx + token.length + 1).trim()
                    exitCode = codeStr.toIntOrNull() ?: 0
                    break
                }
                outLines.add(line)
            }

            errLines.addAll(errFuture.get(timeoutSec, TimeUnit.SECONDS))
            Result(exitCode, outLines, errLines)
        }

        val future = ioExecutor.submit(task)
        return try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            closeQuietly()
            Result(
                code = -1,
                out = emptyList(),
                err = listOf("Command timed out after ${timeoutSec}s")
            )
        } catch (t: Throwable) {
            closeQuietly()
            Result(
                code = -1,
                out = emptyList(),
                err = listOf(t.localizedMessage ?: "Command execution failed")
            )
        }
    }

    private fun closeQuietly() {
        try {
            stdinWriter?.close()
        } catch (_: Throwable) {}
        try {
            stdoutReader?.close()
        } catch (_: Throwable) {}
        try {
            stderrReader?.close()
        } catch (_: Throwable) {}
        try {
            process?.destroyForcibly()
        } catch (_: Throwable) {}
        stdinWriter = null
        stdoutReader = null
        stderrReader = null
        process = null
    }
}
