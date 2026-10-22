package com.fantadrom.copperscroll.storage.transaction

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import java.util.LinkedHashMap
import java.security.MessageDigest

enum class WriteCode { Committed, NoOp, Conflict, ValidationError, IoFailure, CommitUnknown, UnknownOperation, InvalidOperationToken,
    AccessLost, Busy, UnsupportedDurability, UnsupportedVersion, UnsupportedSecretEncoding, CorruptData, ResourceLimit, Limit, NotApplied }
class WriteOutcome(val code: WriteCode, val generation: Long? = null, val zoneSequence: Long? = null,
                   val revision: Long? = null, val objectId: Bytes? = null, overflow: List<Bytes> = emptyList()) {
    val overflow: List<Bytes> = java.util.Collections.unmodifiableList(ArrayList(overflow))
    override fun toString() = "WriteOutcome(code=$code,generation=$generation,zoneSequence=$zoneSequence,revision=$revision)"
}
class SettingsMutation(val expectedZoneSequence: Long, val expectedRevision: Long, val revision: Long,
                       settings: SettingsData) {
    internal val payload = Bytes(SettingsCodec.encode(settings))
    internal fun binding(): ByteArray = StrictCbor.encode(listOf("SetSettings",expectedZoneSequence,expectedRevision,revision,payload))
    override fun toString() = "SettingsMutation(redacted)"
}
class OperationToken internal constructor(val opId: Bytes, internal val epoch: Any,
    internal val permit: WritePermit, internal val binding: Bytes, internal val request: Any) {
    internal var outcome: WriteOutcome? = null
    override fun toString() = "OperationToken(redacted)"
}
class AppliedHint(val opId: Bytes, val objectId: Bytes?) // explicitly NOT a Committed result

/** Synthetic transaction core. Only create/add and settings mutate are commands in P3.
 * Object/children VFS editing belongs to P4. No SAF/UI/maintenance and no sidecar dependency.
 * One core transfers ownership of its keys to the IO owner. Blocking calls run away from UI.
 */
class TransactionCore internal constructor(val owner: ResourceOwner, private val beforeAck: () -> Unit) : AutoCloseable {
    constructor(owner: ResourceOwner) : this(owner,{})
    private class NewZone(val prepared: PreparedZone, val hint: String, val normal: Bytes, val trash: Bytes,
                          val add: Boolean, val expectedHeader: Bytes?, val minor: Int)
    private class Pending(val token: OperationToken, val before: Bytes?, val candidate: Bytes, val zoneKey: ZoneKey,
                          val revision: Long, val sequence: Long, val creating: Boolean,
                          val resultObjectId: Bytes? = null, val overflow: List<Bytes> = emptyList(),
                          val verificationRefs: List<LogicalRef> = emptyList())
    private val store = owner.store
    private var readCheck: ()->Unit = {}
    private val reader = ContainerReader(store,accessCheck={ readCheck() })
    private fun <T> checking(permit: WritePermit,block: ()->T): T {
        val previous = readCheck; readCheck = { owner.check(permit) }
        try { return block() } finally { readCheck = previous }
    }
    private val records = RecordCrypto()
    private val epoch = Any()
    private val tokens = LinkedHashMap<Bytes,OperationToken>()
    private var snapshot: ReadSnapshot? = null
    private var key: ZoneKey? = null
    private var keyResource: AutoCloseable? = null
    private var revision = 0L
    private var pending: Pending? = null
    @Synchronized fun attach(ownedKey: ZoneKey): ZoneView {
        val permit = owner.accept()
        return owner.execute(permit) { checking(permit) {
            requireIo(key == null)
            val s = reader.snapshot(); val view = reader.openZone(s,ownedKey)
            owner.check(permit); owner.adopt(ownedKey); key = ownedKey; keyResource = ownedKey; snapshot = s; view
        } }
    }
    /** Opaque capability minted from the attached authenticated key; caller cannot select a role. */
    class MaintenanceCapability internal constructor()
    private val maintenanceCapability = MaintenanceCapability()
    @Synchronized fun authorizeMaintenance(): MaintenanceCapability {
        requireIo(!owner.accessRevoked && !owner.reconciliationRequired && key?.role == 1,IoCode.AccessLost)
        return maintenanceCapability
    }
    @Synchronized internal fun <T> maintenanceAccess(capability: MaintenanceCapability, freeze: Boolean = false, admission: ()->Unit = {},
        block: (ReadSnapshot,ZoneKey,ContainerReader,WritePermit)->T): T {
        requireIo(capability === maintenanceCapability && key?.role == 1,IoCode.AccessLost)
        // Reject undrained work rather than silently dropping a prepared save or replaying Unknown.
        requireIo(pending == null && tokens.values.none { it.outcome == null },IoCode.Busy)
        val permit = owner.accept()
        admission() // short request/confirmation epoch check, BEFORE any freeze or source IO
        return owner.execute(permit) { checking(permit) {
            if(freeze) owner.beginMaintenance(permit)
            try {
                owner.check(permit)
                val s = snapshot ?: throw StoreException(IoCode.AccessLost)
                val actual = try { reader.snapshot() } catch(e: FormatException) {
                    throw StoreException(IoCode.InvalidRange).also { it.addSuppressed(e) }
                }
                requireIo(actual.headerBytes == s.headerBytes && actual.observedLength == s.observedLength,IoCode.InvalidRange)
                block(actual,key!!,reader,permit)
            } finally { if(freeze) owner.stopMaintenanceAdmission() }
        } }
    }
    private var maintenanceTicket: Any? = null
    private var maintenanceContext: AutoCloseable? = null
    @Synchronized internal fun newMaintenanceTicket(): Any {
        requireIo(!owner.accessRevoked && key?.role == 1,IoCode.AccessLost)
        val previous = maintenanceContext; maintenanceContext = null
        previous?.let { owner.retire(it) }
        return Any().also { maintenanceTicket = it }
    }
    @Synchronized internal fun checkMaintenanceTicket(ticket: Any, context: AutoCloseable? = null) {
        requireIo(!owner.accessRevoked && key?.role == 1,IoCode.AccessLost)
        com.fantadrom.copperscroll.storage.maintenance.refuse(maintenanceTicket === ticket &&
            (context == null || maintenanceContext === context),com.fantadrom.copperscroll.storage.maintenance.MaintenanceCode.Denied)
    }
    @Synchronized internal fun setMaintenanceContext(ticket: Any, context: AutoCloseable?) {
        checkMaintenanceTicket(ticket)
        val previous = maintenanceContext; maintenanceContext = context
        if(context != null) owner.adopt(context)
        previous?.let { owner.retire(it) }
    }
    /** IO may remain pinned for reconciliation, but the revoked session no longer needs a key. */
    @Synchronized internal fun endMaintenanceSession() {
        requireIo(owner.accessRevoked,IoCode.AccessLost)
        val context = maintenanceContext; maintenanceContext = null; maintenanceTicket = null
        context?.let { owner.retire(it) }
        val resource = keyResource; keyResource = null
        if(resource != null) owner.retire(resource) else key?.close()
        key = null; snapshot = null; tokens.clear()
    }
    @Synchronized fun issueCreate(prepared: PreparedZone, hint: String = "", containerMinor: Int = 1): OperationToken {
        requireIo(snapshot == null && key == null && store.size == 0L)
        requireIo(store.guarantees.existingWriteDurable && store.guarantees.durableCreateIdentity,IoCode.UnsupportedDurability)
        requireCrypto(prepared.key.role == 1)
        checkFormat(containerMinor in 1..2,Failure.UnsupportedVersion)
        return issueNewWithMinor(prepared,hint,false,containerMinor)
    }
    private fun issueNew(prepared: PreparedZone, hint: String, add: Boolean): OperationToken {
        return issueNewWithMinor(prepared,hint,add,snapshot?.header?.minor ?: 1)
    }
    private fun issueNewWithMinor(prepared: PreparedZone, hint: String, add: Boolean, minor: Int): OperationToken {
        requireCrypto(hint.toByteArray(Charsets.UTF_8).size <= 512)
        val permit = owner.accept()
        val normal = randomId(); var trash = randomId()
        requireCrypto(normal != trash,CryptoFailure.Collision)
        val request = NewZone(prepared,hint,normal,trash,add,snapshot?.headerBytes?.let { sha(it.copy()) },minor)
        val binding = Bytes(StrictCbor.encode(listOf(if(add) "AddZone" else "Create",Bytes(prepared.envelope.aad()),
            prepared.envelope.nonce,prepared.envelope.wrappedKey,hint,normal,trash,snapshot?.headerBytes?.let { sha(it.copy()) },minor)))
        owner.adopt(prepared)
        try { return register(permit,binding,request) } catch(e: Throwable) { owner.retire(prepared); throw e }
    }
    /** Current-main reverify and new-twice/inequality precede any file write. No forgotten-decoy reset. */
    @Synchronized fun issueAdd(currentMain: CanonicalSecret, newSecret: CanonicalSecret, repeated: CanonicalSecret,
                               inputMethod: Int, parameters: KdfParameters = KdfParameters(), hint: String = ""): OperationToken {
        val currentKey = key ?: throw StoreException(IoCode.AccessLost)
        val s = snapshot ?: throw StoreException(IoCode.AccessLost)
        requireCrypto(currentKey.role == 1 && s.header.zones.size == 1)
        val permit = owner.accept()
        val cancel = CryptoCancellation { owner.accessRevoked }
        val prepared = owner.execute(permit) {
            val manager = CredentialManager(); val main = s.header.zones.single()
            manager.verifyCurrent(s.header.containerId,main,main.zoneId,1,currentMain,currentKey,cancel).use { ticket ->
                owner.check(permit)
                val result = manager.prepareAdditionalZone(ticket,main,randomId(),newSecret,repeated,inputMethod,parameters,cancel)
                try { owner.check(permit); result } catch(e: Throwable) { result.close(); throw e }
            }
        }
        try { return issueNew(prepared,hint,true) } catch(e: Throwable) { prepared.close(); throw e }
    }
    @Synchronized fun issueMutation(request: SettingsMutation): OperationToken {
        requireIo(snapshot != null && key != null,IoCode.AccessLost)
        requireIo(request.expectedZoneSequence > 0 && request.expectedRevision >= 0 && request.revision > request.expectedRevision)
        return register(owner.acceptCheckpoint(),Bytes(request.binding()),request)
    }
    @Synchronized internal fun issueZoneMutation(request: ZoneMutation): OperationToken {
        requireIo(snapshot != null && key != null,IoCode.AccessLost)
        requireIo(request.expectedZoneSequence > 0 && request.expectedRevision >= 0 && request.revision > request.expectedRevision)
        requireIo(request.retainedBytes <= 32L*1024*1024,IoCode.InvalidRange)
        val permit = if(request.checkpoint) owner.acceptCheckpoint() else owner.accept()
        owner.adopt(request)
        try { return register(permit,Bytes(request.binding()),request) }
        catch(e: Throwable) { owner.retire(request); throw e }
    }
    /** Does not acquire the core mutex: a blocked syscall must not delay access revocation. */
    fun revokeForClosingSave(token: OperationToken? = null) {
        if(token != null) requireIo(token.epoch === epoch,IoCode.AccessLost)
        owner.revoke(token?.permit)
    }
    @Synchronized internal fun <T> readZone(block: (ReadSnapshot,ZoneKey,ZoneView,ContainerReader,()->Unit) -> T): T {
        val permit = owner.accept()
        return owner.execute(permit) { checking(permit) {
            owner.check(permit)
            val s = snapshot ?: throw StoreException(IoCode.AccessLost)
            val k = key ?: throw StoreException(IoCode.AccessLost)
            val view = reader.openZone(s,k)
            checkFormat(!view.readOnly,Failure.UnsupportedVersion)
            val result = block(s,k,view,reader,{ owner.check(permit) })
            owner.check(permit); result
        } }
    }
    @Synchronized internal fun requestRevision(): Long { requireIo(!owner.accessRevoked,IoCode.AccessLost); return revision }
    private fun register(permit: WritePermit, binding: Bytes, request: Any): OperationToken {
        val id = randomId(); requireCrypto(id !in tokens,CryptoFailure.Collision)
        val token = OperationToken(id,epoch,permit,binding,request); tokens[id] = token
        while(tokens.size > 256 || tokens.values.sumOf { (it.request as? ZoneMutation)?.retainedBytes ?: 0 } > 32L*1024*1024) {
            val oldest = tokens.entries.first(); requireIo(pending?.token !== oldest.value,IoCode.Busy)
            val oldRequest = oldest.value.request
            if(oldRequest is NewZone && oldRequest.prepared.key !== key) owner.retire(oldRequest.prepared)
            if(oldRequest is ZoneMutation) owner.retire(oldRequest)
            tokens.remove(oldest.key)
        }
        return token
    }
    @Synchronized fun submit(token: OperationToken, repeatedRequest: SettingsMutation? = null): WriteOutcome {
        if(owner.accessRevoked && !owner.admitsClosingSave(token.permit)) return WriteOutcome(WriteCode.AccessLost)
        if(token.epoch !== epoch || tokens[token.opId] !== token) return WriteOutcome(WriteCode.UnknownOperation)
        if(repeatedRequest != null && !MessageDigest.isEqual(token.binding.copy(),repeatedRequest.binding()))
            return WriteOutcome(WriteCode.InvalidOperationToken)
        if(owner.reconciliationRequired) return WriteOutcome(WriteCode.CommitUnknown)
        token.outcome?.let { return it }
        return try { owner.execute(token.permit) { checking(token.permit) { commit(token) } } }
        catch(e: StoreException) { WriteOutcome(if(e.code == IoCode.Busy) WriteCode.Busy else ioCode(e)) }
    }
    private fun commit(token: OperationToken): WriteOutcome {
        var publishing = false
        var ownedSettings: ByteArray? = null
        try {
            owner.check(token.permit)
            requireIo(store.guarantees.existingWriteDurable,IoCode.UnsupportedDurability)
            val old = snapshot
            if(old != null) {
                val actual = reader.snapshot()
                if(actual.headerBytes != old.headerBytes || actual.observedLength != old.observedLength) {
                    owner.revoke(); return terminal(token,WriteOutcome(WriteCode.Conflict))
                }
            } else requireIo(store.size == 0L)
            val request = token.request
            val target: ZoneKey; val descriptor: Descriptor?; val root: RootData?; val newSettings: ByteArray?
            var currentView: ZoneView? = null
            val nextSequence: Long; val nextGeneration: Long
            if(old?.header?.generation == Long.MAX_VALUE) return terminal(token,WriteOutcome(WriteCode.Limit))
            nextGeneration = (old?.header?.generation ?: 0) + 1
            if(request is NewZone) {
                if(request.expectedHeader != old?.headerBytes?.let { sha(it.copy()) })
                    return terminal(token,WriteOutcome(WriteCode.Conflict))
                requireIo(request.add == (old != null))
                if(request.add) {
                    val source = old ?: throw StoreException(IoCode.AccessLost)
                    requireIo(key?.role == 1 && source.header.zones.size == 1)
                    requireCrypto(request.prepared.key.containerId == source.header.containerId &&
                        request.prepared.key.zoneId != source.header.zones.single().zoneId && request.prepared.key.role == 2)
                }
                target = request.prepared.key; descriptor = null; root = null; nextSequence = 1
                newSettings = SettingsCodec.encode(SettingsData())
            } else {
                target = key!!
                descriptor = old!!.header.zones.single { it.zoneId == target.zoneId }
                val expectedSeq = if(request is SettingsMutation) request.expectedZoneSequence else (request as ZoneMutation).expectedZoneSequence
                val expectedRevision = if(request is SettingsMutation) request.expectedRevision else (request as ZoneMutation).expectedRevision
                if(descriptor.sequence != expectedSeq || revision != expectedRevision)
                    return terminal(token,WriteOutcome(WriteCode.Conflict))
                if(descriptor.sequence == Long.MAX_VALUE) return terminal(token,WriteOutcome(WriteCode.Limit))
                val view = reader.openZone(old,target)
                if(view.readOnly) return terminal(token,WriteOutcome(WriteCode.UnsupportedVersion))
                currentView = view; root = view.root!!; nextSequence = descriptor.sequence + 1
                newSettings = (request as? SettingsMutation)?.payload?.copy()
            }
            ownedSettings = newSettings
            if(old == null) store.writeFullyAt(0,ByteArray(131072))
            val known = old?.catalogues?.flatMap { it.keys }?.toHashSet() ?: HashSet()
            val appended = ArrayList<Pair<Bytes,PhysicalRef>>()
            val newRecords = HashMap<Bytes,PhysicalRef>()
            val verificationRefs = ArrayList<LogicalRef>()
            val reservation = RecordIdReservation { known.add(it) }
            fun record(kind: Int, plain: ByteArray, ownerId: Bytes = target.zoneId, ordinal: Long = 0): LogicalRef {
                try {
                    owner.check(token.permit)
                    if(request is ZoneMutation) checkFormat((known.size.toLong()+newRecords.size)*384 <= 32L*1024*1024,Failure.ResourceLimit)
                    val encrypted = records.encrypt(target,kind,ownerId,ordinal,plain,reservation)
                    val offset = store.appendAligned(encrypted.raw.copy())
                    val physical = PhysicalRef(offset,encrypted.raw.size.toLong(),encrypted.reference.hash)
                    appended.add(encrypted.reference.recordId to physical); newRecords[encrypted.reference.recordId] = physical
                    if(request is ZoneMutation) verificationRefs.add(encrypted.reference)
                    return encrypted.reference
                } finally { plain.fill(0) }
            }
            val edit = if(request is ZoneMutation) request.apply(ZoneWriteContext(old!!,currentView!!,
                { ref ->
                    val p = newRecords[ref.recordId]
                    if(p == null) reader.readRecord(old,target,ref)
                    else records.decrypt(target,store.readFullyAt(p.offset,p.length.toInt()),ref).use { it.copyBytes() }
                }, { kind,ownerId,ordinal,b -> record(kind,b,ownerId,ordinal) }, { owner.check(token.permit) })) else null
            if(edit?.noOp == true) {
                checkFormat(appended.isEmpty()); revision = (request as ZoneMutation).revision
                return terminal(token,WriteOutcome(WriteCode.NoOp,old!!.header.generation,descriptor!!.sequence,revision,
                    if(edit.noObject) null else request.resultObjectId,edit.overflow))
            }
            val state = edit?.root ?: root
            val objects = state?.objects ?: record(2,IndexCodec.encode(IndexPage(1,0,emptyList()),target.zoneId))
            val children = state?.children ?: record(2,IndexCodec.encode(IndexPage(2,0,emptyList()),target.zoneId))
            val settings = if(newSettings != null) record(3,newSettings) else state!!.settings
            val resultObjectId = (request as? ZoneMutation)?.resultObjectId
            val recent = if(root == null) emptyList() else (root.recentOperations + (token.opId to resultObjectId)).takeLast(256)
            val newRoot = RootData(nextSequence,root?.normalRoot ?: (request as NewZone).normal,
                root?.trashRoot ?: (request as NewZone).trash,objects,children,settings,state?.nextPinSequence ?: 1uL,recent)
            val rootRef = record(1,RootCodec.encode(newRoot,target.zoneId))
            val catalogue = CowCatalogue(store,target.zoneId)
            var catalogueRoot = descriptor?.catalogue
            if(catalogueRoot == null) catalogueRoot = catalogue.build(appended)
            else for((id,physical) in appended) catalogueRoot = catalogue.insert(catalogueRoot,id,physical)
            val envelope = if(request is NewZone) request.prepared.envelope else Envelope.fromDescriptor(target.containerId,descriptor!!)
            val hint = if(request is NewZone) request.hint else descriptor!!.hint
            val draft = Descriptor(target.zoneId,target.role,nextSequence,envelope.inputMethod,envelope.parameters.memoryKiB,
                envelope.parameters.iterations,envelope.salt,envelope.nonce,envelope.wrappedKey,hint,rootRef,Bytes(ByteArray(32)),catalogueRoot!!)
            val changed = Descriptor(draft.zoneId,draft.role,draft.sequence,draft.inputMethod,draft.memoryKiB,draft.iterations,
                draft.salt,draft.wrapNonce,draft.wrappedKey,draft.hint,draft.root,records.descriptorMac(target,draft),draft.catalogue)
            val zones = old?.header?.zones?.map { if(it.zoneId == target.zoneId) changed else it }?.toMutableList() ?: mutableListOf()
            if(zones.none { it.zoneId == target.zoneId }) zones.add(changed)
            val header = Superblock(nextGeneration,store.size,target.containerId,old?.header?.instanceId ?: randomId(),zones,
                old?.header?.minor ?: (request as NewZone).minor)
            val bytes = SuperblockCodec.encode(header)
            owner.check(token.permit); store.force() // DATA barrier precedes header publication.
            owner.check(token.permit)
            pending = Pending(token,old?.headerBytes,Bytes(bytes),target,
                (request as? SettingsMutation)?.revision ?: (request as? ZoneMutation)?.revision ?: revision,nextSequence,old == null,
                resultObjectId,edit?.overflow ?: emptyList(),verificationRefs)
            publishing = true; owner.markUnknown()
            store.writeFullyAt(if(old == null) 0 else (1-old.slot)*65536L,bytes)
            owner.check(token.permit); store.force()
            owner.check(token.permit)
            val verified = verifyCandidate(pending!!)
            if(old == null) { owner.check(token.permit); store.forceIdentity() }
            owner.check(token.permit); beforeAck(); owner.check(token.permit)
            return finish(verified)
        } catch(e: Throwable) {
            if(publishing) return WriteOutcome(WriteCode.CommitUnknown)
            val result = WriteOutcome(when(e) {
                is OutOfMemoryError -> WriteCode.ResourceLimit
                is MutationRefusal -> e.code
                is StoreException -> ioCode(e)
                is FormatException -> when(e.failure) {
                    Failure.ResourceLimit -> WriteCode.ResourceLimit
                    Failure.UnsupportedLimit -> WriteCode.Limit
                    Failure.UnsupportedVersion -> WriteCode.UnsupportedVersion
                    Failure.ValidationError -> WriteCode.ValidationError
                    else -> WriteCode.CorruptData
                }
                is CryptoException -> when(e.failure) {
                    CryptoFailure.ResourceLimit -> WriteCode.ResourceLimit
                    CryptoFailure.UnsupportedSecretEncoding -> WriteCode.UnsupportedSecretEncoding
                    else -> WriteCode.CorruptData
                }
                else -> WriteCode.IoFailure
            })
            // Our failed append may leave a tail; it remains invisible and the next request appends at EOF.
            try { snapshot?.let { val fresh = reader.snapshot(); if(fresh.headerBytes == it.headerBytes) snapshot = fresh } }
            catch(_: Exception) { owner.revoke() }
            return terminal(token,result)
        } finally { ownedSettings?.fill(0) }
    }
    private fun verifyCandidate(p: Pending): ReadSnapshot {
        val actual = reader.snapshot(); checkFormat(actual.headerBytes == p.candidate)
        val view = reader.openZone(actual,p.zoneKey); checkFormat(!view.readOnly && view.root?.sequence == p.sequence)
        for(ref in p.verificationRefs) {
            val plain = reader.readRecord(actual,p.zoneKey,ref); plain.fill(0)
        }
        return actual
    }
    private fun finish(actual: ReadSnapshot): WriteOutcome {
        val p = pending!!; snapshot = actual; revision = p.revision
        if(key == null) { key = p.zoneKey; keyResource = (p.token.request as? NewZone)?.prepared ?: p.zoneKey }
        val result = WriteOutcome(WriteCode.Committed,actual.header.generation,p.sequence,p.revision,p.resultObjectId,p.overflow)
        pending = null; owner.resolved()
        return terminal(p.token,result)
    }
    private fun terminal(token: OperationToken, result: WriteOutcome): WriteOutcome {
        token.outcome = result
        val request = token.request
        if(request is NewZone && (request.add || result.code != WriteCode.Committed)) owner.retire(request.prepared)
        if(request is ZoneMutation) owner.retire(request)
        return result
    }
    /** No destructive replay. Reread, force the observed state, reread and authenticate before ACK.
     * With no live binding (restart/window loss), recentOperations yields only AppliedHint.
     */
    @Synchronized fun reconcile(): WriteOutcome = owner.reconcile {
        val p = pending ?: return@reconcile WriteOutcome(WriteCode.UnknownOperation)
        try {
            val actual = reader.snapshot()
            if(actual.headerBytes == p.candidate) {
                store.force(); val checked = verifyCandidate(p)
                if(p.creating) store.forceIdentity()
                finish(checked)
            } else if(p.before != null && actual.headerBytes == p.before) {
                reader.openZone(actual,key!!).also { checkFormat(!it.readOnly) }
                store.force(); val checked = reader.snapshot(); checkFormat(checked.headerBytes == p.before)
                snapshot = checked; pending = null; owner.resolved(); terminal(p.token,WriteOutcome(WriteCode.NotApplied))
            } else stopReconciliation(p,WriteCode.Conflict)
        } catch(e: Exception) {
            try {
                if(p.creating && e is FormatException && e.failure == Failure.CorruptHeader && noChecksumHeader()) {
                    // Initial create has no S0. Persist/recheck the incomplete state before saying
                    // NotApplied; do not activate or reuse this unverified document.
                    store.force()
                    if(noChecksumHeader()) return@reconcile stopReconciliation(p,WriteCode.NotApplied)
                    return@reconcile WriteOutcome(WriteCode.CommitUnknown)
                }
            } catch(_: Exception) { return@reconcile WriteOutcome(WriteCode.CommitUnknown) }
            when(e) {
                is FormatException -> stopReconciliation(p,when(e.failure) {
                    Failure.UnsupportedVersion,Failure.UnsupportedLimit -> WriteCode.UnsupportedVersion
                    Failure.ResourceLimit -> WriteCode.ResourceLimit
                    else -> WriteCode.CorruptData
                })
                is CryptoException -> if(e.failure == CryptoFailure.CorruptData) stopReconciliation(p,WriteCode.CorruptData) else WriteOutcome(WriteCode.CommitUnknown)
                else -> WriteOutcome(WriteCode.CommitUnknown)
            }
        }
    }
    private fun noChecksumHeader(): Boolean {
        if(store.size < 131072) return false
        return (0..1).all { slot ->
            val raw = store.readFullyAt(slot*65536L,65536)
            sha(raw.copyOfRange(0,65504)) != Bytes(raw.copyOfRange(65504,65536))
        }
    }
    private fun stopReconciliation(p: Pending, code: WriteCode): WriteOutcome {
        pending = null; owner.resolved(); owner.revoke()
        return terminal(p.token,WriteOutcome(code))
    }
    @Synchronized fun appliedHint(opId: Bytes): AppliedHint? {
        requireIo(!owner.accessRevoked,IoCode.AccessLost)
        val s = snapshot ?: return null; val k = key ?: return null
        val root = reader.openZone(s,k).root ?: return null
        return root.recentOperations.firstOrNull { it.first == opId }?.let { AppliedHint(it.first,it.second) }
    }
    @Synchronized fun currentSnapshot(): ReadSnapshot {
        requireIo(!owner.accessRevoked,IoCode.AccessLost)
        return snapshot ?: throw StoreException(IoCode.AccessLost)
    }
    override fun close() { owner.close() }
    companion object {
        /** Required entry point for files controlled by binding+journal. Low-level constructors are
         * retained for synthetic P3 fixtures, never an alternative application opening policy. */
        fun openBound(owner: ResourceOwner, local: LocalStateStore, ownedKey: ZoneKey): TransactionCore {
            var pin = false
            try {
                val permit = owner.accept()
                owner.execute(permit) {
                    requireIo(local.durableLocalState,IoCode.UnsupportedDurability)
                    val state = local.read(); val journal = state.journal
                    pin = local.reconciliationRequired || (journal != null &&
                        (journal.stage !in listOf(JournalStage.Activated,JournalStage.Aborted) || journal.outcome == JournalOutcome.Unreported))
                    requireIo(!pin,IoCode.Busy)
                    val binding = state.binding ?: throw StoreException(IoCode.AccessLost)
                    val actual = com.fantadrom.copperscroll.storage.maintenance.fingerprint(owner.store,binding.uri,check={ owner.check(permit) })
                    requireIo(com.fantadrom.copperscroll.storage.maintenance.sameBindingDocument(binding,actual),IoCode.AccessLost)
                }
                return TransactionCore(owner).also { it.attach(ownedKey) }
            } catch(e: Throwable) {
                ownedKey.close(); if(pin) owner.markUnknown(); owner.revoke()
                if(!pin) try { owner.release() } catch(cleanup: Throwable) { e.addSuppressed(cleanup) }
                throw e
            }
        }
        private fun randomId() = Bytes(SystemEntropy().bytes(16,true))
        private fun ioCode(e: StoreException) = when(e.code) {
            IoCode.AccessLost,IoCode.Closed -> WriteCode.AccessLost
            IoCode.UnsupportedDurability -> WriteCode.UnsupportedDurability
            IoCode.Busy -> WriteCode.Busy
            else -> WriteCode.IoFailure
        }
    }
}

/** ACK N cannot clean edits N+1. No editor/UI integration is introduced here. */
class RevisionTracker(initial: Long = 0) {
    var current = initial; private set
    var acknowledged = initial; private set
    val dirty get() = current != acknowledged
    fun edited() { require(current < Long.MAX_VALUE); current++ }
    fun acknowledge(result: WriteOutcome) {
        if(result.code == WriteCode.Committed || result.code == WriteCode.NoOp) result.revision?.let { require(it <= current); acknowledged = maxOf(acknowledged,it) }
    }
}
