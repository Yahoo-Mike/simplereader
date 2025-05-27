/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.simplereader.model

import com.simplereader.util.FileUtil
import com.simplereader.util.MiscUtil.hashIdentifier
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.util.mediatype.MediaType

sealed class BookData(
    val publication: Publication,   // Readium-parsed book
    val pubName: String,            // physical location of book
    var currentLocation: Locator? = null   // where user is up to in book
) {

    companion object {
        private val LOG_TAG: String = BookData::class.java.simpleName

        val MEDIA_TYPE_UNKNOWN = "unknown"
        val MEDIA_TYPE_EPUB = "EPUB"
        val MEDIA_TYPE_PDF = "PDF"

        fun getMediaType(filename : String) : MediaType? {
            return when (getMediaTypeExtension(filename)) {
                MEDIA_TYPE_EPUB -> MediaType.Companion.EPUB
                MEDIA_TYPE_PDF -> MediaType.Companion.PDF
                else -> null
            }
        }
        fun getMediaTypeExtension(filename : String) : String? {
            return FileUtil.getExtensionUppercase(filename)
        }
    }

    fun getFileName(): String = pubName
    fun hashId(): String = hashIdentifier( publication.metadata.identifier ?: getFileName() )
    fun bookId(): String = hashId()

    fun getMediaType(): MediaType? = getMediaType(pubName)
    fun getMediaTypeExtension(): String {
        return when {
            publication.conformsTo(Publication.Profile.EPUB) -> MEDIA_TYPE_EPUB
            publication.conformsTo(Publication.Profile.PDF) -> MEDIA_TYPE_PDF
            else -> MEDIA_TYPE_UNKNOWN
        }
    }

}

class EpubData(
    publication: Publication,
    pubName: String,
    currentLocation: Locator?,
    val navigatorFactory: EpubNavigatorFactory,
) : BookData(publication, pubName, currentLocation)

@OptIn(ExperimentalReadiumApi::class)
class PdfData(
    publication: Publication,
    pubName: String,
    currentLocation: Locator?,
    val navigatorFactory: PdfiumNavigatorFactory
) : BookData(publication, pubName, currentLocation)
