package com.markreader.format

/**
 * One entry in a document's table of contents.
 *
 * [offset] is a character offset into the *rendered* text, because that is what the reader
 * scrolls. Only formats whose [FormatCapabilities.hasHeadings] is true produce these.
 */
data class HeadingItem(
    val text: String,
    val level: Int,
    val offset: Int
)
