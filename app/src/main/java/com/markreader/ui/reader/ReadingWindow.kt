package com.markreader.ui.reader

import android.text.Spanned

/**
 * How a long document is cut into pieces a windowed reader can mount and unmount.
 *
 * A few hundred kilobytes of Markdown becomes a [Spanned] of similar size, and a single
 * [android.widget.TextView] lays all of it out and draws all of it. Layout is the part that
 * stalls: it is one pass over the whole text, and it runs again on every content or style
 * change. Cutting the text into chunks bounds that pass to the chunks actually on screen.
 *
 * The cut is by character count, not by a rendered height, because the height does not exist
 * until a chunk has been laid out — which is the cost being avoided. [targetChars] is large
 * enough that a chunk is a page or more, so the seams between chunks are rare, and it is snapped
 * forward to the next paragraph break so a chunk never ends mid-paragraph (and never mid-span:
 * a paragraph boundary is a span boundary too).
 */
internal object ReadingWindow {
    /** Below this, one TextView is cheaper than a window of them. */
    const val WINDOW_THRESHOLD_CHARS = 24_000

    /** Aim for a chunk about this long, then finish the paragraph it lands in. */
    const val TARGET_CHUNK_CHARS = 8_000

    /** Never scan more than this far past the target looking for a paragraph break. */
    const val MAX_CHUNK_CHARS = 12_000

    /**
     * Chunks kept mounted either side of the viewport. Two is enough to absorb a fling without
     * mounting mid-gesture, and few enough that the mounted text stays a small fraction of a
     * large document.
     */
    const val BUFFER_CHUNKS = 2

    /** A chunk shorter than this is not worth a view of its own; fold it into the previous one. */
    private const val MIN_TAIL_CHARS = 1_000

    /** An empty window. `0 until 0` reads as a range that is never iterated, which lint rejects. */
    private val EMPTY_RANGE = IntRange.EMPTY

    /**
     * Half-open `[start, end)` ranges covering [length], split on paragraph boundaries.
     *
     * Returns a single range when the text is at or under [WINDOW_THRESHOLD_CHARS]: the window
     * exists to bound the cost of a large document, and splitting a short one only adds views.
     */
    fun chunkRanges(text: CharSequence, length: Int = text.length): List<IntRange> {
        if (length <= WINDOW_THRESHOLD_CHARS) return listOf(0 until length.coerceAtLeast(0))
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (start < length) {
            val remaining = length - start
            if (remaining <= MAX_CHUNK_CHARS) {
                ranges.add(start until length)
                break
            }
            val target = (start + TARGET_CHUNK_CHARS).coerceAtMost(length)
            val limit = (start + MAX_CHUNK_CHARS).coerceAtMost(length)
            var end = paragraphEnd(text, target, limit)
            // No paragraph break within the cap: cut anyway. A chunk must stay bounded even in a
            // document that is one long paragraph, which is exactly the case that stalls.
            if (end <= start) end = limit
            ranges.add(start until end)
            start = end
        }
        if (ranges.size >= 2) {
            val tail = ranges.last()
            if (tail.last - tail.first + 1 < MIN_TAIL_CHARS) {
                val previous = ranges[ranges.lastIndex - 1]
                ranges[ranges.lastIndex - 1] = previous.first until tail.last + 1
                ranges.removeAt(ranges.lastIndex)
            }
        }
        return ranges
    }

    /**
     * The end offset of the paragraph containing [target], or [limit] when no paragraph break
     * falls inside the window. A paragraph break is a blank line, which is where both Markdown
     * and the renderer put a boundary nothing else crosses.
     */
    private fun paragraphEnd(text: CharSequence, target: Int, limit: Int): Int {
        var index = target
        while (index < limit) {
            if (text[index] == '\n') {
                val next = index + 1
                if (next < limit && text[next] == '\n') return next + 1
                if (next >= text.length) return text.length
            }
            index++
        }
        return limit
    }

    /**
     * The chunks that should be mounted for a viewport, plus [buffer] chunks of headroom on
     * either side.
     *
     * [heights] are the measured heights of mounted chunks and [estimate] for the rest, which is
     * all that exists before a chunk has been laid out. The estimate only decides which chunks
     * to mount; once mounted, a chunk reports its real height and the next pass corrects the
     * window, so a wrong estimate shifts the window by a chunk rather than misplacing text.
     */
    fun visibleRange(
        heights: IntArray,
        scrollY: Int,
        viewportHeight: Int,
        buffer: Int = BUFFER_CHUNKS
    ): IntRange {
        if (heights.isEmpty()) return EMPTY_RANGE
        val viewBottom = scrollY + viewportHeight.coerceAtLeast(1)
        var top = 0
        var first = heights.lastIndex
        var last = 0
        for (index in heights.indices) {
            val bottom = top + heights[index]
            if (bottom > scrollY && top < viewBottom) {
                if (index < first) first = index
                if (index > last) last = index
            }
            top = bottom
        }
        if (first > last) {
            // Past the end of the estimated content: pin to the last chunk.
            first = heights.lastIndex
            last = heights.lastIndex
        }
        return (first - buffer).coerceAtLeast(0)..(last + buffer).coerceAtMost(heights.lastIndex)
    }

    /** Height of the content above chunk [index], from per-chunk heights. */
    fun offsetOf(heights: IntArray, index: Int): Int {
        var sum = 0
        for (i in 0 until index.coerceAtMost(heights.size)) sum += heights[i]
        return sum
    }

    /** Total content height. */
    fun totalHeight(heights: IntArray): Int = offsetOf(heights, heights.size)
}
