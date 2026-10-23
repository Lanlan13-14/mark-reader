package com.markreader.format

/**
 * What a [DocumentFormat] produces, and the only thing the reader surface knows how to draw.
 *
 * This is Fossify Documents' `StructuredDocumentContent` idea — one renderer, several loaders —
 * and it is what lets a format that produces a table share a screen with one that produces
 * prose. The set is closed on purpose: three shapes, and a fourth format has to fit one of them
 * or argue for a fourth.
 *
 * Lives here rather than beside `ReaderContentView` so that the format layer never imports UI.
 * It moves to `render/` if and when that package is carved out with the dependency running that
 * way.
 */
sealed interface ReaderContent {
    /**
     * Markdown source, for the shared renderer to draw in the WebView.
     *
     * **Not HTML, and the distinction is the whole architecture.** The renderer is JavaScript
     * running inside the page — `document.js` calls `engine.render(doc.text)` — so Kotlin never
     * holds rendered markup and could not produce it without a second Markdown implementation,
     * which is the duplication the shared renderer exists to remove. What crosses the bridge is
     * source text; the page turns it into HTML, sanitises it, and puts it on screen.
     *
     * A format reaches this variant by *deciding* its document renders that way, not by doing
     * the rendering. `SourceCodeFormat` fences its source into a code block to get there, which
     * is also how highlight.js comes to have a real grammar for it.
     *
     * The path a document is known by is the reader surface's to supply, not the format's — the
     * format layer has no business knowing about SAF.
     */
    data class Markdown(val source: String) : ReaderContent

    /**
     * A document as text, styled or not.
     *
     * [text] is a `CharSequence` rather than a `Spanned` because unstyled text is a real case,
     * not a placeholder for one: raw mode and the plain-text format both hand over a `String`,
     * and wrapping those in a `SpannableString` would copy up to `MAX_FILE_BYTES` for no styling
     * benefit. Consumers that need markers — split mode, which reads them to give code blocks
     * and tables their own scrollable panes — narrow to `Spanned` at the point of that
     * requirement.
     *
     * Raw mode, and the editor preview until Markwon is retired from it.
     */
    data class Spans(val text: CharSequence) : ReaderContent

    /** A tabular document, drawn by `TableRenderer`. For the planned CSV format. */
    data class Table(val rows: List<List<String>>) : ReaderContent
}
