package com.markreader.ui.editor

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Historical UI shape, driven by Compose InsertTextAtCursor semantics with system IME blocked.
 * NOT an InputConnection/IME benchmark. Only action-through-two-vsync observations are recorded;
 * neither these nor native synchronous commitText times are interchangeable or input-to-photon.
 */
@RunWith(Parameterized::class)
class BasicTextFieldBaselineTest(private val size: Int, private val paragraph: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "chars={0},singleParagraph={1}")
        fun documents(): List<Array<Any>> = NativeEditorInputTest.documents()
    }

    @Test
    fun oldComposeShapeSemanticsFrameTimings() {
        ActivityScenario.launch(EditorInstrumentationActivity::class.java).use { scenario ->
            var expected = document(size, paragraph)
            val inputIntercepted = CountDownLatch(1)
            scenario.onActivity { it.installBaseline(expected, inputIntercepted) }
            // A lifecycle barrier, not an edit retry: focus must reach the interceptor before
            // sampling. The interceptor suspends without forwarding to the Android keyboard.
            assertTrue("Compose input session did not reach IME isolation within 60s",
                inputIntercepted.await(60, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            measureThroughFrame(scenario) { }
            var expectedSelection = 0
            scenario.onActivity { assertBaseline(it, expected, expectedSelection) }
            for (position in listOf("begin", "middle", "end")) {
                expectedSelection = when (position) {
                    "begin" -> 0
                    "middle" -> expected.length / 2
                    else -> expected.length
                }
                // Selection is setup, outside the edit measurement. Verify both the state
                // holder and rendered semantics so a stale semantics closure cannot be used.
                measureThroughFrame(scenario) { it.selectBaseline(expectedSelection) }
                scenario.onActivity { assertBaseline(it, expected, expectedSelection) }
                repeat(6) { index ->
                    val at = expectedSelection
                    var callbacks = 0
                    var insert: ((AnnotatedString) -> Boolean)? = null
                    scenario.onActivity { activity ->
                        assertBaseline(activity, expected, at)
                        callbacks = activity.baselineCallbacks
                        insert = requireNotNull(editorNode(activity).config[
                            SemanticsActions.InsertTextAtCursor].action) {
                            "Compose insertion semantics unavailable; baseline is NOT measured"
                        }
                    }
                    val frameNs = measureThroughFrame(scenario) {
                        assertTrue("Compose insertion semantics rejected edit",
                            requireNotNull(insert).invoke(AnnotatedString("x")))
                    }
                    // Oracle construction, full comparisons and serialization are not timed.
                    expected = expected.substring(0, at) + "x" + expected.substring(at)
                    expectedSelection = at + 1
                    scenario.onActivity { activity ->
                        assertBaseline(activity, expected, expectedSelection)
                        assertEquals("one insertion must emit exactly one onValueChange",
                            callbacks + 1, activity.baselineCallbacks)
                        // Deliberately different engine AND operation names: do not mix with
                        // native commit-* or frame-commit-* distributions/speedup ratios.
                        record("BasicTextField-semantics", size, paragraph, position,
                            "frame-semantics-insert-$index", frameNs, 0,
                            activity.baselineCallbacks - callbacks, activity.window.decorView.height)
                    }
                }
            }
            // A separate, unmeasured stability boundary catches deferred state rollback.
            measureThroughFrame(scenario) { }
            scenario.onActivity { assertBaseline(it, expected, expectedSelection) }
        }
    }

    private fun assertBaseline(activity: EditorInstrumentationActivity, expected: String, selection: Int) {
        assertEquals("complete backing text", expected, activity.baseline.text)
        assertEquals("backing selection start", selection, activity.baseline.selection.start)
        assertEquals("backing selection end", selection, activity.baseline.selection.end)
        val config = editorNode(activity).config
        assertEquals("complete rendered semantics text", expected,
            config[SemanticsProperties.EditableText].text)
        val renderedSelection = config[SemanticsProperties.TextSelectionRange]
        assertEquals("rendered selection start", selection, renderedSelection.start)
        assertEquals("rendered selection end", selection, renderedSelection.end)
        assertTrue("baseline must retain focus", config[SemanticsProperties.Focused])
    }

    private fun editorNode(activity: EditorInstrumentationActivity): SemanticsNode {
        val editors = mutableListOf<SemanticsNode>()
        fun visitNode(node: SemanticsNode) {
            if (node.config.contains(SemanticsActions.InsertTextAtCursor)) editors.add(node)
            node.children.forEach { visitNode(it) }
        }
        fun visitView(view: View) {
            if (view is RootForTest) visitNode(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                visitView(view.getChildAt(index))
            }
        }
        visitView(activity.window.decorView)
        check(editors.size == 1) { "Expected one Compose editor, found ${editors.size}" }
        return editors.single()
    }
}
