package com.simplereader.sync

// books
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

// highlights and bookmarks
data class MarkerKey(val fileId: String, val id: Int)

data class ServerMarker(
    val key: MarkerKey,
    val updatedAt: Long,
    val deletedAt: Long?
)

data class DeleteMarker(
    val key: MarkerKey,
    val deletedAt: Long?
)

data class ClientMarker(
    val key: MarkerKey,
    val updatedAt: Long?
)