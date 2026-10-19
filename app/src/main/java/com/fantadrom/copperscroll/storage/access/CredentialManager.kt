package com.fantadrom.copperscroll.storage.access

import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import org.bouncycastle.util.Arrays
import java.security.MessageDigest

/** Envelope + descriptor MAC authenticated, NOT an open user session: root/graph checks are still due. */
class CredentialMatch internal constructor(val key: ZoneKey, val otherEnvelopeUnsupported: Boolean) : AutoCloseable {
    override fun close() = key.close()
    override fun toString() = "CredentialMatch(redacted)"
}

/** RAM-only current-secret verification ticket; explicit owner for old S1 and independently unwrapped ZDK. */
class CurrentCredential internal constructor(val key: ZoneKey, internal val descriptorFingerprint: Bytes,
                                            private val oldSecret: ByteArray) : AutoCloseable {
    private var closed = false
    private var consumed = false
    @Synchronized internal fun <T> withSecret(block: (ByteArray) -> T): T {
        requireCrypto(!closed && !consumed, CryptoFailure.Closed); return block(oldSecret)
    }
    @Synchronized internal fun consumeSecret() { oldSecret.fill(0); consumed = true }
    @Synchronized override fun close() { oldSecret.fill(0); key.close(); closed = true }
    override fun toString() = "CurrentCredential(redacted)"
}

/** Blocking, bounded crypto requests, without UI/IO. Supplied S1 handles remain caller-owned.
 * Operations copy S1 only after admission to the single worker and clear those copies in finally.
 */
class CredentialManager internal constructor(entropy: Entropy, argon: ArgonDerivation = BcArgon) {
    constructor() : this(SystemEntropy(), BcArgon)
    private val envelopes = EnvelopeCrypto(entropy, argon)
    private val records = RecordCrypto(entropy)

    fun create(container: Bytes, zone: Bytes, role: Int, inputMethod: Int, secret: CanonicalSecret,
               parameters: KdfParameters = KdfParameters(), cancel: CryptoCancellation = CryptoCancellation()): PreparedZone =
        KdfWorker.run(cancel) { submit ->
            val bytes = secret.copyBytes()
            try { envelopes.create(submit, container, zone, role, inputMethod, parameters, bytes) }
            finally { bytes.fill(0) }
        }

    /** Unwrap alone is NOT descriptor/root authentication. Tag failure means wrong secret OR damaged envelope. */
    fun unwrap(envelope: Envelope, secret: CanonicalSecret,
               cancel: CryptoCancellation = CryptoCancellation()): ZoneKey = KdfWorker.run(cancel) { submit ->
        envelope.validate()
        val bytes = secret.copyBytes()
        try { envelopes.open(submit, envelope, bytes) } finally { bytes.fill(0) }
    }

    fun login(container: Bytes, descriptors: List<Descriptor>, secret: CanonicalSecret,
              cancel: CryptoCancellation = CryptoCancellation()): CredentialMatch {
        requireCrypto(descriptors.size in 1..2)
        val snapshot = descriptors.toList()
        requireCrypto(snapshot.map { it.zoneId }.distinct().size == snapshot.size)
        requireCrypto(snapshot.map { it.role } == (1..snapshot.size).toList())
        return KdfWorker.run(cancel) { submit ->
            val matches = ArrayList<Pair<ZoneKey, Descriptor>>(2); val bytes = secret.copyBytes()
            var unsupported: CryptoFailure? = null
            try {
                for (descriptor in snapshot) {
                    try {
                        val opened = envelopes.open(submit, Envelope.fromDescriptor(container, descriptor), bytes)
                        try { matches.add(opened to descriptor) } catch (e: Throwable) { opened.close(); throw e }
                    }
                    catch (e: CryptoException) {
                        when (e.failure) {
                            CryptoFailure.CredentialMismatch -> Unit
                            CryptoFailure.UnsupportedKdf, CryptoFailure.UnsupportedSecretEncoding,
                            CryptoFailure.InvalidParameters -> unsupported = e.failure
                            else -> throw e
                        }
                    }
                }
                requireCrypto(matches.size < 2, CryptoFailure.AmbiguousCredential)
                if (matches.isEmpty()) throw CryptoException(unsupported ?: CryptoFailure.CredentialMismatch)
                val (key, descriptor) = matches.single()
                records.verifyDescriptor(key, descriptor)
                submit.check()
                val match = CredentialMatch(key, unsupported != null)
                matches.clear() // Transfer the sole handle to the result owner.
                match
            } finally { bytes.fill(0); matches.forEach { it.first.close() } }
        }
    }

    /** Service prompts verify exactly the requested current zone, never a generic successful login. */
    fun verifyCurrent(container: Bytes, descriptor: Descriptor, expectedZone: Bytes, expectedRole: Int,
                      secret: CanonicalSecret, existing: ZoneKey? = null,
                      cancel: CryptoCancellation = CryptoCancellation()): CurrentCredential {
        requireCrypto(descriptor.zoneId == expectedZone && descriptor.role == expectedRole, CryptoFailure.CredentialMismatch)
        if (existing != null) requireCrypto(existing.containerId == container && existing.zoneId == expectedZone &&
            existing.role == expectedRole, CryptoFailure.CredentialMismatch)
        return KdfWorker.run(cancel) { submit ->
            val bytes = secret.copyBytes(); var key: ZoneKey? = null
            try {
                val opened = envelopes.open(submit, Envelope.fromDescriptor(container, descriptor), bytes); key = opened
                records.verifyDescriptor(opened, descriptor)
                if (existing != null) requireCrypto(opened.matches(existing), CryptoFailure.CorruptData)
                submit.check()
                CurrentCredential(opened, fingerprint(descriptor), bytes).also { key = null }
            } catch (e: Throwable) { bytes.fill(0); throw e }
            finally { key?.close() }
        }
    }

    /** Prepare only: no file, backup, rewrap-current-key option, or claim that a password was changed.
     * Caller must have parsed/current descriptors and reject any already-known corruption of the other zone.
     * A failed other tag establishes inequality only for that current representation, not its health.
     */
    fun prepareChange(current: CurrentCredential, descriptor: Descriptor, newSecret: CanonicalSecret,
                      repeated: CanonicalSecret, inputMethod: Int, other: Descriptor? = null,
                      otherKnownCorrupt: Boolean = false, parameters: KdfParameters = KdfParameters(),
                      cancel: CryptoCancellation = CryptoCancellation()): PreparedZone {
        requireCrypto(!otherKnownCorrupt, CryptoFailure.CorruptData)
        requireCrypto(inputMethod in 1..4)
        requireCrypto(current.descriptorFingerprint == fingerprint(descriptor), CryptoFailure.CorruptData)
        requireCrypto(descriptor.zoneId == current.key.zoneId && descriptor.role == current.key.role)
        if (other != null) requireCrypto(other.zoneId != descriptor.zoneId && other.role != descriptor.role)
        parameters.validate()
        return KdfWorker.run(cancel) { submit ->
            current.withSecret { old ->
                val bytes = newSecret.copyBytes()
                try {
                    val repeat = repeated.copyBytes()
                    try { requireCrypto(Arrays.constantTimeAreEqual(bytes, repeat), CryptoFailure.SecretMismatch) }
                    finally { repeat.fill(0) }
                    requireCrypto(!Arrays.constantTimeAreEqual(old, bytes), CryptoFailure.UnchangedSecret)
                    if (other != null) {
                        var matched: ZoneKey? = null
                        try { matched = envelopes.open(submit, Envelope.fromDescriptor(current.key.containerId, other), bytes) }
                        catch (e: CryptoException) { if (e.failure != CryptoFailure.CredentialMismatch) throw e }
                        matched?.use {
                            records.verifyDescriptor(it, other)
                            throw CryptoException(CryptoFailure.AmbiguousCredential)
                        }
                    }
                    val prepared = envelopes.create(submit, current.key.containerId, descriptor.zoneId,
                        descriptor.role, inputMethod, parameters, bytes)
                    try {
                        requireCrypto(!prepared.key.matches(current.key), CryptoFailure.Collision)
                        submit.check(); current.consumeSecret(); prepared
                    } catch (e: Throwable) { prepared.close(); throw e }
                } finally { bytes.fill(0) }
            }
        }
    }

    private fun fingerprint(descriptor: Descriptor) = Bytes(MessageDigest.getInstance("SHA-256")
        .digest(DescriptorCodec.logicalBytes(descriptor) + descriptor.logicalMac.copy()))
}
