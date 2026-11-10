package com.example.core.inject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Rewrites an APK so that it contains our DocumentsProvider.
 *
 *  - copies every entry of the source APK (keeping the compression method)
 *  - replaces AndroidManifest.xml with a patched one that declares the provider
 *  - adds the provider dex as the next free classesN.dex
 *
 * Native libraries (.so) are stored uncompressed and aligned to 4 KB, otherwise
 * Android 11+ refuses to install the package.
 */
object ApkInjector {

    data class Result(
        val outputFile: File,
        val providerClass: String,
        val authority: String,
        val dexEntryName: String,
        val entries: Int
    )

    private const val ALIGNMENT = 4096

    fun inject(
        inputApk: File,
        outputApk: File,
        providerDex: ByteArray,
        providerClass: String,
        authority: String
    ): Result {
        ZipFile(inputApk).use { zf ->
            // ---- next free classesN.dex slot -------------------------------
            var dexIndex = 1
            while (zf.getEntry("classes$dexIndex.dex") != null) dexIndex++
            val dexName = "classes$dexIndex.dex"

            // ---- patch the manifest ---------------------------------------
            val manifestEntry = zf.getEntry("AndroidManifest.xml")
                ?: throw IllegalArgumentException("APK has no AndroidManifest.xml")
            val manifest = zf.getInputStream(manifestEntry).use { it.readBytes() }

            val spec = AxmlProviderInjector.ProviderSpec().apply {
                className = providerClass
                this.authority = authority
                exported = true
                grantUriPermissions = true
            }
            val patched = AxmlProviderInjector.inject(manifest, spec).manifest

            // ---- write the new APK ----------------------------------------
            outputApk.parentFile?.mkdirs()
            FileOutputStream(outputApk).use { fos ->
                ZipWriter(fos).use { zip ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val name = e.name
                        // stale signatures must be dropped, they are recreated by the signer
                        if (name.startsWith("META-INF/")) continue
                        if (name == "AndroidManifest.xml") continue

                        val data = zf.getInputStream(e).use { it.readBytes() }
                        val method = if (e.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
                        val align = method == ZipEntry.STORED && (name.endsWith(".so") || name.endsWith(".rsrc"))
                        zip.addFile(name, data, method, align)
                    }
                    // patched manifest
                    zip.addFile("AndroidManifest.xml", patched, ZipEntry.DEFLATED, false)
                    // provider dex
                    zip.addFile(dexName, providerDex, ZipEntry.DEFLATED, false)
                    zip.finish()
                }
            }
            return Result(outputApk, providerClass, authority, dexName, zf.size())
        }
    }

    /**
     * Minimal ZIP writer with full control over entry offsets so stored entries can be
     * page-aligned (required for native libraries on Android 11+).
     */
    private class ZipWriter(private val out: OutputStream) : java.io.Closeable {
        private class Entry(
            val nameBytes: ByteArray,
            val method: Int,
            val crc: Long,
            val size: Long,
            val compressedSize: Long,
            val localOffset: Long,
            val extraLen: Int
        )

        private val entries = mutableListOf<Entry>()
        private var offset = 0L
        private var finished = false

        fun addFile(name: String, data: ByteArray, method: Int, align: Boolean) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            val stored = method == ZipEntry.STORED
            val payload: ByteArray
            val compressedSize: Long
            if (stored) {
                payload = data
                compressedSize = data.size.toLong()
            } else {
                // nowrap = true -> raw deflate stream, which is what ZIP entries require
                val def = Deflater(Deflater.BEST_COMPRESSION, true)
                try {
                    def.setInput(data)
                    def.finish()
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (!def.finished()) {
                        val n = def.deflate(chunk)
                        if (n > 0) buf.write(chunk, 0, n)
                        else if (def.needsInput()) break
                    }
                    payload = buf.toByteArray()
                } finally {
                    def.end()
                }
                compressedSize = payload.size.toLong()
            }

            // local header offset -> decide the alignment padding
            val headerStart = offset
            var extraLen = 0
            var extra: ByteArray = ByteArray(0)
            if (align) {
                val base = headerStart + 30 + nameBytes.size
                var pad = ((ALIGNMENT - (base % ALIGNMENT)) % ALIGNMENT).toInt()
                if (pad in 1..3) pad += ALIGNMENT
                if (pad > 0) {
                    extraLen = pad
                    extra = ByteArray(pad)
                    // a valid extra field: header id 0xD935 ("Android ZIP alignment") + size + payload
                    extra[0] = 0x35.toByte()
                    extra[1] = 0xD9.toByte()
                    extra[2] = (pad - 4).toByte()
                    extra[3] = ((pad - 4) shr 8).toByte()
                }
            }

            writeLocalHeader(nameBytes, method, crc, compressedSize, data.size.toLong(), extraLen)
            out.write(nameBytes)
            if (extraLen > 0) out.write(extra)
            out.write(payload)

            offset = headerStart + 30 + nameBytes.size + extraLen + compressedSize
            entries.add(Entry(nameBytes, method, crc, data.size.toLong(), compressedSize, headerStart, extraLen))
        }

        private fun writeLocalHeader(
            nameBytes: ByteArray,
            method: Int,
            crc: Long,
            compressedSize: Long,
            size: Long,
            extraLen: Int
        ) {
            val h = ByteArray(30)
            putU4(h, 0, 0x04034b50L)
            putU2(h, 4, 20)          // version needed
            putU2(h, 6, 0x0800)      // flags: UTF-8 names
            putU2(h, 8, method)
            putU2(h, 10, 0)          // mod time
            putU2(h, 12, 0x21)       // mod date (1980-01-01)
            putU4(h, 14, crc)
            putU4(h, 18, compressedSize)
            putU4(h, 22, size)
            putU2(h, 26, nameBytes.size)
            putU2(h, 28, extraLen)
            out.write(h)
        }

        fun finish() {
            if (finished) return
            finished = true
            val cdStart = offset
            var pos = offset
            for (e in entries) {
                val h = ByteArray(46)
                putU4(h, 0, 0x02014b50L)
                putU2(h, 4, 20)      // version made by
                putU2(h, 6, 20)      // version needed
                putU2(h, 8, 0x0800)  // flags
                putU2(h, 10, e.method)
                putU2(h, 12, 0)
                putU2(h, 14, 0x21)
                putU4(h, 16, e.crc)
                putU4(h, 20, e.compressedSize)
                putU4(h, 24, e.size)
                putU2(h, 28, e.nameBytes.size)
                putU2(h, 30, e.extraLen)
                putU2(h, 32, 0)      // comment length
                putU2(h, 34, 0)      // disk number
                putU2(h, 36, 0)      // internal attrs
                putU4(h, 38, 0x81a40000L) // external attrs: 0644
                putU4(h, 42, e.localOffset)
                out.write(h)
                pos += h.size
                out.write(e.nameBytes)
                pos += e.nameBytes.size
                if (e.extraLen > 0) {
                    val extra = ByteArray(e.extraLen)
                    extra[0] = 0x35.toByte()
                    extra[1] = 0xD9.toByte()
                    extra[2] = (e.extraLen - 4).toByte()
                    extra[3] = ((e.extraLen - 4) shr 8).toByte()
                    out.write(extra)
                    pos += e.extraLen
                }
            }
            val cdSize = pos - cdStart
            val eocd = ByteArray(22)
            putU4(eocd, 0, 0x06054b50L)
            putU2(eocd, 4, 0)
            putU2(eocd, 6, 0)
            putU2(eocd, 8, entries.size)
            putU2(eocd, 10, entries.size)
            putU4(eocd, 12, cdSize)
            putU4(eocd, 16, cdStart)
            putU2(eocd, 20, 0)
            out.write(eocd)
            out.flush()
            offset = pos + eocd.size
        }

        override fun close() {
            finish()
            out.close()
        }

        private fun putU2(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun putU4(b: ByteArray, off: Int, v: Long) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
    }
}
