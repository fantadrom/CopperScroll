package com.fantadrom.copperscroll.storage.session

import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*

/** No Android/View, KDF, codec, IO, executor wait or presentation callback under this monitor. */
class SessionCoordinator(captureDiagnostics: Boolean=false) {
    enum class Access { Locked, Unlocking, OpenMain, OpenDecoy, Revoked }
    enum class Reason { Home, ScreenOff, DeviceLocked, Invisible, NotTopResumed, FocusLost, DocumentChanged, Picker, Explicit, Destroyed }
    enum class Command { Read, Edit, Save, Maintenance, Backup, ChangeDocument }
    enum class ScopeKind { Modal, Menu, Ime }
    class Window internal constructor(internal val issuer: Any,internal val serial: Long)
    class Handoff internal constructor(internal val issuer: Any,internal val epoch: Long,internal val old: Window)
    class OwnWindow internal constructor(internal val issuer: Any,internal val epoch: Long,internal val window: Window,internal val kind: ScopeKind)
    class Unlock internal constructor(internal val issuer: Any,internal val epoch: Long,internal val document: SessionDocument) { internal var submitted=false }
    class Capability internal constructor(internal val issuer: Any,internal val epoch: Long,internal val session: AuthenticatedSession)
    class CommandTicket internal constructor(internal val issuer: Any,internal val epoch: Long,internal val capability: Capability,val command: Command) { internal var started=false }
    class Picker internal constructor(internal val issuer: Any,internal val epoch: Long,val opId: Long,
        val document: SessionDocument?,val purpose: String)
    class Signals(val visible: Boolean,val interactive: Boolean,val unlocked: Boolean,val topResumed: Boolean,
        val focused: Boolean,val ownFocus: OwnWindow?=null)
    /** Read-only facts, including unknown (null). No window, credential, URI or content references. */
    class Foreground internal constructor(val visible: Boolean?,val interactive: Boolean?,val unlocked: Boolean?,
        val topResumed: Boolean?,val focused: Boolean?,val ownedFocus: Boolean,val windowAttached: Boolean,
        val windowSerial: Long?,val signalSequence: Long,val ready: Boolean)
    class Snapshot internal constructor(val access: Access,val epoch: Long,val presented: Boolean,val owner: OwnerPhase?,
        val reconciliationRequired: Boolean,val reason: Reason?,val handoff: Boolean,val pendingWork: Boolean,val pickerPending: Boolean,
        val foreground: Foreground,val unlockReady: Boolean)
    enum class DiagnosticEvent { Attach, Detach, Signals, Handoff, Revoke, Document,
        UnlockBegin, UnlockDenied, UnlockSubmitted, UnlockSubmissionRejected, UnlockClaimed, UnlockClaimRejected,
        UnlockAccepted, UnlockCompletionRejected, UnlockFailed, UnlockAbandoned, UnlockWorkFinished, OwnerReleased }
    class Transition internal constructor(val serial: Long,val event: DiagnosticEvent,val attemptEpoch: Long?,val state: Snapshot)
    // Opt-in metadata only. Disabled for ordinary callers; no observer/callback under the guard.
    private val history=if(captureDiagnostics) java.util.ArrayDeque<Transition>() else null
    private var diagnosticSerial=0L
    private fun record(event: DiagnosticEvent,attemptEpoch: Long?=null) {
        val ring=history ?: return
        if(diagnosticSerial==Long.MAX_VALUE) return
        if(ring.size==128) ring.removeFirst()
        ring.addLast(Transition(++diagnosticSerial,event,attemptEpoch,snapshotLocked()))
    }
    class Diagnostics internal constructor(val current: Snapshot,val history: List<Transition>)
    fun diagnostics(): Diagnostics = synchronized(guard) { Diagnostics(snapshotLocked(),history?.toList() ?: emptyList()) }
    fun diagnosticHistory(): List<Transition> = diagnostics().history
    class Revocation internal constructor(val session: AuthenticatedSession?,val closing: ClosingSave?,val pendingOwner: ResourceOwner?,val unused: FinalCheckpoint?)
    fun interface Presentation { fun changed(snapshot: Snapshot) }
    private val guard=Any(); private val issuer=Any()
    private var exhausted=false
    private var epoch=1L; private var serial=0L; private var sequence=0L; private var opId=0L
    private var access=Access.Locked; private var document: SessionDocument?=null
    private var window: Window?=null; private var signals: Signals?=null; private var handoff: Handoff?=null
    private var presentation: Presentation?=null; private val scopes=HashSet<OwnWindow>()
    private var attempt: Unlock?=null; private var unlockWork: Unlock?=null; private var pendingOwner: ResourceOwner?=null
    private var session: AuthenticatedSession?=null; private var capability: Capability?=null
    private var command: CommandTicket?=null; private var checkpoint: FinalCheckpoint?=null
    private var picker: Picker?=null; private var lastReason: Reason?=null
    private var revocation: Revocation?=null
    private fun bump() { if(epoch==Long.MAX_VALUE) { exhausted=true; access=Access.Revoked; capability=null; attempt=null } else epoch++ }
    private fun ownedFocus()=signals?.ownFocus?.let { it in scopes && it.window===window && it.epoch==epoch }==true
    private fun foreground(): Boolean {
        val s=signals ?: return false
        return !exhausted && window!=null && handoff==null && s.visible && s.interactive && s.unlocked && s.topResumed &&
            (s.focused || ownedFocus())
    }
    /** Fresh idle login readiness; beginUnlock still performs the authoritative admission. */
    private fun unlockReady()=foreground() && !open() && access!=Access.Unlocking && session==null && pendingOwner==null &&
        unlockWork==null && picker==null && revocation==null && document!=null && epoch<Long.MAX_VALUE
    private fun foregroundFacts()=Foreground(signals?.visible,signals?.interactive,signals?.unlocked,signals?.topResumed,
        signals?.focused,ownedFocus(),window!=null,window?.serial,sequence,foreground())
    private fun open()=access==Access.OpenMain || access==Access.OpenDecoy
    private fun snapshotLocked()=Snapshot(access,epoch,open() && foreground() && session?.live()==true && command?.command!=Command.Maintenance,session?.core?.owner?.phase ?: pendingOwner?.phase,
        session?.core?.owner?.reconciliationRequired ?: pendingOwner?.reconciliationRequired ?: false,lastReason,handoff!=null,unlockWork!=null || pendingOwner!=null || command!=null,picker!=null,foregroundFacts(),unlockReady())
    fun snapshot()=synchronized(guard) { snapshotLocked() }
    private fun notifyPresentation() {
        val p: Presentation?; val s: Snapshot
        synchronized(guard) { p=presentation; s=snapshotLocked() }
        p?.changed(s) // May re-enter; ports must recheck isCurrent before showing content.
    }
    fun ownsWindow(w: Window)=synchronized(guard) { window===w && w.issuer===issuer }
    fun isWindowCurrent(w: Window,epoch: Long)=synchronized(guard) { window===w && isCurrent(epoch) }
    fun isCurrent(epoch: Long)=synchronized(guard) { this.epoch==epoch && open() && foreground() && session?.live()==true && command?.command!=Command.Maintenance }
    fun attach(port: Presentation,transfer: Handoff?=null): Window {
        val w=synchronized(guard) {
            check(window==null) { "WINDOW_ALREADY_ATTACHED" }; requireIo(handoff==null || handoff===transfer,IoCode.AccessLost); requireIo(serial<Long.MAX_VALUE,IoCode.AccessLost)
            Window(issuer,++serial).also { window=it; sequence=0; signals=null; presentation=port; record(DiagnosticEvent.Attach) }
        }
        notifyPresentation(); return w
    }
    fun currentCapability(): Capability?=synchronized(guard) { capability?.takeIf { isCurrent(it.epoch) } }
    fun selectDocument(next: SessionDocument) {
        try { synchronized(guard) {
            if(unlockWork!=null || pendingOwner!=null) { revokeLocked(Reason.DocumentChanged); throw StoreException(IoCode.Busy) }
            requireIo(!open() && session==null,IoCode.Busy)
            bump(); document=next; attempt=null; picker=null; access=Access.Locked; record(DiagnosticEvent.Document)
        } } finally { notifyPresentation() }
    }
    fun signals(w: Window,seq: Long,s: Signals) {
        synchronized(guard) {
            if(window!==w || w.issuer!==issuer || seq<=sequence) return
            if(seq==Long.MAX_VALUE) { signals=null; sequence=seq; revokeLocked(Reason.Destroyed) }
            else {
            sequence=seq; signals=s
            val hard=when { !s.interactive->Reason.ScreenOff; !s.unlocked->Reason.DeviceLocked
                handoff!=null->null; !s.visible->Reason.Invisible; !s.topResumed->Reason.NotTopResumed
                !s.focused && !(s.ownFocus in scopes && s.ownFocus?.epoch==epoch)->Reason.FocusLost; else->null }
            if(hard!=null) revokeLocked(hard)
            if(handoff?.old!==w && s.interactive && s.unlocked && s.visible && s.topResumed && s.focused) handoff=null
            }
            record(DiagnosticEvent.Signals)
        }
        notifyPresentation()
    }
    /** Known configuration transfer only, never a generic pause/focus debounce. */
    fun beginHandoff(w: Window): Handoff {
        val h=synchronized(guard) { check(window===w && handoff==null); Handoff(issuer,epoch,w).also { handoff=it; scopes.clear(); record(DiagnosticEvent.Handoff) } }
        notifyPresentation(); return h
    }
    fun detach(w: Window,configuration: Handoff?=null) {
        synchronized(guard) {
            if(window!==w) return
            if(configuration==null || handoff!==configuration || configuration.epoch!=epoch) revokeLocked(Reason.Destroyed)
            window=null; signals=null; presentation=null; scopes.clear(); record(DiagnosticEvent.Detach)
        }
    }
    fun hardRevoke(reason: Reason) { synchronized(guard) { revokeLocked(reason); record(DiagnosticEvent.Revoke) }; notifyPresentation() }
    private fun revokeLocked(reason: Reason) {
        // Picker's expected external transition has separate signals; real Home/screen-off invalidates it.
        if(picker!=null && reason !in listOf(Reason.NotTopResumed,Reason.Invisible,Reason.FocusLost)) picker=null
        if(!open() && access!=Access.Unlocking) { lastReason=reason; if(picker==null) handoff=null; return }
        val prepared=checkpoint
        var closing: ClosingSave?=null
        if(open()) checkpoint?.let { closing=try { session!!.core.reserveFinalCheckpoint(it) } catch(_: StoreException) { null } }
        val old=session
        try { old?.core?.revokeWithFinalCheckpoint(closing) }
        catch(_: StoreException) { closing=null; old?.core?.revokeWithFinalCheckpoint() }
        pendingOwner?.revoke()
        check(revocation==null) { "REVOCATION_NOT_DRAINED" }
        revocation=Revocation(old,closing,pendingOwner,prepared?.takeIf { closing==null })
        bump(); access=Access.Revoked; lastReason=reason; capability=null; attempt=null; command=null; checkpoint=null
        scopes.clear(); handoff=null
    }
    /** One notification for a worker; repeated lifecycle callbacks cannot enqueue final N+2. */
    fun takeRevocation(): Revocation?=synchronized(guard) { revocation.also { revocation=null } }
    fun beginUnlock(): Unlock {
        val t=try { synchronized(guard) {
            try {
                if(pendingOwner!=null || unlockWork!=null) { revokeLocked(Reason.Explicit); throw StoreException(IoCode.Busy) }
                requireIo(foreground() && !open() && session==null && pendingOwner==null,IoCode.Busy)
                requireIo(picker==null && revocation==null,IoCode.Busy); val d=document ?: throw StoreException(IoCode.AccessLost)
                bump(); requireIo(!exhausted,IoCode.AccessLost); access=Access.Unlocking
                Unlock(issuer,epoch,d).also { attempt=it; record(DiagnosticEvent.UnlockBegin,it.epoch) }
            } catch(e: StoreException) { record(DiagnosticEvent.UnlockDenied); throw e }
        } } finally { notifyPresentation() }; return t
    }
    fun submitUnlock(t: Unlock): Boolean=synchronized(guard) {
        val accepted=if(attempt!==t || t.issuer!==issuer || t.epoch!=epoch || t.submitted || unlockWork!=null || !foreground()) false
        else { t.submitted=true; unlockWork=t; true }
        record(if(accepted) DiagnosticEvent.UnlockSubmitted else DiagnosticEvent.UnlockSubmissionRejected,t.epoch); accepted
    }
    fun claimUnlock(t: Unlock,owner: ResourceOwner): Boolean=synchronized(guard) {
        val accepted=if(t.issuer!==issuer || attempt!==t || t.epoch!=epoch || !foreground() || pendingOwner!=null || session!=null || unlockWork!=null && unlockWork!==t || owner.store.identity!=t.document.store) false
        else { t.submitted=true; unlockWork=t; pendingOwner=owner; true }
        record(if(accepted) DiagnosticEvent.UnlockClaimed else DiagnosticEvent.UnlockClaimRejected,t.epoch); accepted
    }
    /** On rejection the worker still owns the authenticated result and must dispose after IO. */
    fun completeUnlock(t: Unlock,result: AuthenticatedSession): Boolean {
        val accepted=synchronized(guard) {
            val ok=if(t.issuer!==issuer || attempt!==t || t.epoch!=epoch || !foreground() || access!=Access.Unlocking ||
                pendingOwner!==result.core.owner || !t.document.matches(result.document) || !result.live()) false
            else {
                pendingOwner=null; attempt=null; session=result; access=if(result.zone==UserZone.Main) Access.OpenMain else Access.OpenDecoy
                capability=Capability(issuer,epoch,result); lastReason=null; true
            }
            record(if(ok) DiagnosticEvent.UnlockAccepted else DiagnosticEvent.UnlockCompletionRejected,t.epoch); ok
        }; notifyPresentation(); return accepted
    }
    /** The factory may block before there is an owner to claim. Only its actual worker finally clears this fence. */
    fun unlockWorkFinished(t: Unlock) {
        synchronized(guard) { if(unlockWork===t) { unlockWork=null; record(DiagnosticEvent.UnlockWorkFinished,t.epoch) } }; notifyPresentation()
    }
    fun rejectUnlock(t: Unlock) {
        synchronized(guard) { if(attempt===t) { revokeLocked(Reason.Explicit); record(DiagnosticEvent.UnlockFailed,t.epoch) } }; notifyPresentation()
    }
    fun unlockFailed(t: Unlock) {
        synchronized(guard) { if(attempt===t && pendingOwner==null) { attempt=null; access=Access.Locked; record(DiagnosticEvent.UnlockAbandoned,t.epoch) } }; notifyPresentation()
    }
    fun unlockFinished(owner: ResourceOwner) {
        synchronized(guard) { if(pendingOwner===owner && owner.phase==OwnerPhase.Released) { pendingOwner=null; attempt=null; if(access==Access.Unlocking) access=Access.Locked; record(DiagnosticEvent.OwnerReleased) } }
        notifyPresentation()
    }
    fun admit(c: Capability,kind: Command): CommandTicket {
        val ticket=synchronized(guard) {
        requireIo(c.issuer===issuer && capability===c && c.epoch==epoch && foreground() && c.session.live() && command==null,IoCode.AccessLost)
        requireIo(kind !in listOf(Command.Maintenance,Command.Backup,Command.ChangeDocument) || c.session.zone==UserZone.Main,IoCode.AccessLost)
        requireIo(kind!=Command.Maintenance || checkpoint==null,IoCode.Busy)
        CommandTicket(issuer,epoch,c,kind).also { command=it }
        }; notifyPresentation(); return ticket
    }
    fun claimCommand(t: CommandTicket)=synchronized(guard) {
        if(command!==t || t.started || t.issuer!==issuer || t.epoch!=epoch || !foreground() || !t.capability.session.live()) false
        else { t.started=true; true }
    }
    fun canRun(t: CommandTicket)=synchronized(guard) { command===t && t.started && t.epoch==epoch && foreground() && t.capability.session.live() }
    fun canPublish(t: CommandTicket)=synchronized(guard) { command===t && t.issuer===issuer && t.epoch==epoch && isCurrent(t.epoch) }
    fun commandFinished(t: CommandTicket) { synchronized(guard) { if(command===t) { command=null; if(t.command==Command.Maintenance) revokeLocked(Reason.Explicit) } }; notifyPresentation() }
    fun offerCheckpoint(c: Capability,next: FinalCheckpoint): FinalCheckpoint?=synchronized(guard) {
        requireIo(c===capability && isCurrent(c.epoch) && next.core===c.session.core,IoCode.AccessLost)
        next.offer(); checkpoint.also { checkpoint=next }
    }
    fun ownerFinished(core: TransactionCore) {
        synchronized(guard) {
            if(session?.core===core && core.owner.phase==OwnerPhase.Released) { session=null; checkpoint=null; command=null; access=Access.Locked; record(DiagnosticEvent.OwnerReleased) }
        }; notifyPresentation()
    }
    fun registerOwnWindow(w: Window,kind: ScopeKind): OwnWindow=synchronized(guard) {
        requireIo(window===w && isCurrent(epoch) && scopes.size<4,IoCode.AccessLost)
        OwnWindow(issuer,epoch,w,kind).also { scopes.add(it) }
    }
    fun closeOwnWindow(scope: OwnWindow) { synchronized(guard) { scopes.remove(scope) } }
    fun cancelPicker(t: Picker) { synchronized(guard) { if(picker===t) picker=null } }
    fun beginPicker(purpose: String): Picker {
        val t=try { synchronized(guard) {
            requireIo(foreground() && (!open() || isCurrent(epoch)),IoCode.AccessLost)
            requireIo(purpose in setOf("OpenOriginal","Create","ImportCopy","RestoreCopy","BackupTree","CandidateTree"),IoCode.InvalidRange)
            requireIo(!open() || session?.zone==UserZone.Main,IoCode.AccessLost)
            revokeLocked(Reason.Picker); requireIo(opId<Long.MAX_VALUE,IoCode.AccessLost); bump(); requireIo(!exhausted,IoCode.AccessLost); access=Access.Locked
            Picker(issuer,epoch,++opId,document,purpose).also { picker=it }
        } } finally { notifyPresentation() }; return t
    }
    /** This short accepted decision precedes grant IO; a later revoke cannot re-open a session. */
    fun consumePicker(w: Window,t: Picker,purpose: String,expected: SessionDocument?): Boolean=synchronized(guard) {
        if(exhausted || window!==w || picker!==t || t.issuer!==issuer || t.epoch!=epoch || access!=Access.Locked || t.purpose!=purpose ||
            !(t.document==null && expected==null || t.document?.matches(expected ?: return@synchronized false)==true)) false
        else { picker=null; true }
    }
}
