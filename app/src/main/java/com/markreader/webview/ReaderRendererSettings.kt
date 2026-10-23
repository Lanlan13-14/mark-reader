package com.markreader.webview

import com.markreader.data.CodeFontPreference
import com.markreader.data.ReadingFontPreference
import com.markreader.data.UserPreferences
import kotlin.math.roundToInt

/**
 * The reader's stored preferences, as the settings the renderer understands.
 *
 * Two of the app's settings do not survive the trip, and both are deliberate rather than
 * overlooked:
 *
 *  - **Sepia and AMOLED have no palette in the renderer.** It ships VS Code's two, `vscode-light`
 *    and `vscode-dark`, and [RendererTheme] says so. Sepia reads as light and AMOLED as dark
 *    until a third and fourth palette are written — and they belong in the *desktop* repo's
 *    `document-overrides.css`, since this app only vendors a copy of it.
 *  - **The bundled reading fonts are in `res/font/`**, which the page cannot reach: everything it
 *    loads comes through the asset loader. The families below still name Merriweather and
 *    JetBrains Mono first, so the mapping is already correct on the day the files are served —
 *    today CSS falls straight through them to the generic that follows.
 */
fun rendererSettingsFor(
    preferences: UserPreferences,
    isDark: Boolean,
    isCodeBlockWrapEnabled: Boolean,
): RendererSettings {
    // `fontSize` and `lineHeight` are both CSS pixels over there, where the app holds a size in
    // sp and a *multiplier*. Sending the multiplier would be a line height of 2px.
    val fontSize = preferences.fontSizeSp.roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    val lineHeight = (fontSize * preferences.lineHeight).roundToInt()
    return RendererSettings(
        theme = if (isDark) RendererTheme.DARK else RendererTheme.LIGHT,
        wordWrap = isCodeBlockWrapEnabled,
        fontSize = fontSize,
        lineHeight = lineHeight.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
        fontFamily = readingStack(preferences.readingFont),
    )
}

/**
 * The body stack. Code is not asked about here: a fenced block takes its font from
 * `--vscode-editor-font-family` in the vendored theme, not from the document's body font.
 */
private fun readingStack(font: ReadingFontPreference): String = when (font) {
    ReadingFontPreference.Merriweather -> "Merriweather, Georgia, \"Noto Serif\", serif"
    ReadingFontPreference.SystemSerif -> "Georgia, \"Noto Serif\", serif"
    ReadingFontPreference.MerriweatherSans ->
        "\"Merriweather Sans\", system-ui, \"Roboto\", \"Noto Sans\", sans-serif"
}

/** Unused until the fonts are served, and kept beside its sibling so the pair move together. */
internal fun codeStack(font: CodeFontPreference): String = when (font) {
    CodeFontPreference.JetBrainsMono -> "\"JetBrains Mono\", \"Roboto Mono\", monospace"
    CodeFontPreference.SystemMono -> "\"Roboto Mono\", monospace"
}

// The page validates again through `parseConfig` and falls back per setting, so a value outside
// these would be silently replaced by the host default rather than refused. Clamping here means
// an extreme reader setting stays extreme instead of snapping back to 16px.
private const val MIN_FONT_SIZE = 6
private const val MAX_FONT_SIZE = 96
private const val MIN_LINE_HEIGHT = 8
private const val MAX_LINE_HEIGHT = 200
