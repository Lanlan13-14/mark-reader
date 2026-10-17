package com.markreader.format.code

import com.markreader.R
import com.markreader.format.DocumentFormat
import com.markreader.format.FormatCapabilities
import com.markreader.format.ReaderContent
import com.markreader.format.RenderTheme
import kotlinx.coroutines.CancellationException

/**
 * Source code, syntax-highlighted by Prism4j.
 *
 * One instance per language, because the language is a property of the document and not of the
 * call — a format that took it as a `load()` argument would be asking every caller to remember
 * something the registry already knows.
 *
 * There is no raw view: the highlighted form is the source, coloured. That is also why the
 * viewer names the format in its chrome instead of claiming to show a "rendered" document.
 */
class SourceCodeFormat(private val language: String) : DocumentFormat {

    /** Cheap to build, unlike Markwon — but the reader toggles themes often enough to keep. */
    private val renderers = mutableMapOf<Boolean, SourceCodeRenderer>()

    override val capabilities = FormatCapabilities(
        hasHeadings = false,
        hasRawView = false,
        isMonospaced = true,
        hasEditorPreview = false,
        hasFormattingToolbar = false
    )

    override val nameRes = R.string.viewer_subtitle_source_code

    override val editorPlaceholderRes = R.string.editor_placeholder_text

    /**
     * `text/plain`, not a per-language MIME type. The picker uses this to name a *new* file, and
     * offering `text/x-kotlin` there would have the system suggest an extension for a language
     * the user has not chosen yet.
     */
    override val creationMimeType = "text/plain"

    override val renderFailureWarningRes = R.string.viewer_warning_source_code

    override suspend fun load(text: String, theme: RenderTheme): ReaderContent? = try {
        ReaderContent.Spans(renderer(theme.isDark).highlight(text, language))
    } catch (ex: CancellationException) {
        throw ex
    } catch (_: Exception) {
        // A grammar Prism4j mishandles, or a pathological file. Either way: show the source.
        null
    }

    override fun invalidateRenderers() = renderers.clear()

    private fun renderer(isDark: Boolean): SourceCodeRenderer =
        renderers.getOrPut(isDark) { SourceCodeRenderer(isDark) }
}
