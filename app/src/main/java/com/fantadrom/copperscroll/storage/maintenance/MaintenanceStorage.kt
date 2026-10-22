package com.fantadrom.copperscroll.storage.maintenance

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import com.fantadrom.copperscroll.storage.crypto.bytes

/** Synthetic models remain explicit. AndroidGated requires per-endpoint evidence; it is not approval. */
enum class MaintenanceProfile { Synthetic, JvmSynthetic, AndroidGated }
class Artifact(val uri: String, val identity: StoreIdentity) {
    override fun toString() = "Artifact(redacted)"
}
/** Adapter must implement exclusive create (never replace), identity checks and separate grants.
 * Creation failure may have an unknown outcome: no artifact is then assumed safe to remove.
 * deleteOwned is only for a known-identity, unverified artifact created by this operation.
 */
interface MaintenanceDocuments {
    val profile: MaintenanceProfile
    fun checkSource(source: Artifact)
    fun checkDestination(destination: String)
    fun availableBytes(destination: String): Long?
    fun createNew(destination: String, name: String): Pair<Artifact,RandomAccessStore>
    fun open(artifact: Artifact): RandomAccessStore
    fun checkIdentity(artifact: Artifact)
    fun deleteOwned(artifact: Artifact)
}
internal fun uniqueName(kind: String): String = "cscroll-$kind-" +
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()) +
    "-" + UUID.randomUUID().toString() + ".cscroll"
internal fun randomId() = Bytes(com.fantadrom.copperscroll.storage.crypto.SystemEntropy().bytes(16,true))
internal fun fingerprint(store: RandomAccessStore, uri: String, s: ReadSnapshot? = null, check: ()->Unit = {}): DocumentFingerprint {
    check(); val snapshot = s ?: ContainerReader(store,accessCheck=check).snapshot()
    return DocumentFingerprint(uri,snapshot.header.containerId,snapshot.header.instanceId,snapshot.header.generation,snapshot.header.committedLength,
        hashRange(store,131072,check),hashRange(store,snapshot.header.committedLength,check),store.identity)
}
internal fun hashRange(store: RandomAccessStore, length: Long, check: ()->Unit = {}): Bytes {
    requireIo(length >= 0 && length <= store.size)
    val digest = MessageDigest.getInstance("SHA-256"); var pos = 0L
    while(pos < length) {
        check(); val b = store.readFullyAt(pos,minOf(65536L,length-pos).toInt())
        try { digest.update(b) } finally { b.fill(0) }; pos += b.size
    }
    return Bytes(digest.digest())
}
internal fun sameFingerprint(a: DocumentFingerprint?, b: DocumentFingerprint?) = a?.wire() == b?.wire()
internal fun sameSnapshot(a: ReadSnapshot, b: ReadSnapshot) = a.headerBytes == b.headerBytes && a.observedLength == b.observedLength

/** Logical accounting, NOT released or physically allocated bytes. Overflow is rejected. */
class MaintenanceSizes(val source: Long, val candidate: Long, val oldBackups: Long? = null,
                       val scratch: Long? = null, val partial: Long? = null, val reserve: Long = 0, val sourceTail: Long = 0) {
    init { require(listOfNotNull(source,candidate,oldBackups,scratch,partial,reserve).all { it in 0..Limits.FILE_MAX }) }
    init { require(sourceTail in 0..Limits.FILE_MAX-source) }
    val sourceObservedLength = source+sourceTail
    val workingReduction = source-candidate
    val observedWorkingReduction = sourceObservedLength-candidate
    val retainedTotal = Math.addExact(Math.multiplyExact(2,source),candidate)
    val retainedFileLengths = Math.addExact(retainedTotal,sourceTail)
    val growth = Math.addExact(source,candidate)
    val totalWithExtras: Long? = if(oldBackups == null || scratch == null || partial == null) null
        else listOf(oldBackups,scratch,partial,reserve).fold(retainedFileLengths,Math::addExact)
    val allocatedBytes: Long? = null
}

/** Reject aliases without ever deleting them. Adapters must reject duplicate FDs before opening;
 * the synthetic branch still closes a newly returned handle on refusal. */
internal fun artifactOwner(artifact: Artifact, store: RandomAccessStore, forbidden: Set<StoreIdentity>,
                           sourceStore: RandomAccessStore): ResourceOwner {
    try {
        requireIo(artifact.identity !in forbidden && store.identity == artifact.identity,IoCode.InvalidRange)
        return ResourceOwner(store)
    } catch(e: Throwable) {
        if(store !== sourceStore) try { store.close() } catch(cleanup: Throwable) { e.addSuppressed(cleanup) }
        throw e
    }
}

internal fun requireFingerprint(expected: DocumentFingerprint, store: RandomAccessStore, check: ()->Unit = {}) {
    try { refuse(sameFingerprint(expected,fingerprint(store,expected.uri,check=check)),MaintenanceCode.Conflict) }
    catch(_: FormatException) { throw MaintenanceRefusal(MaintenanceCode.Conflict) }
}

/** Binding selects a physical working document. Its fingerprint is the last observed checkpoint,
 * not a sidecar that must be republished on every portable T. Journal.source is exact frozen S. */
internal fun sameBindingDocument(binding: DocumentFingerprint?, current: DocumentFingerprint): Boolean =
    binding != null && binding.identity != null && binding.uri == current.uri && binding.identity == current.identity &&
        binding.container == current.container && binding.instance == current.instance && current.generation >= binding.generation &&
        (current.generation != binding.generation || sameFingerprint(binding,current))

/** A durable namespace alone does not certify file data. Check the actual opened artifact too,
 * including after reopen; a weaker adapter must never receive VerifiedCopy/Activated. */
internal fun requireDurableArtifact(store: RandomAccessStore) {
    requireIo(store.guarantees.existingWriteDurable && store.guarantees.durableCreateIdentity,IoCode.UnsupportedDurability)
}
