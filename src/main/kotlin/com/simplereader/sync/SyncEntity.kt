package com.simplereader.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_table")
data class SyncEntity (
    @PrimaryKey val tableName: String,
    val lastSync: Long
)
