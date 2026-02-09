package com.greenart7c3.morganite.service

import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface FileStore {
    val size: StateFlow<Long>
    fun getFileByHash(hash: String): File?
    fun saveBlob(bytes: ByteArray): String
    fun moveFile(tempFile: File, hash: String)
    fun detectMimeType(file: File): String
}
