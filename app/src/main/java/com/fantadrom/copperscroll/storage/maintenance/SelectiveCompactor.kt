package com.fantadrom.copperscroll.storage.maintenance

import com.fantadrom.copperscroll.storage.crypto.ZoneKey
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import java.util.TreeMap

/** Contains only public record identifiers, no plaintext or keys. */
internal class CompactionPlan(val source: ReadSnapshot, val retained: List<Set<Bytes>>) {
    val estimatedMaximum = source.header.committedLength + source.catalogues.sumOf { it.size.toLong()*Limits.PAGE } + 131072
}
internal class VerifiedCandidate(val artifact: Artifact, val fingerprint: DocumentFingerprint,
                                 val coverage: Map<Bytes,VerificationCoverage>)
internal class SelectiveCompactor {
    fun preflight(store: RandomAccessStore, s: ReadSnapshot, selected: Set<Bytes>, keys: Map<Bytes,ZoneKey>, check: ()->Unit): CompactionPlan {
        checkFormat(s.header.generation < Long.MAX_VALUE,Failure.UnsupportedLimit)
        checkFormat(selected.isNotEmpty() && selected.all { id -> s.header.zones.any { it.zoneId == id } },Failure.ValidationError)
        checkFormat(keys.keys == selected,Failure.ValidationError)
        checkFormat(s.catalogues.sumOf { it.size.toLong() }*1024 <= 32L*1024*1024,Failure.ResourceLimit)
        val reader = ContainerReader(store,accessCheck=check)
        SnapshotCopier().verify(store,s,emptyMap(),check)
        val retained = s.header.zones.mapIndexed { i,d ->
            check()
            if(d.zoneId in selected) reader.reachable(s,keys.getValue(d.zoneId)) else s.catalogues[i].keys.toSet()
        }
        return CompactionPlan(s,retained)
    }
    fun build(source: RandomAccessStore, plan: CompactionPlan, destination: RandomAccessStore, check: ()->Unit): Bytes {
        check(); requireIo(destination.size == 0L && source.identity != destination.identity)
        destination.writeFullyAt(0,ByteArray(131072))
        val zones = plan.source.header.zones.mapIndexed { i,d ->
            val moved = ArrayList<Pair<Bytes,PhysicalRef>>()
            for(id in plan.retained[i].sorted()) {
                check(); val p = plan.source.catalogues[i].getValue(id)
                val start = aligned(destination.size); var pos = 0L
                if(start > destination.size) destination.writeFullyAt(destination.size,ByteArray((start-destination.size).toInt()))
                while(pos < p.length) {
                    check(); val b = source.readFullyAt(p.offset+pos,minOf(65536L,p.length-pos).toInt())
                    try { destination.writeFullyAt(start+pos,b) } finally { b.fill(0) }; pos += b.size
                }
                moved.add(id to PhysicalRef(start,p.length,p.hash))
            }
            check(); val catalogue = CowCatalogue(destination,d.zoneId).build(moved)
            Descriptor(d.zoneId,d.role,d.sequence,d.inputMethod,d.memoryKiB,d.iterations,d.salt,d.wrapNonce,
                d.wrappedKey,d.hint,d.root,d.logicalMac,catalogue)
        }
        val h = plan.source.header
        val instance = randomId(); checkFormat(instance != h.instanceId)
        val bytes = SuperblockCodec.encode(Superblock(h.generation+1,destination.size,h.containerId,instance,zones,h.minor))
        check(); destination.force(); check()
        destination.writeFullyAt(0,bytes); check(); destination.writeFullyAt(65536,bytes)
        return instance
    }
    /** Re-derive catalogue, reachability and physical partition from the new file. Never trusts the
     * builder's offset list. The source plan is only the expected logical snapshot oracle. */
    fun verify(source: RandomAccessStore, plan: CompactionPlan, destination: RandomAccessStore,
               expectedInstance: Bytes, keys: Map<Bytes,ZoneKey>, check: ()->Unit): Map<Bytes,VerificationCoverage> {
        check(); val reader = ContainerReader(destination,accessCheck=check); val actual = reader.snapshot()
        val a = actual.header; val s = plan.source.header
        checkFormat(a.containerId == s.containerId && a.instanceId == expectedInstance && a.instanceId != s.instanceId &&
            a.generation == s.generation+1 && a.minor == s.minor && a.committedLength == destination.size && a.zones.size == s.zones.size)
        checkFormat(destination.readFullyAt(0,65536).contentEquals(destination.readFullyAt(65536,65536)))
        val ranges = TreeMap<Long,Long>()
        fun range(p: PhysicalRef) { checkFormat(ranges.put(p.offset,p.length) == null) }
        for((i,d) in a.zones.withIndex()) {
            check(); val original = s.zones[i]
            checkFormat(DescriptorCodec.logicalBytes(d).contentEquals(DescriptorCodec.logicalBytes(original)) && d.logicalMac == original.logicalMac)
            val records = actual.catalogues[i]
            checkFormat(records.keys == plan.retained[i])
            for((id,p) in records) {
                check(); val old = plan.source.catalogues[i].getValue(id)
                checkFormat(p.hash == old.hash && p.length == old.length)
                // Hashes alone are relative coverage; compare actual bytes to immutable S as well.
                var pos = 0L
                while(pos < p.length) {
                    check(); val n = minOf(65536L,p.length-pos).toInt()
                    val x = source.readFullyAt(old.offset+pos,n); val y = destination.readFullyAt(p.offset+pos,n)
                    try { checkFormat(x.contentEquals(y)) } finally { x.fill(0); y.fill(0) }; pos += n
                }
                range(p)
            }
            fun pages(ref: PhysicalRef) {
                check(); range(ref)
                val page = CatalogueCodec.decode(destination.readFullyAt(ref.offset,Limits.PAGE),a.committedLength)
                if(page.level > 0) page.entries.forEach { pages(it.child!!) }
            }
            pages(d.catalogue)
            keys[d.zoneId]?.let { checkFormat(reader.reachable(actual,it) == plan.retained[i]) }
        }
        var end = 131072L
        for((offset,length) in ranges) {
            check(); checkFormat(offset == aligned(end))
            if(offset > end) checkFormat(destination.readFullyAt(end,(offset-end).toInt()).all { it == 0.toByte() })
            end = offset+length
        }
        checkFormat(end == a.committedLength)
        return SnapshotCopier().verify(destination,actual,keys,check)
    }
}
