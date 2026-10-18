package com.fantadrom.copperscroll.storage.access

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.VersionInfo
import com.fantadrom.copperscroll.storage.format.validUtf16
import java.security.MessageDigest

class SecretEncodingException(val code: Code) : Exception(code.name) {
    enum class Code { InvalidSecret, UnsupportedSecretEncoding }
}
/** Owned temporary bytes. Callers must close; copies are their responsibility. No value in toString. */
class CanonicalSecret internal constructor(private val value: ByteArray) : AutoCloseable {
    private var closed = false
    fun copyBytes(): ByteArray { check(!closed); return value.clone() }
    fun sameAs(other: CanonicalSecret): Boolean {
        check(!closed && !other.closed)
        return MessageDigest.isEqual(value, other.value)
    }
    override fun close() { value.fill(0); closed = true }
    override fun toString() = "CanonicalSecret(redacted)"
}
object SecretEncoding {
    const val UNICODE_VERSION = "16.0.0"
    private fun invalid(): Nothing = throw SecretEncodingException(SecretEncodingException.Code.InvalidSecret)
    private fun unsupported(): Nothing = throw SecretEncodingException(SecretEncodingException.Code.UnsupportedSecretEncoding)
    private val nfc: Normalizer2? by lazy {
        try {
            if (!supportsData(UCharacter.getUnicodeVersion())) null
            else Normalizer2.getNFCInstance().takeIf { it.normalize("\uD804\uDF82\uD804\uDFC9") == "\uD804\uDF83" }
        } catch (_: LinkageError) { null } catch (_: RuntimeException) { null }
    }
    internal fun supportsData(version: VersionInfo): Boolean = version == VersionInfo.getInstance(16, 0, 0, 0)
    fun verifyDataVersion(): Boolean = nfc != null
    /** For full UAX15 corpus conformance only; repertoire restrictions belong to text(). */
    internal fun normalizeUnicode16(text: String): String {
        if (!validUtf16(text)) invalid()
        return (nfc ?: unsupported()).normalize(text)
    }
    fun pin4(input: String) = pin(input, 4)
    fun pin6(input: String) = pin(input, 6)
    private fun pin(input: String, size: Int): CanonicalSecret {
        if (input.length != size || input.any { it !in '0'..'9' }) invalid()
        return CanonicalSecret(input.toByteArray(Charsets.US_ASCII))
    }
    fun text(input: String): CanonicalSecret = textUsing(input, nfc)
    internal fun textUsing(input: String, backend: Normalizer2?): CanonicalSecret {
        val normalizer = backend ?: unsupported()
        repertoire(input, 1024, 4096)
        val normalized = normalizer.normalize(input)
        repertoire(normalized, 256, 1024)
        if (normalized.isEmpty()) invalid()
        return CanonicalSecret(normalized.toByteArray(Charsets.UTF_8))
    }
    /** Strict bytes entry point: malformed UTF8 never becomes replacement characters. */
    fun textUtf8(input: ByteArray): CanonicalSecret {
        if (input.size > 4096) invalid()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(input)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
        return text(text)
    }
    private fun repertoire(input: String, points: Int, bytes: Int) {
        // UTF16 bound precedes encoding/allocation even for huge caller strings.
        if (input.length > points * 2 || !validUtf16(input) || input.codePointCount(0, input.length) > points) invalid()
        if (input.toByteArray(Charsets.UTF_8).size > bytes) invalid()
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val category = UCharacter.getType(cp)
            if (cp == 0 || cp == 10 || cp == 13 || category == UCharacterCategory.UNASSIGNED.toInt() || category == UCharacterCategory.SURROGATE.toInt()) invalid()
            i += Character.charCount(cp)
        }
    }
    fun pattern(hits: List<Int>): CanonicalSecret {
        val selected = BooleanArray(10)
        val accepted = ArrayList<Int>(9)
        fun add(p: Int) { if (!selected[p]) { selected[p] = true; accepted.add(p) } }
        for (p in hits) {
            if (p !in 1..9) invalid()
            if (selected[p]) continue // Must precede midpoint processing.
            if (accepted.isNotEmpty()) {
                val a = accepted.last() - 1; val b = p - 1
                val sx = a % 3 + b % 3; val sy = a / 3 + b / 3
                if (sx % 2 == 0 && sy % 2 == 0) add((sy / 2) * 3 + sx / 2 + 1)
            }
            add(p)
        }
        if (accepted.size !in 4..9) invalid()
        return CanonicalSecret(ByteArray(accepted.size) { ('0'.code + accepted[it]).toByte() })
    }
}
