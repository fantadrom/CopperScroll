package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.*
import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.transaction.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import org.junit.Test

class P5bOwnershipRecoveryTest {
    @Test fun revokeDuringNewKdfWaitsForQuiescenceAndWipesBuffers() = P5aFixture(P4Fixtures.image()).use { f ->
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); var calls = 0
        val copied = ArrayList<ByteArray>(); val entropyBuffers = ArrayList<ByteArray>()
        val manager = CredentialManager(Entropy { b -> SystemEntropy().fill(b); entropyBuffers.add(b) },ArgonDerivation { s,e ->
            copied.add(s)
            if(calls++ == 1) { entered.countDown(); check(finish.await(20,TimeUnit.SECONDS)) }
            BcArgon.derive(s,e)
        })
        val c = MaintenanceCoordinator(f.core,f.docs,f.local,manager,{})
        val request = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),3,parameters=KdfParameters(16384,2))
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P5bOracle.NEW).use { next -> c.credentials(request,old,next,next).close() } } }
            catch(e: Throwable) { failure.set(e) }
        }
        thread.start()
        try {
            check(entered.await(20,TimeUnit.SECONDS)); f.core.owner.revoke()
            check(!f.core.owner.awaitQuiescent(0) && f.store.closeCount == 0)
            check(!f.core.owner.release())
            f.docs.store(f.docs.source).use { attempted -> P5bOracle.reject { ResourceOwner(attempted) } }
        } finally { finish.countDown(); thread.join(20000) }
        check(!thread.isAlive && failure.get() is MaintenanceRefusal)
        check((failure.get() as MaintenanceRefusal).code == MaintenanceCode.Cancelled)
        check(entropyBuffers.first { it.size == 32 }.all { it == 0.toByte() } && copied.all { it.all { b -> b == 0.toByte() } })
        check(f.docs.creates == 0 && f.docs.handles.all { it.closeCount == 1 } && f.core.owner.phase == OwnerPhase.Released)
    }
    @Test fun closingProposalRetiresIndependentOldNewHandles() {
        for(role in 1..2) P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator(); val request = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(role),3,parameters=KdfParameters(16384,2))
            SecretEncoding.text(P5bOracle.old(role)).use { old -> SecretEncoding.text(P5bOracle.NEW).use { next ->
                val proposal = c.credentials(request,old,next,next); val current = proposal.currentTarget!!.key; val prepared = proposal.prepared!!.key
                proposal.close(); proposal.close()
                P5bOracle.reject { current.ensureOpen() }; P5bOracle.reject { prepared.ensureOpen() }
                // Main session owns its separate authority handle until actual revoke/close.
                f.core.authorizeMaintenance(); check(!f.core.owner.accessRevoked)
                P5bOracle.reject { c.confirm(proposal) }; check(f.docs.creates == 0)
            } }
        }
    }
    @Test fun recoveryRejectsMissingChangedFilesAndWrongContextsWithoutReplay() {
        for(problem in listOf("source","backup","candidate","source-hash","candidate-hash","grant")) P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator { if(it == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.After) }
            val result = c.changeSecret(P5bOracle.confirmed(f,c,2)); check(result.code == MaintenanceCode.SwitchUnknown)
            f.core.owner.resolved(); f.core.close(); f.docs.crash(); f.local.crash()
            val file = when(problem) { "source","source-hash" -> f.docs.source; "backup" -> result.backup!!.artifact; else -> result.candidate!! }
            if(problem.endsWith("hash")) {
                val medium = f.docs.media.getValue(file.uri).second
                medium.corrupt(150000,1)
            } else if(problem == "grant") f.docs.allowed = false
            else f.docs.media.remove(file.uri)
            SecretEncoding.text(P3Fixtures.MAIN).use { main -> SecretEncoding.text(P3Fixtures.DECOY).use { old -> SecretEncoding.text(P5bOracle.NEW).use { next ->
                val credentials = RekeyRecoveryCredentials(mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to old),mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to next))
                val before = f.local.bytes()
                val recovered = MaintenanceRecovery().resolveChangeSecret(f.local,f.docs,RecoveryChoice.Candidate,credentials)
                check(recovered.code !in listOf(RecoveryCode.CandidateConfirmed,RecoveryCode.SourceConfirmed))
                check(f.docs.creates == 2 && before.contentEquals(f.local.bytes()))
            } } }
        }
    }
    @Test fun sourceChangedAfterConfirmationHasNoArtifacts() = P5aFixture(P4Fixtures.image()).use { f ->
        val c = f.coordinator(); val confirmation = P5bOracle.confirmed(f,c,1)
        f.store.medium.corrupt(f.store.size.toInt()-1,1)
        val r = c.changeSecret(confirmation)
        check(r.code == MaintenanceCode.Conflict && f.docs.creates == 0)
    }
    @Test fun blockedAbortStillHoldsLeaseAndPreservesPrimaryWithSuppressedCleanup() = P5aFixture(P4Fixtures.image()).use { f ->
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val local = object: LocalStateStore {
            override val durableLocalState = true
            override val reconciliationRequired get() = f.local.reconciliationRequired
            override fun read() = f.local.read()
            override fun reconcile() = f.local.reconcile()
            override fun replace(state: LocalState): LocalWriteCode {
                if(state.journal?.stage == JournalStage.Aborted) { entered.countDown(); check(finish.await(20,TimeUnit.SECONDS)) }
                return f.local.replace(state)
            }
        }
        val primary = IllegalStateException("synthetic primary")
        val docs = object: MaintenanceDocuments by f.docs {
            override fun deleteOwned(artifact: Artifact) { throw IllegalStateException("synthetic cleanup") }
        }
        val c = MaintenanceCoordinator(f.core,docs,local,CredentialManager(),{ if(it == MaintenanceStep.VerifyCandidate) throw primary })
        val confirmation = P5bOracle.confirmed(f,c,1); val result = AtomicReference<MaintenanceResult?>()
        val thread = Thread { result.set(c.changeSecret(confirmation)) }; thread.start()
        try {
            check(entered.await(20,TimeUnit.SECONDS)); check(!f.core.owner.awaitQuiescent(0))
            f.docs.store(f.docs.source).use { attempted -> P5bOracle.reject { ResourceOwner(attempted) } }
            check(MaintenanceRecovery().resolveChangeSecret(local,docs,RecoveryChoice.Source,RekeyRecoveryCredentials(emptyMap(),emptyMap()),f.core.owner).code == RecoveryCode.SwitchUnknown)
        } finally { finish.countDown(); thread.join(20000) }
        check(!thread.isAlive && result.get()!!.failure === primary && primary.suppressed.isNotEmpty())
        check(f.docs.handles.all { it.closeCount == 1 })
    }
    @Test fun jvmSyntheticFileChannelRekeyAndReopen() {
        val root = Files.createTempDirectory("p5b-owned-"); var primary: Throwable? = null
        try {
            val source = root.resolve("source.cscroll"); Files.write(source,P5bImages.rich())
            val docs = JvmMaintenanceDocuments(source,Files.createDirectory(root.resolve("backup")),Files.createDirectory(root.resolve("candidate")))
            val owner = ResourceOwner(docs.open(docs.source)); val core = TransactionCore(owner).also { it.attach(P3Fixtures.key(1)) }
            val local = FaultLocalStateStore(LocalState(fingerprint(owner.store,docs.source.uri),null))
            try {
                val c = MaintenanceCoordinator(core,docs,local)
                val request = c.beginChangeSecret(core.authorizeMaintenance(),docs.destinations,P3Fixtures.id(2),3,parameters=KdfParameters(16384,2))
                SecretEncoding.text(P3Fixtures.DECOY).use { old -> SecretEncoding.text(P5bOracle.NEW).use { next ->
                    val r = c.changeSecret(c.confirm(c.credentials(request,old,next,next))); P5bOracle.success(r)
                    docs.open(r.candidate!!).use { store ->
                        SecretEncoding.text(P5bOracle.NEW).use { secret -> ContainerReader(store).open(secret).use { opened ->
                            ContainerReader(store).verifyAll(ContainerReader(store).snapshot(),opened.key)
                        } }
                    }
                    check(Files.exists(source) && r.backup != null && r.retainedFilesKeepOldProtection)
                } }
            } finally { owner.resolved(); core.close() }
        } catch(e: Throwable) { primary = e; throw e }
        finally {
            try {
                val owned = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toArray { n -> arrayOfNulls<java.nio.file.Path>(n) } }
                owned.forEach { check(it!!.normalize().startsWith(root)); Files.delete(it) }
            } catch(e: Throwable) { if(primary != null) primary!!.addSuppressed(e) else throw e }
        }
    }
    @Test fun liveUnknownUsesPinnedOwnersAndNeedsNewCandidateSecret() = P5aFixture(P4Fixtures.image(true)).use { f ->
        val c = f.coordinator { if(it == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.After) }
        val r = c.changeSecret(P5bOracle.confirmed(f,c,1)); check(r.code == MaintenanceCode.SwitchUnknown)
        check(f.core.owner.reconciliationRequired && f.core.owner.phase != OwnerPhase.Released)
        f.local.fault = null
        SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P5bOracle.NEW).use { next ->
            val source = mapOf(P3Fixtures.id(1) to old)
            check(c.reconcileChangeSecret(RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,source)).code == RecoveryCode.CredentialsRejected)
            check(f.core.owner.reconciliationRequired && !f.core.owner.release())
            check(c.reconcileChangeSecret(RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,mapOf(P3Fixtures.id(1) to next))).code == RecoveryCode.CandidateConfirmed)
            check(f.core.owner.phase == OwnerPhase.Released && f.docs.creates == 2)
        } }
        check(f.docs.handles.all { it.closeCount == 1 })
    }
    @Test fun commitUnknownCannotEnterMaintenanceAndVerifiedCandidateReplacementCannotSwitch() {
        P5aFixture(P4Fixtures.image()).use { f ->
            val repo = com.fantadrom.copperscroll.storage.vfs.ZoneRepository(f.core)
            val token = repo.issueSaveNote(repo.stamp(),P3Fixtures.id(199),repo.roots().first,"pending",P4Fixtures.note("synthetic"),now=1)
            var publishing = false
            f.store.observer = { e ->
                if(e.action == IoAction.Write && e.offset < 131072) publishing = true
                if(publishing && e.action == IoAction.Force) f.store.fault = IoFault(e.index,FaultMoment.After)
            }
            check(repo.submit(token).code == WriteCode.CommitUnknown)
            P5bOracle.reject { f.coordinator().beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),3) }
            check(f.docs.creates == 0 && f.local.read().journal == null)
        }
        P5aFixture(P4Fixtures.image(true)).use { f ->
            var created: Artifact? = null; f.docs.onCreate = { created = it }
            val c = f.coordinator { if(it == MaintenanceStep.AcceptSwitch) {
                val medium = f.docs.media.getValue(created!!.uri).second
                val before = medium.bytes(); medium.corrupt(150000,(before[150000].toInt() xor 1).toByte())
            } }
            val r = c.changeSecret(P5bOracle.confirmed(f,c,2))
            check(r.code == MaintenanceCode.Conflict && f.local.read().binding!!.uri == f.docs.source.uri && r.backup != null)
        }
    }

    @Test fun acceptedSwitchThenRevokeIsUnreportedAndRequiresExplicitResolution() = P5aFixture(P4Fixtures.image(true)).use { f ->
        val local = object: LocalStateStore by f.local {
            override fun replace(state: LocalState): LocalWriteCode {
                if(state.journal?.stage == JournalStage.Activated) f.core.owner.revoke()
                return f.local.replace(state)
            }
        }
        val c = MaintenanceCoordinator(f.core,f.docs,local)
        val r = c.changeSecret(P5bOracle.confirmed(f,c,2)); P5bOracle.success(r)
        check(r.outcome == JournalOutcome.Unreported && r.locked && f.core.owner.phase == OwnerPhase.Released)
        val owner = ResourceOwner(f.docs.open(r.candidate!!))
        try { P5bOracle.reject { TransactionCore.openBound(owner,f.local,P3Fixtures.key(1)) } }
        finally { owner.resolved(); owner.close() }
        SecretEncoding.text(P3Fixtures.MAIN).use { main -> SecretEncoding.text(P3Fixtures.DECOY).use { old -> SecretEncoding.text(P5bOracle.NEW).use { next ->
            val contexts = RekeyRecoveryCredentials(mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to old),mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to next))
            val resolved = c.reconcileChangeSecret(RecoveryChoice.Candidate,contexts)
            check(resolved.code == RecoveryCode.CandidateConfirmed && resolved.priorOutcome == JournalOutcome.Unreported && resolved.locked)
        } } }
    }
    @Test fun revokeBeforeDispatchClearsOldAndNewHandlesWithoutFiles() {
        for(role in 1..2) P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator(); val confirmation = P5bOracle.confirmed(f,c,role)
            val p = confirmation.proposal; val current = p.currentTarget!!.key; val next = p.prepared!!.key
            f.core.owner.revoke(); P5bOracle.reject { c.changeSecret(confirmation) }
            P5bOracle.reject { current.ensureOpen() }; P5bOracle.reject { next.ensureOpen() }
            check(f.core.owner.phase == OwnerPhase.Released && f.docs.creates == 0 && f.docs.handles.all { it.closeCount == 1 })
        }
    }

    @Test fun staleSourceBeforeConfirmationRetiresPreparedContext() = P5aFixture(P4Fixtures.image()).use { f ->
        val c = f.coordinator()
        val request = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(1),3,parameters=KdfParameters(16384,2))
        SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P5bOracle.NEW).use { next ->
            val proposal = c.credentials(request,old,next,next)
            val current = proposal.currentTarget!!.key; val prepared = proposal.prepared!!.key
            f.store.medium.corrupt(f.store.size.toInt()-1,1)
            P5bOracle.reject { c.confirm(proposal) }
            P5bOracle.reject { current.ensureOpen() }; P5bOracle.reject { prepared.ensureOpen() }
            check(f.docs.creates == 0)
        } }
    }

}
