package com.freeftp.core.preview

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** What a file turned out to be when we looked inside it. */
sealed interface FilePreview {

    /** The file decoded cleanly and can be shown. */
    data class Text(
        val content: String,
        val charsetName: String,
        val lineCount: Int,
        val bytesShown: Long,
        /** True when the file was longer than the preview limit and was cut short. */
        val truncated: Boolean,
    ) : FilePreview

    /** The file is not text; showing it would be a screen of noise. */
    data class Binary(val bytesInspected: Long) : FilePreview
}

/**
 * Decides whether bytes are text and, if so, in which encoding.
 *
 * There is no reliable way to *know* a file's encoding — the bytes do not say — so this
 * follows the same order of evidence that editors and `file(1)` use: an explicit byte
 * order mark, then whether the bytes are valid UTF-8 (a strong signal, since arbitrary
 * binary almost never is), then a single-byte fallback that cannot fail. Guessing badly
 * here is what produces mojibake, so the charset actually used is reported back for the
 * UI to show.
 */
object TextPreview {

    /** How much of a file to pull for a preview. Big enough for any config or log tail. */
    const val DEFAULT_LIMIT_BYTES: Long = 1L * 1024 * 1024

    /** Only the start of a file is examined, as `file(1)` and git do. */
    private const val SNIFF_BYTES = 8_000

    /** Above this share of control characters, the content is noise rather than prose. */
    private const val CONTROL_CHARACTER_LIMIT = 0.30

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** Classifies [bytes], which may be the leading [truncated] portion of a longer file. */
    fun of(bytes: ByteArray, truncated: Boolean = false): FilePreview {
        if (looksBinary(bytes)) return FilePreview.Binary(bytes.size.toLong())
        val decoded = decode(bytes)
        return FilePreview.Text(
            content = decoded.text,
            charsetName = decoded.charsetName,
            lineCount = lineCount(decoded.text),
            bytesShown = bytes.size.toLong(),
            truncated = truncated,
        )
    }

    /**
     * True when [bytes] should not be shown as text.
     *
     * A `NUL` byte is the classic marker, but UTF-16 text is half `NUL` bytes, so a byte
     * order mark is checked first — otherwise every UTF-16 file would be called binary.
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (startsWith(bytes, UTF16LE_BOM) || startsWith(bytes, UTF16BE_BOM)) return false

        val window = minOf(bytes.size, SNIFF_BYTES)
        var controlCharacters = 0
        for (index in 0 until window) {
            val byte = bytes[index].toInt() and 0xFF
            if (byte == 0x00) return true
            if (isControlCharacter(byte)) controlCharacters++
        }
        return controlCharacters.toDouble() / window > CONTROL_CHARACTER_LIMIT
    }

    /**
     * Anything below space that is not ordinary formatting, plus DEL. High bytes are
     * deliberately not counted: in a single-byte encoding they are simply accented letters.
     */
    private fun isControlCharacter(byte: Int): Boolean = when (byte) {
        0x09, 0x0A, 0x0C, 0x0D, 0x1B -> false // tab, newline, form feed, return, escape
        else -> byte < 0x20 || byte == 0x7F
    }

    internal data class Decoded(val text: String, val charsetName: String)

    internal fun decode(bytes: ByteArray): Decoded {
        when {
            startsWith(bytes, UTF8_BOM) ->
                return Decoded(String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8), "UTF-8")

            startsWith(bytes, UTF16LE_BOM) ->
                return Decoded(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE), "UTF-16LE")

            startsWith(bytes, UTF16BE_BOM) ->
                return Decoded(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE), "UTF-16BE")
        }
        strictUtf8(bytes)?.let { return Decoded(it, "UTF-8") }
        // ISO-8859-1 maps every possible byte to a character, so this cannot fail. It is
        // the honest fallback: the text may be wrong, but nothing is lost or replaced.
        return Decoded(String(bytes, StandardCharsets.ISO_8859_1), "ISO-8859-1")
    }

    /** Decodes as UTF-8, or null if the bytes are not valid UTF-8. */
    private fun strictUtf8(bytes: ByteArray): String? {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Lines as an editor counts them: a trailing newline does not add an empty last line. */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        val newlines = text.count { it == '\n' }
        return if (text.endsWith('\n')) newlines else newlines + 1
    }

    /**
     * Splits [text] into lines ready to render, hard-wrapping any line longer than
     * [maxLineLength].
     *
     * A minified script or a single-line JSON dump is one line a megabyte long. Handing
     * that to a text view as a single string is what turns a viewer into a frozen screen,
     * so over-long lines are broken up for display. `\r` is dropped so CRLF files do not
     * render a stray glyph at every line end.
     */
    fun displayLines(text: String, maxLineLength: Int = 2_000): List<String> {
        require(maxLineLength > 0) { "the display line length must be positive" }
        if (text.isEmpty()) return emptyList()
        // A file ending in a newline has no phantom empty last line, matching [lineCount]
        // and every editor's line numbering.
        val body = if (text.endsWith('\n')) text.dropLast(1) else text
        return body.split('\n').flatMap { line ->
            val clean = line.removeSuffix("\r")
            when {
                clean.length <= maxLineLength -> listOf(clean)
                else -> clean.chunked(maxLineLength)
            }
        }
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { bytes[it] == prefix[it] }
    }
}
