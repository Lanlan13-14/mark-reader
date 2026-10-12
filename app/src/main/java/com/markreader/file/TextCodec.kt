package com.markreader.file

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * An encoding the app can both read and write back byte-for-byte.
 *
 * The byte-order mark is part of the encoding rather than something stripped and forgotten:
 * a file that arrived with one is rewritten with one.
 */
enum class TextEncoding(val charset: Charset, val byteOrderMark: ByteArray) {
    Utf8(Charsets.UTF_8, byteArrayOf()),
    Utf8WithBom(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    Utf16LittleEndian(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    Utf16BigEndian(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte()))
}

/**
 * Decoded document text, and the encoding to write it back with.
 *
 * **A null [encoding] means the text was recovered lossily** — the bytes were not valid in any
 * encoding we handle, so they were forced through UTF-8 and unmappable sequences became
 * replacement characters. The text is fine to display. Writing it back is not: the replacements
 * would be saved over whatever the original bytes were. Callers that write must treat null as a
 * refusal, not as "assume UTF-8".
 */
data class DecodedText(val text: String, val encoding: TextEncoding?)

/**
 * Bytes to text and back, without losing the encoding in between.
 *
 * The read path was already right about byte-order marks; the write path assumed UTF-8. That
 * gap is the bug this exists to close — open a UTF-16 file, change one character, save, and the
 * file used to come back as UTF-8 with its mark gone.
 */
object TextCodec {

    fun decode(bytes: ByteArray): DecodedText {
        // Before anything else, because a UTF-32LE mark opens with the UTF-16LE one and would
        // otherwise be read as UTF-16 — every fourth byte a character, the rest NULs. We do not
        // handle UTF-32, so say so rather than mangle it.
        if (UNSUPPORTED_BYTE_ORDER_MARKS.any(bytes::startsWith)) return lossy(bytes)

        val encoding = TextEncoding.entries.firstOrNull {
            it.byteOrderMark.isNotEmpty() && bytes.startsWith(it.byteOrderMark)
        } ?: TextEncoding.Utf8

        // No mark and a NUL byte means this is not the UTF-8 it would otherwise be assumed to
        // be. Marked UTF-16 is full of NULs and must not be caught by this.
        if (encoding == TextEncoding.Utf8 && bytes.any { it == ZERO_BYTE }) return lossy(bytes)

        val text = bytes.decodeStrictly(encoding) ?: return lossy(bytes)
        return DecodedText(text, encoding)
    }

    fun encode(text: String, encoding: TextEncoding): ByteArray =
        encoding.byteOrderMark + text.toByteArray(encoding.charset)

    /** Readable at all costs, writable not at all. See [DecodedText.encoding]. */
    private fun lossy(bytes: ByteArray) = DecodedText(String(bytes, Charsets.UTF_8), null)

    private const val ZERO_BYTE: Byte = 0

    private val UNSUPPORTED_BYTE_ORDER_MARKS = listOf(
        byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()),
        byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
    )
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/**
 * Strict rather than lossy on purpose: silently substituting replacement characters is what
 * turns a read into an unnoticed rewrite later. A failure here is how the caller learns the
 * encoding guess was wrong.
 */
private fun ByteArray.decodeStrictly(encoding: TextEncoding): String? {
    val offset = encoding.byteOrderMark.size
    return try {
        encoding.charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this, offset, size - offset))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
