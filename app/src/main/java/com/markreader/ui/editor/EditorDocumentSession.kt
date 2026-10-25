package com.markreader.ui.editor

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.inputmethod.BaseInputConnection
import androidx.annotation.MainThread

/**
 * A main-thread-only document, deliberately outside Compose/StateFlow text state.
 * While attached, [buffer] IS EditText's mutable buffer, including the IME's composing spans.
 * No string snapshot, equality check or selection notification runs on the typing path.
 * Do not read this session on a worker thread: take [snapshot] before dispatching IO.
 */
@MainThread
class EditorDocumentSession {
    private var buffer: Editable = SpannableStringBuilder()
    private var attached = false
    private var replacementListener: (() -> Unit)? = null
    private var beginEdit: (() -> Unit)? = null
    private var endEdit: (() -> Unit)? = null

    var revision: Long = 0
        private set

    /** Diagnostic counter for explicit document snapshots, not a Compose-observable value. */
    var snapshotCount: Long = 0
        private set

    /** Content changes only, not cursor moves or composing-span changes. Keep this callback cheap. */
    var onChanged: (() -> Unit)? = null

    internal var scrollX: Int = 0
    internal var scrollY: Int = 0

    /** An explicit O(n) boundary for save/preview/export, never called by TextWatcher. */
    fun snapshot(): String {
        snapshotCount++
        return buffer.toString()
    }

    /** Load/reset only. Does not report a user edit; invalidates pending save revisions. */
    fun replaceAll(text: String) {
        buffer = SpannableStringBuilder(text)
        Selection.setSelection(buffer, 0)
        scrollX = 0
        scrollY = 0
        revision++
        replacementListener?.invoke()
    }

    internal val textForBinding: CharSequence get() = buffer

    internal val selectionStart: Int get() = Selection.getSelectionStart(buffer).coerceIn(0, buffer.length)
    internal val selectionEnd: Int get() = Selection.getSelectionEnd(buffer).coerceIn(0, buffer.length)

    internal fun attach(
        editable: Editable,
        onReplace: () -> Unit,
        onBeginEdit: () -> Unit,
        onEndEdit: () -> Unit
    ) {
        check(!attached) { "A document session can only have one native editor" }
        buffer = editable
        attached = true
        replacementListener = onReplace
        beginEdit = onBeginEdit
        endEdit = onEndEdit
    }

    internal fun adoptEditable(editable: Editable) {
        buffer = editable
    }

    internal fun detach(editable: Editable) {
        if (!attached || buffer !== editable) return
        val start = selectionStart
        val end = selectionEnd
        // Strip view-owned watchers, selection handles and composing spans. Never retain an Activity.
        buffer = SpannableStringBuilder(editable.toString())
        Selection.setSelection(buffer, start, end)
        attached = false
        replacementListener = null
        beginEdit = null
        endEdit = null
    }

    internal fun onNativeTextChanged() {
        revision++
        onChanged?.invoke()
    }

    fun wrapSelection(prefix: String, suffix: String) {
        val anchor = selectionStart
        val focus = selectionEnd
        val start = minOf(anchor, focus)
        val end = maxOf(anchor, focus)
        edit {
            // One replacement is one platform undo operation. Copy only the selected range,
            // never the rest of the document (beginBatchEdit does not group two distant inserts).
            val selected = buffer.subSequence(start, end).toString()
            buffer.replace(start, end, prefix + selected + suffix)
            Selection.setSelection(buffer, anchor + prefix.length, focus + prefix.length)
        }
    }

    fun insertLinePrefix(prefix: String) {
        val start = selectionStart
        val end = selectionEnd
        var lineStart = minOf(start, end)
        while (lineStart > 0 && buffer[lineStart - 1] != '\n') lineStart--
        edit {
            buffer.insert(lineStart, prefix)
            Selection.setSelection(buffer, start + prefix.length, end + prefix.length)
        }
    }

    /** Replaces a selection, like ordinary native typing; a collapsed selection inserts. */
    fun insertAtCursor(insertion: String) {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        edit {
            buffer.replace(start, end, insertion)
            Selection.setSelection(buffer, start + insertion.length)
        }
    }

    private inline fun edit(block: () -> Unit) {
        beginEdit?.invoke()
        try {
            // Explicit toolbar actions finish composition; ordinary IME input never does this.
            BaseInputConnection.removeComposingSpans(buffer)
            block()
            // Native TextWatcher reports attached changes, including platform undo/redo.
            if (!attached) onNativeTextChanged()
        } finally {
            endEdit?.invoke()
        }
    }
}
