package com.fantadrom.copperscroll.storage.session

import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.access.CanonicalSecret
import com.fantadrom.copperscroll.storage.transaction.*
import java.util.concurrent.*

/** Retain with the coordinator, never with an Activity. Sources/jobs must not capture UI objects.
 * One command and one unlock are admitted by the coordinator; two reserved queue slots are for
 * revoke/cleanup. Interruption is not a resource-lifetime protocol. No shutdownNow(). */
class SessionWorker(val coordinator: SessionCoordinator) {
    interface LoginSource {
        fun owner(): ResourceOwner
        /** Borrowed; its caller retains/closes its local-state owner after all session/maintenance use. */
        fun local(): LocalStateStore
        fun secret(): CanonicalSecret
    }
    private val executor=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(4),
        { r -> Thread(r,"CopperScroll-session").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    @Volatile var lastOutcome: WriteCode?=null; private set
    @Volatile var cleanupFailed=false; private set
    class Failure(val type: String,val frames: List<String>,val suppressed: Int)
    @Volatile var lastFailure: Failure?=null; private set
    private fun record(e: Throwable) { lastFailure=Failure(e.javaClass.name,e.stackTrace.take(12).map { "${it.className}.${it.methodName}:${it.lineNumber}" },e.suppressed.size) }
    private fun release(owner: ResourceOwner,primary: Throwable?=null) {
        try { owner.release() } catch(clean: Throwable) { primary?.addSuppressed(clean); cleanupFailed=true; record(primary ?: clean) }
    }
    fun unlock(attempt: SessionCoordinator.Unlock,source: LoginSource) {
        if(!coordinator.submitUnlock(attempt)) return
        try { executor.execute {
            var owner: ResourceOwner?=null
            try {
                owner=source.owner()
                if(!coordinator.claimUnlock(attempt,owner)) { owner.revoke(); release(owner); return@execute }
                val result=source.secret().use { SessionAuthentication.authenticate(owner,source.local(),attempt.document,it) }
                if(!coordinator.completeUnlock(attempt,result)) { owner.revoke(); release(owner) }
            } catch(e: Throwable) { coordinator.rejectUnlock(attempt); owner?.let { it.revoke(); release(it,e) }; record(e) }
            finally { owner?.let { coordinator.unlockFinished(it) }; coordinator.unlockFailed(attempt); coordinator.unlockWorkFinished(attempt); drainRevocation() }
        } } catch(e: RejectedExecutionException) { coordinator.rejectUnlock(attempt); coordinator.unlockWorkFinished(attempt); throw e }
    }
    /** Worker preparation has already encoded this immutable payload. Retire replaced payload here. */
    fun offerCheckpoint(c: SessionCoordinator.Capability,next: FinalCheckpoint) {
        try { coordinator.offerCheckpoint(c,next)?.let { it.core.owner.retire(it) } }
        catch(e: Throwable) { throw e } // Rejected payload remains owner-owned until explicit retirement/release.
    }
    /** Background operation; result publication must separately use canPublish(ticket). */
    fun command(ticket: SessionCoordinator.CommandTicket,job: () -> Unit) {
        if(!coordinator.claimCommand(ticket)) return
        executor.execute {
            try { if(coordinator.canRun(ticket)) job() }
            catch(e: Throwable) { record(e); coordinator.hardRevoke(SessionCoordinator.Reason.Explicit) }
            finally { coordinator.commandFinished(ticket); drainRevocation() }
        }
    }
    /** Called AFTER immediate presentation update; never waits for the running task. */
    fun drainRevocation() {
        val r=coordinator.takeRevocation() ?: return
        executor.execute {
            val core=r.session?.core
            var primary: Throwable?=null
            fun attempt(block: ()->Unit) {
                try { block() } catch(e: Throwable) { if(primary==null) primary=e else primary!!.addSuppressed(e) }
            }
            attempt { if(r.closing!=null) lastOutcome=r.closing.core.submitClosingSave(r.closing).code }
            attempt { r.unused?.let { core?.owner?.retire(it) } }
            attempt { r.closing?.let { it.core.owner.retire(it.checkpoint) } }
            attempt { core?.let { it.owner.release() } }
            core?.let { coordinator.ownerFinished(it) }
            attempt { r.pendingOwner?.let { it.release() } }
            r.pendingOwner?.let { coordinator.unlockFinished(it) }
            primary?.let { cleanupFailed=true; record(it) }

        }
    }
    /** Explicit reconciliation only. Does not retry a switch/rekey, publish Saved, or unlock. */
    private val resolving=java.util.concurrent.atomic.AtomicBoolean()
    fun resolve(core: TransactionCore) {
        requireIo(core.owner.accessRevoked && core.owner.reconciliationRequired,IoCode.AccessLost)
        if(!resolving.compareAndSet(false,true)) return
        executor.execute {
            try {
                lastOutcome=core.reconcile().code
                if(!core.owner.reconciliationRequired) { release(core.owner); coordinator.ownerFinished(core) }
            } catch(e: Throwable) { record(e) } finally { resolving.set(false) }
        }
    }
    fun stopWhenIdle() { coordinator.hardRevoke(SessionCoordinator.Reason.Destroyed); drainRevocation(); executor.shutdown() }
}
