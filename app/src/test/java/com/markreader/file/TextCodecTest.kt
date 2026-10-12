package com.markreader.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCodecTest {

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    @Test
    fun `plain utf-8 decodes without a mark`() {
        val decoded = TextCodec.decode("# Heading\n".toByteArray(Charsets.UTF_8))
        assertEquals("# Heading\n", decoded.text)
        assertEquals(TextEncoding.Utf8, decoded.encoding)
    }

    @Test
    fun `the byte-order mark is stripped from the text but kept as the encoding`() {
        val decoded = TextCodec.decode(utf8Bom + "hello".toByteArray(Charsets.UTF_8))
        assertEquals("hello", decoded.text)
        assertEquals(TextEncoding.Utf8WithBom, decoded.encoding)
    }

    @Test
    fun `utf-16 in both byte orders`() {
        val le = TextCodec.decode(utf16LeBom + "hello".toByteArray(Charsets.UTF_16LE))
        assertEquals("hello", le.text)
        assertEquals(TextEncoding.Utf16LittleEndian, le.encoding)

        val be = TextCodec.decode(utf16BeBom + "hello".toByteArray(Charsets.UTF_16BE))
        assertEquals("hello", be.text)
        assertEquals(TextEncoding.Utf16BigEndian, be.encoding)
    }

    /** The bug this class exists for: edit one character, save, and the file stays UTF-16. */
    @Test
    fun `every encoding survives a decode-encode round trip`() {
        TextEncoding.entries.forEach { encoding ->
            val original = TextCodec.encode("# Notes\n\nBody text.\n", encoding)
            val decoded = TextCodec.decode(original)
            assertEquals("$encoding was not recovered", encoding, decoded.encoding)
            assertArrayEquals(
                "round trip changed the bytes for $encoding",
                original,
                TextCodec.encode(decoded.text, decoded.encoding!!)
            )
        }
    }

    @Test
    fun `an edit is written back in the encoding the file arrived in`() {
        val original = TextCodec.encode("hello", TextEncoding.Utf16LittleEndian)
        val decoded = TextCodec.decode(original)
        val saved = TextCodec.encode(decoded.text + "!", decoded.encoding!!)

        assertArrayEquals(utf16LeBom, saved.copyOfRange(0, 2))
        assertEquals("hello!", TextCodec.decode(saved).text)
    }

    /**
     * A UTF-32LE mark opens with the UTF-16LE one. Matching the shorter mark first would read
     * the file as UTF-16 and hand back NUL-riddled text.
     */
    @Test
    fun `utf-32 is refused rather than mistaken for utf-16`() {
        val utf32Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00) +
            byteArrayOf(0x41, 0x00, 0x00, 0x00)
        assertNull(TextCodec.decode(utf32Le).encoding)

        val utf32Be = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()) +
            byteArrayOf(0x00, 0x00, 0x00, 0x41)
        assertNull(TextCodec.decode(utf32Be).encoding)
    }

    @Test
    fun `unmarked bytes with a NUL are not claimed as utf-8`() {
        assertNull(TextCodec.decode(byteArrayOf(0x41, 0x00, 0x42)).encoding)
    }

    @Test
    fun `invalid utf-8 is readable but not writable`() {
        // 0xFF never appears in valid UTF-8.
        val decoded = TextCodec.decode(byteArrayOf(0x41, 0xFF.toByte(), 0x42))
        assertNull("lossy text must not claim an encoding", decoded.encoding)
        assertEquals("the text is still shown", 3, decoded.text.length)
    }

    @Test
    fun `an empty document is valid utf-8`() {
        val decoded = TextCodec.decode(ByteArray(0))
        assertEquals("", decoded.text)
        assertEquals(TextEncoding.Utf8, decoded.encoding)
    }

    @Test
    fun `a lone byte-order mark decodes to nothing`() {
        assertEquals("", TextCodec.decode(utf16LeBom).text)
        assertEquals(TextEncoding.Utf16LittleEndian, TextCodec.decode(utf16LeBom).encoding)
    }

    @Test
    fun `non-ascii survives every encoding`() {
        val text = "caf\u00e9 \u2014 \u65e5\u672c\u8a9e"
        TextEncoding.entries.forEach { encoding ->
            val decoded = TextCodec.decode(TextCodec.encode(text, encoding))
            assertEquals("$encoding lost characters", text, decoded.text)
        }
    }
}
