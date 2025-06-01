package com.simplereader.data

import android.content.Context
import androidx.room.*
import com.simplereader.book.BookDao
import com.simplereader.book.BookDataEntity
import com.simplereader.bookmark.BookmarkDao
import com.simplereader.bookmark.BookmarkEntity
import com.simplereader.highlight.HighlightDao
import com.simplereader.highlight.HighlightEntity
import com.simplereader.settings.SettingsDao
import com.simplereader.settings.SettingsEntity

@Database(
    entities = [
        BookDataEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        SettingsEntity::class
       ],
    version = 1,
    exportSchema = false)
abstract class ReaderDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun readerSettingsDao(): SettingsDao

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