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
    private var refineCount = 0
    private var refineStartedAt = 0L

    /** Whether the last [scrollYFor] answered from an estimate rather than a laid-out line. */
    internal var lastAnswerWasEstimate = false
        private set
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

        /** Cached by [chunkIndex], so a binary search does not re-string the chunk per call. */
        internal var cachedPlain: String? = null
        fun plain(): String = cachedPlain ?: text.toString().also { cachedPlain = it }

        /** Whether the chunk contains a code fence; computed lazily, once. */
        internal var codeFenceKnown = false
        internal var containsCodeFence = false
        internal var lineCount = 0

        fun hasCodeFence(): Boolean {
            if (!codeFenceKnown) {
                containsCodeFence = plain().contains("```")
                codeFenceKnown = true
            }
            return containsCodeFence
        }

        /** Number of lines, from the cached plain text. */
        fun countLines(): Int {
            if (lineCount == 0) lineCount = plain().count { it == '\n' } + 1
            return lineCount
        }
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
        val sameContent = previous != null && chunks.isNotEmpty() &&
            (previous === text || text.hashCode() == previous.hashCode() && text.toString() == previous.toString())
        if (sameContent) return
        val ranges = ReadingWindow.chunkRanges(text)
        chunks = ranges.map { range ->
            Chunk(range.first, range.last + 1, text.subSequence(range.first, range.last + 1) as Spanned)
        }.toMutableList()
        mounted = IntRange.EMPTY
        container.removeAllViews()
        averageHeight = estimateChunkHeight(scrollView, newStyle)
        invalidateHeights()
        for (chunk in chunks) {
            container.addView(spacerView(container.context, averageHeight), chunkLayout())
        }
    }

    /** Paints [matches] on the mounted chunks. Chunks off screen get them when they mount. */
    fun applySearch(matches: SearchMatches?, activeIndex: Int, palette: SearchPalette) {
        if (matches === search && activeIndex == searchIndex && palette === searchPalette) return
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
        val after = offsetOfChunk(next.first)
        val shift = after - before
        if (shift != 0) {
            // With one shared height table a remount no longer changes the content above the
            // viewport, so this should never fire; kept as a net: if a height ever changes
            // under the viewport, keep the text under the finger where it was.
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
            val existing = container.getChildAt(index)
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
            } else if (!shouldMount && existing !== null && existing.minimumHeight != heights[index]) {
                // The estimate improved (more chunks measured): resize spacers still on screen,
                // so the laid-out content keeps matching the table every position is computed from.
                existing.minimumHeight = heights[index]
                existing.requestLayout()
            } else if (shouldMount) {
                (chunk.view as? ChunkBody)?.restyle(current)
            }
        }
    }

    /** Copies laid-out heights back onto their chunks and refreshes the per-chunk estimate. */
    private fun recordMeasuredHeights() {
        var measured = 0
        var count = 0
        var changed = false
        for ((index, chunk) in chunks.withIndex()) {
            val view = chunk.view ?: continue
            if (view.height > 0 && view.height != chunk.measuredHeight) {
                chunk.measuredHeight = view.height
                changed = true
            }
            if (view.height > 0) {
                measured += view.height
                count++
            }
            if (container.getChildAt(index) !== view && chunk.measuredHeight > 0) {
                container.getChildAt(index)?.layoutParams?.height = chunk.measuredHeight
            }
        }
        if (count > 0) averageHeight = measured / count
        if (changed) invalidateHeights()
    }

    /**
     * The height of every chunk: measured where known, the running average elsewhere. Cached
     * until a measurement or remount invalidates it — the scroll listener asks every frame.
     */
    private var heightsCache: IntArray? = null
    private var prefixCache: IntArray? = null

    /**
     * Estimated height of an unmeasured chunk. A chunk that contains code fences estimates from
     * its line count — code lines are short and hard-wrapped, so a characters-per-height model
     * badly overstates them — and everything else from the document's measured density.
     */
    private fun estimatedHeight(chunk: Chunk, perChar: Float): Int {
        if (chunk.hasCodeFence()) {
            val lineHeight = style?.typography?.let { it.fontSizeSp * it.lineHeight * it.density } ?: 0f
            if (lineHeight > 0f) return (chunk.countLines() * lineHeight).toInt().coerceAtLeast(MIN_CHUNK_HEIGHT)
        }
        return ((chunk.end - chunk.start) * perChar).toInt().coerceAtLeast(1)
    }

    /**
     * The height of every chunk — and the single source of truth for it: both the spacer a
     * chunk occupies while unmounted and every scroll-position computation read here, so a
     * computed offset always means the same place in the laid-out content.
     *
     * Measured where a chunk has laid out; otherwise estimated from the document's measured
     * pixels-per-character, with code-fenced chunks estimated from their line count. The
     * flat average is only used before anything has been measured at all.
     */
    internal fun measuredHeights(): IntArray {
        heightsCache?.let { return it }
        var pixels = 0
        var chars = 0
        for (chunk in chunks) {
            if (chunk.measuredHeight <= 0) continue
            pixels += chunk.measuredHeight
            chars += chunk.end - chunk.start
        }
        val perChar = if (chars > 0) pixels.toFloat() / chars else 0f
        val fresh = IntArray(chunks.size) { index ->
            val chunk = chunks[index]
            chunk.measuredHeight.takeIf { it > 0 }
                ?: if (perChar > 0f) estimatedHeight(chunk, perChar) else averageHeight
        }
        heightsCache = fresh
        return fresh
    }

    /** Content height above each chunk, cached with [measuredHeights]. O(1) per lookup. */
    internal fun offsetOfChunk(index: Int): Int {
        prefixCache?.let { return it[index] }
        val heights = measuredHeights()
        val prefix = IntArray(heights.size + 1)
        for (i in heights.indices) prefix[i + 1] = prefix[i] + heights[i]
        prefixCache = prefix
        return prefix[index]
    }

    private fun invalidateHeights() {
        heightsCache = null
        prefixCache = null
    }

    /** The index of the chunk that contains [offset], by binary search over the starts. */
    private fun chunkIndex(offset: Int): Int {
        if (chunks.isEmpty()) return -1
        if (offset < chunks.first().start) return 0
        if (offset >= chunks.last().end) return chunks.lastIndex
        var low = 0
        var high = chunks.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val chunk = chunks[mid]
            if (offset < chunk.start) high = mid - 1
            else if (offset >= chunk.end) low = mid + 1
            else return mid
        }
        return chunks.lastIndex
    }

    /**
     * The scroll offset that puts rendered-text offset [offset] at the top of the viewport.
     *
     * Exact when the chunk holding it is mounted and laid out. Otherwise the unified height
     * table in [measuredHeights] — the same one the spacers are built from — supplies the
     * estimate, so the computed offset matches the content actually laid out. [callback] runs
     * once the estimate has had a chance to be corrected.
     */
    fun scrollYFor(offset: Int, callback: (() -> Unit)? = null): Int {
        val index = chunkIndex(offset)
        if (index < 0) return 0
        val chunk = chunks[index]
        val local = (offset - chunk.start).coerceIn(0, (chunk.end - chunk.start).coerceAtLeast(1) - 1)

        val mounted = chunk.view
        if (mounted != null) {
            val lines = segmentLines(mounted)
            if (lines != null && lines.textLength > 0) {
                val base = offsetOfChunk(index)
                lastAnswerWasEstimate = false
                return base + lines.getLineTop(lines.getLineForOffset(local))
            }
        }
        // Not mounted, or mounted but not yet laid out: estimate, then ask again at most
        // [MAX_REFINES] times over at most [REFINE_BUDGET_MS]. Each pass mounts the target
        // chunk closer, and the loop always terminates: it gives up and accepts the estimate.
        val heights = measuredHeights()
        val base = offsetOfChunk(index)
        val callback0 = callback
        if (callback0 != null && !refinePending) {
            if (refineCount < MAX_REFINES &&
                android.os.SystemClock.elapsedRealtime() - refineStartedAt < REFINE_BUDGET_MS
            ) {
                refinePending = true
                refineCount += 1
                container.post {
                    refinePending = false
                    callback0()
                }
            }
        }
        lastAnswerWasEstimate = true
        return base + heights[index] * local / (chunk.end - chunk.start).coerceAtLeast(1)
    }

    /** Cancels the refine budget for the previous jump and arms one for a new request. */
    fun beginJump() {
        refineCount = 0
        refineStartedAt = android.os.SystemClock.elapsedRealtime()
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
            val bottom = top + heights[index]
            val lines = chunk.view?.let { segmentLines(it) }
            if (lines != null) {
                result = latestHeading(chunk, lines, headings, top, limit)
            } else if (bottom > limit) {
                // Unmounted and already past the viewport: nothing later can beat [result].
                break
            }
            top = bottom
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

/** How many times an estimated jump may re-ask for its exact position. */
private const val MAX_REFINES = 8

/** Total wall-clock budget for one jump's refinements, after which the estimate stands. */
private const val REFINE_BUDGET_MS = 1200L

private const val CHAR_WIDTH_EM = 0.5f
private const val MIN_CHARS_PER_LINE = 20
private const val MIN_CHUNK_HEIGHT = 48

