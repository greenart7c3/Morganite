package com.greenart7c3.morganite.service

import android.content.Context
import com.vitorpamplona.quartz.utils.sha256.pool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.tika.Tika
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AndroidFileStore(
    context: Context
) : FileStore {

    private val blobDir = File(context.filesDir, "blobs")

    private val _size = MutableStateFlow(0L)
    override val size: StateFlow<Long> = _size.asStateFlow()

    init {
        blobDir.mkdirs()
        updateSize()
    }

    private fun updateSize() {
        _size.value = blobDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    override fun getFileByHash(hash: String): File? {
        val file = File(blobDir, hash)
        if (file.exists() && file.isFile) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return null
    }

    override fun saveBlob(
        bytes: ByteArray,
    ): String {
        val hash = sha256(bytes)
        val file = File(blobDir, hash)

        if (!file.exists()) {
            file.writeBytes(bytes)
        } else {
            file.setLastModified(System.currentTimeMillis())
        }

        pruneIfNeeded()
        updateSize()

        return hash
    }

    override fun moveFile(tempFile: File, hash: String) {
        try {
            // ATOMIC_MOVE is faster but might fail if moving across different drives
            Files.move(
                tempFile.toPath(),
                File(blobDir, hash).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            pruneIfNeeded()
            updateSize()
        } catch (e: IOException) {
            // Fallback: If move fails, ensure we clean up the temp file
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = pool.hash(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun detectMimeType(file: File): String {
        val tika = Tika()
        return tika.detect(file)
    }

    private fun pruneIfNeeded() {
        val files = blobDir.listFiles() ?: return
        val currentSize = files.sumOf { it.length() }
        if (currentSize <= 1024L * 1024L * 1024L) return

        val sortedFiles = files.sortedBy { it.lastModified() }
        var remainingSize = currentSize
        val targetSize = 850L * 1024L * 1024L

        for (file in sortedFiles) {
            if (remainingSize <= targetSize) break
            val fileSize = file.length()
            if (file.delete()) {
                remainingSize -= fileSize
            }
        }
    }
}
