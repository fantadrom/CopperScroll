package com.fantadrom.copperscroll.storage.runtimechecks

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import java.io.InputStream
import java.security.MessageDigest
import java.security.Security
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-only cross-platform checks. Contains synthetic fixture import, never a production raw-key export. */
class P2Checks(stream: InputStream) {
    private val fixture = Properties().apply { stream.use { load(it) } }
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun value(name: String) = hex(fixture.getProperty(name))
    private fun bytes(name: String) = Bytes(value(name))
    private val container get() = bytes("container")
    private val zone get() = bytes("zone")
    private val profile get() = KdfParameters(16384, 2)
    private val descriptor get() = DescriptorCodec.decode(value("descriptor"), 200000)
    private fun key() = ZoneKey(container, zone, 1, value("zdk"))
    private fun equal(actual: ByteArray, expected: ByteArray) = check(actual.contentEquals(expected)) { "Synthetic known-answer mismatch" }
    private fun rejects(code: CryptoFailure, block: () -> Unit) {
        try { block() } catch (e: CryptoException) { check(e.failure == code) { "Unexpected crypto error category" }; return }
        error("Expected crypto rejection")
    }
    private class SequenceEntropy : Entropy {
        var next = 1
        override fun fill(destination: ByteArray) { destination.fill((next++).toByte()) }
    }
    private fun attach(prepared: PreparedZone, role: Int = prepared.key.role): Descriptor {
        val envelope = prepared.envelope
        fun descriptor(mac: Bytes) = Descriptor(prepared.key.zoneId, role, 7, envelope.inputMethod,
            envelope.parameters.memoryKiB, envelope.parameters.iterations, envelope.salt, envelope.nonce,
            envelope.wrappedKey, "synthetic public hint", LogicalRef(bytes("recordId"), Bytes(ByteArray(32)),
                1, prepared.key.zoneId, 0), mac, PhysicalRef(131072, 16384, Bytes(ByteArray(32))))
        val pending = descriptor(Bytes(ByteArray(32)))
        return descriptor(RecordCrypto().descriptorMac(prepared.key, pending))
    }

    fun primitiveKnownAnswers() {
        val providers = Security.getProviders().map { it.name }
        val argon = CryptoPrimitives.argon(ByteArray(32) { 1 }, ByteArray(16) { 2 }, 32, 3, 4,
            ByteArray(8) { 3 }, ByteArray(12) { 4 })
        try { equal(argon, hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")) }
        finally { argon.fill(0) }
        val hkdf = CryptoPrimitives.hkdf(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        try { equal(hkdf, hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")) }
        finally { hkdf.fill(0) }
        equal(CryptoPrimitives.hmac(ByteArray(20) { 0x0b }, "Hi There".toByteArray()),
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"))
        // NIST AES_GCM.pdf, GCM-AES256 Examples1/2; exercises empty and multi-block plaintext.
        val aesKey = hex("feffe9928665731c6d6a8f9467308308".repeat(2)); val nonce = hex("cafebabefacedbaddecaf888")
        val plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"+
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255")
        equal(CryptoPrimitives.aes(true, aesKey, nonce, byteArrayOf(), byteArrayOf()), hex("fd2caa16a5832e76aa132c1453eeda7e"))
        val cipher = hex("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa"+
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015adb094dac5d93471bdec1a502270e3cc6c")
        equal(CryptoPrimitives.aes(true, aesKey, nonce, byteArrayOf(), plain), cipher)
        equal(CryptoPrimitives.aes(false, aesKey, nonce, byteArrayOf(), cipher), plain)
        check(providers == Security.getProviders().map { it.name }) { "Provider list changed" }
    }

    fun independentFormatFixture() {
        SecretEncoding.pin4("0012").use { secret ->
            CredentialManager().unwrap(Envelope.fromDescriptor(container, descriptor), secret).use { opened ->
                key().use { expected -> check(opened.matches(expected)) }
                val crypto = RecordCrypto(); crypto.verifyDescriptor(opened, descriptor)
                crypto.decrypt(opened, value("rawRecord"), descriptor.root).use { plaintext ->
                    val copy = plaintext.copyBytes(); try { equal(copy, value("plaintext")) } finally { copy.fill(0) }
                }
            }
            val encoded = secret.copyBytes()
            val raw = try { CryptoPrimitives.argon(encoded, value("salt"), 16384, 2, 1) } finally { encoded.fill(0) }
            try {
                equal(raw, value("kraw"))
                val wrap = CryptoPrimitives.hkdf(raw, value("container"), "CS-wrap-key-v1\u0000".toByteArray()+value("zone"))
                try { equal(wrap, value("kwrap")) } finally { wrap.fill(0) }
            } finally { raw.fill(0) }
        }
        key().use { key ->
            val record = RecordCodec.decode(value("rawRecord")); val crypto = RecordCrypto()
            equal(crypto.recordInfo(key, record.header, key.zoneId, 0), value("recordInfo"))
            equal(crypto.aad(key, record.header, key.zoneId, 0), value("recordAAD"))
            equal(Envelope.fromDescriptor(container, descriptor).aad(), value("wrapAAD"))
            equal(DescriptorCodec.logicalBytes(descriptor), value("logicalBytes"))
            val zdk=value("zdk")
            try {
                val derived = CryptoPrimitives.hkdf(zdk, container.copy(), value("recordInfo"))
                try { equal(derived, value("krecord")) } finally { derived.fill(0) }
                val desc = CryptoPrimitives.hkdf(zdk, container.copy(), "CS-desc-v1\u0000".toByteArray()+zone.copy())
                try { equal(desc, value("kdesc")) } finally { desc.fill(0) }
            } finally { zdk.fill(0) }
        }
        // Exact encryption with test-only entropy, not just decode + round trip.
        val entropy = Entropy { destination ->
            val source = if (destination.size == 32) value("recordId") else value("nonce")
            source.copyInto(destination)
        }
        key().use { fixed -> equal(RecordCrypto(entropy).encrypt(fixed, 1, zone, 0, value("plaintext"),
            RecordIdReservation { true }).raw.copy(), value("rawRecord")) }
        val chunkEntropy=Entropy { destination -> (if(destination.size==32) value("chunkId") else value("chunkNonce")).copyInto(destination) }
        key().use { fixed ->
            val ordinal=0x01020304L; val owner=bytes("chunkOwner")
            val chunk=RecordCrypto(chunkEntropy).encrypt(fixed,4,owner,ordinal,value("chunkPlaintext"),RecordIdReservation { true })
            equal(chunk.raw.copy(),value("chunkRaw"))
            val header=RecordCodec.decode(chunk.raw.copy()).header
            equal(RecordCrypto().recordInfo(fixed,header,owner,ordinal),value("chunkInfo"))
            equal(RecordCrypto().aad(fixed,header,owner,ordinal),value("chunkAAD"))
            fixed.withDerivedKey(value("chunkInfo")) { equal(it,value("chunkKey")) }
            RecordCrypto().decrypt(fixed,chunk.raw.copy(),chunk.reference).use { plaintext ->
                val copy=plaintext.copyBytes(); try { equal(copy,value("chunkPlaintext")) } finally { copy.fill(0) }
            }
        }
    }

    fun independentEnvelopesAndExpectedZone() {
        val manager = CredentialManager()
        SecretEncoding.pin4("0012").use { main -> SecretEncoding.text("synthetic decoy").use { decoy ->
            manager.create(container, zone, 1, 1, main, profile).use { a ->
                val otherZone = Bytes(ByteArray(16) { 41 })
                manager.create(container, otherZone, 2, 3, decoy, profile).use { b ->
                    check(!a.key.matches(b.key)); check(a.envelope.salt != b.envelope.salt)
                    val first = attach(a); val second = attach(b)
                    rejects(CryptoFailure.CredentialMismatch) { manager.unwrap(b.envelope, main).close() }
                    rejects(CryptoFailure.CredentialMismatch) { manager.verifyCurrent(container, second, otherZone, 2, main).close() }
                    rejects(CryptoFailure.CredentialMismatch) { manager.verifyCurrent(container, first, otherZone, 2, main).close() }
                    manager.login(container, listOf(first, second), main).use { check(it.key.zoneId == zone) }
                    CredentialManager().login(container, listOf(first, second), decoy).use { check(it.key.zoneId == otherZone) }
                    val badSecond=Descriptor(second.zoneId,second.role,second.sequence,second.inputMethod,1,second.iterations,
                        second.salt,second.wrapNonce,second.wrappedKey,second.hint,second.root,second.logicalMac,second.catalogue)
                    manager.login(container,listOf(first,badSecond),main).use { check(it.otherEnvelopeUnsupported && it.key.zoneId==zone) }
                    manager.verifyCurrent(container,first,zone,1,main).use { current ->
                        SecretEncoding.text("synthetic alternate").use { new ->
                            rejects(CryptoFailure.UnsupportedKdf) { manager.prepareChange(current,first,new,new,3,badSecond,parameters=profile).close() }
                        }
                    }
                    manager.verifyCurrent(container, second, otherZone, 2, decoy, b.key).use { check(it.key.matches(b.key)) }
                }
            }
        } }
    }

    fun ambiguousCredentialAndUnchangedSecret() {
        val manager = CredentialManager()
        SecretEncoding.pin4("0012").use { main -> SecretEncoding.text("0012").use { same ->
            manager.create(container, zone, 1, 1, main, profile).use { a ->
                manager.create(container, Bytes(ByteArray(16) { 42 }), 2, 3, same, profile).use { b ->
                    rejects(CryptoFailure.AmbiguousCredential) { manager.login(container, listOf(attach(a), attach(b)), main).close() }
                }
                manager.verifyCurrent(container, attach(a), zone, 1, main, a.key).use { current ->
                    rejects(CryptoFailure.UnchangedSecret) { manager.prepareChange(current, attach(a), same, same, 3, parameters=profile).close() }
                    SecretEncoding.text("synthetic new").use { new ->
                        rejects(CryptoFailure.SecretMismatch) { manager.prepareChange(current, attach(a), new, same, 3, parameters=profile).close() }
                        manager.create(container, Bytes(ByteArray(16) { 43 }), 2, 3, new, profile).use { other ->
                            rejects(CryptoFailure.AmbiguousCredential) { manager.prepareChange(current, attach(a), new, new, 3, attach(other), parameters=profile).close() }
                        }
                    }
                }
            }
        } }
        // Pattern/PIN/text share the same credential namespace; S1 remains the P1 implementation.
        SecretEncoding.pattern(listOf(1,2,3,4)).use { pattern -> SecretEncoding.pin4("1234").use { pin -> check(pattern.sameAs(pin)) } }
    }

    fun kdfLimitsAndCancellation() {
        val calls = AtomicInteger(); val engine = ArgonDerivation { _, _ -> calls.incrementAndGet(); value("kraw") }
        val manager = CredentialManager(SequenceEntropy(), engine); val good = Envelope.fromDescriptor(container, descriptor)
        fun invalid(p: KdfParameters = profile, salt: Bytes = good.salt, nonce: Bytes = good.nonce,
                    wrapped: Bytes = good.wrappedKey, s1: Int = 1, version: Int = 1, method: Int = 1) =
            Envelope(container, zone, 1, method, p, salt, nonce, wrapped, version, s1)
        val invalids = listOf(KdfParameters(0,2), KdfParameters(16385,2), KdfParameters(Int.MAX_VALUE,2),
            KdfParameters(16384,0), KdfParameters(16384,5), KdfParameters(16384,2,0), KdfParameters(16384,2,2),
            KdfParameters(16384,2,kdfId=0), KdfParameters(16384,2,argonVersion=16), KdfParameters(16384,2,outputBytes=16))
        SecretEncoding.pin4("0012").use { secret ->
            for (p in invalids) rejects(CryptoFailure.UnsupportedKdf) { manager.unwrap(invalid(p), secret).close() }
            for (e in listOf(invalid(salt=Bytes(ByteArray(15))),invalid(nonce=Bytes(ByteArray(11))),invalid(wrapped=Bytes(ByteArray(47))),
                invalid(version=2),invalid(method=0))) rejects(CryptoFailure.InvalidParameters) { manager.unwrap(e, secret).close() }
            rejects(CryptoFailure.UnsupportedSecretEncoding) { manager.unwrap(invalid(s1=2), secret).close() }
            CanonicalSecret(ByteArray(1025)).use { huge -> rejects(CryptoFailure.InvalidParameters) { manager.unwrap(good, huge).close() } }
            check(calls.get()==0) { "Invalid parameters reached Argon2" }
            val preCancelled=CryptoCancellation().apply { cancel() }
            rejects(CryptoFailure.Cancelled) { manager.unwrap(good,secret,preCancelled).close() }
            var input: ByteArray? = null
            val exhausted=CredentialManager(SequenceEntropy(),ArgonDerivation { s, _ -> input=s; throw OutOfMemoryError("synthetic") })
            rejects(CryptoFailure.ResourceLimit) { exhausted.unwrap(good,secret).close() }
            check(input!!.all { it==0.toByte() })
        }
        for (m in listOf(16384,32768,65536)) for (t in 2..4) KdfParameters(m,t).validate()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val cancellation = CryptoCancellation()
        var used: ByteArray? = null; var output: ByteArray? = null
        val blocking = CredentialManager(SequenceEntropy(), ArgonDerivation { input, _ ->
            used=input; started.countDown(); check(release.await(10,TimeUnit.SECONDS)); value("kraw").also { output=it }
        })
        val caller = Executors.newSingleThreadExecutor()
        try {
            val future = caller.submit<CryptoFailure> {
                SecretEncoding.pin4("0012").use { secret ->
                    try { blocking.unwrap(good, secret, cancellation).close(); error("Cancelled operation succeeded") }
                    catch (e: CryptoException) { e.failure }
                }
            }
            check(started.await(5,TimeUnit.SECONDS)); cancellation.cancel()
            check(!future.isDone)
            SecretEncoding.pin4("0012").use { secret -> rejects(CryptoFailure.Busy) { manager.unwrap(good, secret).close() } }
            check(used!!.any { it != 0.toByte() }) { "In-use credential prematurely cleared" }
            release.countDown(); check(future.get(10,TimeUnit.SECONDS)==CryptoFailure.Cancelled)
            check(used!!.all { it == 0.toByte() } && output!!.all { it == 0.toByte() })
        } finally { release.countDown(); caller.shutdownNow(); check(caller.awaitTermination(10,TimeUnit.SECONDS)) }
        rejects(CryptoFailure.ResourceLimit) {
            KdfWorker.run(CryptoCancellation()) { submit ->
                repeat(3) { submit.derive(value("secret"), good, engine).fill(0) }
                AutoCloseable { }
            }.close()
        }
        check(calls.get()==2) { "More than two KDF calls admitted" }
    }

    fun recordTamperingAndNoPlaintext() {
        val crypto = RecordCrypto(); val raw = value("rawRecord"); val ref = descriptor.root
        fun refFor(record: ByteArray, owner: Bytes = ref.ownerId, ordinal: Long = 0, kind: Int = 1) =
            LogicalRef(RecordCodec.decode(record).header.recordId, Bytes(MessageDigest.getInstance("SHA-256").digest(record)), kind, owner, ordinal)
        key().use { good ->
            for (offset in listOf(10,12,28,60,72,76,raw.lastIndex)) {
                val corrupt = raw.clone(); corrupt[offset]=(corrupt[offset].toInt() xor 1).toByte()
                rejects(CryptoFailure.CorruptData) { crypto.decrypt(good, corrupt, ref).close() }
            }
            // Recompute public hash to force the GCM path, rather than only testing hash rejection.
            for (offset in listOf(60,76,raw.lastIndex)) {
                val corrupt = raw.clone(); corrupt[offset]=(corrupt[offset].toInt() xor 1).toByte()
                rejects(CryptoFailure.CorruptData) { crypto.decrypt(good, corrupt, refFor(corrupt)).close() }
            }
            for (offset in listOf(10,28)) {
                val corrupt=raw.clone(); corrupt[offset]=if(offset==10) 2 else (corrupt[offset].toInt() xor 1).toByte()
                rejects(CryptoFailure.CorruptData) { crypto.decrypt(good,corrupt,refFor(corrupt,kind=RecordCodec.decode(corrupt).header.kind)).close() }
            }
            rejects(CryptoFailure.CorruptData) { crypto.decrypt(good, raw, refFor(raw,Bytes(ByteArray(16) { 12 }))).close() }
            rejects(CryptoFailure.CorruptData) { crypto.decrypt(good, raw, refFor(raw,ordinal=1)).close() }
            rejects(CryptoFailure.CorruptData) { crypto.decrypt(good, raw, refFor(raw,kind=2)).close() }
            val d=descriptor
            val tampered=Descriptor(d.zoneId,d.role,d.sequence,d.inputMethod,d.memoryKiB,d.iterations,d.salt,d.wrapNonce,
                d.wrappedKey,"changed hint",d.root,d.logicalMac,d.catalogue)
            rejects(CryptoFailure.CorruptData) { crypto.verifyDescriptor(good,tampered) }
            SecretEncoding.pin4("0012").use { secret -> rejects(CryptoFailure.CorruptData) {
                CredentialManager().verifyCurrent(container,tampered,zone,1,secret).close()
            } }
            val relocated=Descriptor(d.zoneId,d.role,d.sequence,d.inputMethod,d.memoryKiB,d.iterations,d.salt,d.wrapNonce,
                d.wrappedKey,d.hint,d.root,d.logicalMac,PhysicalRef(147456,16384,Bytes(ByteArray(32) { 1 })))
            crypto.verifyDescriptor(good,relocated) // Physical catalogue addresses are intentionally outside the MAC.
        }
        val envelope=Envelope.fromDescriptor(container,descriptor)
        val e=envelope
        fun altered(containerId: Bytes=e.containerId, zoneId: Bytes=e.zoneId, role: Int=e.role,
                    method: Int=e.inputMethod, p: KdfParameters=e.parameters, salt: Bytes=e.salt,
                    nonce: Bytes=e.nonce, wrapped: Bytes=e.wrappedKey) =
            Envelope(containerId,zoneId,role,method,p,salt,nonce,wrapped)
        val modified=Bytes(e.wrappedKey.copy().also { it[0]=(it[0].toInt() xor 1).toByte() })
        // Fixed test Kraw isolates AAD/key-domain binding from the KDF's own salt/parameter binding.
        val envelopeManager=CredentialManager(SequenceEntropy(),ArgonDerivation { _, _ -> value("kraw") })
        SecretEncoding.pin4("0012").use { secret ->
            for(changed in listOf(altered(containerId=Bytes(ByteArray(16) { 9 })),altered(zoneId=Bytes(ByteArray(16) { 9 })),
                altered(role=2),altered(method=3),altered(p=KdfParameters(32768,2)),altered(p=KdfParameters(16384,3)),
                altered(salt=Bytes(ByteArray(16) { 9 })),altered(nonce=Bytes(ByteArray(12) { 9 })),altered(wrapped=modified)))
                rejects(CryptoFailure.CredentialMismatch) { envelopeManager.unwrap(changed,secret).close() }
        }
        ZoneKey(Bytes(ByteArray(16) { 12 }),zone,1,value("zdk")).use { wrong ->
            rejects(CryptoFailure.CorruptData) { crypto.decrypt(wrong,raw,ref).close() }
        }
        ZoneKey(container,Bytes(ByteArray(16) { 12 }),2,value("zdk")).use { wrong ->
            rejects(CryptoFailure.CorruptData) { crypto.decrypt(wrong,raw,ref).close() }
        }
        // Successful buffer becomes inaccessible on close; failed authentication never returns a buffer.
        val plaintext = key().use { crypto.decrypt(it,raw,ref) }; plaintext.close()
        rejects(CryptoFailure.Closed) { plaintext.copyBytes() }
    }

    fun freshRandomnessAndRngFailure() {
        val ids = HashSet<Bytes>(); val crypto=RecordCrypto()
        key().use { key ->
            val a=crypto.encrypt(key,1,zone,0,value("plaintext"),RecordIdReservation { ids.add(it) })
            val b=crypto.encrypt(key,1,zone,0,value("plaintext"),RecordIdReservation { ids.add(it) })
            check(a.reference.recordId!=b.reference.recordId)
            check(RecordCodec.decode(a.raw.copy()).header.nonce!=RecordCodec.decode(b.raw.copy()).header.nonce)
            rejects(CryptoFailure.Collision) { crypto.encrypt(key,1,zone,0,value("plaintext"),RecordIdReservation { false }) }
            rejects(CryptoFailure.RngFailure) { RecordCrypto(Entropy { throw IllegalStateException("synthetic") })
                .encrypt(key,1,zone,0,value("plaintext"),RecordIdReservation { true }) }
        }
        SecretEncoding.pin4("0012").use { secret ->
            for (failureAt in 1..3) {
                var n=0; val touched=ArrayList<ByteArray>()
                val rng=Entropy { target -> touched.add(target); target.fill(9); if (++n==failureAt) throw IllegalStateException("synthetic") }
                rejects(CryptoFailure.RngFailure) { CredentialManager(rng).create(container,zone,1,1,secret,profile).close() }
                check(touched.first().all { it==0.toByte() }) { "Failed preparation retained generated key" }
            }
        }
    }

    fun prepareNewKeyAndCleanup() {
        val manager=CredentialManager()
        SecretEncoding.pin4("0012").use { old -> SecretEncoding.text("synthetic new credential").use { new ->
            manager.verifyCurrent(container,descriptor,zone,1,old).use { current ->
                val before=DescriptorCodec.encode(descriptor,200000)
                manager.prepareChange(current,descriptor,new,new,3,parameters=profile).use { prepared ->
                    check(!prepared.key.matches(current.key)); equal(DescriptorCodec.encode(descriptor,200000),before)
                    val crypto=RecordCrypto()
                    val fresh=crypto.encrypt(prepared.key,1,zone,0,value("plaintext"),RecordIdReservation { true })
                    rejects(CryptoFailure.CorruptData) { crypto.decrypt(current.key,fresh.raw.copy(),fresh.reference).close() }
                    crypto.decrypt(prepared.key,fresh.raw.copy(),fresh.reference).use { plaintext ->
                        val copy=plaintext.copyBytes(); try { equal(copy,value("plaintext")) } finally { copy.fill(0) }
                    }
                    crypto.decrypt(current.key,value("rawRecord"),descriptor.root).close() // Source key remains usable.
                    CredentialManager().unwrap(prepared.envelope,new).use { check(it.matches(prepared.key)) }
                    rejects(CryptoFailure.CredentialMismatch) { manager.unwrap(prepared.envelope,old).close() }
                    rejects(CryptoFailure.CorruptData) { manager.prepareChange(current,descriptor,new,new,3,
                        otherKnownCorrupt=true,parameters=profile).close() }
                }
            }
        } }
        val owned=value("zdk"); val handle=ZoneKey(container,zone,1,owned); handle.close(); handle.close()
        check(owned.all { it==0.toByte() }); check(!handle.toString().contains(fixture.getProperty("zdk")))
        rejects(CryptoFailure.Closed) { RecordCrypto().descriptorMac(handle,descriptor) }
        val oldBytes=value("secret"); val oldKey=value("zdk")
        val ticket=CurrentCredential(ZoneKey(container,zone,1,oldKey),Bytes(ByteArray(32)),oldBytes)
        ticket.close(); check(oldBytes.all { it==0.toByte() } && oldKey.all { it==0.toByte() })
        rejects(CryptoFailure.Closed) { ticket.withSecret { error("Closed current credential used") } }
        ZoneKeys.generate(container,zone,1).use { a -> ZoneKeys.generate(container,zone,1).use { b -> check(!a.matches(b)) } }
    }
}
