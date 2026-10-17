package com.markreader.format

import android.text.Spanned

/**
 * What a [DocumentFormat] produces, and the only thing the reader surface knows how to draw.
 *
 * This is Fossify Documents' `StructuredDocumentContent` idea — one renderer, several loaders —
 * and it is what lets a format that produces a table share a screen with one that produces
 * prose. The set is closed on purpose: three shapes, and a fourth format has to fit one of them
 * or argue for a fourth.
 *
 * P3 defines it; the reader surface still takes `text: Any` and P4 is where that dies. So today
 * every producer returns [Spans] and the view models unwrap it at one place each — which is the
 * point of defining it a phase early rather than changing `load()`'s signature later.
 *
 * Lives here rather than beside `ReaderContentView` so that the format layer never imports UI.
 * It moves to `render/` if and when that package is carved out with the dependency running that
 * way.
 */
sealed interface ReaderContent {
    /**
     * A rendered document as HTML, for the WebView host to load. Produced from P6, when the
     * shared renderer replaces Markwon.
     */
    data class Html(val html: String) : ReaderContent

    /**
     * A rendered document as styled text. The Markwon and Prism paths today; after P6 it
     * survives for raw mode, which needs no renderer at all.
     */
    data class Spans(val spanned: Spanned) : ReaderContent

    /** A tabular document, drawn by `TableRenderer`. Produced from P5, by the CSV format. */
    data class Table(val rows: List<List<String>>) : ReaderContent
}
