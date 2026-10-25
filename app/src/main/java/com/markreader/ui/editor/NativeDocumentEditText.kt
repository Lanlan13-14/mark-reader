package com.markreader.ui.editor

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView.BufferType
import androidx.annotation.ColorInt

/**
 * One bounded, internally scrolling native editor. Do not place in a ScrollView or measure with
 * unbounded height. Uses platform IME, selection/action-mode, accessibility and undo semantics.
 * Android's DynamicLayout still has document/paragraph-dependent costs; this is not virtualization.
 */
class NativeDocumentEditText(context: Context) : EditText(context) {
    private var session: EditorDocumentSession? = null
    private var replacing = false
    private var appliedStyle: NativeEditorStyle? = null
    private var restoreScroll: Runnable? = null
    private var toolbarBatchDepth = 0
    private var toolbarBatchChanged = false

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (!replacing) {
                if (toolbarBatchDepth > 0) toolbarBatchChanged = true else session?.onNativeTextChanged()
            }
        }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        setSingleLine(false)
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        // Session owns document/selection restoration; avoid a second full Parcelable text copy.
        isSaveEnabled = false
        // EditText already provides selection/action-mode; setTextIsSelectable is for read-only
        // TextViews and can change focus/input behavior. Keep the normal editable key listener.
        showSoftInputOnFocus = true
        val horizontal = (HORIZONTAL_PADDING_DP * resources.displayMetrics.density).toInt()
        val vertical = (VERTICAL_PADDING_DP * resources.displayMetrics.density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        addTextChangedListener(watcher)
    }

    /** Idempotent on Compose updates. Never resets text or cursor for the already-bound session. */
    fun bind(document: EditorDocumentSession) {
        if (session === document) return
        unbind()
        session = document
        replaceNativeBuffer(document)
        document.attach(
            text,
            onReplace = { replaceNativeBuffer(document) },
            onBeginEdit = { beginToolbarEdit() },
            onEndEdit = { endToolbarEdit() }
        )
    }

    /** One O(n) detach copy strips all view-owned spans, so a ViewModel cannot leak an Activity. */
    fun unbind() {
        restoreScroll?.let(::removeCallbacks)
        restoreScroll = null
        session?.let { document ->
            document.scrollX = scrollX
            document.scrollY = scrollY
            document.detach(text)
        }
        session = null
    }

    private fun beginToolbarEdit() {
        toolbarBatchDepth++
        beginBatchEdit()
    }

    private fun endToolbarEdit() {
        try {
            endBatchEdit()
        } finally {
            toolbarBatchDepth--
            if (toolbarBatchDepth == 0 && toolbarBatchChanged) {
                toolbarBatchChanged = false
                session?.onNativeTextChanged()
            }
        }
    }

    private fun replaceNativeBuffer(document: EditorDocumentSession) {
        val start = document.selectionStart
        val end = document.selectionEnd
        replacing = true
        try {
            setText(document.textForBinding, BufferType.EDITABLE)
            setSelection(start.coerceAtMost(length()), end.coerceAtMost(length()))
            document.adoptEditable(text)
        } finally {
            replacing = false
        }
        val x = document.scrollX
        val y = document.scrollY
        restoreScroll?.let(::removeCallbacks)
        val restore = Runnable {
            if (session === document) scrollTo(x, y)
        }
        restoreScroll = restore
        post(restore)
    }

    internal fun applyStyle(style: NativeEditorStyle) {
        val previous = appliedStyle
        if (previous == style) return
        // Changing only a placeholder/theme must not reset typeface/size and relayout the document.
        if (previous?.typeface != style.typeface) typeface = style.typeface
        if (previous?.fontSizeSp != style.fontSizeSp) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizeSp)
        }
        if (previous?.lineHeight != style.lineHeight) setLineSpacing(0f, style.lineHeight)
        applyColors(previous, style)
        if (previous?.hint != style.hint) hint = style.hint
        appliedStyle = style
    }

    private fun applyColors(previous: NativeEditorStyle?, style: NativeEditorStyle) {
        if (previous?.textColor != style.textColor) setTextColor(style.textColor)
        if (previous?.hintColor != style.hintColor) setHintTextColor(style.hintColor)
        if (previous?.selectionColor != style.selectionColor) highlightColor = style.selectionColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && previous?.cursorColor != style.cursorColor) {
            // A fresh mutated drawable prevents tinting other native text fields in the same theme.
            textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(style.cursorColor) }
        }
    }

    private companion object {
        const val HORIZONTAL_PADDING_DP = 16
        const val VERTICAL_PADDING_DP = 12
    }
}

internal data class NativeEditorStyle(
    val typeface: Typeface,
    val fontSizeSp: Float,
    val lineHeight: Float,
    @ColorInt val textColor: Int,
    @ColorInt val hintColor: Int,
    @ColorInt val cursorColor: Int,
    @ColorInt val selectionColor: Int,
    val hint: String
)
