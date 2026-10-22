package com.fantadrom.copperscroll.storage.io

import com.fantadrom.copperscroll.storage.format.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URI

/** Safe identity/fingerprint metadata only. No password, key, plaintext, or command payload fields. */
class DocumentFingerprint(val uri: String, val container: Bytes, val instance: Bytes, val generation: Long,
                          val length: Long, val headersHash: Bytes, val fullHash: Bytes? = null, val identity: StoreIdentity? = null) {
    internal fun wire(): List<Any?> {
        val parsed = try { URI(uri) } catch(_: Exception) { throw FormatException(Failure.ValidationError) }
        checkFormat(uri.toByteArray(Charsets.UTF_8).size in 1..4096 && parsed.scheme in listOf("content","file","synthetic") &&
            parsed.userInfo == null && parsed.fragment == null)
        container.id(16); instance.id(16); headersHash.sized(32); fullHash?.sized(32)
        checkFormat(generation > 0 && length in 131072..Limits.FILE_MAX)
        return listOf(uri,container,instance,generation,length,headersHash,fullHash) +
            if(identity == null) emptyList() else listOf(listOf(identity.namespace,identity.document))
    }
    override fun toString() = "DocumentFingerprint(redacted)"
}
class OwnedArtifactIdentity(val uri: String, val identity: StoreIdentity) {
    internal fun wire() = listOf(uri,identity.namespace,identity.document).also {
        checkFormat(uri.toByteArray(Charsets.UTF_8).size in 1..4096 && URI(uri).scheme in listOf("content","file","synthetic"))
    }
}
enum class JournalStage { Prepared, BackupVerified, Building, CandidateVerified, SwitchIntent, Activated,
    Aborted, Unverified, ReconciliationRequired }
enum class JournalOutcome { Pending, Confirmed, Unreported, Unknown, Aborted }
class StateJournal(val opId: Bytes, val kind: String, val zones: List<Bytes>, val source: DocumentFingerprint,
                   val backup: DocumentFingerprint?, val candidate: DocumentFingerprint?, val stage: JournalStage,
                   val previousBinding: DocumentFingerprint?, val outcome: JournalOutcome = JournalOutcome.Pending,
                   val pendingArtifact: OwnedArtifactIdentity? = null) {
    internal fun wire(): List<Any?> {
        opId.id(16); checkFormat(kind.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        checkFormat(zones.size in 1..2 && zones.distinct().size == zones.size); zones.forEach { it.id(16) }
        if(stage in listOf(JournalStage.BackupVerified,JournalStage.Building,JournalStage.CandidateVerified,
                JournalStage.SwitchIntent,JournalStage.Activated)) checkFormat(backup?.fullHash != null)
        if(stage in listOf(JournalStage.CandidateVerified,JournalStage.SwitchIntent,JournalStage.Activated))
            checkFormat(candidate?.fullHash != null)
        checkFormat(backup?.uri != source.uri && candidate?.uri != source.uri &&
            (backup == null || candidate == null || backup.uri != candidate.uri))
        if(outcome in listOf(JournalOutcome.Confirmed,JournalOutcome.Unreported)) checkFormat(stage == JournalStage.Activated)
        if(outcome == JournalOutcome.Aborted) checkFormat(stage == JournalStage.Aborted)
        if(source.identity != null) {
            checkFormat(backup?.identity != source.identity && candidate?.identity != source.identity &&
                (backup?.identity == null || candidate?.identity == null || backup.identity != candidate.identity))
            pendingArtifact?.let { pending -> checkFormat(pending.identity != source.identity && pending.identity != backup?.identity &&
                (pending.identity != candidate?.identity || stage == JournalStage.Aborted) && stage !in listOf(JournalStage.CandidateVerified,JournalStage.SwitchIntent,JournalStage.Activated)) }
        }
        return listOf(opId,kind,zones,source.wire(),backup?.wire(),candidate?.wire(),stage.name,previousBinding?.wire(),outcome.name) +
            if(pendingArtifact == null) emptyList() else listOf(pendingArtifact.wire())
    }
    override fun toString() = "StateJournal(stage=$stage)"
}
class LocalState(val binding: DocumentFingerprint?, val journal: StateJournal?) {
    override fun toString() = "LocalState(redacted)"
}
object LocalStateCodec {
    const val MAX_BYTES = 65536
    fun encode(state: LocalState): ByteArray {
        val journal = state.journal
        if(journal?.stage == JournalStage.Activated) checkFormat(state.binding != null &&
            state.binding.wire() == journal.candidate?.wire())
        else if(journal != null) checkFormat(state.binding?.wire() == journal.previousBinding?.wire())
        val extended = listOf(state.binding,journal?.source,journal?.backup,journal?.candidate,journal?.previousBinding).any { it?.identity != null } || journal?.pendingArtifact != null
        val body = StrictCbor.encode(listOf(if(extended) 2 else 1,state.binding?.wire(),journal?.wire()),MAX_BYTES-44)
        val framed = ByteBuffer.allocate(body.size+12).order(ByteOrder.LITTLE_ENDIAN)
            .put("CSLOCAL1".toByteArray(Charsets.US_ASCII)).putInt(body.size).put(body).array()
        return framed + sha(framed).copy()
    }
    fun decode(bytes: ByteArray): LocalState {
        checkFormat(bytes.size in 45..MAX_BYTES)
        checkFormat(bytes.copyOfRange(0,8).contentEquals("CSLOCAL1".toByteArray(Charsets.US_ASCII)))
        val length = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
        checkFormat(length > 0 && length == bytes.size-44)
        checkFormat(sha(bytes.copyOfRange(0,bytes.size-32)) == Bytes(bytes.copyOfRange(bytes.size-32,bytes.size)))
        val a = StrictCbor.decode(bytes.copyOfRange(12,12+length),MAX_BYTES).arr(3); checkFormat(a[0] in listOf(1uL,2uL),Failure.UnsupportedVersion)
        fun fingerprint(value: Any?): DocumentFingerprint? {
            if(value == null) return null
            val p = value.arr(); checkFormat(p.size == 7 || (a[0] == 2uL && p.size == 8))
            return DocumentFingerprint(p[0].text(4096),p[1].ident(),p[2].ident(),p[3].number(),p[4].number(Limits.FILE_MAX),
                p[5].bin(32),p[6]?.bin(32),if(p.size == 8) p[7].arr(2).let { StoreIdentity(it[0].text(4096),it[1].text(4096)) } else null).also { it.wire() }
        }
        val binding = fingerprint(a[1])
        val journal = a[2]?.arr()?.let { j ->
            checkFormat(j.size == 9 || (a[0] == 2uL && j.size == 10))
            val stage = JournalStage.entries.firstOrNull { it.name == j[6].text(32) }
                ?: throw FormatException(Failure.UnsupportedVersion)
            StateJournal(j[0].ident(),j[1].text(32),j[2].arr().also { checkFormat(it.size in 1..2) }.map { it.ident() },
                fingerprint(j[3]) ?: throw FormatException(Failure.CorruptData),fingerprint(j[4]),fingerprint(j[5]),stage,fingerprint(j[7]),
                JournalOutcome.entries.firstOrNull { it.name == j[8].text(32) } ?: throw FormatException(Failure.UnsupportedVersion),
                if(j.size == 10) j[9].arr(3).let { OwnedArtifactIdentity(it[0].text(4096),StoreIdentity(it[1].text(4096),it[2].text(4096))) } else null)
        }
        val state = LocalState(binding,journal); checkFormat(encode(state).contentEquals(bytes)); return state
    }
}
enum class LocalWriteCode { Committed, IoFailure, StateUnknown }
enum class LocalPoint { TempWrite, TempForce, Rename, ParentForce, Readback, Ack }
class LocalFault(val point: LocalPoint, val moment: FaultMoment, val prefix: Int = 0)

/** Atomic replacement MODEL: forced temp bytes and a separate volatile/durable name binding.
 * A lost/retained unsynced rename yields an old/new WHOLE record, never binding+journal mixtures.
 * No real Android AtomicFile/rename/parent-fsync capability is asserted by this implementation.
 */
/** A single atomic binding+journal port. Durability is an explicit adapter contract. */
interface LocalStateStore {
    val durableLocalState: Boolean
    val reconciliationRequired: Boolean
    fun read(): LocalState
    fun replace(state: LocalState): LocalWriteCode
    fun reconcile(): LocalState
}
class FaultLocalStateStore(initial: LocalState) : LocalStateStore {
    override val durableLocalState = true // synthetic model ONLY
    private var current = LocalStateCodec.encode(initial)
    private var durable = current.clone()
    var fault: LocalFault? = null
    override var reconciliationRequired = false; private set
    @Synchronized fun bytes() = current.clone()
    @Synchronized override fun read() = LocalStateCodec.decode(current)
    @Synchronized override fun replace(state: LocalState): LocalWriteCode {
        if(reconciliationRequired) return LocalWriteCode.StateUnknown
        val encoded = LocalStateCodec.encode(state); var renameStarted = false
        fun point(p: LocalPoint, moment: FaultMoment) {
            if(fault?.point == p && fault?.moment == moment) throw StoreException(IoCode.Eio)
        }
        try {
            point(LocalPoint.TempWrite,FaultMoment.Before)
            if(fault?.point == LocalPoint.TempWrite && fault?.moment == FaultMoment.Partial) {
                encoded.copyOf(fault!!.prefix.coerceIn(0,encoded.size)); throw StoreException(IoCode.NoSpace)
            }
            point(LocalPoint.TempWrite,FaultMoment.After)
            point(LocalPoint.TempForce,FaultMoment.Before); point(LocalPoint.TempForce,FaultMoment.Partial)
            val forcedTemp = encoded.clone(); point(LocalPoint.TempForce,FaultMoment.After)
            renameStarted = true; point(LocalPoint.Rename,FaultMoment.Before)
            if(fault?.point == LocalPoint.Rename && fault?.moment == FaultMoment.Partial) {
                if(fault!!.prefix != 0) current = forcedTemp
                throw StoreException(IoCode.Eio)
            }
            current = forcedTemp; point(LocalPoint.Rename,FaultMoment.After)
            point(LocalPoint.ParentForce,FaultMoment.Before)
            if(fault?.point == LocalPoint.ParentForce && fault?.moment == FaultMoment.Partial) {
                if(fault!!.prefix != 0) durable = current.clone()
                throw StoreException(IoCode.Eio)
            }
            durable = current.clone(); point(LocalPoint.ParentForce,FaultMoment.After)
            point(LocalPoint.Readback,FaultMoment.Before); checkFormat(LocalStateCodec.encode(read()).contentEquals(encoded))
            point(LocalPoint.Readback,FaultMoment.Partial); point(LocalPoint.Readback,FaultMoment.After)
            point(LocalPoint.Ack,FaultMoment.Before); point(LocalPoint.Ack,FaultMoment.Partial); point(LocalPoint.Ack,FaultMoment.After)
            return LocalWriteCode.Committed
        } catch(_: Exception) {
            reconciliationRequired = renameStarted
            return if(renameStarted) LocalWriteCode.StateUnknown else LocalWriteCode.IoFailure
        }
    }
    @Synchronized override fun reconcile(): LocalState {
        val observed = read()
        if(fault?.point == LocalPoint.ParentForce) throw StoreException(IoCode.Eio)
        durable = current.clone(); reconciliationRequired = false
        return observed
    }
    @Synchronized fun crash(retainUnsyncedRename: Boolean = false) {
        if(retainUnsyncedRename) durable = current.clone() else current = durable.clone()
        fault = null; reconciliationRequired = false
    }
}
