package com.simplereader.data

import android.content.Context
import androidx.room.*
import com.simplereader.book.BookDao
import com.simplereader.book.BookDataEntity
import com.simplereader.bookmark.BookmarkDao
import com.simplereader.bookmark.BookmarkEntity
import com.simplereader.highlight.HighlightDao
import com.simplereader.highlight.HighlightEntity
import com.simplereader.note.NoteDao
import com.simplereader.note.NoteEntity
import com.simplereader.settings.SettingsDao
import com.simplereader.settings.SettingsEntity
import com.simplereader.sync.DeletedRecordsEntity
import com.simplereader.sync.SyncFileIdMapEntity
import com.simplereader.sync.SyncCheckpointEntity
import com.simplereader.sync.SyncDao

@Database(
    entities = [
        BookDataEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        NoteEntity::class,
        SettingsEntity::class,
        SyncFileIdMapEntity::class,
        SyncCheckpointEntity::class,
        DeletedRecordsEntity::class
       ],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4)
    ]
)
abstract class ReaderDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun noteDao(): NoteDao
    abstract fun settingsDao(): SettingsDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var INSTANCE: ReaderDatabase? = null

        fun getInstance(context: Context): ReaderDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ReaderDatabase::class.java,
                    "simplereader"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}