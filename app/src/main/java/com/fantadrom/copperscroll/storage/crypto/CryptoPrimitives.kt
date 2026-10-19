package com.fantadrom.copperscroll.storage.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class CryptoFailure {
    InvalidParameters, UnsupportedKdf, UnsupportedSecretEncoding, ResourceLimit, Busy,
    Cancelled, Closed, CredentialMismatch, AmbiguousCredential, CorruptData,
    UnchangedSecret, SecretMismatch, Collision, RngFailure, BackendUnavailable
}
class CryptoException(val failure: CryptoFailure) : Exception(failure.name)
internal fun requireCrypto(ok: Boolean, failure: CryptoFailure = CryptoFailure.InvalidParameters) {
    if (!ok) throw CryptoException(failure)
}

/** Library algorithms only. Raw-key entry points are module-internal, not the production API. */
internal object CryptoPrimitives {
    fun hkdf(key: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val parameters = HKDFParameters(key, salt, info)
        val generator = HKDFBytesGenerator(SHA256Digest())
        val out = ByteArray(length)
        var complete = false
        try {
            generator.init(parameters)
            generator.generateBytes(out, 0, length)
            complete = true
            return out
        } catch (_: RuntimeException) {
            throw CryptoException(CryptoFailure.BackendUnavailable)
        } finally { if (!complete) out.fill(0) }
        // BC HKDF has no destroy API. Its getters clone arrays, so calling/filling those
        // would only create additional secret copies. Internal PRK/HMAC state is GC-managed.
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest()); val out = ByteArray(32); val parameters = KeyParameter(key)
        var complete = false
        try {
            mac.init(parameters); mac.update(message, 0, message.size); mac.doFinal(out, 0)
            complete = true
            return out
        } finally {
            if (!complete) out.fill(0)
            parameters.key.fill(0)
            // reset() alone restores the keyed ipad. Replace keyed state before dropping the instance.
            mac.init(KeyParameter(ByteArray(32)))
        }
    }

    fun aes(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        requireCrypto(key.size == 32 && nonce.size == 12)
        val cipher = try { Cipher.getInstance("AES/GCM/NoPadding") }
        catch (_: GeneralSecurityException) { throw CryptoException(CryptoFailure.BackendUnavailable) }
        // No provider registration/selection: Android's JCA implementation is used on Android.
        try {
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            // One-shot doFinal: no update() plaintext can escape before authentication.
            return cipher.doFinal(input)
        } catch (_: AEADBadTagException) {
            throw CryptoException(CryptoFailure.CredentialMismatch)
        } catch (_: GeneralSecurityException) {
            throw CryptoException(CryptoFailure.BackendUnavailable)
        }
    }

    /** Tracks BC's public blocks so exceptions/OOM also clear blocks not returned by its normal reset. */
    private class OwnedBlocks : Argon2BytesGenerator.BlockPool, AutoCloseable {
        private val blocks = ArrayList<Argon2BytesGenerator.Block>()
        override fun allocate(): Argon2BytesGenerator.Block {
            val block = Argon2BytesGenerator.Block()
            try { blocks.add(block) } catch (e: Throwable) { block.clear(); throw e }
            return block
        }
        override fun deallocate(block: Argon2BytesGenerator.Block) { block.clear() }
        override fun close() {
            // Avoid allocating an iterator on the OOM cleanup path.
            var i = 0
            while (i < blocks.size) blocks[i++].clear()
            blocks.clear()
        }
    }

    fun argon(password: ByteArray, salt: ByteArray, memory: Int, iterations: Int, lanes: Int,
              secret: ByteArray? = null, additional: ByteArray? = null): ByteArray {
        val pool = OwnedBlocks()
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
            .withMemoryAsKB(memory).withIterations(iterations).withParallelism(lanes)
            .withSecret(secret).withAdditional(additional).withBlockPool(pool)
        val parameters = builder.build(); val out = ByteArray(32)
        try {
            val generator = Argon2BytesGenerator(); generator.init(parameters)
            generator.generateBytes(password, out)
            return out
        } catch (_: OutOfMemoryError) {
            out.fill(0); throw CryptoException(CryptoFailure.ResourceLimit)
        } catch (_: RuntimeException) {
            out.fill(0); throw CryptoException(CryptoFailure.BackendUnavailable)
        } finally { pool.close(); parameters.clear(); builder.clear() }
    }
}
