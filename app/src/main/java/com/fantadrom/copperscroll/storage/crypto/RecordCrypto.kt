package com.fantadrom.copperscroll.storage.crypto

import com.fantadrom.copperscroll.storage.format.*
import org.bouncycastle.util.Arrays
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** A future writer must atomically reserve IDs against source AND candidate; true only on insertion. */
fun interface RecordIdReservation { fun reserve(recordId: Bytes): Boolean }
class EncryptedRecord(val raw: Bytes, val reference: LogicalRef)

class RecordCrypto internal constructor(private val entropy: Entropy) {
    constructor() : this(SystemEntropy())
    private fun ordinal(n: Long): ByteArray {
        requireCrypto(n in 0..0xffffffffL)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n.toInt()).array()
    }
    internal fun recordInfo(key: ZoneKey, header: RecordHeader, owner: Bytes, n: Long): ByteArray {
        requireCrypto(header.zoneId == key.zoneId && owner.size == 16 && owner.copy().any { it != 0.toByte() })
        return "CS-data-v1\u0000".toByteArray(Charsets.US_ASCII) + key.zoneId.copy() +
            header.recordId.copy() + byteArrayOf(header.kind.toByte()) + owner.copy() + ordinal(n)
    }
    internal fun aad(key: ZoneKey, header: RecordHeader, owner: Bytes, n: Long): ByteArray =
        "CS-record-v1\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 0) + key.containerId.copy() +
            RecordCodec.encodeHeader(header) + owner.copy() + ordinal(n)

    fun descriptorMac(key: ZoneKey, descriptor: Descriptor): Bytes {
        requireCrypto(descriptor.zoneId == key.zoneId && descriptor.role == key.role)
        val info = "CS-desc-v1\u0000".toByteArray(Charsets.US_ASCII) + key.zoneId.copy()
        return key.withDerivedKey(info) { derived ->
            Bytes(CryptoPrimitives.hmac(derived, "CS-logical-v1\u0000".toByteArray(Charsets.US_ASCII) +
                key.containerId.copy() + DescriptorCodec.logicalBytes(descriptor)))
        }
    }
    fun verifyDescriptor(key: ZoneKey, descriptor: Descriptor) {
        try {
            requireCrypto(descriptor.zoneId == key.zoneId && descriptor.role == key.role && descriptor.logicalMac.size == 32 &&
                Arrays.constantTimeAreEqual(descriptorMac(key, descriptor).copy(), descriptor.logicalMac.copy()), CryptoFailure.CorruptData)
        } catch (_: FormatException) { throw CryptoException(CryptoFailure.CorruptData) }
    }
    fun encrypt(key: ZoneKey, kind: Int, owner: Bytes, n: Long, plaintext: ByteArray,
                reservations: RecordIdReservation): EncryptedRecord {
        // Validate size and metadata before RNG, reserve or encryption.
        requireCrypto(kind in 1..5 && plaintext.size in (if (kind >= 4) 1 else 0)..(if (kind <= 3) Limits.PAGE else Limits.CHUNK))
        requireCrypto(owner.size == 16 && owner.copy().any { it != 0.toByte() }); ordinal(n)
        key.ensureOpen()
        val id = Bytes(entropy.bytes(32, true))
        requireCrypto(reservations.reserve(id), CryptoFailure.Collision)
        val header = RecordHeader(kind, key.zoneId, id, Bytes(entropy.bytes(12)), plaintext.size)
        return key.withDerivedKey(recordInfo(key, header, owner, n)) { derived ->
            val raw = RecordCodec.encode(OpaqueRecord(header, Bytes(CryptoPrimitives.aes(true, derived,
                header.nonce.copy(), aad(key, header, owner, n), plaintext))))
            EncryptedRecord(Bytes(raw), LogicalRef(id, Bytes(MessageDigest.getInstance("SHA-256").digest(raw)), kind, owner, n))
        }
    }
    /** Caller supplies the authenticated parent's ref (or descriptor root AFTER its MAC verification). */
    fun decrypt(key: ZoneKey, raw: ByteArray, reference: LogicalRef): Plaintext {
        try {
            val record = RecordCodec.decode(raw); RecordCodec.match(record, key.zoneId, reference)
            return key.withDerivedKey(recordInfo(key, record.header, reference.ownerId, reference.ordinal)) { derived ->
                val plain = CryptoPrimitives.aes(false, derived, record.header.nonce.copy(),
                    aad(key, record.header, reference.ownerId, reference.ordinal), record.ciphertextAndTag.copy())
                try { Plaintext(plain) } catch (e: Throwable) { plain.fill(0); throw e }
            }
        } catch (_: FormatException) { throw CryptoException(CryptoFailure.CorruptData) }
        catch (e: CryptoException) {
            if (e.failure == CryptoFailure.CredentialMismatch || e.failure == CryptoFailure.InvalidParameters)
                throw CryptoException(CryptoFailure.CorruptData)
            throw e
        }
    }
}
