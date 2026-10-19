package com.fantadrom.copperscroll.storage.crypto

import com.fantadrom.copperscroll.storage.format.Bytes
import com.fantadrom.copperscroll.storage.format.Descriptor
import com.fantadrom.copperscroll.storage.format.StrictCbor

/** Untrusted parameters are validated before starting BC or allocating its memory. */
class KdfParameters(val memoryKiB: Int = 65536, val iterations: Int = 3, val parallelism: Int = 1,
                    val kdfId: Int = 1, val argonVersion: Int = 19, val outputBytes: Int = 32) {
    fun validate() {
        requireCrypto(kdfId == 1 && argonVersion == 19, CryptoFailure.UnsupportedKdf)
        requireCrypto(memoryKiB in setOf(16384, 32768, 65536) && iterations in 2..4 &&
            parallelism == 1 && outputBytes == 32, CryptoFailure.UnsupportedKdf)
    }
}

class Envelope(val containerId: Bytes, val zoneId: Bytes, val role: Int, val inputMethod: Int,
               val parameters: KdfParameters, val salt: Bytes, val nonce: Bytes, val wrappedKey: Bytes,
               val envelopeVersion: Int = 1, val secretEncodingVersion: Int = 1) {
    fun validate() {
        validateIdentity(containerId, zoneId, role)
        requireCrypto(envelopeVersion == 1 && inputMethod in 1..4, CryptoFailure.InvalidParameters)
        requireCrypto(secretEncodingVersion == 1, CryptoFailure.UnsupportedSecretEncoding)
        parameters.validate()
        requireCrypto(salt.size == 16 && nonce.size == 12 && wrappedKey.size == 48)
    }
    internal fun aad(): ByteArray {
        validate()
        return StrictCbor.encode(listOf("CS-wrap-v1", containerId, zoneId, role, envelopeVersion,
            inputMethod, secretEncodingVersion, parameters.kdfId, parameters.argonVersion,
            parameters.memoryKiB, parameters.iterations, parameters.parallelism, salt))
    }
    companion object {
        fun fromDescriptor(containerId: Bytes, descriptor: Descriptor) = Envelope(containerId,
            descriptor.zoneId, descriptor.role, descriptor.inputMethod,
            KdfParameters(descriptor.memoryKiB, descriptor.iterations), descriptor.salt,
            descriptor.wrapNonce, descriptor.wrappedKey)
    }
}

/** Prepared in RAM only. This is NOT ChangeSecret committed and does not modify a descriptor/file. */
class PreparedZone internal constructor(val key: ZoneKey, val envelope: Envelope) : AutoCloseable {
    override fun close() = key.close()
    override fun toString() = "PreparedZone(redacted)"
}

internal class EnvelopeCrypto(private val entropy: Entropy, private val argon: ArgonDerivation) {
    fun open(submit: KdfWorker.Submit, envelope: Envelope, secret: ByteArray): ZoneKey {
        envelope.validate()
        val raw = submit.derive(secret, envelope, argon)
        try {
            val wrap = CryptoPrimitives.hkdf(raw, envelope.containerId.copy(),
                "CS-wrap-key-v1\u0000".toByteArray(Charsets.US_ASCII) + envelope.zoneId.copy())
            try {
                val key = CryptoPrimitives.aes(false, wrap, envelope.nonce.copy(), envelope.aad(), envelope.wrappedKey.copy())
                try { submit.check(); return ZoneKey(envelope.containerId, envelope.zoneId, envelope.role, key) }
                catch (e: Throwable) { key.fill(0); throw e }
            } finally { wrap.fill(0) }
        } finally { raw.fill(0) }
    }
    fun create(submit: KdfWorker.Submit, container: Bytes, zone: Bytes, role: Int, method: Int,
               parameters: KdfParameters, secret: ByteArray): PreparedZone {
        validateIdentity(container, zone, role); parameters.validate()
        requireCrypto(method in 1..4 && secret.size in 1..1024)
        val zdk = entropy.bytes(32)
        try {
            val salt = Bytes(entropy.bytes(16)); val nonce = Bytes(entropy.bytes(12))
            val pending = Envelope(container, zone, role, method, parameters, salt, nonce, Bytes(ByteArray(48)))
            val raw = submit.derive(secret, pending, argon)
            try {
                val wrap = CryptoPrimitives.hkdf(raw, container.copy(),
                    "CS-wrap-key-v1\u0000".toByteArray(Charsets.US_ASCII) + zone.copy())
                try {
                    val ciphertext = CryptoPrimitives.aes(true, wrap, nonce.copy(), pending.aad(), zdk)
                    // Verify the new envelope with the SAME derived key; no third Argon2 call.
                    val verified = CryptoPrimitives.aes(false, wrap, nonce.copy(), pending.aad(), ciphertext)
                    try { requireCrypto(org.bouncycastle.util.Arrays.constantTimeAreEqual(verified, zdk), CryptoFailure.CorruptData) }
                    finally { verified.fill(0) }
                    submit.check()
                    return PreparedZone(ZoneKey(container, zone, role, zdk), Envelope(container, zone, role,
                        method, parameters, salt, nonce, Bytes(ciphertext)))
                } finally { wrap.fill(0) }
            } finally { raw.fill(0) }
        } catch (e: Throwable) { zdk.fill(0); throw e }
    }
}
