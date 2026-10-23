package com.fantadrom.copperscroll.storage.session

import com.fantadrom.copperscroll.sessiontest.*
import com.fantadrom.copperscroll.storage.access.SecretEncoding
import com.fantadrom.copperscroll.storage.io.StoreException
import org.junit.Assert.*
import org.junit.Test

class RuntimeForegroundFenceTest {
    private fun denied(action: ()->Unit) { try { action(); fail("EXPECTED_DENIAL") } catch(_: StoreException) {} }
    @Test fun staleFocusNeedsActualDepartureThenFreshResumeAndAllFacts() = SessionCoordinatorTest.Fixture(true).use { f ->
        f.login(); val lifecycle=RuntimeForegroundFence(); lifecycle.observe(RuntimeForegroundFence.Stage.Resumed)
        val before=lifecycle.resumedAt; val mark=lifecycle.sequence
        f.c.hardRevoke(SessionCoordinator.Reason.Home)
        val r=f.c.takeRevocation()!!; r.session!!.core.owner.release(); f.c.ownerFinished(r.session.core)
        // Hide and resource release can precede PAUSED; the old focus and coordinator facts remain true.
        assertTrue(f.c.snapshot().unlockReady); assertFalse(lifecycle.departedAfter(mark,true))
        lifecycle.observe(RuntimeForegroundFence.Stage.Paused); f.signal(top=false)
        assertFalse(lifecycle.foreground(f.c.snapshot(),true,before)); denied { f.c.beginUnlock() }
        val denied=f.c.diagnosticHistory().last { it.event==SessionCoordinator.DiagnosticEvent.UnlockDenied }
        assertEquals(false,denied.state.foreground.topResumed)
        lifecycle.observe(RuntimeForegroundFence.Stage.Stopped); f.signal(visible=false,top=false,focus=false)
        assertTrue(lifecycle.departedAfter(mark,false))
        lifecycle.observe(RuntimeForegroundFence.Stage.Started); f.signal(top=false)
        assertFalse(lifecycle.foreground(f.c.snapshot(),true,before)) // Focus before RESUMED is insufficient.
        lifecycle.observe(RuntimeForegroundFence.Stage.Resumed)
        assertFalse(lifecycle.loginReady(f.c.snapshot(),true)) // RESUMED before top-resumed is insufficient.
        f.signal(); assertTrue(lifecycle.foreground(f.c.snapshot(),true,before)); assertTrue(lifecycle.loginReady(f.c.snapshot(),true))
        assertFalse(f.c.snapshot().presented) // Foreground never performs automatic re-entry.
        f.login(); assertTrue(f.c.snapshot().presented)
    }
    @Test fun queuedPauseInvalidatesAnOffThreadReadBeforeTheUiAttempt() = SessionCoordinatorTest.Fixture(true).use { f ->
        val lifecycle=RuntimeForegroundFence(); lifecycle.observe(RuntimeForegroundFence.Stage.Resumed)
        val staleReady=f.c.snapshot().unlockReady; assertTrue(staleReady)
        var attempts=0; val queue=java.util.ArrayDeque<()->Unit>()
        queue.add { lifecycle.observe(RuntimeForegroundFence.Stage.Paused); f.signal(top=false) }
        queue.add { if(lifecycle.loginReady(f.c.snapshot(),true)) { attempts++; f.login() } }
        while(queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals(0,attempts); assertFalse(f.c.snapshot().presented)
        lifecycle.observe(RuntimeForegroundFence.Stage.Resumed); f.signal()
        if(lifecycle.loginReady(f.c.snapshot(),true)) { attempts++; f.login() }
        assertEquals(1,attempts); assertTrue(f.c.snapshot().presented)
    }
    @Test fun rejectedResultAndBrieflyOpenedThenRevokedAreDifferentOutcomes() {
        SessionCoordinatorTest.Fixture(true).use { f ->
            val t=f.c.beginUnlock(); assertTrue(f.c.submitUnlock(t)); val owner=f.synthetic.owner()
            assertTrue(f.c.claimUnlock(t,owner))
            val proof=SecretEncoding.text(SyntheticSession.MAIN).use { SessionAuthentication.authenticate(owner,f.synthetic.local(owner.store),f.synthetic.document,it) }
            f.c.hardRevoke(SessionCoordinator.Reason.Home); assertFalse(f.c.completeUnlock(t,proof))
            owner.release(); f.c.unlockFinished(owner); f.c.unlockWorkFinished(t)
            assertEquals(RuntimeSessionDiagnostics.Outcome.COMPLETION_REJECTED,RuntimeSessionDiagnostics.outcome(t.epoch,f.c.diagnosticHistory(),f.c.snapshot()))
            assertFalse(f.c.diagnosticHistory().any { it.event==SessionCoordinator.DiagnosticEvent.UnlockAccepted })
        }
        SessionCoordinatorTest.Fixture(true).use { f ->
            f.c.detach(f.w); f.w=f.c.attach({ if(it.presented) f.c.hardRevoke(SessionCoordinator.Reason.FocusLost) }); f.seq=0; f.signal()
            f.login() // Presentation callback revokes immediately, before the login caller observes Open.
            val accepted=f.c.diagnosticHistory().single { it.event==SessionCoordinator.DiagnosticEvent.UnlockAccepted }
            assertEquals(SessionCoordinator.Access.OpenMain,accepted.state.access)
            assertEquals(RuntimeSessionDiagnostics.Outcome.OPENED_THEN_REVOKED,RuntimeSessionDiagnostics.outcome(accepted.attemptEpoch!!,f.c.diagnosticHistory(),f.c.snapshot()))
            assertFalse(f.c.snapshot().presented)
        }
    }
    @Test fun submissionAndOwnerClaimRejectionsRemainVisible() {
        SessionCoordinatorTest.Fixture(true).use { f ->
            val t=f.c.beginUnlock(); f.c.hardRevoke(SessionCoordinator.Reason.Home); assertFalse(f.c.submitUnlock(t))
            assertEquals(RuntimeSessionDiagnostics.Outcome.SUBMISSION_REJECTED,RuntimeSessionDiagnostics.outcome(t.epoch,f.c.diagnosticHistory(),f.c.snapshot()))
        }
        SessionCoordinatorTest.Fixture(true).use { f ->
            val t=f.c.beginUnlock(); assertTrue(f.c.submitUnlock(t)); f.c.hardRevoke(SessionCoordinator.Reason.Home)
            val owner=f.synthetic.owner()
            try { assertFalse(f.c.claimUnlock(t,owner)) } finally { owner.revoke(); owner.release(); f.c.unlockWorkFinished(t) }
            assertEquals(RuntimeSessionDiagnostics.Outcome.CLAIM_REJECTED,RuntimeSessionDiagnostics.outcome(t.epoch,f.c.diagnosticHistory(),f.c.snapshot()))
        }
    }
    @Test fun metadataHistoryIsBoundedRedactedAndOffByDefault() = SessionCoordinatorTest.Fixture(true).use { f ->
        repeat(200) { f.signal(top=false) }
        val h=f.c.diagnosticHistory(); assertEquals(128,h.size); assertTrue(h.zipWithNext().all { it.first.serial<it.second.serial })
        val dump=RuntimeSessionDiagnostics.render(h,f.c.snapshot(),listOf("ACTUAL_PAUSE"),null)
        for(field in listOf("access=","epoch=","reason=","owner=","pendingWork=","handoff=","visible=","interactive=","unlocked=","top=","focus=")) assertTrue(dump.contains(field))
        assertFalse(dump.contains(SyntheticSession.MAIN)); assertFalse(dump.contains(SyntheticSession.DECOY)); assertFalse(dump.contains(f.synthetic.identity.toString()))
        val plain=SessionCoordinator(); plain.hardRevoke(SessionCoordinator.Reason.Home); assertTrue(plain.diagnosticHistory().isEmpty())
    }
}
