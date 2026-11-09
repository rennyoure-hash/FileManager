package com.example.core.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.core.fs.Capability
import com.example.core.fs.FileSystem
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import com.example.core.tools.KeystoreSecretManager
import com.example.core.tools.SecurityHardeningPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.net.ssl.SSLServerSocketFactory

enum class NetworkProtocol(val isPlaintextWarningRequired: Boolean) {
    FTP(true),
    FTPS(false),
    SFTP(false),
    SMB(false),
    WEBDAV(false),
    S3(false)
}

/**
 * Fase 8b.2 & 8c.3: NetworkProfile never stores a plaintext password at rest.
 * The password is encrypted with [KeystoreSecretManager.encryptSecretToHex] (Android Keystore AES-256-GCM).
 */
data class NetworkProfile(
    val id: String,
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val encryptedPasswordHex: String = "",
    val basePath: String = "/",
    val useImplicitTLS: Boolean = false,
    val privateKeyPath: String? = null,
    val jumpHost: String? = null
) {
    fun decryptPassword(
        aesKeyProvider: () -> SecretKey = { KeystoreSecretManager.getOrCreateCredentialsAesKey() }
    ): String = KeystoreSecretManager.decryptSecretFromHex(encryptedPasswordHex, aesKeyProvider)

    companion object {
        fun createWithEncryptedPassword(
            id: String,
            name: String,
            protocol: NetworkProtocol,
            host: String,
            port: Int,
            username: String,
            rawPassword: String,
            basePath: String = "/",
            aesKeyProvider: () -> SecretKey = { KeystoreSecretManager.getOrCreateCredentialsAesKey() }
        ): NetworkProfile {
            return NetworkProfile(
                id = id,
                name = name,
                protocol = protocol,
                host = host,
                port = port,
                username = username,
                encryptedPasswordHex = KeystoreSecretManager.encryptSecretToHex(rawPassword, aesKeyProvider),
                basePath = basePath
            )
        }
    }
}

class NetworkFileSystem(
    override val id: String,
    val profile: NetworkProfile
) : FileSystem {
    override val name: String = profile.name
    override val rootPath: VPath = VPath(profile.basePath)
    override val capabilities: Set<Capability> = setOf(Capability.RANDOM_ACCESS)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun list(path: VPath): List<VEntry> = withContext(Dispatchers.IO) {
        throw UnsupportedOperationException(
            "Network protocol ${profile.protocol} requires active remote host connection."
        )
    }

    override suspend fun stat(path: VPath): VEntry? = null

    override fun openRead(path: VPath, offset: Long): InputStream {
        throw UnsupportedOperationException("Network storage (${profile.protocol}) is not connected")
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        throw UnsupportedOperationException("Network storage (${profile.protocol}) is not connected")
    }

    override suspend fun mkdir(path: VPath): Boolean = false
    override suspend fun rename(from: VPath, to: VPath): Boolean = false
    override suspend fun delete(path: VPath, recursive: Boolean): Boolean = false
    override suspend fun exists(path: VPath): Boolean = false
}

data class ConnectedClientInfo(
    val clientId: String,
    val ipAddress: String,
    val connectedAtMillis: Long,
    val lastActivityMillis: Long,
    val requestCount: Int
)

data class HttpRequestDecision(
    val statusCode: Int,
    val reason: String,
    val allowed: Boolean,
    val resolvedFile: File? = null
)

/**
 * Fase 8b.5 & Fase 8c.5: Hardened Local HTTP/HTTPS File Server:
 * - **Dedicated Single Share Folder (`sharedRootDir`)**: Only serves files inside [sharedRootDir],
 *   never the root filesystem (`/`, `/system`, `/data`).
 * - **Canonical Path Traversal Protection (`resolveSafeSharedFile`)**: Rejects `..`, URL-encoded `%2e%2e`,
 *   backslashes, NUL bytes, and symlinks resolving outside `sharedRootDir.canonicalFile`.
 * - **Constant-Time Session Token Validation on Every Request**: Validates the 128-bit random [sessionToken]
 *   using `MessageDigest.isEqual` on every single HTTP request.
 * - **Read-Only Default** (`isReadOnlyMode = true` by default; blocks `PUT`, `POST`, `DELETE`, `MKCOL`, `MOVE`).
 * - **Private LAN IP Guard** via [SecurityHardeningPolicy.isLocalNetworkAddress].
 * - **Per-IP Rate Limiting** via [SecurityHardeningPolicy.checkServerIpRateLimit].
 * - **Connected Client Registry + Disconnect All (`disconnectAllClients`)**.
 * - **Auto-Stop on Idle (`idleTimeoutMillis`) or Screen Lock (`Intent.ACTION_SCREEN_OFF`)**.
 * - **Plaintext HTTP Warning & Self-Signed HTTPS (`sslServerSocketFactory`) Support**.
 */
class RemoteFileServer(
    private val port: Int = 8080,
    val sharedRootDir: File,
    val isReadOnlyDefault: Boolean = true,
    private val maxRequestsPerMinutePerIp: Int = 60,
    private val idleTimeoutMillis: Long = 10 * 60 * 1000L, // 10 minutes idle auto-stop
    private val sslServerSocketFactory: SSLServerSocketFactory? = null
) {
    companion object {
        const val PLAINTEXT_HTTP_WARNING =
            "PERINGATAN KEAMANAN HTTP POLOS: Server lokal tanpa TLS mengirimkan token sesi dan isi file tanpa enkripsi " +
                "di jaringan Wi-Fi/LAN lokal (rentan terhadap ARP spoofing / packet sniffing). Gunakan jaringan tepercaya " +
                "atau aktifkan opsi HTTPS Self-Signed (TLS 1.3)."

        const val SELF_SIGNED_HTTPS_GUIDE =
            "Opsi HTTPS Self-Signed: Server mendukung injeksi SSLServerSocketFactory berbasis PKCS12/AndroidKeyStore " +
                "(EC P-256 / RSA-2048 self-signed certificate). Saat aktif, koneksi dienkripsi dengan TLS 1.2/1.3 dan " +
                "browser klien akan menampilkan peringatan sertifikat self-signed yang dapat diverifikasi via SHA-256 fingerprint."

        private val FORBIDDEN_SHARE_ROOTS = setOf(
            "/",
            "/system",
            "/system_ext",
            "/vendor",
            "/product",
            "/boot",
            "/data",
            "/data/data",
            "/dev",
            "/proc",
            "/sys"
        )
    }

    init {
        val canonicalRoot = try {
            sharedRootDir.canonicalPath.trimEnd('/')
        } catch (_: Throwable) {
            sharedRootDir.absolutePath.trimEnd('/')
        }.ifEmpty { "/" }
        require(!FORBIDDEN_SHARE_ROOTS.contains(canonicalRoot)) {
            "Security Policy Violation: Folder share server lokal tidak boleh berupa partisi sistem/root ('$canonicalRoot')!"
        }
    }

    val isTlsEnabled: Boolean get() = sslServerSocketFactory != null

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var idleWatchdogJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var registeredContext: Context? = null

    private val clientSockets = ConcurrentHashMap<String, Socket>()
    private val clientRegistry = ConcurrentHashMap<String, ConnectedClientInfo>()

    private val _activeClients = MutableStateFlow<List<ConnectedClientInfo>>(emptyList())
    val activeClients: StateFlow<List<ConnectedClientInfo>> = _activeClients.asStateFlow()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isReadOnlyMode: Boolean = isReadOnlyDefault

    @Volatile
    var sessionToken: String = SecurityHardeningPolicy.generateRandomSessionToken()
        private set

    @Volatile
    private var lastActivityTimestamp: Long = System.currentTimeMillis()

    /**
     * Fase 8c.5: Resolves a requested URL path against [sharedRootDir] and strictly rejects:
     * - NUL bytes (`\u0000`) or backslashes (`\`)
     * - Raw or URL-decoded `..` traversal segments (`../`, `%2e%2e`, `%252e%252e`)
     * - Symlinks inside the shared directory (`Files.isSymbolicLink`)
     * - Any canonical path that escapes `sharedRootDir.canonicalFile`!
     */
    fun resolveSafeSharedFile(rawRequestPath: String): Result<File> {
        val pathWithoutQuery = rawRequestPath.substringBefore('?').substringBefore('#').trim()
        if (pathWithoutQuery.contains('\u0000')) {
            return Result.failure(SecurityException("Path Traversal Blocked: NUL byte tidak diizinkan"))
        }

        val decodedOnce = try {
            URLDecoder.decode(pathWithoutQuery, "UTF-8")
        } catch (_: Throwable) {
            return Result.failure(SecurityException("Path Traversal Blocked: Encoding URL tidak valid"))
        }
        val decodedTwice = try {
            URLDecoder.decode(decodedOnce, "UTF-8")
        } catch (_: Throwable) {
            decodedOnce
        }

        if (decodedOnce.contains('\\') || decodedTwice.contains('\\') || decodedOnce.contains('\u0000')) {
            return Result.failure(SecurityException("Path Traversal Blocked: Karakter backslash/NUL dilarang"))
        }

        val segments = decodedTwice.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." || it == "." }) {
            return Result.failure(
                SecurityException("Path Traversal Blocked: Segmen relatif '..' atau '.' ditolak pada '$rawRequestPath'")
            )
        }

        val rootCanonical = sharedRootDir.canonicalFile
        val rootCanonicalPath = rootCanonical.path
        val candidate = if (segments.isEmpty()) {
            rootCanonical
        } else {
            File(rootCanonical, segments.joinToString(File.separator))
        }

        val isSymlink = try {
            Files.isSymbolicLink(candidate.toPath())
        } catch (_: Throwable) {
            false
        }
        if (isSymlink) {
            return Result.failure(
                SecurityException("Path Traversal Blocked: Symlink '${candidate.name}' di dalam folder share ditolak!")
            )
        }

        val candidateCanonical = candidate.canonicalFile
        val candidateCanonicalPath = candidateCanonical.path
        val insideShareRoot = candidateCanonicalPath == rootCanonicalPath ||
            candidateCanonicalPath.startsWith(rootCanonicalPath + File.separator)

        if (!insideShareRoot) {
            return Result.failure(
                SecurityException(
                    "Path Traversal Blocked: Resolusi canonical '$candidateCanonicalPath' keluar dari folder share '$rootCanonicalPath'!"
                )
            )
        }

        return Result.success(candidateCanonical)
    }

    /**
     * Starts the local server bound to private LAN / loopback, generates a fresh 128-bit session token
     * via [SecurityHardeningPolicy.generateRandomSessionToken], registers `ACTION_SCREEN_OFF` auto-stop,
     * and starts the idle timeout watchdog.
     */
    fun start(context: Context? = null) {
        if (isRunning) return
        sharedRootDir.mkdirs()
        sessionToken = SecurityHardeningPolicy.generateRandomSessionToken()
        isReadOnlyMode = isReadOnlyDefault
        lastActivityTimestamp = System.currentTimeMillis()
        isRunning = true

        if (context != null) {
            registerScreenLockAutoStop(context.applicationContext)
        }

        acceptJob = serverScope.launch {
            try {
                val bindAddr = InetAddress.getByName("0.0.0.0")
                val socket = if (sslServerSocketFactory != null) {
                    sslServerSocketFactory.createServerSocket(port, 16, bindAddr)
                } else {
                    ServerSocket(port, 16, bindAddr)
                }
                serverSocket = socket
                while (isActive && isRunning) {
                    val client = socket.accept()
                    handleSocketConnection(client)
                }
            } catch (_: Throwable) {
                isRunning = false
            }
        }

        idleWatchdogJob = serverScope.launch {
            while (isActive && isRunning) {
                delay(15_000L)
                if (System.currentTimeMillis() - lastActivityTimestamp >= idleTimeoutMillis) {
                    stop()
                    break
                }
            }
        }
    }

    /**
     * Evaluates an incoming HTTP/HTTPS request against all Fase 8b.5 & Fase 8c.5 security policies:
     * 1. [SecurityHardeningPolicy.isLocalNetworkAddress] (LAN / Loopback IP check)
     * 2. [SecurityHardeningPolicy.checkServerIpRateLimit] (Per-IP sliding window rate limiter)
     * 3. Constant-time [sessionToken] verification (`MessageDigest.isEqual`) on EVERY request
     * 4. Read-only mode enforcement (`GET`/`HEAD` only when [isReadOnlyMode] is true)
     * 5. Canonical path containment inside [sharedRootDir] via [resolveSafeSharedFile]
     */
    fun evaluateClientRequest(
        clientIp: String,
        httpMethod: String,
        providedToken: String,
        rawRequestPath: String = "/",
        nowMillis: Long = System.currentTimeMillis()
    ): HttpRequestDecision {
        // 1. Private LAN / Loopback IP check via SecurityHardeningPolicy
        if (!SecurityHardeningPolicy.isLocalNetworkAddress(clientIp)) {
            return HttpRequestDecision(
                statusCode = 403,
                reason = "Forbidden: IP '$clientIp' bukan jaringan lokal (LAN/Loopback)",
                allowed = false
            )
        }

        // 2. Per-IP Rate Limit check via SecurityHardeningPolicy
        if (!SecurityHardeningPolicy.checkServerIpRateLimit(clientIp, maxRequestsPerMinutePerIp, nowMillis)) {
            return HttpRequestDecision(
                statusCode = 429,
                reason = "Too Many Requests: Rate limit per IP ($maxRequestsPerMinutePerIp req/menit) terlampaui untuk $clientIp",
                allowed = false
            )
        }

        // 3. Random Session Token check on EVERY request (constant-time comparison via MessageDigest.isEqual)
        val tokenValid = providedToken.isNotEmpty() && MessageDigest.isEqual(
            providedToken.toByteArray(Charsets.UTF_8),
            sessionToken.toByteArray(Charsets.UTF_8)
        )
        if (!tokenValid) {
            return HttpRequestDecision(
                statusCode = 401,
                reason = "Unauthorized: Session token tidak valid atau kadaluarsa",
                allowed = false
            )
        }

        // 4. Read-Only Default enforcement
        val upperMethod = httpMethod.trim().uppercase()
        val isWriteMethod = upperMethod !in setOf("GET", "HEAD", "OPTIONS")
        if (isReadOnlyMode && isWriteMethod) {
            return HttpRequestDecision(
                statusCode = 405,
                reason = "Method Not Allowed: Server dalam mode Read-Only Default ($upperMethod ditolak)",
                allowed = false
            )
        }

        // 5. Canonical Path Traversal & Single Share Folder check
        val resolvedResult = resolveSafeSharedFile(rawRequestPath)
        if (resolvedResult.isFailure) {
            return HttpRequestDecision(
                statusCode = 403,
                reason = "Forbidden: ${resolvedResult.exceptionOrNull()?.message ?: "Path traversal ditolak"}",
                allowed = false
            )
        }

        lastActivityTimestamp = nowMillis
        val existing = clientRegistry[clientIp]
        val updated = ConnectedClientInfo(
            clientId = clientIp,
            ipAddress = clientIp,
            connectedAtMillis = existing?.connectedAtMillis ?: nowMillis,
            lastActivityMillis = nowMillis,
            requestCount = (existing?.requestCount ?: 0) + 1
        )
        clientRegistry[clientIp] = updated
        _activeClients.value = clientRegistry.values.sortedByDescending { it.lastActivityMillis }

        return HttpRequestDecision(
            statusCode = 200,
            reason = "OK",
            allowed = true,
            resolvedFile = resolvedResult.getOrNull()
        )
    }

    private fun handleSocketConnection(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "unknown"
        clientSockets[clientIp] = socket
        serverScope.launch {
            try {
                socket.soTimeout = 10_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                val requestLine = reader.readLine() ?: return@launch
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawPath = parts.getOrNull(1) ?: "/"

                var headerToken = ""
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    if (line!!.startsWith("X-Session-Token:", ignoreCase = true)) {
                        headerToken = line!!.substringAfter(":").trim()
                    }
                }
                if (headerToken.isEmpty() && rawPath.contains("token=")) {
                    headerToken = rawPath.substringAfter("token=").substringBefore("&")
                }

                val decision = evaluateClientRequest(
                    clientIp = clientIp,
                    httpMethod = method,
                    providedToken = headerToken,
                    rawRequestPath = rawPath
                )
                if (!decision.allowed) {
                    writer.print("HTTP/1.1 ${decision.statusCode} ${decision.reason}\r\nContent-Type: text/plain\r\n\r\n${decision.reason}")
                    writer.flush()
                    return@launch
                }

                val target = decision.resolvedFile ?: sharedRootDir
                val body = if (target.isDirectory) {
                    target.listFiles()?.joinToString("\n") { it.name }.orEmpty()
                } else if (target.isFile && target.canRead()) {
                    target.readText(Charsets.UTF_8).take(64 * 1024)
                } else {
                    writer.print("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nFile not found in shared folder")
                    writer.flush()
                    return@launch
                }
                writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$body")
                writer.flush()
            } catch (_: Throwable) {
            } finally {
                clientSockets.remove(clientIp)
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Fase 8b.5: Immediately disconnects all connected clients and clears the client registry.
     */
    fun disconnectAllClients(): Int {
        val count = clientRegistry.size + clientSockets.size
        for ((_, sock) in clientSockets) {
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
        clientSockets.clear()
        clientRegistry.clear()
        _activeClients.value = emptyList()
        return count
    }

    /**
     * Automatically stops the server when the device screen is locked (`Intent.ACTION_SCREEN_OFF`).
     */
    private fun registerScreenLockAutoStop(appContext: Context) {
        unregisterScreenLockReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    stop()
                }
            }
        }
        screenOffReceiver = receiver
        registeredContext = appContext
        try {
            appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (_: Throwable) {
        }
    }

    private fun unregisterScreenLockReceiver() {
        val ctx = registeredContext ?: return
        val rec = screenOffReceiver ?: return
        try {
            ctx.unregisterReceiver(rec)
        } catch (_: Throwable) {
        } finally {
            screenOffReceiver = null
            registeredContext = null
        }
    }

    fun stop() {
        isRunning = false
        disconnectAllClients()
        acceptJob?.cancel()
        idleWatchdogJob?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        unregisterScreenLockReceiver()
    }

    fun localUrl(): String {
        val scheme = if (isTlsEnabled) "https" else "http"
        return "$scheme://127.0.0.1:$port/?token=$sessionToken"
    }
}
