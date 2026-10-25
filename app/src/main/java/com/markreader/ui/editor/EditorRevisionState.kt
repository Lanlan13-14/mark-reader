package com.markreader.ui.editor

/** Main-thread operation bookkeeping. Never stores or compares document content. */
internal class EditorRevisionState(initialRevision: Long = 0L) {
    private var savedRevision = initialRevision
    private var savingRevision: Long? = null
    private var previewGeneration = 0L

    val isSaving: Boolean get() = savingRevision != null

    fun isModified(revision: Long): Boolean = revision != savedRevision

    fun reset(revision: Long) {
        check(!isSaving)
        savedRevision = revision
        invalidatePreview()
    }

    /** Single flight: a second save must not write an older snapshot after a newer one. */
    fun beginSave(revision: Long): Boolean {
        if (isSaving) return false
        savingRevision = revision
        return true
    }

    fun finishSave(written: Boolean) {
        if (written) savedRevision = checkNotNull(savingRevision)
        savingRevision = null
    }

    fun invalidatePreview(): Long = ++previewGeneration

    fun acceptsPreview(generation: Long, revision: Long, currentRevision: Long): Boolean =
        generation == previewGeneration && revision == currentRevision
}
