package com.markreader.format.markdown

import com.markreader.format.HeadingItem
import com.markreader.format.ReaderContent
import com.markreader.ui.reader.FakeSpanned
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The table of contents, exercised through the format rather than the parser, because the offset
 * mapping is the part that goes wrong and it depends on the rendering being passed in.
 *
 * Offsets index the *rendered* text, since that is what the reader scrolls. Markwon eats the
 * `#` markers, so a source offset would be wrong by the accumulated width of every marker above
 * it — which is why this mapping exists at all.
 */
class MarkdownHeadingsTest {

    private val format = MarkdownFormat { error("no renderer is needed for these assertions") }

    private fun headings(markdown: String, rendered: String?): List<HeadingItem> = format.headings(
        markdown,
        if (rendered == null) {
            ReaderContent.Table(emptyList())
        } else {
            ReaderContent.Spans(FakeSpanned(rendered))
        }
    )

    @Test
    fun `levels one to three are navigation, deeper ones are not`() {
        val result = headings("# One\n## Two\n### Three\n#### Four\n##### Five", null)

        assertEquals(listOf("One", "Two", "Three"), result.map { it.text })
        assertEquals(listOf(1, 2, 3), result.map { it.level })
    }

    @Test
    fun `a heading with no text is not a heading`() {
        assertEquals(emptyList<HeadingItem>(), headings("#\n##   \nbody", null))
    }

    @Test
    fun `leading whitespace does not hide a heading`() {
        assertEquals(listOf("Indented"), headings("   ## Indented", null).map { it.text })
    }

    @Test
    fun `a hash inside a line is not a heading`() {
        assertEquals(emptyList<HeadingItem>(), headings("see issue #42 for detail", null))
    }

    @Test
    fun `offsets point into the rendered text, not the source`() {
        val result = headings("# Title\n\nBody text.\n\n## Section\n", "Title\n\nBody text.\n\nSection\n")

        assertEquals(listOf(0, 19), result.map { it.offset })
    }

    /**
     * The mapping is a forward scan for exactly this: a cursor that only moves forward keeps the
     * second "Usage" pointing at the second one rather than back at the first.
     */
    @Test
    fun `repeated heading text resolves to successive occurrences`() {
        val result = headings("## Usage\ntext\n## Usage\n", "Usage\ntext\nUsage\n")

        assertEquals(listOf(0, 11), result.map { it.offset })
    }

    @Test
    fun `a heading absent from the rendering falls back to a clamped source offset`() {
        // Markwon dropped the second heading entirely; the first still maps. The fallback is the
        // source offset (8) clamped into the rendering, which is shorter — scrolling past the end
        // is not a thing the reader can do, so the last character is as far as it goes.
        val result = headings("# Kept\n\n## Gone\n", "Kept\n\n")

        assertEquals(listOf(0, 5), result.map { it.offset })
    }

    @Test
    fun `with no rendering to map onto, offsets stay in the source`() {
        val result = headings("# One\nbody\n## Two\n", null)

        assertEquals(listOf(0, 11), result.map { it.offset })
    }
}
