package com.markreader.ui.reader

import android.content.Context
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.markreader.data.CodeFontPreference
import com.markreader.data.ReadingFontPreference
import com.markreader.data.TextAlignmentPreference
import com.markreader.format.HeadingItem

/**
 * Reads one chunk of a windowed document.
 *
 * Everything that differs between a call that builds the view tree and a call that only
 * restyles it lives here, so [WindowedReaderHost.sync] does not grow a parameter list of its
 * own and the two call sites cannot drift apart.
 */
/** The three colours a chunk is drawn with. */
internal data class ChunkColors(
    val text: Int,
    val selection: Int,
    val codeBackground: Int
)

/** The typography a chunk is drawn with. */
internal data class ChunkTypography(
    val fontSizeSp: Float,
    val lineHeight: Float,
    val readingFont: ReadingFontPreference,
    val codeFont: CodeFontPreference,
    val isMonospaced: Boolean,
    val textAlignment: TextAlignmentPreference,
    val isCodeBlockWrapEnabled: Boolean,
    val density: Float
)

/** Everything that differs between building a chunk and restyling one. */
internal data class ChunkStyle(
    val colors: ChunkColors,
    val typography: ChunkTypography
)

internal fun WindowedReaderHost.totalHeight(): Int = ReadingWindow.totalHeight(measuredHeights())

/** The last heading in [chunk] whose line starts at or above [top] and below [limit]. */
private fun latestHeading(
    chunk: WindowedReaderHost.Chunk,
    lines: LineMetrics,
    headings: List<HeadingItem>,
    top: Int,
    limit: Int
): Int {
    var result = -1
    for (headingIndex in headings.indices) {
        val offset = headings[headingIndex].offset
        if (offset < chunk.start || offset >= chunk.end) continue
        val local = (offset - chunk.start).coerceIn(0, (lines.textLength - 1).coerceAtLeast(0))
        if (top + lines.getLineTop(lines.getLineForOffset(local)) <= limit) result = headingIndex
    }
    return result
}

/** Groups the two bundles a chunk is drawn with. */
internal fun chunkStyleOf(colors: ChunkColors, typography: ChunkTypography): ChunkStyle =
    ChunkStyle(colors, typography)

/**
 * A document split into chunks, of which only the ones near the viewport are real views.
 *
 * Everything else is a blank spacer whose height is either the height that chunk last measured
 * at, or an estimate derived from chunks that have been measured. The scroll range is therefore
 * the whole document from the start, while the cost of laying text out stays proportional to the
 * viewport rather than to the document.
 *
 * Re-windowing never moves the scroll position. A chunk that grows from its estimate to its real
 * height changes the content above the viewport; that difference is added back to [ScrollView]
 * so the text under the user's finger stays where it was. Without that, mounting a chunk above
 * the viewport yanks the page.
 */
internal class WindowedReaderHost(
    context: Context,
    private val scrollView: ScrollView,
    private val paddingPx: Int
) {
    val container: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
    }

    private var chunks: MutableList<Chunk> = mutableListOf()
    private var search: SearchMatches? = null
    private var searchIndex = -1
    private var searchPalette = SearchPalette(0, 0, 0)
    private var source: Spanned? = null
    private var style: ChunkStyle? = null
    private var mounted = IntRange.EMPTY
    private var averageHeight = 0

    /** True while a posted refinement for an in-flight jump is still queued. */
    private var refinePending = false
    private var layoutPending = false

    /**
     * One listener, re-armed per sync, so a fling cannot stack a listener per frame.
     *
     * A lambda here would reference the property it is assigned to, which the compiler cannot
     * type-check. An object can refer to itself as `this` instead.
     */
    private val layoutListener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            layoutPending = false
            container.viewTreeObserver.removeOnGlobalLayoutListener(this)
            val changed = chunks.any { chunk ->
                val view = chunk.view
                view != null && view.height > 0 && view.height != chunk.measuredHeight
            }
            if (changed) sync(scrollView.scrollY, scrollView.height, container.paddingTop)
        }
    }

    internal class Chunk(val start: Int, val end: Int, val text: Spanned) {
        var view: View? = null
        var measuredHeight = 0
    }

    /**
     * Rebuilds the chunk list from [text]. An unchanged document is left untouched, so a search
     * highlight — which replaces the [Spanned] but not its characters — updates the mounted
     * chunks in place instead of throwing the window away.
     */
    fun setText(text: Spanned, newStyle: ChunkStyle) {
        style = newStyle
        val previous = source
        source = text
        if (previous != null && previous.toString() == text.toString() && chunks.isNotEmpty()) {
            return
        }
        val ranges = ReadingWindow.chunkRanges(text)
        chunks = ranges.map { range ->
            Chunk(range.first, range.last + 1, text.subSequence(range.first, range.last + 1) as Spanned)
        }.toMutableList()
        mounted = IntRange.EMPTY
        container.removeAllViews()
        averageHeight = estimateChunkHeight(scrollView, newStyle)
        for (chunk in chunks) {
            container.addView(spacerView(container.context, averageHeight), chunkLayout())
        }
    }

    /** Paints [matches] on the mounted chunks. Chunks off screen get them when they mount. */
    fun applySearch(matches: SearchMatches?, activeIndex: Int, palette: SearchPalette) {
        search = matches
        searchIndex = activeIndex
        searchPalette = palette
        for (chunk in chunks) {
            val body = chunk.view as? ChunkBody ?: continue
            body.showSearch(matches, activeIndex, chunk.start, chunk.end, palette)
        }
    }

    /**
     * Mounts whatever the viewport now covers and records the real height of anything that has
     * been laid out since last time. Returns false when nothing changed, so the caller can skip
     * the scroll-extent bookkeeping.
     */
    fun sync(scrollY: Int, viewportHeight: Int, contentTopPx: Int): Boolean {
        val current = style ?: return false
        if (container.paddingTop != contentTopPx) {
            container.setPadding(container.paddingLeft, contentTopPx, container.paddingRight, paddingPx)
        }
        recordMeasuredHeights()
        val heights = measuredHeights()
        val next = ReadingWindow.visibleRange(heights, scrollY, viewportHeight)
        val unchanged = next == mounted && chunks.indices.all { (chunks[it].view != null) == (it in next) }
        if (unchanged) return false
        val before = ReadingWindow.offsetOf(heights, next.first)
        remount(next, current)
        val after = ReadingWindow.offsetOf(measuredHeights(), next.first)
        val shift = after - before
        if (shift != 0) {
            // A chunk above the viewport took on its real height. Put that growth back into the
            // scroll offset, or the viewport slides to a different part of the document.
            scrollView.scrollTo(0, (scrollY + shift).coerceAtLeast(0))
        }
        mounted = next
        // Chunks mount with an unknown height. One layout pass later they have one, and the
        // scroll listener only fires on a scroll, so ask for that pass explicitly.
        if (!layoutPending) {
            layoutPending = true
            container.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }
        return true
    }

    /** Mounts [range] and turns everything outside it back into a spacer of the remembered height. */
    private fun remount(range: IntRange, current: ChunkStyle) {
        val heights = measuredHeights()
        for (index in chunks.indices) {
            val chunk = chunks[index]
            val shouldMount = index in range
            if (shouldMount && chunk.view == null) {
                val body = ChunkBody(container.context, current)
                body.bind(chunk.text, current)
                body.showSearch(search, searchIndex, chunk.start, chunk.end, searchPalette)
                chunk.view = body
                container.removeViewAt(index)
                container.addView(body, index, chunkLayout())
            } else if (!shouldMount && chunk.view != null) {
                chunk.view = null
                container.removeViewAt(index)
                container.addView(spacerView(container.context, heights[index]), index, chunkLayout())
            } else if (shouldMount) {
                (chunk.view as? ChunkBody)?.restyle(current)
            }
        }
    }

    /** Copies laid-out heights back onto their chunks and refreshes the per-chunk estimate. */
    private fun recordMeasuredHeights() {
        var measured = 0
        var count = 0
        for ((index, chunk) in chunks.withIndex()) {
            val view = chunk.view ?: continue
            if (view.height > 0) {
                chunk.measuredHeight = view.height
                measured += view.height
                count++
            }
            if (container.getChildAt(index) !== view && chunk.measuredHeight > 0) {
                container.getChildAt(index)?.layoutParams?.height = chunk.measuredHeight
            }
        }
        if (count > 0) averageHeight = measured / count
    }

    internal fun measuredHeights(): IntArray = IntArray(chunks.size) { index ->
        chunks[index].measuredHeight.takeIf { it > 0 } ?: averageHeight
    }

    /** The index of the chunk that contains [offset]. */
    private fun chunkIndex(offset: Int): Int {
        val found = chunks.indexOfFirst { offset >= it.start && offset < it.end }
        return if (found >= 0) found else chunks.lastIndex
    }

    /**
     * The scroll offset that puts rendered-text offset [offset] at the top of the viewport.
     *
     * Exact when the chunk holding it is mounted and laid out. Otherwise calibrated from the
     * chunks already measured: the average pixels per character of this document, applied to
     * every unmeasured chunk, which follows a mixed prose/code document far better than a flat
     * height per chunk. [callback] runs once the estimate has had a chance to be corrected.
     */
    fun scrollYFor(offset: Int, callback: (() -> Unit)? = null): Int {
        val index = chunkIndex(offset)
        if (index < 0) return 0
        val chunk = chunks[index]
        val local = (offset - chunk.start).coerceIn(0, (chunk.end - chunk.start).coerceAtLeast(1) - 1)
        if (local == 0) return ReadingWindow.offsetOf(measuredHeights(), index)

        val mounted = chunk.view
        if (mounted != null) {
            val lines = segmentLines(mounted)
            if (lines != null && lines.textLength > 0) {
                val base = ReadingWindow.offsetOf(measuredHeights(), index)
                return base + lines.getLineTop(lines.getLineForOffset(local))
            }
        }
        // Not mounted, or mounted but not yet laid out: estimate from measured density.
        val heights = calibratedHeights()
        val base = ReadingWindow.offsetOf(heights, index)
        val callback0 = callback
        if (callback0 != null) {
            if (!refinePending) {
                refinePending = true
                container.post {
                    refinePending = false
                    callback0()
                }
                container.postDelayed({ if (refinePending) refinePending = false }, REFINE_GIVE_UP_MS)
            }
        }
        return base + heights[index] * local / (chunk.end - chunk.start).coerceAtLeast(1)
    }

    /**
     * Heights for positioning: measured where a chunk has been laid out, and for the rest the
     * document's own measured pixels-per-character, not a flat average height. A heading near
     * the end of a dense document then estimates close to where it really is, instead of a
     * flat average placing it in the middle.
     */
    private fun calibratedHeights(): IntArray {
        val heights = measuredHeights()
        var pixels = 0
        var chars = 0
        for (chunk in chunks) {
            if (chunk.measuredHeight <= 0) continue
            pixels += chunk.measuredHeight
            chars += chunk.end - chunk.start
        }
        if (chars == 0) return heights
        val perChar = pixels.toFloat() / chars
        for (index in chunks.indices) {
            if (chunks[index].measuredHeight > 0) continue
            heights[index] = ((chunks[index].end - chunks[index].start) * perChar).toInt().coerceAtLeast(1)
        }
        return heights
    }

    /** The lines of the first text view inside [view], or null when it has none laid out. */
    private fun segmentLines(view: View): LineMetrics? {
        if (view is LinearLayout) {
            for (index in 0 until view.childCount) {
                val inner = segmentLines(view.getChildAt(index))
                if (inner != null) return inner
            }
            return null
        }
        return segmentPlacement(view).lines
    }

    /** Rendered-text offset at the top of the viewport, for restoring a position by content. */
    fun anchorAt(scrollY: Int): Int? {
        val heights = measuredHeights()
        var top = 0
        for ((index, chunk) in chunks.withIndex()) {
            val bottom = top + heights[index]
            if (bottom > scrollY) {
                val lines = chunk.view?.let { segmentLines(it) }
                if (lines == null) return chunk.start
                val localY = (scrollY - top).coerceAtLeast(0)
                return chunk.start + lines.getLineStart(lines.getLineForVertical(localY))
            }
            top = bottom
        }
        return chunks.lastOrNull()?.start
    }

    /** Index of the last heading whose line starts above [scrollY], across mounted chunks. */
    fun activeHeading(headings: List<HeadingItem>, scrollY: Int): Int {
        if (headings.isEmpty()) return -1
        val heights = measuredHeights()
        var top = 0
        var result = -1
        val limit = scrollY + container.paddingTop
        for ((index, chunk) in chunks.withIndex()) {
            val lines = chunk.view?.let { segmentPlacement(it).lines }
            result = if (lines == null) result else latestHeading(chunk, lines, headings, top, limit)
            top += heights[index]
        }
        return result
    }


}

/**
 * The view for one chunk: its own segments for code blocks and tables, exactly as the
 * single-view reader splits them, only bounded to this chunk's text.
 */
/** Paints the matches that fall inside this chunk, replacing the previous highlight. */
internal fun ChunkBody.showSearch(
    matches: SearchMatches?,
    activeIndex: Int,
    chunkStart: Int,
    chunkEnd: Int,
    palette: SearchPalette
) {
    if (segmentTexts.size != childCount) return
    val positions = matches?.within(chunkStart, chunkEnd) ?: IntArray(0)
    val activeStart = if (matches != null && activeIndex in 0 until matches.count) {
        matches.startAt(activeIndex) - chunkStart
    } else {
        -1
    }
    var origin = 0
    for (index in 0 until childCount) {
        val length = segmentTexts[index].length
        val view = extractTextView(getChildAt(index))
        if (view != null) {
            view.text = highlightedSlice(
                segmentTexts[index], matchesIn(positions, origin, origin + length), activeStart - origin, palette
            )
        }
        origin += length
    }
}


internal class ChunkBody(context: Context, style: ChunkStyle) : LinearLayout(context) {
    private var builtForWrap = style.typography.isCodeBlockWrapEnabled

    /** The spanned slice each child view was built from, kept for search repainting. */
    internal var segmentTexts: List<Spanned> = emptyList()

    init {
        orientation = VERTICAL
    }

    fun bind(text: Spanned, style: ChunkStyle) {
        if (builtForWrap != style.typography.isCodeBlockWrapEnabled || childCount == 0) {
            builtForWrap = style.typography.isCodeBlockWrapEnabled
            removeAllViews()
            val segments = splitByMarkers(
                text,
                splitCode = !style.typography.isCodeBlockWrapEnabled,
                splitTables = true
            )
            segmentTexts = segments.map { (start, end, _) -> text.subSequence(start, end) as Spanned }
            for ((start, end, type) in segments) {
                addView(segmentView(text.subSequence(start, end) as Spanned, type, style), matchWrap())
            }
        } else {
            // Same structure, new spans: write them into the existing views.
            val segments = splitByMarkers(text, splitCode = !builtForWrap, splitTables = true)
            if (segments.size != childCount) {
                builtForWrap = !style.typography.isCodeBlockWrapEnabled
                bind(text, style)
                return
            }
            for ((index, segment) in segments.withIndex()) {
                val slice = text.subSequence(segment.start, segment.end) as Spanned
                val content = if (segment.type == SegmentType.Code) stripBackgroundSpans(slice) else slice
                extractTextView(getChildAt(index))?.text = content
            }
            segmentTexts = segments.map { (start, end, _) -> text.subSequence(start, end) as Spanned }
        }
        restyle(style)
    }

    fun restyle(style: ChunkStyle) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val target = if (child is FrameLayout) {
                child.setBackgroundColor(style.colors.codeBackground)
                child.getChildAt(0)
            } else {
                child
            }
            val inner = if (target is HorizontalScrollView) target.getChildAt(0) else target
            if (inner is android.widget.TextView) {
                val type = style.typography
                applyStyleToTextView(
                    inner, type.fontSizeSp, type.lineHeight, type.readingFont, type.codeFont,
                    type.isMonospaced, type.textAlignment, style.colors.text, style.colors.selection
                )
            }
        }
    }

    private fun segmentView(text: Spanned, type: SegmentType, style: ChunkStyle): View {
        val content = if (type == SegmentType.Code) stripBackgroundSpans(text) else text
        if (type == SegmentType.Table) {
            val table = buildTableLayout(
                content, context, style.colors.text, style.typography.fontSizeSp, style.typography.lineHeight,
                style.typography.readingFont, style.colors.selection, style.typography.density
            )
            if (table != null) {
                return HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = true
                    addView(table, frameWrap())
                }
            }
        }
        val scroll = type == SegmentType.Code
        val typeFace = style.typography
        val textView = createStyledTextView(
            context, 0, typeFace.fontSizeSp, typeFace.lineHeight, typeFace.readingFont,
            typeFace.codeFont, typeFace.isMonospaced || type == SegmentType.Code,
            typeFace.textAlignment, style.colors.text, style.colors.selection, scroll
        )
        textView.text = content
        val body: View = if (scroll) {
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = true
                addView(textView, frameWrap())
            }
        } else {
            textView
        }
        if (type != SegmentType.Code) return body
        return FrameLayout(context).apply {
            setBackgroundColor(style.colors.codeBackground)
            addView(body, matchWrap())
        }
    }

    private fun matchWrap() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun frameWrap() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
    )
}

/** A blank stand-in for a chunk that is not mounted, holding the height it will occupy. */
private fun spacerView(context: Context, height: Int): View = View(context).apply {
    minimumHeight = height
}

/** How long an estimated jump waits for a layout pass before giving up on refinement. */
private const val REFINE_GIVE_UP_MS = 750L

/** Layout params shared by every chunk row: full width, as tall as the content. */
private fun chunkLayout() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
)

/**
 * Rough height of one chunk before any has been measured: characters per line times the line
 * height. Only the order of magnitude matters; measured heights replace it as chunks mount.
 */
private fun estimateChunkHeight(scrollView: android.widget.ScrollView, current: ChunkStyle): Int {
    val widthPx = scrollView.width.takeIf { it > 0 } ?: scrollView.resources.displayMetrics.widthPixels
    val em = current.typography.fontSizeSp * current.typography.density * CHAR_WIDTH_EM
    val charsPerLine = (widthPx / em).toInt().coerceAtLeast(MIN_CHARS_PER_LINE)
    val lines = (ReadingWindow.TARGET_CHUNK_CHARS / charsPerLine).coerceAtLeast(1)
    val lineHeightPx = current.typography.fontSizeSp * current.typography.lineHeight
    val estimated = (lines * lineHeightPx * current.typography.density).toInt()
    return estimated.coerceAtLeast(MIN_CHUNK_HEIGHT)
}

private const val CHAR_WIDTH_EM = 0.5f
private const val MIN_CHARS_PER_LINE = 20
private const val MIN_CHUNK_HEIGHT = 48

