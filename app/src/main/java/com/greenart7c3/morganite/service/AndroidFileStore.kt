package com.greenart7c3.morganite.service

import android.content.Context
import org.apache.tika.Tika
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class AndroidFileStore(
    context: Context
) : FileStore {

    private val blobDir = File(context.filesDir, "blobs")

    init {
        blobDir.mkdirs()
    }

    override fun getFileByHash(hash: String): File? {
        val file = File(blobDir, hash)
        return file.takeIf { it.exists() && it.isFile }
    }

    override fun saveBlob(
        bytes: ByteArray,
    ): String {
        val hash = sha256(bytes)
        val file = File(blobDir, hash)

        if (!file.exists()) {
            file.writeBytes(bytes)
        }

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
        } catch (e: IOException) {
            // Fallback: If move fails, ensure we clean up the temp file
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun detectMimeType(file: File): String {
        val tika = Tika()
        return tika.detect(file)
    }
}


