package com.greenart7c3.morganite.models

import kotlinx.serialization.Serializable

@Serializable
data class BlobDescriptor(
    val url: String,
    val sha256: String,
    val size: Long,
    val type: String,
    val uploaded: Long
)
