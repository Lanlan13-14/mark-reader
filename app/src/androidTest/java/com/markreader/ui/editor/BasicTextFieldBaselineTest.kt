package com.markreader.ui.editor

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Optional historical UI-shape baseline, not a full historical ViewModel benchmark. */
@RunWith(Parameterized::class)
class BasicTextFieldBaselineTest(private val size: Int, private val paragraph: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "chars={0},singleParagraph={1}")
        fun documents(): List<Array<Any>> = NativeEditorInputTest.documents()
    }

    @Test
    fun oldComposeShapeInputConnectionTimings() {
        ActivityScenario.launch(EditorInstrumentationActivity::class.java).use { scenario ->
            var expected = document(size, paragraph)
            scenario.onActivity { it.installBaseline(expected) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            for (position in listOf("begin", "middle", "end")) {
                scenario.onActivity { activity ->
                    activity.selectBaseline(when (position) {
                        "begin" -> 0
                        "middle" -> expected.length / 2
                        else -> expected.length
                    })
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                lateinit var activeInput: InputConnection
                scenario.onActivity { activity ->
                    activeInput = requireNotNull(connection(activity.window.decorView))
                }
                repeat(12) { index ->
                    scenario.onActivity { activity ->
                        val input = activeInput
                        val at = activity.baseline.selection.start
                        val callbacks = activity.baselineCallbacks
                        val started = SystemClock.elapsedRealtimeNanos()
                        assertTrue(input.commitText("x", 1))
                        val elapsed = SystemClock.elapsedRealtimeNanos() - started
                        expected = expected.substring(0, at) + "x" + expected.substring(at)
                        assertEquals(expected, activity.baseline.text)
                        assertEquals(at + 1, activity.baseline.selection.start)
                        assertTrue(activity.baselineCallbacks > callbacks)
                        record("BasicTextField", size, paragraph, position, "commit-$index", elapsed,
                            0, activity.baselineCallbacks - callbacks, activity.window.decorView.height)
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
                repeat(3) { index ->
                    var at = 0
                    val elapsed = measureThroughFrame(scenario) { activity ->
                        at = activity.baseline.selection.start
                        assertTrue(activeInput.commitText("f", 1))
                    }
                    expected = expected.substring(0, at) + "f" + expected.substring(at)
                    scenario.onActivity { activity ->
                        assertEquals(expected, activity.baseline.text)
                        assertEquals(at + 1, activity.baseline.selection.start)
                        record("BasicTextField", size, paragraph, position, "frame-commit-$index", elapsed,
                            0, -1, activity.window.decorView.height)
                    }
                }
            }
        }
    }

    private fun connection(view: View): InputConnection? {
        if (view.onCheckIsTextEditor()) view.onCreateInputConnection(EditorInfo())?.let { return it }
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            connection(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
