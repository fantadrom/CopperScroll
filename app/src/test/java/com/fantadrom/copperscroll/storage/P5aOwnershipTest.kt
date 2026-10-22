package com.fantadrom.copperscroll.storage
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.runtimechecks.*
import org.junit.Test
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class P5aOwnershipTest {
    @Test fun admissionAlreadyClosedBeforeErrorLeavesCoreAndHoldSurvives() {
        P5aFixture(P4Fixtures.image()).use { f ->
            var late: WritePermit? = null
            try {
                f.core.maintenanceAccess(f.core.authorizeMaintenance(),freeze=true) { _,_,_,_ ->
                    late = f.core.owner.accept(); throw StoreException(IoCode.Eio)
                }
            } catch(e: StoreException) { check(e.code == IoCode.Eio) }
            try {
                check(f.core.owner.accessRevoked && !f.core.owner.revokeRequested)
                check(f.core.owner.phase == OwnerPhase.StopRequested && !f.core.owner.release() && !f.core.owner.awaitQuiescent(0))
                try { f.core.owner.execute(late!!) { error("Late writer entered") }; error("Expected denial") }
                catch(e: StoreException) { check(e.code == IoCode.AccessLost) }
                check(!f.core.owner.awaitQuiescent(1))
            } finally { f.core.owner.finishMaintenanceIo() }
        }
    }
    @Test fun abortMetadataIoStillOwnsLeaseAndBlocksRecovery() {
        P5aFixture(P4Fixtures.image()).use { f ->
            val entered = CountDownLatch(1); val resume = CountDownLatch(1); var reconciles = 0
            val port = object: LocalStateStore {
                override val durableLocalState = true
                override val reconciliationRequired get() = f.local.reconciliationRequired
                override fun read() = f.local.read()
                override fun reconcile(): LocalState { reconciles++; return f.local.reconcile() }
                override fun replace(state: LocalState): LocalWriteCode {
                    if(state.journal!!.stage == JournalStage.Aborted) { entered.countDown(); check(resume.await(15,TimeUnit.SECONDS)) }
                    return f.local.replace(state)
                }
            }
            val c = MaintenanceCoordinator(f.core,f.docs,port) { if(it == MaintenanceStep.VerifyCandidate) throw StoreException(IoCode.Eio) }
            val confirmation = f.confirmed(c); val result = AtomicReference<MaintenanceResult>()
            val worker = Thread { result.set(c.compact(confirmation)) }; worker.start()
            try {
                check(entered.await(15,TimeUnit.SECONDS))
                check(!f.core.owner.awaitQuiescent(1) && !f.core.owner.release())
                val extra = f.docs.store(f.docs.source)
                try { ResourceOwner(extra); error("Second owner") } catch(e: StoreException) { check(e.code == IoCode.Busy) } finally { extra.close() }
                check(c.reconcile(RecoveryChoice.Source,emptyMap()).code == RecoveryCode.SwitchUnknown && reconciles == 0)
            } finally { resume.countDown(); worker.join(15000) }
            check(!worker.isAlive && result.get().code == MaintenanceCode.IoFailure && f.core.owner.phase == OwnerPhase.Released)
        }
    }
    @Test fun revokeDuringCredentialsClosesMainOwnerAfterRealKdfQuiescence() {
        P5aFixture(P4Fixtures.image(true)).use { f ->
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            val manager = CredentialManager(SystemEntropy(),ArgonDerivation { secret,envelope ->
                entered.countDown(); check(resume.await(15,TimeUnit.SECONDS)); BcArgon.derive(secret,envelope)
            })
            val c = MaintenanceCoordinator(f.core,f.docs,f.local,manager,{})
            val request = c.begin(f.core.authorizeMaintenance(),f.destinations)
            val result = AtomicReference<Throwable>()
            val worker = Thread {
                try { SecretEncoding.text(P3Fixtures.DECOY).use { c.credentials(request,it).close() }; error("Unexpected late proposal") }
                catch(e: Throwable) { result.set(e) }
            }; worker.start()
            try {
                check(entered.await(15,TimeUnit.SECONDS)); f.core.owner.revoke()
                check(!f.core.owner.release() && !f.core.owner.awaitQuiescent(1) && f.docs.creates == 0)
            } finally { resume.countDown(); worker.join(15000) }
            check(!worker.isAlive && (result.get() as MaintenanceRefusal).code == MaintenanceCode.Cancelled)
            check(f.core.owner.phase == OwnerPhase.Released && f.store.closeCount == 1 && f.docs.creates == 0)
        }
    }
    @Test fun failedReopenCloseIsNotRetriedAndLeaseIsReleased() {
        val base = FaultStore(); var closes = 0; var lockCloses = 0; var opens = 0
        val failure = StoreException(IoCode.Eio)
        val store = object : RandomAccessStore by base {
            override fun close() { closes++; base.close(); throw failure }
            override fun acquireExclusive(): AutoCloseable {
                val lock = base.acquireExclusive(); return AutoCloseable { lockCloses++; lock.close() }
            }
        }
        val owner = ResourceOwner(store); val permit = owner.accept()
        try { owner.execute(permit) { owner.reopen(permit) { opens++; FaultStore() } }; error("Expected close error") }
        catch(e: StoreException) { check(e === failure) }
        owner.close(); owner.close()
        check(closes == 1 && lockCloses == 1 && opens == 0 && owner.phase == OwnerPhase.Released)
        ResourceOwner(FaultStore(identity=store.identity)).close()
    }
    @Test fun cleanupKeepsFirstFailureAndClosesEveryResourceOnce() {
        val base = FaultStore(); val a = Exception("synthetic-A"); val b = Exception("synthetic-B"); val c = Exception("synthetic-C")
        var closes = 0; var resources = 0
        val owner = ResourceOwner(object: RandomAccessStore by base {
            override fun close() { closes++; base.close(); throw c }
        })
        owner.adopt(AutoCloseable { resources++; throw a }); owner.adopt(AutoCloseable { resources++; throw b })
        try { owner.release(); error("Expected cleanup error") }
        catch(e: Exception) { check(e === b && e.suppressed.toList() == listOf(a,c)) }
        check(owner.release() && closes == 1 && resources == 2 && base.closeCount == 1)
    }
    @Test fun copyWriteFailureSurvivesCleanupAsPrimary() {
        val f = P5aFixture(P4Fixtures.image())
        try {
            val cleanup = Exception("synthetic-close"); var closes = 0
            val docs = object: MaintenanceDocuments by f.docs {
                override fun createNew(destination: String,name: String): Pair<Artifact,RandomAccessStore> {
                    val (artifact,base) = f.docs.createNew(destination,name)
                    return artifact to object: RandomAccessStore by base {
                        override fun writeFullyAt(offset: Long,bytes: ByteArray) { throw StoreException(IoCode.NoSpace) }
                        override fun close() { closes++; base.close(); throw cleanup }
                    }
                }
            }
            val permit = f.core.owner.accept()
            try {
                f.core.owner.execute(permit) { SnapshotCopier().copy(f.core.owner,permit,f.core.currentSnapshot(),f.docs.source,docs,
                    "synthetic:/backup",emptyMap()) }
                error("Expected copy error")
            } catch(e: StoreException) { check(e.code == IoCode.NoSpace && e.suppressed.toList() == listOf(cleanup)) }
            check(closes == 1)
        } finally { f.close() }
    }
}
