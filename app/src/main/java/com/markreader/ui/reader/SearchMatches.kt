package com.markreader.ui.reader

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

/** Colours of one search hit, passive for the rest and strong for the one being visited. */
internal class SearchPalette(
    val passive: Int,
    val active: Int,
    val activeText: Int
)

/** The pairs in [positions] that overlap [start, end), shifted so they are local to [start]. */
internal fun matchesIn(positions: IntArray, start: Int, end: Int): IntArray {
    val found = ArrayList<Int>()
    var index = 0
    while (index < positions.size) {
        val matchStart = positions[index]
        val matchEnd = positions[index + 1]
        if (matchEnd > start && matchStart < end) {
            found.add((matchStart - start).coerceAtLeast(0))
            found.add((matchEnd - start).coerceAtMost(end - start))
        }
        index += 2
    }
    return found.toIntArray()
}

/**
 * [text] with the matches in [positions] marked. [positions] holds start/end pairs local to
 * [text], and only those are spanned, so a chunk never carries the rest of the document's hits.
 */
internal fun highlightedSlice(
    text: CharSequence,
    positions: IntArray,
    activeStart: Int,
    palette: SearchPalette
): Spanned {
    val highlighted = SpannableString(text)
    var index = 0
    while (index < positions.size) {
        val start = positions[index]
        val end = positions[index + 1]
        val active = start == activeStart
        highlighted.setSpan(
            SearchHighlightSpan(if (active) palette.active else palette.passive, active),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (active) {
            highlighted.setSpan(
                ForegroundColorSpan(palette.activeText), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            highlighted.setSpan(
                StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        index += 2
    }
    return highlighted
}

/**
 * Where a search query occurs in a document, found once and shared.
 *
 * The reader used to style every match by copying the whole rendered document into a new
 * [android.text.SpannableString] and attaching a span per match. On a few hundred kilobytes that
 * copy alone is the stall, and it ran again for every keystroke and every step between matches.
 * The positions are all a search actually needs: the count, the current one, and where to scroll.
 * Painting them is the text view's job, and it only paints the ones on screen.
 */
class SearchMatches internal constructor(val query: String, private val ranges: IntArray) {

    val count: Int = ranges.size / 2

    operator fun get(index: Int): IntRange {
        val slot = index.coerceIn(0, count - 1) * 2
        return ranges[slot] until ranges[slot + 1]
    }

    fun startAt(index: Int): Int = ranges[index.coerceIn(0, count - 1) * 2]

    /** The matches overlapping [start, end), as pairs of offsets local to [start]. */
    fun within(start: Int, end: Int): IntArray {
        if (count == 0 || end <= start) return EMPTY
        var first = 0
        while (first < count && ranges[first * 2 + 1] <= start) first++
        var last = first
        while (last < count && ranges[last * 2] < end) last++
        if (first == last) return EMPTY
        val local = IntArray((last - first) * 2)
        for (index in first until last) {
            val slot = (index - first) * 2
            local[slot] = (ranges[index * 2] - start).coerceAtLeast(0)
            local[slot + 1] = (ranges[index * 2 + 1] - start).coerceAtMost(end - start)
        }
        return local
    }

    companion object {
        private val EMPTY = IntArray(0)

        /** Every case-insensitive occurrence of [query], stored as flat start/end pairs. */
        fun find(text: CharSequence, query: String): SearchMatches {
            if (query.isBlank()) return SearchMatches(query, EMPTY)
            val found = ArrayList<Int>()
            var from = 0
            while (from <= text.length - query.length) {
                val at = indexOfIgnoreCase(text, query, from)
                if (at < 0) break
                found.add(at)
                found.add(at + query.length)
                from = at + query.length
            }
            return SearchMatches(query, found.toIntArray())
        }

        private fun indexOfIgnoreCase(text: CharSequence, query: String, from: Int): Int {
            val limit = text.length - query.length
            var index = from
            while (index <= limit) {
                if (regionMatches(text, index, query)) return index
                index++
            }
            return -1
        }

        private fun regionMatches(text: CharSequence, at: Int, query: String): Boolean {
            for (offset in query.indices) {
                if (!text[at + offset].equals(query[offset], ignoreCase = true)) return false
            }
            return true
        }
    }
}
