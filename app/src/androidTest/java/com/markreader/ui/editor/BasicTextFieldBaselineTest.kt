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

/** Historical UI-shape baseline; results are verified after Compose has rendered a frame. */
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
            // waitForIdleSync alone does not await a scheduled Compose recomposition.
            measureThroughFrame(scenario) { }
            for (position in listOf("begin", "middle", "end")) {
                measureThroughFrame(scenario) { activity ->
                    activity.selectBaseline(when (position) {
                        "begin" -> 0
                        "middle" -> expected.length / 2
                        else -> expected.length
                    })
                }
                repeat(6) { index ->
                    var at = 0
                    var syncNs = 0L
                    var callbacks = 0
                    val frameNs = measureThroughFrame(scenario) { activity ->
                        at = activity.baseline.selection.start
                        callbacks = activity.baselineCallbacks
                        // A synthetic connection is not the IMM-owned connection. Compose/IMM can
                        // replace the input session between frames; never reuse this handle after
                        // yielding to the main loop. Create and use it in the same UI-thread turn.
                        val input = requireNotNull(connection(activity.window.decorView)) {
                            "Compose input connection unavailable; baseline is NOT measured"
                        }
                        try {
                            val started = SystemClock.elapsedRealtimeNanos()
                            assertTrue(input.commitText("x", 1))
                            syncNs = SystemClock.elapsedRealtimeNanos() - started
                        } finally {
                            // Release only our synthetic handle, not the platform IME's connection.
                            input.closeConnection()
                        }
                    }
                    expected = expected.substring(0, at) + "x" + expected.substring(at)
                    scenario.onActivity { activity ->
                        assertEquals(expected, activity.baseline.text)
                        assertEquals(at + 1, activity.baseline.selection.start)
                        assertEquals(at + 1, activity.baseline.selection.end)
                        assertTrue("commitText accepted without onValueChange",
                            activity.baselineCallbacks > callbacks)
                        record("BasicTextField", size, paragraph, position, "commit-$index", syncNs,
                            0, -1, activity.window.decorView.height)
                        record("BasicTextField", size, paragraph, position, "frame-commit-$index", frameNs,
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
