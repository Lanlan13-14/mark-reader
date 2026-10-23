package com.markreader.format

import android.content.Context
import com.markreader.file.DocumentType
import com.markreader.format.code.SourceCodeFormat
import com.markreader.format.markdown.MarkdownFormat
import com.markreader.format.plaintext.PlainTextFormat

/**
 * The [DocumentType] to [DocumentFormat] lookup.
 *
 * `architecture.md` seam 4 sketched this as a plain list, ordered by precedence. Precedence was
 * for *recognition* — walk the list, first format that claims the file wins — and formats no
 * longer recognise anything, so a list would carry an ordering that means nothing. What protects
 * us instead is exhaustiveness: [formatFor] is a `when` over a sealed interface, so adding a
 * document type fails to compile until someone decides which format reads it. A CSV format is
 * still one
 * field and one branch.
 *
 * Not a singleton, and not injected. One instance per view model, holding the renderers for as
 * long as the screen lives.
 */
class FormatRegistry(private val markdown: DocumentFormat) {

    constructor(context: Context) : this(MarkdownFormat(context.applicationContext))

    /**
     * One format per language seen, so the highlighter cache survives a theme toggle. Bounded by
     * the languages opened on one screen, which is one for all but the curious.
     */
    private val sourceCode = mutableMapOf<String, DocumentFormat>()

    /**
     * The format that reads [type], or **null for [DocumentType.Binary]** — bytes that are not
     * text have no format, and that is the one case the caller has to handle rather than render.
     */
    fun formatFor(type: DocumentType): DocumentFormat? = when (type) {
        DocumentType.Markdown -> markdown
        is DocumentType.SourceCode -> sourceCode.getOrPut(type.language) {
            SourceCodeFormat(type.language)
        }
        // Csv reads as what it also is, until it gets a format of its own.
        DocumentType.Csv, DocumentType.PlainText -> PlainTextFormat
        DocumentType.Binary -> null
    }

    /** The reader palette changed, so every renderer built against the old one is stale. */
    fun invalidateRenderers() {
        markdown.invalidateRenderers()
        sourceCode.values.forEach { it.invalidateRenderers() }
    }
}
