package com.fantadrom.copperscroll.storage.maintenance

import com.fantadrom.copperscroll.storage.crypto.ZoneKey
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*

/** Relative coverage NEVER means AEAD health of a closed zone, or protection from malicious replay. */
enum class VerificationCoverage { RelativeToSnapshot, AeadReachable }
class VerifiedCopy internal constructor(val artifact: Artifact, val fingerprint: DocumentFingerprint,
    coverage: Map<Bytes,VerificationCoverage>) {
    val coverage = java.util.Collections.unmodifiableMap(HashMap(coverage))
    override fun toString() = "VerifiedCopy(redacted)"
}

class SnapshotCopier {
    /** Caller holds source's exclusive operation permit through copy, verification and retention.
     * Keys are borrowed, only for selected zones (or the single open supported zone for manual backup).
     */
    internal fun copy(sourceOwner: ResourceOwner, sourcePermit: WritePermit, snapshot: ReadSnapshot,
        source: Artifact, documents: MaintenanceDocuments, destination: String, keys: Map<Bytes,ZoneKey>,
        created: (Artifact)->Unit = {}): VerifiedCopy {
        val check = { sourceOwner.check(sourcePermit) }
        check(); documents.checkSource(source); documents.checkDestination(destination)
        requireIo(sourceOwner.store.identity == source.identity,IoCode.AccessLost)
        val reader = ContainerReader(sourceOwner.store,accessCheck=check)
        requireIo(sameSnapshot(snapshot,reader.snapshot()),IoCode.InvalidRange)
        val before = fingerprint(sourceOwner.store,source.uri,snapshot,check)
        val coverage = verify(sourceOwner.store,snapshot,keys,check)
        val (artifact,store) = documents.createNew(destination,uniqueName("backup"))
        val owner = artifactOwner(artifact,store,setOf(source.identity),sourceOwner.store); var primary: Throwable? = null
        try {
            // A provider returning an alias to any earlier healthy copy must never make that
            // existing document an owned cleanup artifact, nor receive the first write.
            requireIo(owner.store.size == 0L,IoCode.InvalidRange)
            created(artifact)
            val permit = owner.accept()
            return owner.execute(permit) {
                var pos = 0L
                while(pos < snapshot.header.committedLength) {
                    check(); val b = sourceOwner.store.readFullyAt(pos,minOf(65536L,snapshot.header.committedLength-pos).toInt())
                    try { owner.store.writeFullyAt(pos,b) } finally { b.fill(0) }; pos += b.size
                }
                requireIo(owner.store.size == snapshot.header.committedLength,IoCode.Eio)
                requireIo(owner.store.guarantees.durableCreateIdentity,IoCode.UnsupportedDurability)
                check(); owner.store.force(); check(); owner.store.forceIdentity()
                owner.reopen(permit) { documents.open(artifact) }
                check(); documents.checkIdentity(artifact)
                val s = ContainerReader(owner.store,accessCheck=check).snapshot()
                val after = fingerprint(owner.store,artifact.uri,s,check)
                requireIo(after.length == before.length && after.fullHash == before.fullHash,IoCode.Eio)
                verify(owner.store,s,keys,check)
                requireIo(sameFingerprint(before,fingerprint(sourceOwner.store,source.uri,check=check)),IoCode.InvalidRange)
                requireIo(sameSnapshot(snapshot,reader.snapshot()),IoCode.InvalidRange)
                VerifiedCopy(artifact,after,coverage)
            }
        } catch(e: Throwable) { primary = e; throw e }
        finally { try { owner.close() } catch(e: Throwable) { if(primary != null) primary.addSuppressed(e) else throw e } }
    }
    internal fun verify(store: RandomAccessStore, s: ReadSnapshot, keys: Map<Bytes,ZoneKey>, check: ()->Unit): Map<Bytes,VerificationCoverage> {
        val reader = ContainerReader(store,accessCheck=check)
        for(catalogue in s.catalogues) for(p in catalogue.values) {
            check(); checkFormat(hashRecord(store,p,check) == p.hash)
        }
        checkFormat(keys.keys.all { id -> s.header.zones.any { it.zoneId == id } })
        return s.header.zones.associate { d ->
            keys[d.zoneId]?.let { key -> reader.verifyAll(s,key) }
            d.zoneId to if(d.zoneId in keys) VerificationCoverage.AeadReachable else VerificationCoverage.RelativeToSnapshot
        }
    }
    private fun hashRecord(store: RandomAccessStore, ref: PhysicalRef, check: ()->Unit): Bytes {
        val digest = java.security.MessageDigest.getInstance("SHA-256"); var pos = 0L
        while(pos < ref.length) {
            check(); val b = store.readFullyAt(ref.offset+pos,minOf(65536L,ref.length-pos).toInt())
            try { digest.update(b) } finally { b.fill(0) }; pos += b.size
        }
        return Bytes(digest.digest())
    }
}

class RetainedCopy internal constructor(val copy: VerifiedCopy, val order: Long, val currentlyValid: Boolean)
object CopyRetention {
    /** Fresh byte/identity recheck of the managed inventory. Bit equality with an earlier verified
     * copy preserves that copy's known coverage; it never elevates relative to AEAD coverage.
     * Returning a plan does not delete files and is not production SAF retention.
     */
    fun plan(documents: MaintenanceDocuments, previous: List<Pair<VerifiedCopy,Long>>,
             fresh: Pair<VerifiedCopy,Long>): List<Artifact> {
        checkFormat(previous.size <= 256,Failure.ResourceLimit)
        fun recheck(copy: VerifiedCopy): Boolean = try {
            documents.checkIdentity(copy.artifact)
            ResourceOwner(documents.open(copy.artifact)).use { owner ->
                val snapshot = ContainerReader(owner.store).snapshot()
                checkFormat(sameFingerprint(copy.fingerprint,fingerprint(owner.store,copy.artifact.uri,snapshot)))
                SnapshotCopier().verify(owner.store,snapshot,emptyMap(),{})
            }
            true
        } catch(_: Exception) { false }
        val next = RetainedCopy(fresh.first,fresh.second,recheck(fresh.first))
        require(next.currentlyValid)
        return removable(previous.map { RetainedCopy(it.first,it.second,recheck(it.first)) },next)
    }

    /** Pure managed-store policy; never deletes user documents. A fresh verified copy is mandatory.
     * For every known zone, retain a witness of the strongest known coverage. Invalid copies do not count.
     */
    internal fun removable(previous: List<RetainedCopy>, fresh: RetainedCopy): List<Artifact> {
        checkFormat(previous.size <= 256,Failure.ResourceLimit)
        require(fresh.currentlyValid && previous.none { it.copy.artifact.identity == fresh.copy.artifact.identity })
        require(previous.map { it.copy.artifact.identity }.distinct().size == previous.size)
        require(previous.all { it.copy.fingerprint.container == fresh.copy.fingerprint.container })
        val all = (previous + fresh).filter { it.currentlyValid }.sortedBy { it.order }.toMutableList()
        val strongest = all.flatMap { it.copy.coverage.entries }.groupBy({ it.key },{ it.value })
            .mapValues { (_,v) -> v.maxBy { it.ordinal } }
        val result = ArrayList<Artifact>()
        for(old in previous.sortedBy { it.order }) {
            if(!old.currentlyValid || all.size <= 3 || old !in all) continue
            val survivors = all.filter { it !== old }
            if(strongest.all { (zone,level) -> survivors.any { (it.copy.coverage[zone]?.ordinal ?: -1) >= level.ordinal } }) {
                all.remove(old); result.add(old.copy.artifact)
            }
        }
        return result
    }
}
