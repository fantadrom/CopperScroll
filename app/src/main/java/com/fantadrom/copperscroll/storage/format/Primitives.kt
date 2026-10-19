package com.fantadrom.copperscroll.storage.format

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Stable, content-free errors; no input or library exception is attached. */
enum class Failure { CorruptData, CorruptHeader, UnsupportedVersion, UnsupportedLimit, ResourceLimit, ValidationError }
class FormatException(val failure: Failure) : Exception(failure.name)
internal fun checkFormat(ok: Boolean, code: Failure = Failure.CorruptData) {
    if (!ok) throw FormatException(code)
}

/** Immutable bytes with value equality and deliberately redacted diagnostics. */
class Bytes(bytes: ByteArray) : Comparable<Bytes> {
    private val value = bytes.clone()
    val size: Int get() = value.size
    fun copy(): ByteArray = value.clone()
    override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun compareTo(other: Bytes): Int {
        for (i in 0 until minOf(size, other.size)) {
            val c = (value[i].toInt() and 255).compareTo(other.value[i].toInt() and 255)
            if (c != 0) return c
        }
        return size.compareTo(other.size)
    }
    override fun toString(): String = "Bytes(size=$size)"
}
internal fun Bytes.sized(n: Int): Bytes { checkFormat(size == n); return this }
internal fun Bytes.id(n: Int): Bytes { sized(n); checkFormat(copy().any { it != 0.toByte() }); return this }
internal fun sha(bytes: ByteArray) = Bytes(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun utf8(text: String): ByteArray {
    checkFormat(validUtf16(text), Failure.ValidationError)
    return text.toByteArray(Charsets.UTF_8)
}
fun validUtf16(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        if (c.isHighSurrogate()) {
            if (i == s.length || !s[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}
internal fun decodeUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, length)).toString()
} catch (_: java.nio.charset.CharacterCodingException) { throw FormatException(Failure.CorruptData) }
internal fun checkedEnd(offset: Long, length: Long, limit: Long): Long {
    checkFormat(offset >= 131072 && offset % 8 == 0L && length > 0 && offset <= limit && length <= limit - offset)
    return offset + length
}
object Limits {
    const val HEADER = 65536
    const val PAGE = 16384
    const val CHUNK = 1048576
    const val NOTE = 16 * CHUNK
    const val FILE_MAX = 1099511627776L
}
