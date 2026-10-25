package com.markreader.ui.editor

import android.os.Build
import android.os.SystemClock
import android.text.Selection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** Real device/emulator correctness tests. Times are observations, never absolute FPS gates. */
@RunWith(Parameterized::class)
class NativeEditorInputTest(private val size: Int, private val singleParagraph: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "chars={0},singleParagraph={1}")
        fun documents(): List<Array<Any>> = listOf(100_000, 500_000, 1_000_000).flatMap { size ->
            listOf(arrayOf<Any>(size, false), arrayOf<Any>(size, true))
        }
    }

    @Test
    fun editsCompositionFormattingAndRebindPreserveEveryCharacter() {
        val initial = document(size, singleParagraph)
        ActivityScenario.launch(EditorInstrumentationActivity::class.java).use { scenario ->
            var expected = initial
            var callbacks = 0
            var callbackNanos = 0L
            scenario.onActivity { activity ->
                activity.installNative(initial)
                activity.session.onChanged = {
                    val start = SystemClock.elapsedRealtimeNanos()
                    callbacks++
                    callbackNanos += SystemClock.elapsedRealtimeNanos() - start
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val view = activity.native
                assertTrue("editor measured within viewport", view.height in 1..activity.window.decorView.height)
                assertTrue("editor measured width", view.width > 0)
                assertEquals(initial, view.text.toString())
            }
            for (position in listOf("begin", "middle", "end")) {
                scenario.onActivity { activity ->
                    val view = activity.native
                    val offset = when (position) { "begin" -> 0; "middle" -> expected.length / 2; else -> expected.length }
                    view.setSelection(offset)
                    val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
                    repeat(12) { index ->
                        val at = view.selectionStart
                        val beforeSnapshot = activity.session.snapshotCount
                        val beforeCallbacks = callbacks
                        val beforeRevision = activity.session.revision
                        val beforeCallbackNs = callbackNanos
                        val started = SystemClock.elapsedRealtimeNanos()
                        assertTrue(connection.commitText("x", 1))
                        val editNs = SystemClock.elapsedRealtimeNanos() - started
                        // Timing ends before oracle construction/full document comparison.
                        expected = expected.substring(0, at) + "x" + expected.substring(at)
                        assertEquals(expected, view.text.toString())
                        assertEquals(at + 1, view.selectionStart)
                        assertEquals(view.selectionStart, view.selectionEnd)
                        assertTrue("content callback emitted", callbacks > beforeCallbacks)
                        assertTrue(activity.session.revision > beforeRevision)
                        assertEquals("no full session snapshot on typing path", beforeSnapshot, activity.session.snapshotCount)
                        record("native", size, singleParagraph, position, "commit-$index", editNs,
                            callbackNanos - beforeCallbackNs, callbacks - beforeCallbacks, view.height)
                    }
                    val at = view.selectionStart
                    operation(activity, position, "newline-insert") { assertTrue(connection.commitText("\n", 1)) }
                    expected = expected.substring(0, at) + "\n" + expected.substring(at)
                    assertEquals(expected, view.text.toString())
                    assertEquals(at + 1, view.selectionStart)
                    operation(activity, position, "newline-delete") { assertTrue(connection.deleteSurroundingText(1, 0)) }
                    expected = expected.removeRange(at, at + 1)
                    assertEquals(expected, view.text.toString())
                    assertEquals(at, view.selectionStart)
                    connection.closeConnection()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                repeat(3) { index ->
                    var at = 0
                    var snapshots = 0L
                    val elapsed = measureThroughFrame(scenario) { activity ->
                        at = activity.native.selectionStart
                        snapshots = activity.session.snapshotCount
                        assertTrue(requireNotNull(activity.native.onCreateInputConnection(EditorInfo())).commitText("f", 1))
                    }
                    // Full oracle comparison and trace serialization run after the measured frame.
                    expected = expected.substring(0, at) + "f" + expected.substring(at)
                    scenario.onActivity { activity ->
                        assertEquals(expected, activity.native.text.toString())
                        assertEquals(at + 1, activity.native.selectionStart)
                        assertEquals(snapshots, activity.session.snapshotCount)
                        record("native", size, singleParagraph, position, "frame-commit-$index", elapsed,
                            0, -1, activity.native.height)
                    }
                }
            }
            scenario.onActivity { activity ->
                val view = activity.native
                val connection = requireNotNull(view.onCreateInputConnection(EditorInfo()))
                val at = expected.length / 2
                view.setSelection(at + 7, at) // reversed selection; composing text replaces it
                operation(activity, "middle", "compose-ni") { assertTrue(connection.setComposingText("ni", 1)) }
                expected = expected.replaceRange(at, at + 7, "ni")
                assertEquals(expected, view.text.toString())
                assertEquals(at, BaseInputConnection.getComposingSpanStart(checkNotNull(view.text)))
                assertEquals(at + 2, BaseInputConnection.getComposingSpanEnd(checkNotNull(view.text)))
                operation(activity, "middle", "compose-nihao") { assertTrue(connection.setComposingText("nihao", 1)) }
                expected = expected.replaceRange(at, at + 2, "nihao")
                assertEquals(expected, view.text.toString())
                operation(activity, "middle", "commit-chinese") { assertTrue(connection.commitText("你好世界", 1)) }
                expected = expected.replaceRange(at, at + 5, "你好世界")
                assertEquals(expected, view.text.toString())
                assertEquals(at + 4, view.selectionStart)
                assertTrue(connection.finishComposingText())
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(checkNotNull(view.text)))
                val emojiAt = view.selectionStart
                operation(activity, "middle", "emoji-insert") { assertTrue(connection.commitText("😀", 1)) }
                expected = expected.substring(0, emojiAt) + "😀" + expected.substring(emojiAt)
                assertEquals(expected, view.text.toString())
                assertEquals(emojiAt + 2, view.selectionStart)
                operation(activity, "middle", "emoji-codepoint-delete") {
                    assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))
                }
                expected = expected.removeRange(emojiAt, emojiAt + 2)
                assertEquals(expected, view.text.toString())
                assertEquals(emojiAt, view.selectionStart)
                val revision = activity.session.revision
                view.setSelection(at + 4, at)
                assertEquals("selection must not dirty session", revision, activity.session.revision)
                operation(activity, "middle", "toolbar-wrap-reversed") { activity.session.wrapSelection("**", "**") }
                expected = expected.substring(0, at) + "**你好世界**" + expected.substring(at + 4)
                assertEquals(expected, view.text.toString())
                assertEquals(at + 2, minOf(view.selectionStart, view.selectionEnd))
                assertEquals(at + 6, maxOf(view.selectionStart, view.selectionEnd))
                operation(activity, "middle", "toolbar-replace-selection") { activity.session.insertAtCursor("中文") }
                expected = expected.replaceRange(at + 2, at + 6, "中文")
                assertEquals(expected, view.text.toString())
                assertEquals(at + 4, view.selectionStart)
                assertEquals(view.selectionStart, view.selectionEnd)
                val selection = view.selectionStart
                val snapshot = activity.session.snapshot() // explicit preview/save boundary
                val capturedRevision = activity.session.revision
                assertEquals(expected, snapshot)
                activity.detachNative()
                assertEquals(expected, activity.session.snapshot())
                activity.reattachNative()
                assertEquals(expected, activity.native.text.toString())
                assertEquals(selection, activity.native.selectionStart)
                activity.session.insertAtCursor("!")
                expected = expected.substring(0, selection) + "!" + expected.substring(selection)
                assertEquals(expected, activity.native.text.toString())
                assertTrue("late save cannot clean a newer revision", capturedRevision != activity.session.revision)
                // The actual VM save/IO clean transition is a separate integration concern.
                assertEquals(expected, activity.session.snapshot())
                // A fresh buffer isolates undo history from earlier IME merge groups.
                activity.session.replaceAll(expected)
                activity.native.setSelection(at + 4, at)
                val beforeToolbar = expected
                activity.session.wrapSelection("**", "**")
                val afterToolbar = expected.substring(0, at) + "**" + expected.substring(at, at + 4) + "**" + expected.substring(at + 4)
                assertEquals(afterToolbar, activity.native.text.toString())
                assertTrue("native undo of toolbar edit", activity.native.onTextContextMenuItem(android.R.id.undo))
                assertEquals("toolbar must undo atomically", beforeToolbar, activity.native.text.toString())
                assertTrue("native redo of toolbar edit", activity.native.onTextContextMenuItem(android.R.id.redo))
                assertEquals(afterToolbar, activity.native.text.toString())
                assertEquals(afterToolbar, activity.session.snapshot())
            }
        }
    }

    private fun operation(activity: EditorInstrumentationActivity, position: String, name: String, block: () -> Unit) {
        val snapshots = activity.session.snapshotCount
        val started = SystemClock.elapsedRealtimeNanos()
        block()
        val duration = SystemClock.elapsedRealtimeNanos() - started
        assertEquals("no implicit full snapshot: $name", snapshots, activity.session.snapshotCount)
        record("native", size, singleParagraph, position, name, duration, 0, -1, activity.native.height)
    }
}

internal fun document(size: Int, singleParagraph: Boolean): String {
    val line = if (singleParagraph) "中文 abcd 0123456789 " else "中文 abcd 0123456789\n"
    return buildString(size) { while (length < size) append(line) }.take(size)
}

internal fun record(engine: String, size: Int, paragraph: Boolean, position: String, operation: String,
    elapsed: Long, callbackNs: Long, callbacks: Int, height: Int) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val output = File(context.filesDir, "editor-metrics.jsonl")
    val row = JSONObject().put("engine", engine).put("chars", size).put("singleParagraph", paragraph)
        .put("position", position).put("operation", operation).put("editNs", elapsed)
        .put("callbackNs", callbackNs).put("callbacks", callbacks).put("viewHeightPx", height)
        .put("api", Build.VERSION.SDK_INT).put("model", Build.MODEL)
    output.appendText("$row\n")
    android.util.Log.i("EditorMetrics", row.toString())
}
