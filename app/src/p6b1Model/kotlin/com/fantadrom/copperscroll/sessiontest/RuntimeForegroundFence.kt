package com.fantadrom.copperscroll.sessiontest

import com.fantadrom.copperscroll.storage.session.SessionCoordinator

/** Test-only sequence fence. Android callbacks supply stages; it never injects production facts. */
class RuntimeForegroundFence {
    enum class Stage { Created, Started, Resumed, Paused, Stopped, Destroyed }
    var stage=Stage.Created; private set
    var sequence=0L; private set
    var resumedAt=0L; private set
    fun observe(next: Stage) {
        check(sequence<Long.MAX_VALUE)
        sequence++; stage=next
        if(next==Stage.Resumed) resumedAt=sequence
    }
    fun departedAfter(mark: Long,hasWindowFocus: Boolean)=sequence>mark && !hasWindowFocus &&
        stage in setOf(Stage.Stopped,Stage.Destroyed)
    fun foreground(state: SessionCoordinator.Snapshot,hasWindowFocus: Boolean,afterResume: Long=0)=
        stage==Stage.Resumed && resumedAt>afterResume && hasWindowFocus && state.foreground.ready
    fun loginReady(state: SessionCoordinator.Snapshot,hasWindowFocus: Boolean)=foreground(state,hasWindowFocus) && state.unlockReady
}

/** No Throwable messages, URI, editor content, role key or credential objects. */
object RuntimeSessionDiagnostics {
    enum class Outcome { NOT_OBSERVED, ADMITTED, SUBMITTED, CLAIMED, OPENED, OPENED_THEN_REVOKED,
        SUBMISSION_REJECTED, CLAIM_REJECTED, COMPLETION_REJECTED, FAILED, REVOKED_BEFORE_OPEN }
    fun outcome(epoch: Long,history: List<SessionCoordinator.Transition>,now: SessionCoordinator.Snapshot): Outcome {
        val attempt=history.filter { it.attemptEpoch==epoch }.map { it.event }
        if(SessionCoordinator.DiagnosticEvent.UnlockAccepted in attempt)
            return if(now.epoch==epoch && now.access in setOf(SessionCoordinator.Access.OpenMain,SessionCoordinator.Access.OpenDecoy)) Outcome.OPENED else Outcome.OPENED_THEN_REVOKED
        return when {
            SessionCoordinator.DiagnosticEvent.UnlockCompletionRejected in attempt -> Outcome.COMPLETION_REJECTED
            SessionCoordinator.DiagnosticEvent.UnlockClaimRejected in attempt -> Outcome.CLAIM_REJECTED
            SessionCoordinator.DiagnosticEvent.UnlockSubmissionRejected in attempt -> Outcome.SUBMISSION_REJECTED
            SessionCoordinator.DiagnosticEvent.UnlockFailed in attempt -> Outcome.FAILED
            now.epoch!=epoch -> Outcome.REVOKED_BEFORE_OPEN
            SessionCoordinator.DiagnosticEvent.UnlockClaimed in attempt -> Outcome.CLAIMED
            SessionCoordinator.DiagnosticEvent.UnlockSubmitted in attempt -> Outcome.SUBMITTED
            SessionCoordinator.DiagnosticEvent.UnlockBegin in attempt -> Outcome.ADMITTED
            else -> Outcome.NOT_OBSERVED
        }
    }
    fun state(s: SessionCoordinator.Snapshot): String {
        val f=s.foreground
        return "access=${s.access},epoch=${s.epoch},reason=${s.reason},owner=${s.owner},pendingWork=${s.pendingWork},handoff=${s.handoff},presented=${s.presented},unlockReady=${s.unlockReady},visible=${f.visible},interactive=${f.interactive},unlocked=${f.unlocked},top=${f.topResumed},focus=${f.focused},ownFocus=${f.ownedFocus},attached=${f.windowAttached},window=${f.windowSerial},signal=${f.signalSequence},foreground=${f.ready}"
    }
    fun render(history: List<SessionCoordinator.Transition>,now: SessionCoordinator.Snapshot,events: List<String>,attempt: Long?): String = buildString {
        append("P6B1_SAFE_TRACE\ncurrent: ${state(now)}\n")
        if(attempt!=null) append("attemptEpoch=$attempt,outcome=${outcome(attempt,history,now)}\n")
        history.takeLast(128).forEach { append("${it.serial}:${it.event}:attempt=${it.attemptEpoch}:${state(it.state)}\n") }
        events.takeLast(128).forEachIndexed { n,event -> append("lifecycle.$n=$event\n") }
    }
}
