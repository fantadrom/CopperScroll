package com.fantadrom.copperscroll.storage.crypto

import com.fantadrom.copperscroll.storage.format.Bytes
import org.bouncycastle.util.Arrays
import java.security.SecureRandom

/** Exclusive owner. close waits for any synchronous borrow; no raw ZDK export in the public API. */
class ZoneKey internal constructor(val containerId: Bytes, val zoneId: Bytes, val role: Int,
                                   private val key: ByteArray) : AutoCloseable {
    private var closed = false
    init { validateIdentity(containerId, zoneId, role); requireCrypto(key.size == 32) }
    @Synchronized internal fun ensureOpen() { requireCrypto(!closed, CryptoFailure.Closed) }
    /** Only derived subkeys leave this class, within a bounded operation; the ZDK is never passed out. */
    @Synchronized internal fun <T> withDerivedKey(info: ByteArray, block: (ByteArray) -> T): T {
        ensureOpen()
        val derived = CryptoPrimitives.hkdf(key, containerId.copy(), info)
        try { return block(derived) } finally { derived.fill(0) }
    }
    @Synchronized override fun close() { key.fill(0); closed = true }
    override fun toString() = "ZoneKey(redacted)"
    internal fun matches(other: ZoneKey): Boolean {
        // Never acquire two handles in reverse order: copy only an operation-owned comparison buffer.
        val copy = copyForComparison()
        return try { other.compare(copy) } finally { copy.fill(0) }
    }
    @Synchronized private fun copyForComparison(): ByteArray { ensureOpen(); return key.clone() }
    @Synchronized private fun compare(copy: ByteArray): Boolean { ensureOpen(); return Arrays.constantTimeAreEqual(copy, key) }
}

/** Authenticated plaintext owner; callers own and must clear any copy they request. */
class Plaintext internal constructor(private val bytes: ByteArray) : AutoCloseable {
    private var closed = false
    @Synchronized fun copyBytes(): ByteArray { requireCrypto(!closed, CryptoFailure.Closed); return bytes.clone() }
    @Synchronized override fun close() { bytes.fill(0); closed = true }
    override fun toString() = "Plaintext(redacted)"
}

internal fun validateIdentity(container: Bytes, zone: Bytes, role: Int) {
    requireCrypto(container.size == 16 && container.copy().any { it != 0.toByte() })
    requireCrypto(zone.size == 16 && zone.copy().any { it != 0.toByte() } && role in 1..2)
}

object ZoneKeys {
    /** Fresh independent random ZDK; no device key, common verifier, or arbitrary key import/export. */
    fun generate(container: Bytes, zone: Bytes, role: Int): ZoneKey {
        validateIdentity(container, zone, role)
        val bytes = SystemEntropy().bytes(32)
        try { return ZoneKey(container, zone, role, bytes) } catch (e: Throwable) { bytes.fill(0); throw e }
    }
}

internal fun interface Entropy { fun fill(destination: ByteArray) }
internal class SystemEntropy : Entropy {
    private val rng = SecureRandom()
    override fun fill(destination: ByteArray) { rng.nextBytes(destination) }
}
internal fun Entropy.bytes(size: Int, nonzero: Boolean = false): ByteArray {
    val bytes = ByteArray(size)
    try {
        fill(bytes)
        requireCrypto(!nonzero || bytes.any { it != 0.toByte() }, CryptoFailure.RngFailure)
        return bytes
    } catch (_: Exception) {
        bytes.fill(0); throw CryptoException(CryptoFailure.RngFailure)
    }
}
