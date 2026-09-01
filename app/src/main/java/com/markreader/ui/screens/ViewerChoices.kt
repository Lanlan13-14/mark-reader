package com.markreader.ui.screens

import androidx.annotation.StringRes
import com.markreader.R
import com.markreader.format.ReaderContent

/**
 * The three decisions the viewer makes about *what* to show, kept out of the composable so they
 * can be tested.
 *
 * They are here rather than inline because one of their inputs — a WebView that cannot run the
 * shared renderer — is unreachable without a device old enough to have one, so nobody will
 * exercise these branches by hand again. Left in `ViewerScreen` they would be verified once and
 * then rot quietly; as functions they are checked on every build.
 */

/**
 * What the reader draws.
 *
 * @param rendererUnavailable the WebView never started the renderer, so a `Markdown` document has
 *   nothing to draw it *with*. Raw mode needs no WebView, which is why the source is the fallback
 *   rather than a second native renderer kept alive for the purpose.
 */
fun readerContentFor(
    isRawMode: Boolean,
    rawText: String,
    rawHighlighted: ReaderContent?,
    rendered: ReaderContent?,
    rendererUnavailable: Boolean,
): ReaderContent = when {
    isRawMode -> rawHighlighted ?: ReaderContent.Spans(rawText)
    rendererUnavailable -> ReaderContent.Spans(rawText)
    else -> rendered ?: ReaderContent.Spans(rawText)
}

/**
 * What the chrome calls the current view.
 *
 * Naming a view mode is only truthful where there is more than one; where there is not, the chip
 * names the format instead. A renderer that never started is the same problem wearing a different
 * hat — the source is on screen, so calling it "Rendered mode" would be a lie.
 */
@StringRes
fun viewModeLabelFor(
    hasRawView: Boolean,
    isRawMode: Boolean,
    @StringRes formatNameRes: Int,
    rendererUnavailable: Boolean,
): Int = when {
    !hasRawView -> formatNameRes
    isRawMode || rendererUnavailable -> R.string.viewer_subtitle_raw_mode
    else -> R.string.viewer_subtitle_rendered_mode
}

/** Whether the raw/rendered toggle does anything. Both modes draw the source with no renderer. */
fun canToggleViewMode(
    hasRawView: Boolean,
    hasRendered: Boolean,
    rendererUnavailable: Boolean,
): Boolean = hasRawView && hasRendered && !rendererUnavailable
