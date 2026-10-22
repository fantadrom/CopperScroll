package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.*
import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import org.junit.Test

class P5bBoundaryTest {
    private fun reject(block: ()->Unit) = P5bOracle.reject(block)
    @Test fun sourceAndCandidateIdReservationsAndLateRngFailures() {
        val image = P5aImages.rich()
        val ids = FaultStore(FaultMedium(image)).use { ContainerReader(it).snapshot().catalogues.flatMap { c -> c.keys } }
        for(id in listOf(ids.first(),ids.last(),null)) P5aFixture(image).use { f ->
            var n = 0
            val entropy = Entropy { b ->
                if(b.size == 32) (id?.copy() ?: ByteArray(32) { 77 }).copyInto(b)
                else b.fill((++n).toByte())
            }
            val c = MaintenanceCoordinator(f.core,f.docs,f.local,CredentialManager(),{},RecordCrypto(entropy))
            val r = c.changeSecret(P5bOracle.confirmed(f,c,1)); check(r.code != MaintenanceCode.MaintenanceCommitted)
            check(r.backup != null && f.docs.bytes(f.docs.source).contentEquals(image) && f.local.read().binding!!.uri == f.docs.source.uri)
        }
        var calls = 0
        P5aFixture(image).use { f ->
            val c = MaintenanceCoordinator(f.core,f.docs,f.local,CredentialManager(),{},RecordCrypto(Entropy { b -> calls++; SystemEntropy().fill(b) }))
            P5bOracle.success(c.changeSecret(P5bOracle.confirmed(f,c,1)))
        }
        for(at in 0 until calls) P5aFixture(image).use { f ->
            var n = 0
            val c = MaintenanceCoordinator(f.core,f.docs,f.local,CredentialManager(),{},RecordCrypto(Entropy { b ->
                if(n++ == at) throw IllegalStateException("synthetic RNG failure") else SystemEntropy().fill(b)
            }))
            val r = c.changeSecret(P5bOracle.confirmed(f,c,1)); check(r.code != MaintenanceCode.MaintenanceCommitted && r.backup != null)
            check(f.docs.bytes(f.docs.source).contentEquals(image) && f.docs.creates == 2)
            check(f.docs.handles.all { it.closeCount == 1 })
        }
        println("P5b late record RNG boundaries=$calls; every ID/nonce allocation including leaf/index/root")
    }
    @Test fun preparationFailuresHaveNoArtifactsOrReservations() {
        for(failure in listOf("rng","oom","known-corrupt")) P5aFixture(P4Fixtures.image(true)).use { f ->
            val manager = when(failure) {
                "rng" -> CredentialManager(Entropy { throw IllegalStateException("synthetic") })
                "oom" -> CredentialManager(SystemEntropy(),ArgonDerivation { _,_-> throw OutOfMemoryError("synthetic") })
                else -> CredentialManager()
            }
            val c = MaintenanceCoordinator(f.core,f.docs,f.local,manager,{})
            val request = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),3,
                parameters=KdfParameters(16384,2),otherKnownCorrupt=failure == "known-corrupt")
            SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P5bOracle.NEW).use { new -> reject { c.credentials(request,old,new,new) } } }
            check(f.docs.creates == 0 && f.local.read().journal == null)
        }
    }
    @Test fun staleRequestAcrossCoordinatorsAndSourceChange() = P5aFixture(P4Fixtures.image(true)).use { f ->
        val c = f.coordinator(); val old = P5bOracle.confirmed(f,c,1)
        val newer = f.coordinator(); val live = P5bOracle.confirmed(f,newer,2)
        val checks = f.docs.checks; reject { c.changeSecret(old) }; check(f.docs.checks == checks && !f.core.owner.accessRevoked)
        P5bOracle.success(newer.changeSecret(live))
    }
    @Test fun repeatedCredentialsClearBothOwnedKeysAndOldConfirmation() = P5aFixture(P4Fixtures.image()).use { f ->
        val c = f.coordinator(); val request = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),3,parameters=KdfParameters(16384,2))
        SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P5bOracle.NEW).use { new ->
            val proposal = c.credentials(request,old,new,new); val confirmed = c.confirm(proposal)
            check(proposal.prepared != null)
            SecretEncoding.text("wrong").use { wrong -> reject { c.credentials(request,wrong,new,new) } }
            reject { proposal.currentTarget!!.key.ensureOpen() }; reject { proposal.prepared!!.key.ensureOpen() }
            reject { c.changeSecret(confirmed) }; check(f.docs.creates == 0 && !f.core.owner.accessRevoked)
        } }
    }
    @Test fun unsupportedSelectedGraphOpaqueUnselectedAndOverflow() {
        val unsupported = P5bImages.rewrite(P4Fixtures.image(true),2,unknown=true)
        P5aFixture(unsupported).use { f -> val c = f.coordinator(); reject { P5bOracle.confirmed(f,c,2) }; check(f.docs.creates == 0) }
        P5aFixture(unsupported).use { f ->
            val c = f.coordinator(); val r = c.changeSecret(P5bOracle.confirmed(f,c,1)); P5bOracle.success(r)
            FaultStore(FaultMedium(unsupported)).use { a -> FaultStore(FaultMedium(f.docs.bytes(r.candidate!!))).use { b ->
                val x = ContainerReader(a).snapshot(); val y = ContainerReader(b).snapshot()
                check(x.catalogues[1].keys == y.catalogues[1].keys)
                for((id,p) in x.catalogues[1]) { val q = y.catalogues[1].getValue(id); check(a.readFullyAt(p.offset,p.length.toInt()).contentEquals(b.readFullyAt(q.offset,q.length.toInt()))) }
            } }
        }
        P5aFixture(P5bImages.rewrite(P4Fixtures.image(),1,sequence=Long.MAX_VALUE)).use { f ->
            val c = f.coordinator(); val r = c.changeSecret(P5bOracle.confirmed(f,c,1)); check(r.code == MaintenanceCode.Unsupported && f.docs.creates == 0)
        }
        FaultStore(FaultMedium(P4Fixtures.image())).use { store ->
            val s = ContainerReader(store).snapshot(); val h = s.header
            val header = SuperblockCodec.encode(Superblock(Long.MAX_VALUE,h.committedLength,h.containerId,h.instanceId,h.zones,h.minor))
            store.writeFullyAt(0,header); store.writeFullyAt(65536,header); store.force()
            P5aFixture(store.medium.durableBytes()).use { f -> val c = f.coordinator(); val r = c.changeSecret(P5bOracle.confirmed(f,c,1)); check(r.code == MaintenanceCode.Unsupported && f.docs.creates == 0) }
        }
    }
    @Test fun pendingSaveUnknownAndOrdinarySaveBeforeRekey() {
        P5aFixture(P4Fixtures.image()).use { f ->
            val repo = ZoneRepository(f.core); val pending = repo.issueSaveNote(repo.stamp(),P3Fixtures.id(81),repo.roots().first,"pending",P4Fixtures.note("synthetic"),now=1)
            reject { f.coordinator().beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),3) }
            check(f.docs.creates == 0); P4Fixtures.committed(repo,pending)
            val image = f.docs.bytes(f.docs.source); f.core.close()
            P5bOracle.core(f.docs,f.docs.source,f.local,P3Fixtures.MAIN).use { core ->
                val c = MaintenanceCoordinator(core,f.docs,f.local)
                val request = c.beginChangeSecret(core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),3,parameters=KdfParameters(16384,2))
                SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P5bOracle.NEW).use { new ->
                    val r = c.changeSecret(c.confirm(c.credentials(request,old,new,new))); P5bOracle.success(r)
                    check(P5bOracle.logical(image,P3Fixtures.MAIN) == P5bOracle.logical(f.docs.bytes(r.candidate!!),P5bOracle.NEW))
                } }
            }
        }
    }
    @Test fun legacyMetadataUnsupportedKindAndRekeyCodecRoundTrip() {
        P5aFixture(P4Fixtures.image()).use { f ->
            val state = f.local.read(); val fp = state.binding!!
            for(kind in listOf("compact","rekey","future")) {
                val journal = StateJournal(P3Fixtures.id(77),kind,listOf(P3Fixtures.id(1)),fp,null,null,JournalStage.Aborted,fp,JournalOutcome.Aborted)
                val bytes = LocalStateCodec.encode(LocalState(fp,journal)); check(LocalStateCodec.encode(LocalStateCodec.decode(bytes)).contentEquals(bytes))
                if(kind == "future") {
                    val local = FaultLocalStateStore(LocalState(fp,journal)); f.core.close()
                    val owner = ResourceOwner(f.docs.open(f.docs.source))
                    try { reject { TransactionCore.openBound(owner,local,P3Fixtures.key(1)) } }
                    finally { owner.resolved(); owner.close() }
                }
            }
        }
    }
    @Test fun frozenLocalMetadataV1V2AndTruncatedChecksum() {
        for(name in listOf("local-v1-prepared.hex","local-v2-rekey-prepared.hex")) {
            val hex = javaClass.getResourceAsStream("/p5b/$name")!!.bufferedReader().use { it.readText().trim() }
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val state = LocalStateCodec.decode(bytes)
            check(state.journal!!.kind == if(name.contains("v2")) "rekey" else "compact")
            check(LocalStateCodec.encode(state).contentEquals(bytes))
            reject { LocalStateCodec.decode(bytes.copyOf(bytes.size-1)) }
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); reject { LocalStateCodec.decode(bytes) }
        }
    }

    @Test fun sameOwnS1AcrossPinAndTextCannotRewrap() = P5aFixture(P4Fixtures.image()).use { f ->
        val c = f.coordinator()
        val request = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),1,parameters=KdfParameters(16384,2))
        val first = SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.pin4("0012").use { pin ->
            c.changeSecret(c.confirm(c.credentials(request,old,pin,pin)))
        } }; P5bOracle.success(first)
        P5bOracle.core(f.docs,first.candidate!!,f.local,"0012").use { core ->
            val next = MaintenanceCoordinator(core,f.docs,f.local)
            val r = next.beginChangeSecret(core.authorizeMaintenance(),MaintenanceDestinations(first.candidate,"synthetic:/backup","synthetic:/candidate"),
                P3Fixtures.id(1),3,parameters=KdfParameters(16384,2))
            SecretEncoding.pin4("0012").use { old -> SecretEncoding.text("0012").use { text ->
                try { next.credentials(r,old,text,text); error("Expected UnchangedSecret") }
                catch(e: MaintenanceRefusal) { check(e.code == MaintenanceCode.UnchangedSecret && f.docs.creates == 2) }
            } }
        }
    }
    @Test fun unsupportedKdfPolicyNeverSilentlyDowngrades() = P5aFixture(P4Fixtures.image(true)).use { f ->
        val c = f.coordinator()
        reject { c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(2),3,parameters=KdfParameters(49152,2)) }
        check(f.docs.creates == 0 && f.local.read().journal == null)
    }

    @Test fun weakDataDurabilityNeverProducesVerifiedBackupOrActivation() {
        for(weak in listOf("synthetic:/backup","synthetic:/candidate")) P5aFixture(P4Fixtures.image(true)).use { f ->
            val docs = object: MaintenanceDocuments by f.docs {
                override fun createNew(destination: String,name: String): Pair<Artifact,RandomAccessStore> {
                    val (artifact,store) = f.docs.createNew(destination,name)
                    return artifact to if(destination != weak) store else object: RandomAccessStore by store {
                        override val guarantees = StoreGuarantees(false,true)
                    }
                }
            }
            val c = MaintenanceCoordinator(f.core,docs,f.local)
            val r = c.changeSecret(P5bOracle.confirmed(f,c,2))
            check(r.code == MaintenanceCode.Unsupported && f.local.read().binding!!.uri == f.docs.source.uri)
            check((r.backup == null) == weak.endsWith("backup"))
            check(f.local.read().journal!!.stage == JournalStage.Aborted && f.docs.handles.all { it.closeCount == 1 })
        }
    }

}
