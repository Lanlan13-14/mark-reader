package com.markreader.ui.editor

import android.os.SystemClock
import android.view.Choreographer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Two vsync callbacks allow intervening traversal; not GPU-present or input-to-photon latency. */
internal fun measureThroughFrame(scenario: ActivityScenario<EditorInstrumentationActivity>,
    edit: (EditorInstrumentationActivity) -> Unit): Long {
    val done = CountDownLatch(1)
    var started = 0L
    var finished = 0L
    scenario.onActivity { activity ->
        started = SystemClock.elapsedRealtimeNanos()
        edit(activity)
        Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback {
                finished = SystemClock.elapsedRealtimeNanos()
                done.countDown()
            }
        }
    }
    assertTrue("No second frame within 60s (severe stall)", done.await(60, TimeUnit.SECONDS))
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    return finished - started
}
