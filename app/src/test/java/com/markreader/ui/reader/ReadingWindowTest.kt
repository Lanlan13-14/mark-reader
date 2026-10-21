package com.markreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window is what keeps a large document from being laid out all at once, so the cases here
 * pin the properties that make that true: short documents are not split, long ones are split
 * into bounded pieces, no piece is empty, and the pieces cover the text exactly once.
 */
class ReadingWindowTest {

    /** Paragraphs of [words] words, separated by blank lines, repeated [times] times. */
    private fun document(times: Int, words: Int = 40): String {
        val paragraph = (1..words).joinToString(" ") { "word$it" }
        return (1..times).joinToString("\n\n") { paragraph }
    }

    @Test
    fun `a short document stays one chunk`() {
        val text = document(times = 3)

        assertTrue(text.length < ReadingWindow.WINDOW_THRESHOLD_CHARS)
        assertEquals(listOf(0 until text.length), ReadingWindow.chunkRanges(text))
    }

    @Test
    fun `an empty document is one empty chunk`() {
        assertEquals(listOf(IntRange.EMPTY), ReadingWindow.chunkRanges(""))
    }

    @Test
    fun `a long document is split into bounded chunks on paragraph boundaries`() {
        val text = document(times = 400)
        assertTrue(text.length > ReadingWindow.WINDOW_THRESHOLD_CHARS)

        val ranges = ReadingWindow.chunkRanges(text)

        assertTrue("expected several chunks, got ${ranges.size}", ranges.size > 1)
        assertEquals(0, ranges.first().first)
        assertEquals(text.length, ranges.last().last + 1)
        ranges.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.last + 1, next.first)
        }
        ranges.forEach { range ->
            assertTrue(range.last >= range.first)
            assertTrue(
                "chunk of ${range.last - range.first + 1} chars exceeds the cap",
                range.last - range.first + 1 <= ReadingWindow.MAX_CHUNK_CHARS
            )
        }
    }

    @Test
    fun `a chunk never ends in the middle of a paragraph`() {
        val text = document(times = 400)
        val ranges = ReadingWindow.chunkRanges(text)

        // Every chunk but the last ends where a paragraph ended, i.e. right after a blank line.
        ranges.dropLast(1).forEach { range ->
            val end = range.last + 1
            assertEquals('\n', text[end - 1])
            assertEquals('\n', text[end - 2])
        }
    }

    @Test
    fun `one enormous paragraph is still cut into bounded chunks`() {
        val text = "word ".repeat(20_000)
        val ranges = ReadingWindow.chunkRanges(text)

        assertTrue(ranges.size > 1)
        assertEquals(text.length, ranges.sumOf { it.last - it.first + 1 })
        ranges.forEach { range ->
            assertTrue(range.last - range.first + 1 <= ReadingWindow.MAX_CHUNK_CHARS)
        }
    }

    @Test
    fun `the visible window covers the viewport plus a buffer and nothing else`() {
        val heights = IntArray(20) { 1_000 }

        val range = ReadingWindow.visibleRange(heights, scrollY = 5_000, viewportHeight = 2_000)

        // Chunks 5 and 6 overlap the viewport; the buffer mounts two more on each side.
        assertEquals(3, range.first)
        assertEquals(8, range.last)
    }

    @Test
    fun `the visible window clamps at both ends of the document`() {
        val heights = IntArray(6) { 1_000 }

        assertEquals(0, ReadingWindow.visibleRange(heights, scrollY = 0, viewportHeight = 500).first)
        assertEquals(5, ReadingWindow.visibleRange(heights, scrollY = 9_000, viewportHeight = 500).last)
    }

    @Test
    fun `a search records positions without copying the document`() {
        val text = "Alpha beta ALPHA tail"
        val matches = SearchMatches.find(text, "alpha")

        assertEquals(2, matches.count)
        assertEquals(0, matches.startAt(0))
        assertEquals(11, matches.startAt(1))
    }

    @Test
    fun `measured density puts a late offset near the end of the document`() {
        // A heading near the end of a dense document must not land in the middle of it. The
        // estimate uses the measured pixels per character, not a flat height per chunk.
        val heights = intArrayOf(2000, 2000, 2000, 2000)
        val end = ReadingWindow.totalHeight(heights)

        assertEquals(8000, end)
        assertTrue(ReadingWindow.offsetOf(heights, 3) > end * 2 / 3)
    }

    @Test
    fun `within returns the same matches as a linear scan`() {
        val text = (1..600).joinToString(" ") { "ip$it value$it ip" }
        val matches = SearchMatches.find(text, "ip")

        // Sliding windows over the document: the binary-search fast path must agree with the
        // straightforward reading of "matches overlapping this window".
        for (start in 0 until text.length step 997) {
            for (span in intArrayOf(0, 1, 50, 5000)) {
                val end = (start + span).coerceAtMost(text.length)
                val window = matches.within(start, end)
                val expected = ArrayList<Int>()
                for (i in 0 until matches.count) {
                    val ms = matches.startAt(i)
                    val me = ms + 2
                    if (me > start && ms < end) {
                        expected.add((ms - start).coerceAtLeast(0))
                        expected.add((me - start).coerceAtMost(end - start))
                    }
                }
                assertEquals(expected.toList(), window.toList())
            }
        }
    }

    @Test
    fun `matches after a segment without a text view still get their positions`() {
        // A table has no TextView, so its matches cannot be painted. The positions computed
        // for the views after it must not drift by the table's length.
        val positions = intArrayOf(0, 2, 10, 12) // one match before the table, one after
        val segmentLengths = intArrayOf(10, 40, 10)

        val local = matchesIn(positions, 0, segmentLengths.sum())
        assertEquals(4, local.size)
        assertEquals(0, local[0])
        assertEquals(2, local[1])
        assertEquals(10, local[2]) // the second match, still at its own offset
        assertEquals(12, local[3])
    }

    @Test
    fun `a search over a chunk returns only the matches inside it`() {
        val text = "one two one two one"
        val matches = SearchMatches.find(text, "one")

        assertEquals(3, matches.count)
        val middle = matches.within(start = 4, end = 15)

        // "one two one two one": the window [4, 15) holds only the second "one", at 8.
        assertEquals(2, middle.size)
        assertEquals(4, middle[0])
        assertEquals(7, middle[1])
    }

    @Test
    fun `offsets reconstruct the scroll position of every chunk`() {
        val heights = intArrayOf(100, 250, 400)

        assertEquals(0, ReadingWindow.offsetOf(heights, 0))
        assertEquals(100, ReadingWindow.offsetOf(heights, 1))
        assertEquals(350, ReadingWindow.offsetOf(heights, 2))
        assertEquals(750, ReadingWindow.totalHeight(heights))
    }
}
