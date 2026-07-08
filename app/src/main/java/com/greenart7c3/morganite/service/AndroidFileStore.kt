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

    // Guards writes/deletes and the tracked size so concurrent downloads
    // don't race each other or the pruner.
    private val lock = Any()

    // Detecting a MIME type happens on every served blob and constructing a Tika
    // instance loads the whole MIME-type registry, so build it once.
    private val tika = Tika()

    private val _size = MutableStateFlow(0L)
    override val size: StateFlow<Long> = _size.asStateFlow()

    init {
        blobDir.mkdirs()
        // The directory is scanned once here; afterwards the size is maintained
        // incrementally so saving a blob no longer stats every cached file
        // (previously two full scans per download: pruneIfNeeded + updateSize).
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

        synchronized(lock) {
            if (!file.exists()) {
                file.writeBytes(bytes)
                _size.value += bytes.size
                pruneIfNeeded()
            } else {
                file.setLastModified(System.currentTimeMillis())
            }
        }

        return hash
    }

    override fun moveFile(tempFile: File, hash: String) {
        try {
            val incomingSize = tempFile.length()
            synchronized(lock) {
                val target = File(blobDir, hash)
                val replacedSize = if (target.exists()) target.length() else 0L
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                _size.value += incomingSize - replacedSize
                pruneIfNeeded()
            }
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
        return tika.detect(file)
    }

    override fun clear() {
        synchronized(lock) {
            blobDir.listFiles()?.forEach { it.delete() }
            _size.value = 0L
        }
    }

    // Must be called while holding [lock]. The directory is only listed when the
    // tracked size actually crosses the limit, instead of on every single save;
    // the fresh listing also re-syncs the tracked size in case it drifted.
    private fun pruneIfNeeded() {
        if (_size.value <= MAX_CACHE_SIZE_BYTES) return

        val files = blobDir.listFiles() ?: return
        var remainingSize = files.sumOf { it.length() }
        val sortedFiles = files.sortedBy { it.lastModified() }

        for (file in sortedFiles) {
            if (remainingSize <= PRUNE_TARGET_SIZE_BYTES) break
            val fileSize = file.length()
            if (file.delete()) {
                remainingSize -= fileSize
            }
        }
        _size.value = remainingSize
    }

    companion object {
        private const val MAX_CACHE_SIZE_BYTES = 1024L * 1024L * 1024L
        private const val PRUNE_TARGET_SIZE_BYTES = 850L * 1024L * 1024L
    }
}
