package com.simplereader.util

import android.content.Context
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

//
// Getting started:     https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/getting-started.md
// Open a publication:  https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/open-publication.md
// Navigators:          https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/navigator.md
//
// EPub fonts:          https://readium.org/kotlin-toolkit/latest/guides/epub-fonts/
// EPub preferences:    https://readium.org/kotlin-toolkit/latest/guides/navigator-preferences/
//

/**
 * Holds the shared Readium objects and services used by the app.
 */
class Readium(context: Context) {

    val httpClient = DefaultHttpClient()

    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)

    /**
     * The PublicationFactory is used to open publications.
     */
    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = PdfiumDocumentFactory(context)
        ),
    )

}