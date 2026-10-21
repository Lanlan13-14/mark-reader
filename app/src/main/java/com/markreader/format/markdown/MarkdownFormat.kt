package com.markreader.format.markdown

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.markreader.R
import com.markreader.format.DocumentFormat
import com.markreader.format.FormatCapabilities
import com.markreader.format.HeadingItem
import com.markreader.format.ReaderContent
import com.markreader.format.RenderTheme
import kotlinx.coroutines.CancellationException

/** Headings deeper than this are left out of the table of contents; it stops being navigation. */
private const val MAX_TOC_LEVEL = 3

/** Markwon's own default: the text colour at ~10% alpha, so inline and block code agree. */
private const val CODE_BACKGROUND_ALPHA = 25

private fun codeBackground(isDark: Boolean): Int =
    ColorUtils.setAlphaComponent(if (isDark) Color.WHITE else Color.BLACK, CODE_BACKGROUND_ALPHA)

/**
 * Markdown, rendered by Markwon.
 *
 * Takes a renderer factory rather than building one, for two reasons: the renderer is expensive
 * enough to be worth caching per surface theme, and a factory is the only part of this class
 * that needs a [Context] — so everything else about the format can be asserted in a JVM test.
 */
class MarkdownFormat(private val newRenderer: (isDark: Boolean) -> MarkwonRenderer) : DocumentFormat {

    constructor(context: Context) : this({ isDark ->
        MarkwonRenderer(context, isDark, codeBackground(isDark))
    })

    /** One renderer per surface theme. Building Markwon costs milliseconds; toggling is instant. */
    private val renderers = mutableMapOf<Boolean, MarkwonRenderer>()

    override val capabilities = FormatCapabilities(
        hasHeadings = true,
        hasRawView = true,
        isMonospaced = false,
        hasEditorPreview = true,
        hasFormattingToolbar = true
    )

    override val nameRes = R.string.viewer_subtitle_markdown

    override val editorPlaceholderRes = R.string.editor_placeholder_markdown

    override val creationMimeType = "text/markdown"

    override val renderFailureWarningRes = R.string.viewer_warning_markdown

    override suspend fun load(text: String, theme: RenderTheme): ReaderContent? = try {
        ReaderContent.Spans(renderer(theme.isDark).render(text))
    } catch (ex: CancellationException) {
        throw ex
    } catch (_: Exception) {
        // Markwon and commonmark between them can fail in a great many ways on a malformed
        // document, and they all have the same remedy: show the source instead.
        null
    }

    override fun headings(text: String, content: ReaderContent): List<HeadingItem> =
        parseHeadings(text, (content as? ReaderContent.Spans)?.spanned?.toString())

    override fun invalidateRenderers() = renderers.clear()

    private fun renderer(isDark: Boolean): MarkwonRenderer =
        renderers.getOrPut(isDark) { newRenderer(isDark) }
}

/**
 * ATX headings, with each offset mapped onto [renderedText].
 *
 * The reader positions everything by offsets into the *rendered* text, so every heading has to
 * be mapped there too. A source offset is meaningless in that space: the renderer drops the
 * markup, so the two drift apart by however much markup it ate — increasingly so deeper into
 * the document, which is exactly where a wrong offset is hardest to notice.
 *
 * The mapping is a forward scan: heading text can repeat, and a cursor that only moves forward
 * keeps the second "Usage" pointing at the second one. The heading text is searched in the form
 * the renderer produced, with inline markup stripped, because `## `Zephyr Cell` 全端` renders
 * without its backticks and would otherwise never be found.
 */
private fun parseHeadings(markdown: String, renderedText: String?): List<HeadingItem> {
    if (renderedText.isNullOrEmpty()) return emptyList()
    val headings = mutableListOf<HeadingItem>()
    var cursor = 0
    markdown.lineSequence().forEach { line ->
        val heading = markdownHeading(line) ?: return@forEach
        val needle = stripInlineMarkup(heading.text)
        val found = when {
            needle.isEmpty() -> -1
            else -> renderedText.indexOf(needle, cursor)
        }
        if (found < 0) return@forEach
        cursor = found + needle.length
        headings.add(heading.copy(offset = found))
    }
    return headings
}

/** [text] as the renderer leaves it: inline code, emphasis and links reduced to their content. */
private fun stripInlineMarkup(text: String): String {
    var result = text
    for (pattern in INLINE_MARKUP) result = result.replace(pattern, "$1")
    return result.replace("`", "").trim()
}

/** The markup pairs a heading line can contain, reduced to their captured content. */
private val INLINE_MARKUP = listOf(
    Regex("\\*\\*(.+?)\\*\\*"),
    Regex("__(.+?)__"),
    Regex("~~(.+?)~~"),
    Regex("\\*(.+?)\\*"),
    Regex("_(.+?)_"),
    Regex("\\[(.+?)]\\([^)]*\\)")
)

/** The heading on this line, with [HeadingItem.offset] left for [parseHeadings] to fill in. */
private fun markdownHeading(line: String): HeadingItem? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("#")) return null
    val level = trimmed.takeWhile { it == '#' }.length
    if (level !in 1..MAX_TOC_LEVEL) return null
    val text = trimmed.drop(level).trim()
    if (text.isBlank()) return null
    return HeadingItem(text = text, level = level, offset = 0)
}
