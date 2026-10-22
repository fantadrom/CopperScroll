package com.fantadrom.copperscroll.storage.maintenance

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*

/** Explicit resolution is never a login or a replay of the maintenance command. */
enum class RecoveryChoice { Source, Candidate }
enum class RecoveryCode { SourceConfirmed, CandidateConfirmed, NeedsCredentials, Conflict, AccessLost, SwitchUnknown, CredentialsRejected }
class RecoveryResult(val code: RecoveryCode, val binding: DocumentFingerprint? = null, val priorOutcome: JournalOutcome? = null) {
    val locked = true
    override fun toString() = "RecoveryResult(code=$code)"
}
/** Caller-owned secrets are explicitly scoped to documents, even when zoneId is identical. */
class RekeyRecoveryCredentials(source: Map<Bytes,CanonicalSecret>, candidate: Map<Bytes,CanonicalSecret>) {
    internal val source = source.toMap()
    internal val candidate = candidate.toMap()
    override fun toString() = "RekeyRecoveryCredentials(redacted)"
}
class MaintenanceRecovery {
    fun resolve(local: LocalStateStore, documents: MaintenanceDocuments, choice: RecoveryChoice,
                secrets: Map<Bytes,CanonicalSecret>, pinnedSource: ResourceOwner? = null,
                pinnedCandidate: ResourceOwner? = null): RecoveryResult =
        resolveInternal(local,documents,choice,secrets,null,false,pinnedSource,pinnedCandidate)
    fun resolveChangeSecret(local: LocalStateStore, documents: MaintenanceDocuments, choice: RecoveryChoice,
        credentials: RekeyRecoveryCredentials, pinnedSource: ResourceOwner? = null,
        pinnedCandidate: ResourceOwner? = null): RecoveryResult =
        resolveInternal(local,documents,choice,credentials.source,credentials.candidate,true,pinnedSource,pinnedCandidate)
    private fun resolveInternal(local: LocalStateStore, documents: MaintenanceDocuments, choice: RecoveryChoice,
        secrets: Map<Bytes,CanonicalSecret>, candidateSecrets: Map<Bytes,CanonicalSecret>?, explicitRekey: Boolean,
        pinnedSource: ResourceOwner?, pinnedCandidate: ResourceOwner?): RecoveryResult {
        var sourceOwner: ResourceOwner? = pinnedSource; var candidateOwner: ResourceOwner? = pinnedCandidate
        val keys = ArrayList<ZoneKey>(); var resolved = false; var primary: Throwable? = null
        try {
            if(pinnedSource != null && !pinnedSource.awaitQuiescent(0)) return RecoveryResult(RecoveryCode.SwitchUnknown)
            refuse(local.durableLocalState,MaintenanceCode.Unsupported)
            val state = if(local.reconciliationRequired) local.reconcile() else local.read()
            val j = state.journal ?: return RecoveryResult(RecoveryCode.SwitchUnknown)
            refuse(j.kind in setOf("compact","rekey") && (j.kind != "rekey" || j.zones.size == 1) && j.previousBinding != null && sameBindingDocument(j.previousBinding,j.source),MaintenanceCode.Conflict)
            fun artifact(f: DocumentFingerprint): Artifact = Artifact(f.uri,f.identity ?: throw MaintenanceRefusal(MaintenanceCode.Conflict))
            if(j.kind == "rekey" && !explicitRekey) return RecoveryResult(RecoveryCode.NeedsCredentials)
            val source = artifact(j.source); documents.checkSource(source); documents.checkIdentity(source)
            if(sourceOwner == null) sourceOwner = ResourceOwner(documents.open(source))
            val ownedSource = sourceOwner!!
            requireIo(ownedSource.store.guarantees.existingWriteDurable,IoCode.UnsupportedDurability)
            refuse(ownedSource.store.identity == source.identity,MaintenanceCode.Conflict)
            return ownedSource.reconcile {
                val s = ContainerReader(ownedSource.store).snapshot()
                refuse(sameFingerprint(j.source,fingerprint(ownedSource.store,source.uri,s)),MaintenanceCode.Conflict)
                // Even resolving to old source rechecks selected crypto; no saved verified flags.
                val main = s.header.zones.single { it.role == 1 }
                val required = (j.zones + main.zoneId).distinct()
                if(required.any { it !in secrets }) return@reconcile RecoveryResult(RecoveryCode.NeedsCredentials)
                for(id in required) {
                    val d = s.header.zones.singleOrNull { it.zoneId == id } ?: throw MaintenanceRefusal(MaintenanceCode.Conflict)
                    val ticket = CredentialManager().verifyCurrent(s.header.containerId,d,id,d.role,secrets.getValue(id))
                    try { ticket.consumeSecret(); keys.add(ticket.key) } catch(e: Throwable) { ticket.close(); throw e }
                    // key ownership moved to this service operation; ticket contains no secret now
                }
                val authorityKey = keys.single { it.zoneId == main.zoneId }
                val authorityRoot = ContainerReader(ownedSource.store).readRecord(s,authorityKey,main.root)
                authorityRoot.fill(0) // main authority is not an unselected main graph traversal
                val keyMap = keys.filter { it.zoneId in j.zones }.associateBy { it.zoneId }
                val compactor = SelectiveCompactor()
                val plan = compactor.preflight(ownedSource.store,s,j.zones.toSet(),keyMap,{})
                j.backup?.let { fp ->
                    val a = artifact(fp); documents.checkIdentity(a)
                    ResourceOwner(documents.open(a)).use { owner ->
                        requireDurableArtifact(owner.store)
                        val b = ContainerReader(owner.store).snapshot()
                        refuse(sameFingerprint(fp,fingerprint(owner.store,a.uri,b)) && fp.fullHash == j.source.fullHash && fp.length == j.source.length,
                            MaintenanceCode.Conflict)
                        SnapshotCopier().verify(owner.store,b,keyMap,{})
                    }
                }
                val final: LocalState
                val result: RecoveryResult
                if(choice == RecoveryChoice.Candidate) {
                    refuse(j.stage in listOf(JournalStage.CandidateVerified,JournalStage.SwitchIntent,JournalStage.Activated) && j.backup != null,
                        MaintenanceCode.Conflict)
                    if(j.kind == "rekey") {
                        val ids = setOf(j.zones.single(),main.zoneId)
                        if(candidateSecrets == null || ids.any { it !in candidateSecrets }) return@reconcile RecoveryResult(RecoveryCode.NeedsCredentials)
                    }
                    val fp = j.candidate ?: throw MaintenanceRefusal(MaintenanceCode.Conflict)
                    val a = artifact(fp); documents.checkIdentity(a)
                    if(candidateOwner == null) candidateOwner = ownedSource.ownedDocument(a.identity) ?: ResourceOwner(documents.open(a)).also {
                        ownedSource.adopt(it) // failed verification keeps an addressable pinned owner, not a lost lease
                    }
                    val ownedCandidate = candidateOwner!!
                    refuse(ownedCandidate.store.identity == a.identity && a.identity != source.identity && a.identity != j.backup!!.identity,MaintenanceCode.Conflict)
                    ownedCandidate.reconcile {
                        requireDurableArtifact(ownedCandidate.store)
                        refuse(sameFingerprint(fp,fingerprint(ownedCandidate.store,a.uri)),MaintenanceCode.Conflict)
                        if(j.kind == "compact") compactor.verify(ownedSource.store,plan,ownedCandidate.store,fp.instance,keyMap,{})
                        else {
                            val snapshot = ContainerReader(ownedCandidate.store).snapshot()
                            val targetId = j.zones.single()
                            val candidateMain = snapshot.header.zones.single { it.role == 1 }
                            val candidateRequired = setOf(targetId,candidateMain.zoneId)
                            refuse(candidateSecrets != null && candidateRequired.all { it in candidateSecrets },MaintenanceCode.Conflict)
                            val candidateKeys = HashMap<Bytes,ZoneKey>()
                            for(id in candidateRequired) {
                                val descriptor = snapshot.header.zones.single { it.zoneId == id }
                                val ticket = CredentialManager().verifyCurrent(snapshot.header.containerId,descriptor,id,descriptor.role,candidateSecrets!!.getValue(id))
                                ticket.consumeSecret(); keys.add(ticket.key); candidateKeys[id] = ticket.key
                            }
                            val mainRoot = ContainerReader(ownedCandidate.store).readRecord(snapshot,candidateKeys.getValue(candidateMain.zoneId),candidateMain.root)
                            mainRoot.fill(0)
                            val target = snapshot.header.zones.single { it.zoneId == targetId }
                            RekeyBuilder().verify(ownedSource.store,plan,ownedCandidate.store,fp.instance,keyMap.getValue(targetId),
                                candidateKeys.getValue(targetId),Envelope.fromDescriptor(snapshot.header.containerId,target),target.hint,{})
                        }
                        ownedCandidate.store.force(); ownedCandidate.store.forceIdentity()
                        refuse(sameFingerprint(fp,fingerprint(ownedCandidate.store,a.uri)),MaintenanceCode.Conflict)
                    }
                    final = LocalState(fp,StateJournal(j.opId,j.kind,j.zones,j.source,j.backup,fp,JournalStage.Activated,j.previousBinding,JournalOutcome.Confirmed))
                    result = RecoveryResult(RecoveryCode.CandidateConfirmed,fp,if(j.stage == JournalStage.Activated) JournalOutcome.Unreported else j.outcome)
                } else {
                    final = LocalState(j.previousBinding,StateJournal(j.opId,j.kind,j.zones,j.source,j.backup,j.candidate,
                        JournalStage.Aborted,j.previousBinding,JournalOutcome.Aborted,j.pendingArtifact))
                    result = RecoveryResult(RecoveryCode.SourceConfirmed,j.previousBinding)
                }
                // Refresh both source identity and contents immediately before durable resolution.
                documents.checkIdentity(source)
                refuse(sameFingerprint(j.source,fingerprint(ownedSource.store,source.uri)),MaintenanceCode.Conflict)
                if(local.replace(final) != LocalWriteCode.Committed) { ownedSource.markUnknown(); return@reconcile RecoveryResult(RecoveryCode.SwitchUnknown) }
                ownedSource.resolved(); resolved = true; result
            }
        } catch(e: Throwable) {
            primary = e
            return RecoveryResult(when(e) {
                is StoreException -> if(e.code == IoCode.AccessLost) RecoveryCode.AccessLost else RecoveryCode.SwitchUnknown
                is CryptoException -> RecoveryCode.CredentialsRejected
                is MaintenanceRefusal -> if(e.code == MaintenanceCode.Conflict) RecoveryCode.Conflict else RecoveryCode.SwitchUnknown
                else -> RecoveryCode.SwitchUnknown
            })
        } finally {
            var cleanup: Throwable? = null
            fun close(block: ()->Unit) {
                try { block() } catch(e: Throwable) { if(cleanup == null) cleanup = e else cleanup!!.addSuppressed(e) }
            }
            keys.forEach { close { it.close() } }
            if(resolved) {
                candidateOwner?.let { close { it.resolved(); it.close() } }
                sourceOwner?.let { close { it.resolved(); it.close() } }
            } else if(pinnedSource == null) {
                // A restart inspection never admitted a writer. Release its quiescent descriptors;
                // local binding/journal gate (openBound) still forbids writes until explicit resolve.
                if(pinnedCandidate == null) candidateOwner?.let { close { it.resolved(); it.close() } }
                sourceOwner?.let { close { it.resolved(); it.close() } }
            }
            cleanup?.let { if(primary != null) primary!!.addSuppressed(it) else throw it }
        }
    }
}
