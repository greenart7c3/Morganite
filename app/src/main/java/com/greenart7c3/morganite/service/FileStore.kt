package com.greenart7c3.morganite.service

import java.io.File

interface FileStore {
    fun getFileByHash(hash: String): File?
    fun saveBlob(bytes: ByteArray): String
    fun moveFile(tempFile: File, hash: String)
    fun detectMimeType(file: File): String
}
