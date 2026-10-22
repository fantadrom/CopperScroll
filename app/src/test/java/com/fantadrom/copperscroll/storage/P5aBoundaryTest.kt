package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import java.nio.file.Files
import org.junit.Test

class P5aBoundaryTest {
    private fun reject(block: ()->Unit) { try { block() } catch(_: Exception) { return }; error("Expected rejection") }
    private fun rewritten(image: ByteArray,role: Int,unknown: Boolean = false,template: Boolean = false): ByteArray =
        FaultStore(FaultMedium(image,128*1024*1024)).use { store -> P3Fixtures.key(role).use { key ->
            val reader = ContainerReader(store); val s = reader.snapshot(); val view = reader.verifyAll(s,key)
            val known = s.catalogues.flatMap { it.keys }.toHashSet()
            val added = ArrayList<Pair<Bytes,PhysicalRef>>()
            fun add(kind: Int,owner: Bytes,plain: ByteArray): LogicalRef {
                val r = try { RecordCrypto().encrypt(key,kind,owner,0,plain,RecordIdReservation { known.add(it) }) } finally { plain.fill(0) }
                added.add(r.reference.recordId to PhysicalRef(store.appendAligned(r.raw.copy()),r.raw.size.toLong(),r.reference.hash)); return r.reference
            }
            val root = view.root!!; var objects = root.objects
            if(template) {
                val card = view.objects.values.single { it.type == 6 }
                val fields = StrictCbor.decode(ObjectCodec.encode(card,2)).arr().toMutableList()
                val payload = fields[13].arr().toMutableList(); payload[3] = true; fields[13] = payload
                val ref = add(3,card.id,StrictCbor.encode(fields))
                // Independent flat-index oracle for small fixture, no compactor builder involved.
                objects = add(2,key.zoneId,IndexCodec.encode(IndexPage(1,0,view.objectRefs.entries.sortedBy { it.key }.map {
                    IndexEntry(IndexKey(it.key),if(it.key == card.id) ref else it.value)
                }),key.zoneId))
            }
            val raw = RootCodec.encode(RootData(root.sequence,root.normalRoot,root.trashRoot,objects,root.children,root.settings,root.nextPinSequence,root.recentOperations),key.zoneId)
            val a = StrictCbor.decode(raw).arr().toMutableList(); raw.fill(0)
            if(unknown) a[0] = 99 // oracle knows schema99 = same complete tuple, deliberately unknown to production
            val rootRef = add(1,key.zoneId,StrictCbor.encode(a))
            val d = view.descriptor
            val catalogue = CowCatalogue(store,key.zoneId).build((s.catalogues[role-1].entries.map { it.key to it.value })+added)
            val changed = P3Fixtures.mac(P3Fixtures.descriptor(d,rootRef,catalogue),key)
            val header = Superblock(s.header.generation+1,store.size,s.header.containerId,s.header.instanceId,
                s.header.zones.map { if(it.role == role) changed else it },s.header.minor)
            store.writeFullyAt((1-s.slot)*65536L,SuperblockCodec.encode(header)); store.force(); store.medium.durableBytes()
        } }
    @Test fun unknownSelectedRefusesOpaqueUnselectedUsesKnowingOracle() {
        val image = rewritten(P4Fixtures.image(true),2,unknown=true)
        P5aFixture(image).use { f ->
            val c = f.coordinator(); val r = c.begin(f.core.authorizeMaintenance(),f.destinations)
            SecretEncoding.text(P3Fixtures.DECOY).use { reject { c.credentials(r,it) } }
            check(f.docs.creates == 0)
        }
        P5aFixture(image).use { f ->
            val c = f.coordinator(); val r = c.compact(f.confirmed(c,setOf(P3Fixtures.id(1))))
            check(r.code == MaintenanceCode.MaintenanceCommitted)
            fun schema99(bytes: ByteArray): List<Any?> = FaultStore(FaultMedium(bytes)).use { store ->
                val reader = ContainerReader(store); val s = reader.snapshot(); P3Fixtures.key(2).use { k ->
                    val b = reader.readRecord(s,k,s.header.zones[1].root)
                    try {
                        val a = StrictCbor.decode(b).arr(); check(a.size == 10 && a[0] == 99uL)
                        // This fixture's schema99 is DEFINED as the ten-field v1 tuple with tag99.
                        // The knowing oracle validates its complete outgoing graph: two empty leaf
                        // indexes plus settings, rather than accepting an arbitrary opaque tuple.
                        val known = a.toMutableList(); known[0] = 1
                        val root = RootCodec.decode(StrictCbor.encode(known),k.zoneId,s.header.zones[1].sequence)
                        val objects = IndexCodec.decode(reader.readRecord(s,k,root.objects),k.zoneId)
                        val children = IndexCodec.decode(reader.readRecord(s,k,root.children),k.zoneId)
                        check(objects.type == 1 && objects.level == 0 && objects.entries.isEmpty())
                        check(children.type == 2 && children.level == 0 && children.entries.isEmpty())
                        SettingsCodec.decode(reader.readRecord(s,k,root.settings))
                        a
                    } finally { b.fill(0) }
                }
            }
            check(schema99(image) == schema99(f.docs.bytes(r.candidate!!)))
            check(r.coverage[P3Fixtures.id(2)] == VerificationCoverage.RelativeToSnapshot)
        }
        val mainUnknown = rewritten(P4Fixtures.image(),1,unknown=true)
        P5aFixture(mainUnknown).use { f -> val c = f.coordinator(); val r = c.compact(f.confirmed(c)); check(r.code == MaintenanceCode.Unsupported && f.docs.creates == 0) }
    }
    @Test fun populatedTemplateFlagAndValuesSurviveAndEditPreservesFlag() {
        val image = rewritten(P5aImages.rich(two=false),1,template=true)
        val expected = P5aImages.logical(image,1)
        P5aFixture(image).use { f ->
            val c = f.coordinator(); val r = c.compact(f.confirmed(c)); check(r.code == MaintenanceCode.MaintenanceCommitted)
            val bytes = f.docs.bytes(r.candidate!!); check(P5aImages.logical(bytes,1) == expected)
            P4Fixtures.open(bytes).use { read ->
                val repo = read.repo; val card = repo.readCard(P3Fixtures.id(105))
                check((card.metadata.payload as CardPayload).useAsUserTemplate)
                P4Fixtures.committed(repo,repo.issueSaveCard(repo.stamp(),card.metadata.id,card.metadata.parent,"edited",card.body,
                    expectedObjectRevision=card.metadata.revision,now=100))
                check((repo.metadata(card.metadata.id).payload as CardPayload).useAsUserTemplate)
            }
        }
    }
    @Test fun kdfFailuresSetupPendingSavesAndStaleConfirmation() {
        for(failure in listOf<CryptoFailure?>(null,CryptoFailure.UnsupportedKdf)) P5aFixture(P4Fixtures.image(true)).use { f ->
            val manager = CredentialManager(SystemEntropy(),ArgonDerivation { _,_-> if(failure == null) throw OutOfMemoryError() else throw CryptoException(failure) })
            val c = MaintenanceCoordinator(f.core,f.docs,f.local,manager,{})
            val request = c.begin(f.core.authorizeMaintenance(),f.destinations)
            SecretEncoding.text(P3Fixtures.DECOY).use { secret -> reject { c.credentials(request,secret) } }
            check(f.docs.creates == 0 && f.local.read().journal == null)
        }
        P5aFixture(P4Fixtures.image()).use { f ->
            val c = f.coordinator(); val confirmed = f.confirmed(c)
            val token = f.core.issueMutation(P3Fixtures.mutation(f.core))
            check(c.compact(confirmed).code == MaintenanceCode.Busy && f.docs.creates == 0)
            // session revoked, late save cannot write after the refused maintenance workflow
            check(f.core.submit(token).code == WriteCode.AccessLost)
        }
        P5aFixture(P4Fixtures.image()).use { f ->
            val c = f.coordinator(); val confirmed = f.confirmed(c)
            check(f.core.submit(f.core.issueMutation(P3Fixtures.mutation(f.core))).code == WriteCode.Committed)
            check(c.compact(confirmed).code == MaintenanceCode.Conflict && f.docs.creates == 0)
        }
        P5aFixture(P4Fixtures.image()).use { f ->
            val c = f.coordinator(); val cap = f.core.authorizeMaintenance(); f.docs.allowed = false
            reject { c.begin(cap,f.destinations) }; check(f.core.owner.accessRevoked && f.docs.creates == 0)
            f.docs.allowed = true; reject { c.begin(cap,f.destinations) }
        }
    }
    @Test fun aliasesReopenCorruptionSourceReplacementAndUnknownOuter() {
        for(mode in listOf("alias","reopen","flip","source","candidate","grant","identity")) P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator { step ->
                if(mode == "source" && step == MaintenanceStep.PrepareSwitch) {
                    val old = f.docs.media.getValue(f.docs.source.uri)
                    f.docs.media[f.docs.source.uri] = Artifact(f.docs.source.uri,StoreIdentity("new","new")) to old.second
                }
                if(mode == "candidate" && step == MaintenanceStep.AcceptSwitch) {
                    val a = f.docs.media.values.single { it.first.uri.contains("candidate") }
                    a.second.corrupt(0,0)
                }
            }
            val confirmed = f.confirmed(c)
            when(mode) {
                "alias" -> f.docs.alias = f.docs.source
                "reopen" -> f.docs.reopenFailure = true
                "flip" -> f.docs.onEvent = { a,store,e,_ -> if(a.uri.contains("backup") && e.action == IoAction.Force) store.medium.corrupt(132000,17) }
                "grant" -> f.docs.onCreate = { f.docs.allowed = false }
                "identity" -> f.docs.identitySupported = false
            }
            val r = c.compact(confirmed)
            check(r.code != MaintenanceCode.MaintenanceCommitted) { mode }
            check(f.local.read().binding!!.uri == f.docs.source.uri)
        }
        val bad = P4Fixtures.image(); java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(10,9)
        P3Fixtures.rehashHeader(bad) // no guess/fallback for unknown outer framing
        val owner = ResourceOwner(FaultStore(FaultMedium(bad)))
        try { reject { TransactionCore(owner).attach(P3Fixtures.key(1)) } } finally { owner.close() }
    }
    @Test fun restartRejectsBadStaleAndMissingJournalFiles() {
        P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator { if(it == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.Before) }
            check(c.compact(f.confirmed(c)).code == MaintenanceCode.SwitchUnknown)
            val bytes = f.local.bytes(); val decoded = LocalStateCodec.decode(bytes)
            for(n in bytes.indices) {
                val broken = bytes.clone(); broken[n] = (broken[n].toInt() xor 1).toByte()
                reject { LocalStateCodec.decode(broken) }
            }
            for(n in listOf(0,1,12,bytes.size-1)) reject { LocalStateCodec.decode(bytes.copyOf(n)) }
            check(f.docs.creates == 2)
            f.local.fault = null
            f.core.owner.resolved(); f.core.close() // simulated death, never a runtime cancellation primitive
            val reopened = ResourceOwner(f.docs.store(f.docs.source))
            reject { TransactionCore.openBound(reopened,f.local,P3Fixtures.key(1)) }
            check(reopened.reconciliationRequired && !reopened.release())
            val secrets = (1..2).associate { P3Fixtures.id(it) to SecretEncoding.text(if(it == 1) P3Fixtures.MAIN else P3Fixtures.DECOY) }
            try {
                val candidate = decoded.journal!!.candidate!!
                val removed = f.docs.media.remove(candidate.uri)!!
                check(MaintenanceRecovery().resolve(f.local,f.docs,RecoveryChoice.Candidate,secrets,reopened).code == RecoveryCode.AccessLost)
                f.docs.media[candidate.uri] = removed
                val medium = f.docs.media.getValue(f.docs.source.uri).second
                medium.corrupt(0,0)
                check(MaintenanceRecovery().resolve(f.local,f.docs,RecoveryChoice.Candidate,secrets,reopened).code == RecoveryCode.Conflict)
                // Old local state is checksummed, deliberately NOT protected against malicious rollback.
                check(LocalStateCodec.decode(bytes).journal!!.opId == decoded.journal.opId)
            } finally { secrets.values.forEach { it.close() }; reopened.resolved(); reopened.close() }
        }
    }
    @Test fun retentionCoverageClosedCorruptionGenerationAndMapLimits() {
        P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator(); val copy = c.backup(f.core.authorizeMaintenance(),f.destinations)
            val full = copy.coverage + (P3Fixtures.id(2) to VerificationCoverage.AeadReachable)
            fun retained(n: Int,coverage: Map<Bytes,VerificationCoverage>,valid: Boolean = true): RetainedCopy {
                val a = Artifact("synthetic:/old$n",StoreIdentity("synthetic","$n"))
                return RetainedCopy(VerifiedCopy(a,copy.fingerprint,coverage),n.toLong(),valid)
            }
            val old = listOf(retained(1,full),retained(2,copy.coverage),retained(3,copy.coverage),retained(4,full,false))
            val removed = CopyRetention.removable(old,retained(5,copy.coverage))
            check(removed.size == 1 && removed.single() === old[1].copy.artifact)
            check(removed.none { it === old[0].copy.artifact || it === old[3].copy.artifact })
            val s = ContainerReader(f.store).snapshot(); val root = s.catalogues[0].values.first()
            val fake = ReadSnapshot(s.header,s.slot,s.headerBytes,s.observedLength,
                listOf((1..32769).associate { n -> Bytes(java.nio.ByteBuffer.allocate(32).putInt(n).array()) to root },s.catalogues[1]))
            try {
                P3Fixtures.key(1).use { SelectiveCompactor().preflight(f.store,fake,setOf(it.zoneId),mapOf(it.zoneId to it),{}) }
                error("Budget not enforced")
            } catch(e: FormatException) { check(e.failure == Failure.ResourceLimit) }
        }
        val image = P4Fixtures.image()
        val header = java.nio.ByteBuffer.wrap(image).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putLong(16,Long.MAX_VALUE); P3Fixtures.rehashHeader(image)
        P5aFixture(image).use { f -> val c = f.coordinator(); check(c.compact(f.confirmed(c)).code == MaintenanceCode.Unsupported && f.docs.creates == 0) }
        // Closed-zone ciphertext can be maliciously changed and public hashes recomputed. Its graph
        // is still copied exactly, but relative coverage explicitly does NOT certify AEAD health.
        val closed = rewritten(P4Fixtures.image(true),2,unknown=true)
        P5aFixture(closed).use { f ->
            val c = f.coordinator(); val copy = c.backup(f.core.authorizeMaintenance(),f.destinations)
            check(copy.coverage[P3Fixtures.id(2)] == VerificationCoverage.RelativeToSnapshot)
        }
    }
    @Test fun maliciousClosedDamageDoesNotAcquireAeadCoverage() {
        val original = P5aImages.rich()
        val image = FaultStore(FaultMedium(original,128*1024*1024)).use { store ->
            val reader = ContainerReader(store); val snapshot = reader.snapshot(); val d = snapshot.header.zones[1]
            val records = snapshot.catalogues[1].entries.map { (id,p) ->
                val raw = store.readFullyAt(p.offset,p.length.toInt())
                if(RecordCodec.decodeHeader(raw.copyOfRange(0,76)).kind == 5) {
                    raw[76] = (raw[76].toInt() xor 1).toByte(); store.writeFullyAt(p.offset,raw)
                    id to PhysicalRef(p.offset,p.length,sha(raw))
                } else id to p
            }
            val catalogue = CowCatalogue(store,d.zoneId).build(records)
            val relocated = Descriptor(d.zoneId,d.role,d.sequence,d.inputMethod,d.memoryKiB,d.iterations,d.salt,d.wrapNonce,
                d.wrappedKey,d.hint,d.root,d.logicalMac,catalogue)
            val h = snapshot.header
            store.writeFullyAt((1-snapshot.slot)*65536L,SuperblockCodec.encode(Superblock(h.generation+1,store.size,h.containerId,h.instanceId,
                listOf(h.zones[0],relocated),h.minor))); store.force(); store.medium.durableBytes()
        }
        P5aFixture(image).use { f ->
            val c = f.coordinator(); val copy = c.backup(f.core.authorizeMaintenance(),f.destinations)
            check(copy.coverage[P3Fixtures.id(2)] == VerificationCoverage.RelativeToSnapshot)
            check(f.docs.bytes(copy.artifact).contentEquals(image))
            val r = c.compact(f.confirmed(c,setOf(P3Fixtures.id(1)))); check(r.code == MaintenanceCode.MaintenanceCommitted)
            reject { P5aImages.logical(f.docs.bytes(r.candidate!!),2) }
        }
        P5aFixture(image).use { f ->
            val c = f.coordinator(); val r = c.compact(f.confirmed(c))
            check(r.code != MaintenanceCode.MaintenanceCommitted && f.docs.creates == 0)
        }
    }
    @Test fun pendingUnknownAndAcceptBeforeRevoke() {
        P5aFixture(P4Fixtures.image()).use { f ->
            var publishing = false
            f.store.observer = { event ->
                if(event.action == IoAction.Write && event.offset < 131072) publishing = true
                if(publishing && event.action == IoAction.Force) f.store.fault = IoFault(event.index,FaultMoment.After)
            }
            val token = f.core.issueMutation(P3Fixtures.mutation(f.core))
            check(f.core.submit(token).code == WriteCode.CommitUnknown)
            reject { f.coordinator().begin(f.core.authorizeMaintenance(),f.destinations) }
            check(f.docs.creates == 0)
            f.store.observer = {}; f.store.fault = null
            check(f.core.reconcile().code == WriteCode.Committed)
        }
        P5aFixture(P4Fixtures.image()).use { f ->
            val port = object : LocalStateStore {
                override val durableLocalState = true
                override val reconciliationRequired get() = f.local.reconciliationRequired
                override fun read() = f.local.read()
                override fun reconcile() = f.local.reconcile()
                override fun replace(state: LocalState): LocalWriteCode {
                    if(state.journal!!.stage == JournalStage.Activated) f.core.owner.revoke()
                    return f.local.replace(state)
                }
            }
            val c = MaintenanceCoordinator(f.core,f.docs,port)
            val result = c.compact(f.confirmed(c))
            check(result.code == MaintenanceCode.MaintenanceCommitted && result.outcome == JournalOutcome.Unreported)
            check(f.local.read().binding!!.uri != f.docs.source.uri && result.locked)
        }
    }
    @Test fun lowLevelSymmetryWithoutUnselectedKey() {
        val image = P4Fixtures.image(true)
        for(role in 1..2) P5aFixture(image,role).use { f ->
            val permit = f.core.owner.accept()
            f.core.owner.execute(permit) {
                P3Fixtures.key(role).use { key ->
                    val s = ContainerReader(f.store).snapshot(); val keys = mapOf(key.zoneId to key)
                    val copy = SnapshotCopier().copy(f.core.owner,permit,s,f.docs.source,f.docs,"synthetic:/backup",keys)
                    check(copy.coverage[P3Fixtures.id(3-role)] == VerificationCoverage.RelativeToSnapshot)
                    val compactor = SelectiveCompactor(); val plan = compactor.preflight(f.store,s,keys.keys,keys,{})
                    val (artifact,store) = f.docs.createNew("synthetic:/candidate",uniqueName("candidate"))
                    ResourceOwner(store).use { target ->
                        val cp = target.accept()
                        target.execute(cp) {
                            val instance = compactor.build(f.store,plan,target.store,{})
                            target.store.force(); target.store.forceIdentity(); target.reopen(cp) { f.docs.open(artifact) }
                            compactor.verify(f.store,plan,target.store,instance,keys,{})
                        }
                    }
                    for(other in 1..2) check(P5aImages.logical(image,other) == P5aImages.logical(f.docs.bytes(artifact),other))
                }
            }
        }
        check((1..64).map { uniqueName("backup") }.distinct().size == 64)
    }
    @Test fun retentionRechecksInventoryAndFreshCopyBytes() {
        P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator(); val cap = f.core.authorizeMaintenance()
            val copies = (0..4).map { c.backup(cap,f.destinations) to it.toLong() }
            val removals = CopyRetention.plan(f.docs,copies.take(4),copies.last())
            check(removals.size == 2 && removals.first() === copies[0].first.artifact)
            val old = copies[3].first.artifact; f.docs.media.getValue(old.uri).second.corrupt(0,0)
            check(CopyRetention.plan(f.docs,copies.take(4),copies.last()).size == 1)
            val fresh = copies.last().first.artifact; f.docs.media.getValue(fresh.uri).second.corrupt(0,0)
            reject { CopyRetention.plan(f.docs,copies.take(4),copies.last()) }
            check(copies.all { it.first.artifact.uri in f.docs.media }) // policy never deletes to make room
        }
    }
    @Test fun failedCandidateRecoveryRetainsAddressableOwnerForRetry() {
        P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator { if(it == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.Before) }
            check(c.compact(f.confirmed(c)).code == MaintenanceCode.SwitchUnknown)
            f.local.fault = null; f.core.owner.resolved(); f.core.close()
            val source = ResourceOwner(f.docs.store(f.docs.source))
            val secrets = (1..2).associate { P3Fixtures.id(it) to SecretEncoding.text(if(it == 1) P3Fixtures.MAIN else P3Fixtures.DECOY) }
            try {
                reject { TransactionCore.openBound(source,f.local,P3Fixtures.key(1)) }
                val fp = f.local.read().journal!!.candidate!!; val medium = f.docs.media.getValue(fp.uri).second
                val byte = medium.bytes()[0]; medium.corrupt(0,0)
                check(MaintenanceRecovery().resolve(f.local,f.docs,RecoveryChoice.Candidate,secrets,source).code == RecoveryCode.Conflict)
                check(source.ownedDocument(fp.identity!!) != null && !source.release())
                medium.corrupt(0,byte)
                check(MaintenanceRecovery().resolve(f.local,f.docs,RecoveryChoice.Candidate,secrets,source).code == RecoveryCode.CandidateConfirmed)
                check(source.phase == OwnerPhase.Released)
            } finally { secrets.values.forEach { it.close() }; source.resolved(); source.close() }
        }
    }
    @Test fun ordinaryTransactionDoesNotRequireSidecarBindingRefresh() {
        for(unknown in listOf(false,true)) P5aFixture(P4Fixtures.image(true)).use { f ->
            val previous = f.local.read().binding!!
            check(f.core.submit(f.core.issueMutation(P3Fixtures.mutation(f.core,sort=99))).code == WriteCode.Committed)
            val saved = f.docs.bytes(f.docs.source); val sequence = f.core.currentSnapshot().header.generation
            f.core.close()
            val owner = ResourceOwner(f.docs.store(f.docs.source))
            val opened = TransactionCore.openBound(owner,f.local,P3Fixtures.key(1))
            try {
                check(opened.currentSnapshot().header.generation == sequence && previous.generation < sequence)
                check(sameFingerprint(previous,f.local.read().binding)) // T stays portable, no private-state write
                val c = MaintenanceCoordinator(opened,f.docs,f.local) { if(unknown && it == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.Before) }
                val request = c.begin(opened.authorizeMaintenance(),f.destinations)
                val confirmation = SecretEncoding.text(P3Fixtures.DECOY).use { c.confirm(c.credentials(request,it)) }
                val r = c.compact(confirmation)
                if(unknown) {
                    check(r.code == MaintenanceCode.SwitchUnknown)
                    val j = f.local.read().journal!!
                    check(j.previousBinding!!.generation == previous.generation && j.source.generation == sequence)
                    f.local.fault = null
                    val secrets = (1..2).associate { P3Fixtures.id(it) to SecretEncoding.text(if(it == 1) P3Fixtures.MAIN else P3Fixtures.DECOY) }
                    try { check(c.reconcile(RecoveryChoice.Candidate,secrets).code == RecoveryCode.CandidateConfirmed) }
                    finally { secrets.values.forEach { it.close() } }
                } else check(r.code == MaintenanceCode.MaintenanceCommitted)
                check(f.docs.bytes(f.docs.source).contentEquals(saved))
                check(P5aImages.logical(f.docs.bytes(r.candidate!!),1) == P5aImages.logical(saved,1))
            } finally { owner.resolved(); owner.finishMaintenanceIo(); opened.close() }
        }
    }
    @Test fun retainedSourceTailIsCountedSeparatelyFromCommittedSnapshot() {
        val image = P4Fixtures.image()
        P5aFixture(image+ByteArray(77) { 17 }).use { f ->
            val c = f.coordinator(); val r = c.compact(f.confirmed(c)); check(r.code == MaintenanceCode.MaintenanceCommitted)
            val sizes = r.sizes!!; val s = image.size.toLong(); val candidate = f.docs.bytes(r.candidate!!).size.toLong()
            check(sizes.source == s && sizes.sourceTail == 77L && sizes.sourceObservedLength == s+77)
            check(sizes.retainedTotal == 2*s+candidate && sizes.retainedFileLengths == 2*s+candidate+77)
            check(sizes.growth == s+candidate && sizes.observedWorkingReduction == s+77-candidate && sizes.totalWithExtras == null)
            check(f.docs.bytes(f.docs.source).size == image.size+77 && f.docs.bytes(r.backup!!.artifact).size == image.size)
        }
    }
    @Test fun changedRequestAndCredentialsInvalidateEarlierConfirmationAcrossCoordinators() {
        P5aFixture(P4Fixtures.image(true)).use { f ->
            val first = f.coordinator(); val old = f.confirmed(first,setOf(P3Fixtures.id(1)))
            val second = f.coordinator(); val request = second.begin(f.core.authorizeMaintenance(),f.destinations,setOf(P3Fixtures.id(2)))
            reject { first.compact(old) }; check(!f.core.owner.accessRevoked && f.docs.creates == 0)
            SecretEncoding.text(P3Fixtures.DECOY).use { secret ->
                val proposal = second.credentials(request,secret); val stale = second.confirm(proposal)
                SecretEncoding.text("wrong synthetic").use { wrong -> reject { second.credentials(request,wrong) } }
                reject { second.compact(stale) }; check(!f.core.owner.accessRevoked && f.docs.creates == 0)
                val fresh = second.confirm(second.credentials(request,secret))
                check(second.compact(fresh).code == MaintenanceCode.MaintenanceCommitted)
            }
        }
    }
    @Test fun aliasesToEarlierHealthyBackupAreNeitherWrittenNorDeleted() {
        for(kind in listOf("backup","candidate")) P5aFixture(P4Fixtures.image(true)).use { f ->
            val previous = f.coordinator().backup(f.core.authorizeMaintenance(),f.destinations)
            val original = f.docs.bytes(previous.artifact)
            val c = f.coordinator { if(kind == "candidate" && it == MaintenanceStep.BuildCandidate) f.docs.alias = previous.artifact }
            val confirmed = f.confirmed(c)
            if(kind == "backup") f.docs.alias = previous.artifact
            val result = c.compact(confirmed)
            check(result.code == MaintenanceCode.Conflict && result.locked)
            check(f.docs.bytes(previous.artifact).contentEquals(original))
            check(result.unverifiedArtifacts.none { it.identity == previous.artifact.identity })
            check(f.local.read().binding!!.uri == f.docs.source.uri)
        }
    }
    @Test fun realJvmAdapterAndExclusiveReopen() {
        val directory = Files.createTempDirectory("p5a-owned-")
        var primary: Throwable? = null
        try {
            val source = directory.resolve("source.cscroll"); Files.write(source,P4Fixtures.image(true))
            val docs = JvmMaintenanceDocuments(source,directory,directory)
            val owner = ResourceOwner(FileChannelStore.open(source)); val core = TransactionCore(owner); core.attach(P3Fixtures.key(1))
            val local = FaultLocalStateStore(LocalState(fingerprint(owner.store,docs.source.uri),null))
            val c = MaintenanceCoordinator(core,docs,local)
            val request = c.begin(core.authorizeMaintenance(),docs.destinations)
            val confirmation = SecretEncoding.text(P3Fixtures.DECOY).use { c.confirm(c.credentials(request,it)) }
            val r = c.compact(confirmation)
            check(r.code == MaintenanceCode.MaintenanceCommitted) { "${r.code} ${r.failure?.stackTraceToString()}" }
            check(owner.phase == OwnerPhase.Released)
            docs.open(r.candidate!!).use { target ->
                val s = ContainerReader(target).snapshot(); P3Fixtures.key(2).use { check(!ContainerReader(target).verifyAll(s,it).readOnly) }
            }
            val (reserved,handle) = docs.createNew(docs.destinations.backup,"cscroll-backup-collision.cscroll")
            handle.use { it.writeFullyAt(0,byteArrayOf(11,22,33)) }
            reject { docs.createNew(docs.destinations.backup,"cscroll-backup-collision.cscroll") }
            check(Files.readAllBytes(java.nio.file.Path.of(java.net.URI(reserved.uri))).contentEquals(byteArrayOf(11,22,33)))
            docs.deleteOwned(reserved)
            val actualSource = Files.readAllBytes(source)
            check(Files.readAllBytes(java.nio.file.Path.of(java.net.URI(r.backup!!.artifact.uri))).contentEquals(actualSource))
        } catch(e: Throwable) { primary = e; throw e }
        finally {
            try {
                val files = Files.list(directory).use { it.toList() }
                files.forEach { Files.delete(it) }; Files.delete(directory)
            } catch(e: Throwable) { if(primary != null) primary.addSuppressed(e) else throw e }
        }
    }
}
