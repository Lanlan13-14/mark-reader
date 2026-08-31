package com.markreader.file

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the eager file read performed in MainActivity (while the Activity holds
 * the intent's URI grant) with the ViewModel's async read.
 *
 * Flow:
 *  1. MainActivity.handleIntent() calls register(uri), receives the CompletableDeferred,
 *     then launches a background read that completes the deferred directly.
 *  2. DocumentSource.read() calls consume(uri) and awaits the result.
 *
 * Undecoded bytes, so that whoever consumes them still gets to see the byte-order mark.
 */
internal object ExternalFileCache {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /** Registers a pre-read for [uri] and returns the [CompletableDeferred] to complete directly. */
    fun register(uri: String): CompletableDeferred<ByteArray?> {
        val deferred = CompletableDeferred<ByteArray?>()
        pending[uri] = deferred
        return deferred
    }

    /** Returns the deferred for this URI (removing it), or null if none was registered. */
    fun consume(uri: String): CompletableDeferred<ByteArray?>? = pending.remove(uri)

    /** Removes all pending entries. Call from Activity.onDestroy to avoid leaks. */
    fun clear() {
        pending.clear()
    }
}
