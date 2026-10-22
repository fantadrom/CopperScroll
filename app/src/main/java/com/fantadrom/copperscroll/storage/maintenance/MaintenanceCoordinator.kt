package com.fantadrom.copperscroll.storage.maintenance

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.TransactionCore

enum class MaintenanceStep { AuthorizeMain, Destinations, Credentials, Confirm, DrainAndFreeze, Preflight,
    Backup, BuildCandidate, VerifyCandidate, PrepareSwitch, AcceptSwitch, ResolveAndClose }
enum class MaintenanceCode { MaintenanceCommitted, Denied, Cancelled, SetupRequired, CredentialsRejected,
    Conflict, AccessLost, Busy, Unsupported, ResourceLimit, IoFailure, CopyUnknown, SwitchUnknown, NeedsCredentials }
class MaintenanceRefusal(val code: MaintenanceCode) : Exception(code.name)
class MaintenanceDestinations(val source: Artifact, val backup: String, val candidate: String)
class CompactionRequest internal constructor(internal val issuer: Any, internal val capability: TransactionCore.MaintenanceCapability,
    val opId: Bytes, internal val ticket: Any, val destinations: MaintenanceDestinations, selected: Set<Bytes>, internal val source: DocumentFingerprint,
    internal val snapshot: ReadSnapshot, internal val previousBinding: DocumentFingerprint) {
    val selected: Set<Bytes> = java.util.Collections.unmodifiableSet(HashSet(selected))
    override fun toString() = "CompactionRequest(redacted)"
}
/** RAM-only service context. No repository/UI handle is exposed. Closing invalidates confirmations. */
class MaintenanceProposal internal constructor(internal val request: CompactionRequest, internal val decoy: CurrentCredential?,
    val maximumCandidateBytes: Long, val additionalBytesRequired: Long) : AutoCloseable {
    @Volatile internal var closed = false
    internal var onClose: ()->Unit = {}
    internal var confirmed = false
    internal var consumed = false
    @Synchronized override fun close() { if(!closed) { closed = true; try { decoy?.close() } finally { onClose() } } }
    override fun toString() = "MaintenanceProposal(redacted)"
}
class MaintenanceConfirmation internal constructor(internal val proposal: MaintenanceProposal)
class MaintenanceResult internal constructor(val code: MaintenanceCode, val stage: JournalStage?, selected: Set<Bytes>,
    val source: Artifact, val backup: VerifiedCopy?, val candidate: Artifact?, val sizes: MaintenanceSizes?,
    coverage: Map<Bytes,VerificationCoverage>, val sourceFingerprint: DocumentFingerprint,
    val candidateFingerprint: DocumentFingerprint?, retained: List<Artifact>, unverified: List<Artifact>, val outcome: JournalOutcome,
    internal val failure: Throwable? = null) {
    val selected = java.util.Collections.unmodifiableSet(HashSet(selected))
    val processed = if(code == MaintenanceCode.MaintenanceCommitted) this.selected else emptySet()
    val coverage = java.util.Collections.unmodifiableMap(HashMap(coverage))
    val locked = true
    val retained = java.util.Collections.unmodifiableList(ArrayList(retained))
    val unverifiedArtifacts = java.util.Collections.unmodifiableList(ArrayList(unverified))
    override fun toString() = "MaintenanceResult(code=$code,stage=$stage)"
}

/** Synchronous headless worker protocol. Run blocking methods off the UI thread. One core/owner
 * keeps the exclusive process/document lease for the WHOLE operation, including Unknown resolution.
 * Observers are test/progress notifications only; they cannot supply authorization or accept a switch.
 */
class MaintenanceCoordinator internal constructor(private val core: TransactionCore, private val documents: MaintenanceDocuments,
    private val local: LocalStateStore, private val credentialManager: CredentialManager, private val progress: (MaintenanceStep)->Unit) {
    constructor(core: TransactionCore, documents: MaintenanceDocuments, local: LocalStateStore,
                progress: (MaintenanceStep)->Unit = {}) : this(core,documents,local,CredentialManager(),progress)
    private val issuer = Any()
    private var pinnedCandidate: ResourceOwner? = null
    fun begin(capability: TransactionCore.MaintenanceCapability, destinations: MaintenanceDestinations,
              selection: Set<Bytes>? = null): CompactionRequest {
        try { return preparation(capability) { s,_,_,permit ->
        // capability checked by core before any callbacks, grants, KDF, naming or artifacts.
        progress(MaintenanceStep.AuthorizeMain); core.owner.check(permit)
        val ticket = core.newMaintenanceTicket() // invalidates earlier requests in every coordinator on this core
        progress(MaintenanceStep.Destinations)
        try { checkDestinations(destinations,setupAllowed=true) } catch(e: MaintenanceRefusal) {
            core.owner.revoke(); throw e // picker/settings can only proceed Locked, never resume this request
        }
        val selected = selection ?: s.header.zones.map { it.zoneId }.toSet()
        refuse(selected.isNotEmpty() && selected.all { id -> s.header.zones.any { it.zoneId == id } },MaintenanceCode.Unsupported)
        val fp = fingerprint(core.owner.store,destinations.source.uri,s,check={ core.owner.check(permit) })
        val state = local.read()
        if(local.reconciliationRequired || (state.journal != null && (state.journal.stage !in listOf(JournalStage.Activated,JournalStage.Aborted) ||
            state.journal.outcome == JournalOutcome.Unreported))) {
            core.owner.markUnknown(); core.owner.revoke(); throw MaintenanceRefusal(MaintenanceCode.SwitchUnknown)
        }
        refuse(sameBindingDocument(state.binding,fp),MaintenanceCode.Conflict)
        CompactionRequest(issuer,capability,randomId(),ticket,destinations,selected,fp,s,state.binding!!)
        } } catch(e: MaintenanceRefusal) {
            if(e.code == MaintenanceCode.SetupRequired) try { core.owner.release() } catch(cleanup: Throwable) { e.addSuppressed(cleanup) }
            throw e
        }
    }
    /** Secret is caller-owned. Cancel = null when selected decoy exists, never partial fallback. */
    fun credentials(request: CompactionRequest, decoySecret: CanonicalSecret? = null): MaintenanceProposal {
        refuse(request.issuer === issuer,MaintenanceCode.Denied)
        return preparation(request.capability,admission={ core.checkMaintenanceTicket(request.ticket) }) { s,_,reader,permit ->
            core.setMaintenanceContext(request.ticket,null)
            fresh(request,s); progress(MaintenanceStep.Credentials); core.owner.check(permit)
            var ticket: CurrentCredential? = null
            try {
                val d = s.header.zones.singleOrNull { it.role == 2 && it.zoneId in request.selected }
                if(d != null) {
                    refuse(decoySecret != null,MaintenanceCode.Cancelled)
                    ticket = credentialManager.verifyCurrent(s.header.containerId,d,d.zoneId,2,decoySecret!!,
                        cancel=CryptoCancellation { core.owner.accessRevoked })
                    ticket.consumeSecret() // retain only independently unwrapped operation key
                    val view = reader.openZone(s,ticket.key)
                    refuse(!view.readOnly,MaintenanceCode.Unsupported)
                }
                core.owner.check(permit)
                // Conservative upper bound for compact plus one full backup, reserve, no prior-copy deletion.
                val bound = s.header.committedLength + s.catalogues.sumOf { it.size.toLong()*Limits.PAGE } + 131072
                val proposal = MaintenanceProposal(request,ticket,bound,Math.addExact(s.header.committedLength,bound)+RESERVE)
                proposal.onClose = { core.owner.retire(proposal) }
                core.setMaintenanceContext(request.ticket,proposal)
                try { progress(MaintenanceStep.Confirm) } catch(e: Throwable) { proposal.close(); throw e }
                proposal.also { ticket = null }
            } catch(e: CryptoException) {
                throw MaintenanceRefusal(when(e.failure) {
                    CryptoFailure.Cancelled -> MaintenanceCode.Cancelled
                    CryptoFailure.ResourceLimit -> MaintenanceCode.ResourceLimit
                    CryptoFailure.UnsupportedKdf,CryptoFailure.UnsupportedSecretEncoding,CryptoFailure.InvalidParameters -> MaintenanceCode.Unsupported
                    else -> MaintenanceCode.CredentialsRejected
                })
            } finally { ticket?.close() }
        }
    }
    /** Explicit final consent, bound by object identity to this op/selection/destinations/S/space.
     * No mutable flags or dismiss-forever setting. Late confirmations cannot resume closed sessions. */
    fun confirm(proposal: MaintenanceProposal): MaintenanceConfirmation {
        refuse(proposal.request.issuer === issuer,MaintenanceCode.Denied)
        return preparation(proposal.request.capability,admission={ core.checkMaintenanceTicket(proposal.request.ticket,proposal) }) { s,_,_,_ ->
            fresh(proposal.request,s)
            synchronized(proposal) {
                refuse(!proposal.closed && !proposal.confirmed && !proposal.consumed,MaintenanceCode.Denied)
                proposal.confirmed = true; MaintenanceConfirmation(proposal)
            }
        }
    }
    fun compact(confirmation: MaintenanceConfirmation): MaintenanceResult {
        val p = confirmation.proposal; val r = p.request
        refuse(r.issuer === issuer,MaintenanceCode.Denied)
        core.checkMaintenanceTicket(r.ticket,p)
        synchronized(p) {
            refuse(!p.closed && p.confirmed && !p.consumed,MaintenanceCode.Denied); p.consumed = true
        }
        var stage: JournalStage? = null; var backup: VerifiedCopy? = null; var candidate: Artifact? = null
        var candidateFp: DocumentFingerprint? = null; var pendingArtifact: OwnedArtifactIdentity? = null
        var code = MaintenanceCode.IoFailure
        var failure: Throwable? = null; var accepted = false; var frozen = false; var unreported = false
        var admitted = false; var superseded = false
        var coverage: Map<Bytes,VerificationCoverage> = emptyMap(); var sizes: MaintenanceSizes? = null
        val ownUnverified = ArrayList<Artifact>()
        val removed = HashSet<Artifact>()
        fun publish(next: JournalStage) {
            val journal = StateJournal(r.opId,"compact",r.selected.sorted(),r.source,backup?.fingerprint,candidateFp,
                next,r.previousBinding,if(next == JournalStage.Activated) { if(core.owner.switchUnreported) JournalOutcome.Unreported else JournalOutcome.Confirmed } else JournalOutcome.Pending,pendingArtifact)
            when(local.replace(LocalState(if(next == JournalStage.Activated) candidateFp else r.previousBinding,journal))) {
                LocalWriteCode.Committed -> stage = next
                LocalWriteCode.IoFailure -> throw MaintenanceRefusal(MaintenanceCode.IoFailure)
                LocalWriteCode.StateUnknown -> { core.owner.markUnknown(); throw MaintenanceRefusal(MaintenanceCode.SwitchUnknown) }
            }
        }
        try {
            core.maintenanceAccess(r.capability,freeze=true,admission={
                core.checkMaintenanceTicket(r.ticket,p)
                synchronized(p) { refuse(!p.closed && p.confirmed && p.consumed,MaintenanceCode.Denied) }
                admitted = true
            }) { s,main,_,permit ->
                frozen = true
                val check = { core.owner.check(permit) }
                try {
                progress(MaintenanceStep.DrainAndFreeze); check(); fresh(r,s)
                checkDestinations(r.destinations)
                refuse(sameFingerprint(local.read().binding,r.previousBinding),MaintenanceCode.Conflict)
                progress(MaintenanceStep.Preflight)
                val keys = HashMap<Bytes,ZoneKey>()
                if(main.zoneId in r.selected) keys[main.zoneId] = main
                p.decoy?.let { keys[it.key.zoneId] = it.key }
                val compactor = SelectiveCompactor()
                val plan = compactor.preflight(core.owner.store,s,r.selected,keys,check)
                val backupFree = documents.availableBytes(r.destinations.backup)
                val candidateFree = documents.availableBytes(r.destinations.candidate)
                // Both budgets independently reserve worst combined usage (safe also for same underlying volume).
                refuse((backupFree == null || backupFree >= p.additionalBytesRequired) &&
                    (candidateFree == null || candidateFree >= p.additionalBytesRequired),MaintenanceCode.ResourceLimit)
                check(); publish(JournalStage.Prepared)
                progress(MaintenanceStep.Backup)
                backup = SnapshotCopier().copy(core.owner,permit,s,r.destinations.source,documents,r.destinations.backup,keys) {
                    ownUnverified.add(it); pendingArtifact = OwnedArtifactIdentity(it.uri,it.identity); publish(JournalStage.Prepared)
                }
                ownUnverified.remove(backup!!.artifact); pendingArtifact = null
                check(); publish(JournalStage.BackupVerified)
                progress(MaintenanceStep.BuildCandidate); check(); publish(JournalStage.Building)
                val (artifact,store) = documents.createNew(r.destinations.candidate,uniqueName("candidate"))
                val owned = artifactOwner(artifact,store,setOf(r.destinations.source.identity,backup!!.artifact.identity),core.owner.store)
                try { requireIo(owned.store.size == 0L,IoCode.InvalidRange) }
                catch(e: Throwable) { try { owned.close() } catch(cleanup: Throwable) { e.addSuppressed(cleanup) }; throw e }
                candidate = artifact; ownUnverified.add(artifact); pinnedCandidate = owned
                core.owner.adopt(owned) // remains pinned together with source if switch becomes Unknown
                pendingArtifact = OwnedArtifactIdentity(artifact.uri,artifact.identity); publish(JournalStage.Building)
                val cp = owned.accept()
                owned.execute(cp) {
                    val instance = compactor.build(core.owner.store,plan,owned.store,check)
                    check(); progress(MaintenanceStep.VerifyCandidate)
                    requireIo(owned.store.guarantees.durableCreateIdentity,IoCode.UnsupportedDurability)
                    owned.store.force(); check(); owned.store.forceIdentity()
                    owned.reopen(cp) { documents.open(artifact) }
                    coverage = compactor.verify(core.owner.store,plan,owned.store,instance,keys,check)
                    candidateFp = fingerprint(owned.store,artifact.uri,check=check)
                    documents.checkIdentity(artifact)
                    requireFingerprint(r.source,core.owner.store,check)
                    fresh(r,ContainerReader(core.owner.store).snapshot())
                    ownUnverified.remove(artifact); pendingArtifact = null
                    progress(MaintenanceStep.PrepareSwitch); check(); publish(JournalStage.CandidateVerified)
                    check(); publish(JournalStage.SwitchIntent)
                    checkDestinations(r.destinations); documents.checkIdentity(artifact)
                    requireFingerprint(candidateFp!!,owned.store,check)
                    requireFingerprint(r.source,core.owner.store,check)
                    progress(MaintenanceStep.AcceptSwitch)
                    checkDestinations(r.destinations); documents.checkIdentity(artifact)
                    requireFingerprint(candidateFp!!,owned.store,check)
                    requireFingerprint(r.source,core.owner.store,check)
                    // Atomic with revoke; all long reads/forces/metadata IO are outside this monitor.
                    refuse(core.owner.acceptSwitch(permit),MaintenanceCode.Cancelled); accepted = true
                    publish(JournalStage.Activated)
                    if(core.owner.switchUnreported && local.read().journal?.outcome != JournalOutcome.Unreported)
                        publish(JournalStage.Activated)
                    sizes = MaintenanceSizes(r.source.length,candidateFp!!.length,scratch=0,partial=0,reserve=RESERVE,sourceTail=s.observedLength-r.source.length)
                    code = MaintenanceCode.MaintenanceCommitted
                }
                } catch(e: Throwable) {
                    // Diagnose changed source while the source permit is STILL active. No IO in
                    // the outer exception/cleanup gap where a caller could otherwise close its FD.
                    if(!accepted && !core.owner.revokeRequested && (e is FormatException || e is CryptoException)) {
                        try { requireFingerprint(r.source,core.owner.store,check) }
                        catch(other: Throwable) {
                            if(other is MaintenanceRefusal && other.code == MaintenanceCode.Conflict) { other.addSuppressed(e); throw other }
                            e.addSuppressed(other)
                        }
                    }
                    throw e
                }
            }
        } catch(e: Throwable) {
            if(!admitted && e is MaintenanceRefusal && e.code == MaintenanceCode.Denied) { superseded = true; throw e }
            failure = e
            code = if(accepted || core.owner.reconciliationRequired) MaintenanceCode.SwitchUnknown
                else if(core.owner.revokeRequested) MaintenanceCode.Cancelled else classify(e)
            if(accepted) core.owner.markUnknown()
        } finally {
            if(superseded) p.close() else {
            unreported = core.owner.switchUnreported
            core.owner.revoke() // even success ends previous session; callback cannot reopen UI
            if(frozen) try { core.endMaintenanceSession() } catch(e: Throwable) {
                if(failure == null) failure = e else failure!!.addSuppressed(e)
            }
            try { p.close() } catch(e: Throwable) { if(failure == null) failure = e else failure!!.addSuppressed(e) }
            if(!core.owner.reconciliationRequired) {
                if(code != MaintenanceCode.MaintenanceCommitted && stage != null) {
                    try {
                        val state = LocalState(r.previousBinding,StateJournal(r.opId,"compact",r.selected.sorted(),r.source,backup?.fingerprint,candidateFp,
                            JournalStage.Aborted,r.previousBinding,JournalOutcome.Aborted,pendingArtifact))
                        if(local.replace(state) != LocalWriteCode.Committed) { core.owner.markUnknown(); code = MaintenanceCode.SwitchUnknown }
                        else stage = JournalStage.Aborted
                    } catch(e: Throwable) { core.owner.markUnknown(); code = MaintenanceCode.SwitchUnknown; failure?.addSuppressed(e) }
                }
                if(!core.owner.reconciliationRequired) {
                    // Close candidate descriptors before deletion (Windows), while source lease
                    // and maintenance hold STILL exclude every other writer/recovery worker.
                    try { pinnedCandidate?.let { core.owner.retire(it) } } catch(e: Throwable) {
                        if(failure == null) failure = e else failure!!.addSuppressed(e)
                        if(code == MaintenanceCode.MaintenanceCommitted) code = MaintenanceCode.IoFailure
                    }
                    for(artifact in ownUnverified) try {
                        documents.checkIdentity(artifact); documents.deleteOwned(artifact); removed.add(artifact)
                    } catch(e: Throwable) { if(failure == null) failure = e else failure!!.addSuppressed(e) }
                }
            }
            core.owner.finishMaintenanceIo()
            if(!core.owner.reconciliationRequired) try { core.owner.release() } catch(e: Throwable) {
                if(failure == null) failure = e else failure!!.addSuppressed(e)
                if(code == MaintenanceCode.MaintenanceCommitted) code = MaintenanceCode.IoFailure
            }
            // No timeout-driven close. execute has returned, hence IO quiescent. Unknown pins owner.
            try { progress(MaintenanceStep.ResolveAndClose) } catch(e: Throwable) {
                if(failure == null) failure = e else failure!!.addSuppressed(e)
            }
            }
        }
        return MaintenanceResult(code,stage,r.selected,r.destinations.source,backup,candidate,sizes,coverage,r.source,candidateFp,
            (listOfNotNull(r.destinations.source,backup?.artifact,candidate) + ownUnverified).filter { it !in removed }.distinctBy { it.identity },
            ownUnverified.filter { it !in removed },
            if(core.owner.reconciliationRequired) JournalOutcome.Unknown else if(stage == JournalStage.Activated) {
                if(unreported) JournalOutcome.Unreported else JournalOutcome.Confirmed
            } else JournalOutcome.Aborted,failure)
    }
    /** Standalone low-level backup checks only the authenticated open zone. It grants no compact
     * capability; user command authorization stays at the main coordinator boundary. */
    fun backup(capability: TransactionCore.MaintenanceCapability, destinations: MaintenanceDestinations): VerifiedCopy =
        preparation(capability) { s,key,_,permit ->
            checkDestinations(destinations)
            SnapshotCopier().copy(core.owner,permit,s,destinations.source,documents,destinations.backup,mapOf(key.zoneId to key))
        }
    fun reconcile(choice: RecoveryChoice, secrets: Map<Bytes,CanonicalSecret>): RecoveryResult =
        MaintenanceRecovery().resolve(local,documents,choice,secrets,
            if(core.owner.phase != OwnerPhase.Released) core.owner else null,
            pinnedCandidate?.takeIf { it.phase != OwnerPhase.Released })
    private fun <T> preparation(capability: TransactionCore.MaintenanceCapability, admission: ()->Unit = {},
        block: (ReadSnapshot,ZoneKey,ContainerReader,WritePermit)->T): T {
        try { return core.maintenanceAccess(capability,admission=admission,block=block) }
        catch(e: Throwable) {
            // A background revoke during credentials/readback is quiescent on return. Close all
            // operation-owned proposals too, even if the caller never received the late result.
            if(core.owner.revokeRequested && !core.owner.reconciliationRequired)
                try { core.owner.release() } catch(cleanup: Throwable) { e.addSuppressed(cleanup) }
            throw e
        }
    }
    private fun checkDestinations(d: MaintenanceDestinations, setupAllowed: Boolean = false) {
        refuse(local.durableLocalState && core.owner.store.guarantees.existingWriteDurable,MaintenanceCode.Unsupported)
        try {
            documents.checkSource(d.source); documents.checkDestination(d.backup); documents.checkDestination(d.candidate)
        } catch(e: StoreException) {
            if(e.code == IoCode.AccessLost && setupAllowed) throw MaintenanceRefusal(MaintenanceCode.SetupRequired)
            throw e
        }
    }
    private fun fresh(r: CompactionRequest,s: ReadSnapshot) {
        refuse(sameSnapshot(r.snapshot,s),MaintenanceCode.Conflict)
        requireFingerprint(r.source,core.owner.store) { refuse(!core.owner.accessRevoked,MaintenanceCode.Cancelled) }
    }
    companion object { const val RESERVE = 1024L*1024 }
}
internal fun refuse(ok: Boolean, code: MaintenanceCode) { if(!ok) throw MaintenanceRefusal(code) }
internal fun classify(e: Throwable): MaintenanceCode = when(e) {
    is MaintenanceRefusal -> e.code
    is StoreException -> when(e.code) {
        IoCode.AccessLost,IoCode.Closed -> MaintenanceCode.AccessLost
        IoCode.Busy -> MaintenanceCode.Busy
        IoCode.InvalidRange -> MaintenanceCode.Conflict
        IoCode.UnsupportedDurability -> MaintenanceCode.Unsupported
        else -> MaintenanceCode.IoFailure
    }
    is FormatException -> when(e.failure) {
        Failure.UnsupportedVersion,Failure.UnsupportedLimit -> MaintenanceCode.Unsupported
        Failure.ResourceLimit -> MaintenanceCode.ResourceLimit
        else -> MaintenanceCode.IoFailure
    }
    is OutOfMemoryError -> MaintenanceCode.ResourceLimit
    else -> MaintenanceCode.IoFailure
}
