// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.data

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Full local backup. The API key is deliberately outside this archive. */
class BackupManager(context: Context, private val repository: MemoroRepository) {
    private val app = context.applicationContext
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val root = File(app.filesDir, "archive-files")

    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) {
        val snapshot = repository.snapshot()
        validateSnapshot(snapshot)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("snapshot.json"))
            zip.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (stored in snapshot.files) {
                val input = repository.openFile(stored.path) ?: error("Missing file: ${stored.path}")
                zip.putNextEntry(ZipEntry("files/${stored.path}"))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Caller obtains explicit user confirmation before invoking this destructive operation. */
    suspend fun restore(input: InputStream): File = withContext(Dispatchers.IO) {
        val staging = File(app.cacheDir, "restore-${System.nanoTime()}")
        check(staging.mkdirs())
        try {
            var manifest: ByteArray? = null
            val extracted = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(extracted.add(name)) { "Duplicate ZIP entry" }
                    require(!entry.isDirectory && (name == "snapshot.json" || name.startsWith("files/")))
                    val relative = if (name == "snapshot.json") name else name.removePrefix("files/")
                    validatePath(relative)
                    val destination = File(staging, name).canonicalFile
                    require(destination.path.startsWith(staging.canonicalPath + File.separator))
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 1_000_000_000L) { "Backup too large" }
                            out.write(buffer, 0, count)
                        }
                    }
                    if (name == "snapshot.json") {
                        require(destination.length() <= 32_000_000L) { "Manifest too large" }
                        manifest = destination.readBytes()
                    }
                    zip.closeEntry()
                }
            }
            val snapshot = json.decodeFromString<ArchiveSnapshot>((manifest ?: error("Missing manifest")).toString(Charsets.UTF_8))
            validateSnapshot(snapshot)
            require(extracted.size == snapshot.files.size + 1 && snapshot.files.all { "files/${it.path}" in extracted })
            for (stored in snapshot.files) {
                val file = File(staging, "files/${stored.path}")
                require(file.length() == stored.size && sha256(file) == stored.sha256) { "Corrupt backup file: ${stored.path}" }
            }
            // Keep an on-device copy before changing either storage layer.
            val preventive = File(app.filesDir, "pre-restore-${System.currentTimeMillis()}.memoro.zip")
            preventive.outputStream().use { export(it) }
            val oldFiles = File(app.filesDir, "archive-files-before-restore")
            if (oldFiles.exists()) oldFiles.deleteRecursively()
            val hadOld = root.exists()
            if (hadOld) check(root.renameTo(oldFiles))
            val newFiles = File(staging, "files")
            try {
                if (newFiles.exists()) check(newFiles.renameTo(root)) else check(root.mkdirs())
                repository.restoreSnapshot(snapshot)
                oldFiles.deleteRecursively()
            } catch (failure: Throwable) {
                root.deleteRecursively()
                if (hadOld) oldFiles.renameTo(root)
                throw failure
            }
            preventive
        } finally { staging.deleteRecursively() }
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it.isBlank() || it == "." || it == ".." || '\\' in it })
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
