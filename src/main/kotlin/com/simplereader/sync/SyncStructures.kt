package com.simplereader.sync

data class ServerRecord(
    val fileId: String,
    val progress: String?,
    val updatedAt: Long,
    val deletedAt: Long?
)

data class DeleteRecord(
    val fileId: String,
    val deletedAt: Long?
)

data class ClientRecord(
    val fileId: String,
    val updatedAt: Long?
)
