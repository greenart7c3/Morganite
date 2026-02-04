package com.greenart7c3.morganite.service

import android.content.Context
import org.apache.tika.Tika
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest

class AndroidFileStore(
    context: Context
) : FileStore {

    private val blobDir = File(context.filesDir, "blobs")
    private val mimeDir = File(blobDir, "mime")

    init {
        blobDir.mkdirs()
        mimeDir.mkdirs()
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

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun detectMimeType(file: File): String? {
        val tika = Tika()
        return tika.detect(file)
    }
}


