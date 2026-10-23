package com.fantadrom.copperscroll.storage.session

import com.fantadrom.copperscroll.sessiontest.SyntheticSession
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.format.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

class SessionCoordinatorTest {
    class Fixture : AutoCloseable {
        val synthetic=SyntheticSession(); val c=SessionCoordinator(); val frames=ArrayList<SessionCoordinator.Snapshot>()
        val opened=CountDownLatch(1)
        var w=c.attach({ frames.add(it); if(it.presented) opened.countDown() }); var seq=0L
        init { c.selectDocument(synthetic.document); signal() }
        fun signal(visible: Boolean=true,interactive: Boolean=true,unlocked: Boolean=true,top: Boolean=true,focus: Boolean=true,own: SessionCoordinator.OwnWindow?=null) {
            c.signals(w,++seq,SessionCoordinator.Signals(visible,interactive,unlocked,top,focus,own))
        }
        fun login(decoy: Boolean=false)=synthetic.login(c,decoy)
        override fun close() {
            c.hardRevoke(SessionCoordinator.Reason.Explicit)
            c.takeRevocation()?.let { r ->
                r.closing?.let { it.core.submitClosingSave(it); it.core.owner.retire(it.checkpoint) }
                r.unused?.let { r.session!!.core.owner.retire(it) }
                r.session?.core?.let { if(it.owner.reconciliationRequired) it.reconcile(); it.owner.release(); c.ownerFinished(it) }
                r.pendingOwner?.let { it.release(); c.unlockFinished(it) }
            }
        }
    }
    private fun reject(block: () -> Unit) { try { block(); fail("Expected refusal") } catch(_: StoreException) {} }
    @Test fun allForegroundFactsAndFreshProcessLocked() {
        for(index in 0..4) Fixture().use { f ->
            val flags=MutableList(5) { true }; flags[index]=false
            f.signal(flags[0],flags[1],flags[2],flags[3],flags[4]); reject { f.c.beginUnlock() }
            assertEquals(SessionCoordinator.Access.Locked,SessionCoordinator().snapshot().access)
        }
    }
    @Test fun realMainAndDecoyLoginAndHiddenMenuBypass() {
        for(decoy in listOf(false,true)) Fixture().use { f ->
            val s=f.login(decoy); assertEquals(if(decoy) UserZone.Decoy else UserZone.Main,s.zone)
            val c=f.c.currentCapability()!!
            if(decoy) for(k in listOf(SessionCoordinator.Command.Backup,SessionCoordinator.Command.Maintenance,SessionCoordinator.Command.ChangeDocument)) reject { f.c.admit(c,k) }
            else { val t=f.c.admit(c,SessionCoordinator.Command.Maintenance); f.c.commandFinished(t) }
            val foreign=SessionCoordinator(); reject { foreign.admit(c,SessionCoordinator.Command.Read) }
        }
    }
    @Test fun eachRevokeIdempotentAndReturnNeverUnlocks() {
        for(reason in SessionCoordinator.Reason.entries.filter { it!=SessionCoordinator.Reason.Picker }) Fixture().use { f ->
            f.login(); val c=f.c.currentCapability()!!; val ticket=f.c.admit(c,SessionCoordinator.Command.Read)
            f.c.hardRevoke(reason); val epoch=f.c.snapshot().epoch
            assertFalse(f.c.snapshot().presented); assertFalse(f.c.canPublish(ticket)); reject { f.c.admit(c,SessionCoordinator.Command.Edit) }
            f.c.hardRevoke(reason); assertEquals(epoch,f.c.snapshot().epoch); f.signal(); assertFalse(f.c.snapshot().presented)
        }
    }
    @Test fun focusScopesNeverOverrideScreenTopVisibilityOrForeignWindow() {
        for(kind in SessionCoordinator.ScopeKind.entries) Fixture().use { f ->
            f.login(); val own=f.c.registerOwnWindow(f.w,kind)
            f.signal(focus=false,own=own); assertTrue(f.c.snapshot().presented)
            f.signal(interactive=false,focus=false,own=own); assertFalse(f.c.snapshot().presented)
        }
        Fixture().use { f -> f.login(); val scope=f.c.registerOwnWindow(f.w,SessionCoordinator.ScopeKind.Modal)
            f.c.closeOwnWindow(scope); f.signal(focus=false,own=scope); assertFalse(f.c.snapshot().presented) }
    }
    @Test fun handoffPreservesEpochButOldWindowAndHomeCannotOpen() = Fixture().use { f ->
        val s=f.login(); val epoch=f.c.snapshot().epoch; val old=f.w
        val h=f.c.beginHandoff(old); assertFalse(f.c.snapshot().presented)
        f.c.detach(old,h); f.w=f.c.attach({ f.frames.add(it) },h); f.seq=0
        f.signal(); assertEquals(epoch,f.c.snapshot().epoch); assertTrue(f.c.snapshot().presented)
        assertSame(s.core.owner,s.core.owner); f.c.signals(old,Long.MAX_VALUE,SessionCoordinator.Signals(false,false,false,false,false))
        assertTrue(f.c.snapshot().presented); assertFalse(f.c.isWindowCurrent(old,epoch)); assertTrue(f.c.isWindowCurrent(f.w,epoch))
        f.c.beginHandoff(f.w); f.c.hardRevoke(SessionCoordinator.Reason.Home); f.signal(); assertFalse(f.c.snapshot().presented)
    }
    @Test fun lateSuccessfulLoginAndNewAttemptDisposedOnlyAfterWorkerReturns() = Fixture().use { f ->
        val t=f.c.beginUnlock(); val owner=f.synthetic.owner(); check(f.c.claimUnlock(t,owner))
        val result=SecretEncoding.text(SyntheticSession.MAIN).use { SessionAuthentication.authenticate(owner,f.synthetic.local(owner.store),f.synthetic.document,it) }
        f.c.hardRevoke(SessionCoordinator.Reason.Home)
        assertFalse(f.c.completeUnlock(t,result)); reject { f.c.beginUnlock() }
        assertFalse(owner.phase==OwnerPhase.Released); owner.release(); f.c.unlockFinished(owner); f.c.unlockWorkFinished(t); f.c.takeRevocation()
        f.signal(); val newer=f.c.beginUnlock(); assertFalse(f.c.claimUnlock(t,owner)); assertNotSame(t,newer)
    }
    @Test fun pickerSingleUseDocumentPurposeOldWindowAndScreenOff() = Fixture().use { f ->
        val token=f.c.beginPicker("BackupTree")
        f.signal(visible=false,top=false,focus=false)
        assertFalse(f.c.consumePicker(f.w,token,"CandidateTree",f.synthetic.document))
        assertTrue(f.c.consumePicker(f.w,token,"BackupTree",f.synthetic.document))
        assertFalse(f.c.consumePicker(f.w,token,"BackupTree",f.synthetic.document))
        f.signal(); val next=f.c.beginPicker("BackupTree"); f.c.hardRevoke(SessionCoordinator.Reason.ScreenOff)
        assertFalse(f.c.consumePicker(f.w,next,"BackupTree",f.synthetic.document))
    }
    @Test fun inFlightNThenOneLastValidNPlusOneNoCoreMutexOnRevoke() = Fixture().use { f ->
        val s=f.login(); val repo=ZoneRepository(s.core); val id=ZoneRepository.newId(); val parent=repo.roots().first
        val draft=CardDraftSession(repo,id,parent); draft.edit("first",CardBody())
        val save=draft.prepareSave()!!
        draft.edit("last valid",CardBody()); val checkpoint=draft.prepareFinalCheckpoint()!!
        draft.edit("invalid",CardBody(),icon="INVALID")
        f.c.offerCheckpoint(f.c.currentCapability()!!,checkpoint)
        reject { f.c.admit(f.c.currentCapability()!!,SessionCoordinator.Command.Maintenance) }
        val entered=CountDownLatch(1); val resume=CountDownLatch(1); val outcome=AtomicReference<WriteOutcome>(); val errors=AtomicReference<Throwable>()
        f.synthetic.lastStore!!.observer={ if(it.action==IoAction.Force && entered.count>0) { entered.countDown(); check(resume.await(10,TimeUnit.SECONDS)) } }
        val thread=Thread { try { outcome.set(draft.submit(save)) } catch(e: Throwable) { errors.set(e) } }; thread.start()
        assertTrue(entered.await(10,TimeUnit.SECONDS))
        f.c.hardRevoke(SessionCoordinator.Reason.Home); assertFalse(f.c.snapshot().presented)
        reject { draft.edit("late",CardBody()) }; reject { draft.prepareFinalCheckpoint() }; assertFalse(draft.saved)
        assertFalse(s.core.owner.awaitQuiescent(0)); assertFalse(s.core.owner.release()); reject { f.synthetic.owner() }
        val r=f.c.takeRevocation()!!; f.c.hardRevoke(SessionCoordinator.Reason.ScreenOff); assertNull(f.c.takeRevocation())
        resume.countDown(); thread.join(10000); errors.get()?.let { throw it }; assertEquals(WriteCode.Committed,outcome.get().code)
        assertEquals(WriteCode.Committed,s.core.submitClosingSave(r.closing!!).code)
        assertEquals(WriteCode.AccessLost,s.core.submitClosingSave(r.closing).code)
        assertFalse(f.c.snapshot().presented); s.core.owner.retire(checkpoint); s.core.owner.release(); f.c.ownerFinished(s.core)
        f.signal(); val reopened=f.login(); assertTrue("FINAL_CARD_WRONG",ZoneRepository(reopened.core).readCard(id).metadata.name=="last valid")
    }
    @Test fun noPreparedCheckpointDoesNotCreatePayloadAfterRevoke() = Fixture().use { f ->
        val s=f.login(); val c=f.c.currentCapability()!!
        val repo=ZoneRepository(s.core); val checkpoint=repo.prepareFinalCard(ZoneRepository.newId(),repo.roots().first,CardDraft("valid",CardBody(),1))
        f.c.hardRevoke(SessionCoordinator.Reason.Explicit)
        assertNull(f.c.takeRevocation()!!.closing)
        reject { f.c.offerCheckpoint(c,checkpoint) }
        s.core.owner.release(); f.c.ownerFinished(s.core)
    }
    @Test fun unknownPinsOwnerUntilExplicitReconcileAndNeverSaysSaved() = Fixture().use { f ->
        val session=f.login(); val repo=ZoneRepository(session.core)
        val checkpoint=repo.prepareFinalCard(ZoneRepository.newId(),repo.roots().first,CardDraft("valid",CardBody(),1))
        f.c.offerCheckpoint(f.c.currentCapability()!!,checkpoint)
        f.c.hardRevoke(SessionCoordinator.Reason.Home); val r=f.c.takeRevocation()!!
        var force=0
        f.synthetic.lastStore!!.observer={ if(it.action==IoAction.Force && ++force==2) f.synthetic.lastStore!!.fault=IoFault(it.index,FaultMoment.After) }
        assertEquals(WriteCode.CommitUnknown,session.core.submitClosingSave(r.closing!!).code)
        assertTrue(session.core.owner.reconciliationRequired); assertFalse(session.core.owner.release())
        f.signal(); reject { f.c.beginUnlock() }; reject { f.synthetic.owner() }
        f.synthetic.lastStore!!.observer={}; f.synthetic.lastStore!!.fault=null
        assertEquals(WriteCode.Committed,session.core.reconcile().code); assertFalse(f.c.snapshot().presented)
        session.core.owner.retire(checkpoint); session.core.owner.release(); f.c.ownerFinished(session.core)
    }
    @Test fun presentationCallbackRunsOutsideAdmissionMonitor() = Fixture().use { f ->
        f.c.detach(f.w); f.c.takeRevocation()
        val inside=CountDownLatch(1); val resume=CountDownLatch(1)
        val thread=Thread { f.w=f.c.attach({ inside.countDown(); check(resume.await(10,TimeUnit.SECONDS)) }) }
        thread.start(); assertTrue(inside.await(10,TimeUnit.SECONDS))
        // This would deadlock if attach's callback held the serialized policy boundary.
        assertEquals(SessionCoordinator.Access.Locked,f.c.snapshot().access)
        resume.countDown(); thread.join(10000); assertFalse(thread.isAlive)
    }
    @Test fun newAttemptAndDocumentInvalidateOldBeforeClaim() = Fixture().use { f ->
        val old=f.c.beginUnlock(); val newer=f.c.beginUnlock(); val owner=f.synthetic.owner()
        try { assertFalse(f.c.claimUnlock(old,owner)); assertTrue(f.c.claimUnlock(newer,owner)) }
        finally { f.c.hardRevoke(SessionCoordinator.Reason.DocumentChanged); owner.release(); f.c.unlockFinished(owner); f.c.unlockWorkFinished(newer); f.c.takeRevocation() }
        f.c.selectDocument(f.synthetic.document); assertFalse(f.c.claimUnlock(newer,owner))
    }
    @Test fun pickerRotationAndCancelForeignTokenAndEpochOverflowRefuse() = Fixture().use { f ->
        val token=f.c.beginPicker("OpenOriginal"); val old=f.w; val h=f.c.beginHandoff(old)
        f.c.detach(old,h); f.w=f.c.attach({},h); f.seq=0; f.signal()
        assertFalse(f.c.consumePicker(old,token,"OpenOriginal",f.synthetic.document))
        f.c.cancelPicker(token); assertFalse(f.c.consumePicker(f.w,token,"OpenOriginal",f.synthetic.document))
        val foreign=SessionCoordinator(); assertFalse(foreign.consumePicker(f.w,token,"OpenOriginal",f.synthetic.document))
        val field=SessionCoordinator::class.java.getDeclaredField("epoch"); field.isAccessible=true; field.setLong(f.c,Long.MAX_VALUE)
        reject { f.c.beginPicker("OpenOriginal") }; assertFalse(f.c.snapshot().pickerPending)
        reject { f.c.beginUnlock() }; assertFalse(f.c.snapshot().presented)
    }

    @Test fun acceptSwitchAndRevokeHaveBothOrdersWithoutSecondAccept() {
        for(acceptFirst in listOf(false,true)) Fixture().use { f ->
            val session=f.login(); val c=f.c.currentCapability()!!
            val ticket=f.c.admit(c,SessionCoordinator.Command.Maintenance)
            assertFalse(f.c.snapshot().presented); assertTrue(f.c.claimCommand(ticket)); assertFalse(f.c.canPublish(ticket))
            val owner=session.core.owner; val permit=owner.accept()
            owner.execute(permit) {
                if(acceptFirst) assertTrue(owner.acceptSwitch(permit))
                f.c.hardRevoke(SessionCoordinator.Reason.Home)
                assertFalse(owner.acceptSwitch(permit))
                assertEquals(acceptFirst,owner.switchUnreported)
            }
            f.c.commandFinished(ticket); assertFalse(f.c.snapshot().presented)
        }
    }
    @Test fun ownerCleanupPreservesPrimaryAndClosesExactlyOnce() {
        val store=FaultStore(); val owner=ResourceOwner(store)
        val primary=IllegalStateException("PRIMARY"); val secondary=IllegalStateException("CLEANUP")
        owner.adopt(AutoCloseable { throw secondary }); owner.adopt(AutoCloseable { throw primary })
        try { owner.release(); fail("Expected close error") } catch(e: IllegalStateException) { assertSame(primary,e); assertSame(secondary,e.suppressed.single()) }
        assertEquals(OwnerPhase.Released,owner.phase); assertTrue(owner.release()); assertEquals(1,store.closeCount)
    }

    @Test fun duplicateSubmissionCannotFillCleanupQueueOrRunTwice() = Fixture().use { f ->
        val session=f.login(); val worker=SessionWorker(f.c)
        val ticket=f.c.admit(f.c.currentCapability()!!,SessionCoordinator.Command.Save)
        val entered=CountDownLatch(1); val resume=CountDownLatch(1); val count=java.util.concurrent.atomic.AtomicInteger()
        val permit=session.core.owner.accept()
        repeat(20) { worker.command(ticket) { session.core.owner.execute(permit) { count.incrementAndGet(); entered.countDown(); check(resume.await(10,TimeUnit.SECONDS)) } } }
        assertTrue(entered.await(10,TimeUnit.SECONDS)); f.c.hardRevoke(SessionCoordinator.Reason.Home); worker.drainRevocation()
        assertFalse(session.core.owner.release()); resume.countDown(); worker.stopWhenIdle()
        val field=SessionWorker::class.java.getDeclaredField("executor"); field.isAccessible=true
        assertTrue((field.get(worker) as ExecutorService).awaitTermination(10,TimeUnit.SECONDS))
        assertEquals(1,count.get()); assertEquals(OwnerPhase.Released,session.core.owner.phase); assertFalse(f.c.snapshot().presented)
    }

    @Test fun blockingFactoryBeforeOwnerClaimRemainsBusyAfterRevoke() = Fixture().use { f ->
        val worker=SessionWorker(f.c); val attempt=f.c.beginUnlock()
        val entered=CountDownLatch(1); val resume=CountDownLatch(1)
        worker.unlock(attempt,object: SessionWorker.LoginSource {
            override fun owner(): ResourceOwner { entered.countDown(); check(resume.await(10,TimeUnit.SECONDS)); return f.synthetic.owner() }
            override fun local(): LocalStateStore=error("STALE_FACTORY_MUST_NOT_LOGIN")
            override fun secret(): CanonicalSecret=error("STALE_FACTORY_MUST_NOT_LOGIN")
        })
        assertTrue(entered.await(10,TimeUnit.SECONDS)); f.c.hardRevoke(SessionCoordinator.Reason.Home); worker.drainRevocation(); f.signal()
        assertNull(f.c.snapshot().owner); assertTrue(f.c.snapshot().pendingWork)
        repeat(20) { reject { f.c.beginUnlock() } }
        resume.countDown(); worker.stopWhenIdle()
        val field=SessionWorker::class.java.getDeclaredField("executor"); field.isAccessible=true
        assertTrue((field.get(worker) as ExecutorService).awaitTermination(10,TimeUnit.SECONDS))
        assertFalse(f.c.snapshot().pendingWork); assertNull(worker.lastFailure); assertEquals(1,f.synthetic.lastStore!!.closeCount)
        val next=f.c.beginUnlock(); assertFalse(f.c.submitUnlock(attempt)); assertNotSame(attempt,next)
        val fresh=SessionWorker(f.c)
        fresh.unlock(next,object: SessionWorker.LoginSource {
            private var owner: ResourceOwner?=null
            override fun owner()=f.synthetic.owner().also { owner=it }
            override fun local()=f.synthetic.local(checkNotNull(owner).store)
            override fun secret()=SecretEncoding.text(SyntheticSession.MAIN)
        })
        assertTrue(f.opened.await(15,TimeUnit.SECONDS))
        assertEquals(SessionCoordinator.Access.OpenMain,f.c.snapshot().access)
        fresh.stopWhenIdle()
        assertTrue((field.get(fresh) as ExecutorService).awaitTermination(10,TimeUnit.SECONDS))
        assertNull(fresh.lastFailure); assertNull(f.c.snapshot().owner)
    }

}
