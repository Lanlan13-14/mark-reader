package com.markreader.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM operation-order regressions; no Android buffer or text comparison involved. */
class EditorRevisionStateTest {
    @Test fun `loaded revision starts clean`() {
        val state = EditorRevisionState()
        state.reset(7)
        assertFalse(state.isModified(7))
        assertTrue(state.isModified(8))
    }

    @Test fun `matching successful snapshot becomes clean`() {
        val state = EditorRevisionState()
        assertTrue(state.beginSave(1))
        assertTrue(state.isSaving)
        state.finishSave(written = true)
        assertFalse(state.isSaving)
        assertFalse(state.isModified(1))
    }

    @Test fun `typing during successful save remains dirty`() {
        val state = EditorRevisionState()
        assertTrue(state.beginSave(1))
        // Revision 2 arrived while the immutable revision-1 snapshot was on IO.
        state.finishSave(written = true)
        assertTrue(state.isModified(2))
        assertTrue(state.beginSave(2))
        state.finishSave(written = true)
        assertFalse(state.isModified(2))
    }

    @Test fun `selection during save does not dirty matching revision`() {
        val state = EditorRevisionState()
        state.reset(9)
        assertTrue(state.beginSave(9))
        // Native selection callbacks never increment content revision.
        state.finishSave(written = true)
        assertFalse(state.isModified(9))
    }

    @Test fun `second save or save as cannot overtake active write`() {
        val state = EditorRevisionState()
        assertTrue(state.beginSave(3))
        assertFalse(state.beginSave(4))
        assertFalse(state.beginSave(3))
        state.finishSave(written = true)
        assertFalse(state.isModified(3))
        assertTrue(state.isModified(4))
        assertTrue(state.beginSave(4))
        state.finishSave(written = true)
        assertFalse(state.isModified(4))
    }

    @Test fun `failed or cancelled write preserves previous saved baseline and permits retry`() {
        val state = EditorRevisionState(5)
        assertTrue(state.beginSave(6))
        state.finishSave(written = false)
        assertFalse(state.isSaving)
        assertFalse(state.isModified(5))
        assertTrue(state.isModified(6))
        assertTrue(state.beginSave(6))
        state.finishSave(written = true)
        assertFalse(state.isModified(6))
    }

    @Test fun `failed save while already clean remains clean`() {
        val state = EditorRevisionState(5)
        assertTrue(state.beginSave(5))
        state.finishSave(written = false)
        assertFalse(state.isModified(5))
    }

    @Test fun `same revision preview is accepted only by current request`() {
        val state = EditorRevisionState()
        val old = state.invalidatePreview()
        val latest = state.invalidatePreview()
        assertFalse(state.acceptsPreview(old, 12, 12))
        assertTrue(state.acceptsPreview(latest, 12, 12))
    }

    @Test fun `changed buffer rejects preview even if generation was not invalidated`() {
        val state = EditorRevisionState()
        val generation = state.invalidatePreview()
        assertFalse(state.acceptsPreview(generation, 12, 13))
    }

    @Test fun `leaving preview or changing theme rejects old result without text edit`() {
        val state = EditorRevisionState()
        val generation = state.invalidatePreview()
        state.invalidatePreview()
        assertFalse(state.acceptsPreview(generation, 12, 12))
    }

    @Test fun `loading resets baseline and invalidates pending result`() {
        val state = EditorRevisionState()
        val generation = state.invalidatePreview()
        state.reset(42)
        assertFalse(state.isModified(42))
        assertFalse(state.acceptsPreview(generation, 42, 42))
    }
}
