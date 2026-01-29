package com.simplereader.book

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.simplereader.model.BookData
import com.simplereader.util.MiscUtil.hashIdentifier
import com.simplereader.util.Readium
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toUrl
import java.io.File

@Entity(tableName = "book_data")
data class BookDataEntity (
    @PrimaryKey val bookId: String, // hash
    val pubFile: String,            // physical location of this book
    val mediaType: String,          // EDF/PDF serialized MediaType
    val currentProgress: String?,   // serialized Locator of current location
    val currentBookmark: String?,   // serialized Locator of current bookmark
    val sha256: String?,            // checksum of file at pubFile  (used for syncing)
    val filesize: Long,             // filesize of file at pubFile  (used for syncing)
    val lastUpdated: Long           // timestamp of last update
) {
    companion object {
        // given a file
        suspend fun constructBookId(ctx: Context, filename: String): String? {
            // bookId is generally the hash of the Readium-parsed publication.metadata.identifier
            // or of the "pubFile" if that is not available.

            // so we have to Readium parse the passed filename and then try to extract the
            // publication.metadata.identifier value
            val bookUrl = File(filename).toUrl()
            val bookType: MediaType? = BookData.getMediaType(filename)
            if (bookType == null) {
                // no extension on the file to determine its type
                return null
            }

            // retrieve asset to access the file content
            val readium = Readium(ctx);
            val asset = readium.assetRetriever.retrieve(bookUrl, bookType).getOrElse {
                return null     // error
            }

            // open publication from the asset
            val publication = readium.publicationOpener.open(
                asset,
                allowUserInteraction = false    // don't ask user for credentials
            ).getOrElse {
                return null     // error
            }

            return hashIdentifier(publication.metadata.identifier ?: filename)
        }
    }

    fun isOnDisk() : Boolean = File(pubFile).exists()
    fun deleteFromDisk() = File(pubFile).delete()

}