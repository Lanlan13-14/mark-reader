package com.markreader.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.markreader.data.CodeFontPreference
import com.markreader.data.ReadingFontPreference
import com.markreader.ui.reader.resolveTypeface

/** The caller supplies a finite viewport, normally Modifier.weight(1f).fillMaxWidth(). */
@Composable
fun NativeDocumentEditor(
    session: EditorDocumentSession,
    fontSizeSp: Float,
    lineHeight: Float,
    readingFont: ReadingFontPreference,
    codeFont: CodeFontPreference,
    isMonospaced: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val typeface = remember(context, isMonospaced, readingFont, codeFont) {
        resolveTypeface(context, isMonospaced, readingFont, codeFont)
    }
    val colors = MaterialTheme.colorScheme
    val style = NativeEditorStyle(
        typeface = typeface,
        fontSizeSp = fontSizeSp,
        lineHeight = lineHeight,
        textColor = colors.onSurface.toArgb(),
        hintColor = colors.onSurfaceVariant.toArgb(),
        cursorColor = colors.primary.toArgb(),
        selectionColor = colors.primary.copy(alpha = 0.3f).toArgb(),
        hint = placeholder
    )
    AndroidView(
        factory = { NativeDocumentEditText(it).apply { applyStyle(style); bind(session) } },
        modifier = modifier,
        onReset = null,
        onRelease = { it.unbind() },
        update = { view ->
            view.applyStyle(style)
            view.bind(session)
        }
    )
}
