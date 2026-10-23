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
 *
 * All of which applies to the surfaces the app renders itself. A document drawn by the shared
 * renderer has its rendering inside the page, and gets no headings from here at all.
 */
class MarkdownHeadingsTest {

    private val format = MarkdownFormat { error("no renderer is needed for these assertions") }

    /**
     * Defaults the rendering to the source, for the cases that are about *which* lines are
     * headings rather than where they land. Only [ReaderContent.Spans] carries offsets at all,
     * so there is nothing else these could be passed.
     */
    private fun headings(markdown: String, rendered: String = markdown): List<HeadingItem> =
        format.headings(markdown, ReaderContent.Spans(FakeSpanned(rendered)))

    @Test
    fun `levels one to three are navigation, deeper ones are not`() {
        val result = headings("# One\n## Two\n### Three\n#### Four\n##### Five")

        assertEquals(listOf("One", "Two", "Three"), result.map { it.text })
        assertEquals(listOf(1, 2, 3), result.map { it.level })
    }

    @Test
    fun `a heading with no text is not a heading`() {
        assertEquals(emptyList<HeadingItem>(), headings("#\n##   \nbody"))
    }

    @Test
    fun `leading whitespace does not hide a heading`() {
        assertEquals(listOf("Indented"), headings("   ## Indented").map { it.text })
    }

    @Test
    fun `a hash inside a line is not a heading`() {
        assertEquals(emptyList<HeadingItem>(), headings("see issue #42 for detail"))
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

    /**
     * The WebView path. Offsets index a rendering that lives in the page, so this side has none
     * to give — and a table of contents built from source offsets would scroll to the wrong
     * place. Absent beats wrong; rebuilding it where the rendering actually is, is planned.
     */
    @Test
    fun `a document rendered in the page has no headings to offer out here`() {
        val source = "# One\nbody\n## Two\n"

        assertEquals(
            emptyList<HeadingItem>(),
            format.headings(source, ReaderContent.Markdown(source))
        )
    }
}
