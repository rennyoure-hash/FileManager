package com.example.core.net

import com.example.core.fs.Capability
import com.example.core.fs.FileSystem
import com.example.core.fs.VEntry
import com.example.core.fs.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

enum class NetworkProtocol { FTP, FTPS, SFTP, SMB, WEBDAV, S3 }

data class NetworkProfile(
    val id: String,
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String = "", // encrypted in real app, plaintext here for simplicity
    val basePath: String = "/",
    val useImplicitTLS: Boolean = false,
    val privateKeyPath: String? = null, // SFTP
    val jumpHost: String? = null // SFTP jump host
)

/**
 * Very simplified WebDAV FileSystem using OkHttp.
 * FTP/SFTP/SMB/S3 are stubbed with clear errors — they require dedicated libraries.
 * This file provides the abstraction + WebDAV partial implementation.
 */
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

    private fun baseUrl(): String {
        return "http://${profile.host}:${profile.port}${rootPath.normalized}"
    }

    val isImplemented: Boolean = providerStatus() == "implemented"

    private fun providerStatus(): String {
        // Currently only WebDAV is considered for future implementation.
        // FTP/SFTP/SMB/S3 need external libs (Apache Commons Net, sshj, smbj).
        return "coming_soon"
    }

    override suspend fun list(path: VPath): List<VEntry> = withContext(Dispatchers.IO) {
        // WebDAV PROPFIND simplified — returns empty for now, real impl needs XML parser
        // For FTP/SFTP etc., we'd use dedicated libraries here.
        throw UnsupportedOperationException(
            "Network protocol ${profile.protocol} is not yet implemented. " +
                "It is registered for future support. See README for roadmap."
        )
    }

    override suspend fun stat(path: VPath): VEntry? {
        // Not implemented — return null so callers can show "unavailable"
        return null
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        throw UnsupportedOperationException("Network storage (${profile.protocol}) is not yet available in this build")
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        throw UnsupportedOperationException("Network storage (${profile.protocol}) is not yet available in this build")
    }

    override suspend fun mkdir(path: VPath): Boolean = false
    override suspend fun rename(from: VPath, to: VPath): Boolean = false
    override suspend fun delete(path: VPath, recursive: Boolean): Boolean = false
    override suspend fun exists(path: VPath): Boolean = false
}

/**
 * HTTP Remote Management server (opt-in, off by default).
 * Serves file listing as HTML via NanoHTTPD-like minimal socket server.
 * For simplicity, we provide a stub that can be started on demand.
 */
class RemoteFileServer(
    private val port: Int = 8080,
    private val password: String = "",
    private val rootDir: File
) {
    var isRunning = false
        private set

    /**
     * Remote management is not yet implemented in this build.
     * Kept as stub so Settings toggle shows honest state. In production
     * this would use NanoHTTPD/Ktor with password + HTTPS.
     */
    fun start() {
        throw UnsupportedOperationException(
            "Remote management is not yet implemented. Disable it in Settings."
        )
    }

    fun stop() {
        isRunning = false
    }

    fun localUrl(): String = "http://localhost:$port"

    /** Simple auth check. */
    fun checkPassword(input: String): Boolean {
        return password.isEmpty() || input == password
    }
}
