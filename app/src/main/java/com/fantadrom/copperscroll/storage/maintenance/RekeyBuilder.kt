package com.fantadrom.copperscroll.storage.maintenance

import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import java.util.TreeMap

/** Schema-directed edges only; opaque body/media bytes never undergo CBOR reference scanning.
 * Public preflight authenticates and validates the entire graph before this transformer is used.
 * Non-reference fields (including unknown display enums/icons) retain their canonical raw values.
 */
internal object RekeyEdges {
    fun transform(ref: LogicalRef, plain: ByteArray, rootSequence: Long? = null,
                  edge: (LogicalRef)->LogicalRef): ByteArray {
        if(ref.kind >= 4) return plain.clone()
        val a = StrictCbor.decode(plain).arr().toMutableList()
        fun replace(value: Any?) = edge(ReferenceCodec.logical(value)).wire()
        when(ref.kind) {
            1 -> { checkFormat(a.size == 10); if(rootSequence != null) a[2] = rootSequence
                for(i in 5..7) a[i] = replace(a[i]) }
            2 -> { checkFormat(a.size == 3); a[2] = a[2].arr().map { v ->
                val e = v.arr(2); listOf(e[0],e[1]?.let { replace(it) }) } }
            3 -> when(a.size) {
                8 -> Unit // settings, no edges
                2 -> a[1] = a[1].arr().map { v -> val e = v.arr(2); listOf(e[0],replace(e[1])) } // chunk-list
                14 -> if(a[13] != null) {
                    val p = a[13].arr().toMutableList()
                    when(a[2].number().toInt()) {
                        2,6 -> p[2] = p[2].arr().map { replace(it) }
                        3,4,5 -> p[4] = replace(p[4])
                        else -> throw FormatException(Failure.UnsupportedVersion)
                    }
                    a[13] = p
                }
                else -> throw FormatException(Failure.UnsupportedVersion)
            }
            else -> throw FormatException(Failure.UnsupportedVersion)
        }
        return StrictCbor.encode(a)
    }
}

/** No plaintext disk scratch. O(record count) bounded public maps, one <=1MiB leaf at a time;
 * recursion holds <=64KiB metadata per level (validated index/chunk-list heights <=7 each).
 */
internal class RekeyBuilder(private val crypto: RecordCrypto = RecordCrypto()) {
    fun validate(plan: CompactionPlan, target: Bytes) {
        val d = plan.source.header.zones.single { it.zoneId == target }
        checkFormat(d.sequence < Long.MAX_VALUE,Failure.UnsupportedLimit)
    }
    fun build(source: RandomAccessStore, plan: CompactionPlan, destination: RandomAccessStore,
              old: ZoneKey, prepared: PreparedZone, hint: String, check: ()->Unit): Bytes {
        validate(plan,old.zoneId); check()
        requireIo(destination.size == 0L && source.identity != destination.identity)
        val s = plan.source; val reader = ContainerReader(source,accessCheck=check)
        val reserved = s.catalogues.flatMap { it.keys }.toHashSet()
        val reservations = RecordIdReservation { reserved.add(it) }
        destination.writeFullyAt(0,ByteArray(131072))
        val zones = s.header.zones.mapIndexed { i,d ->
            val moved = ArrayList<Pair<Bytes,PhysicalRef>>()
            fun append(id: Bytes, raw: ByteArray, hash: Bytes) {
                check(); val start = aligned(destination.size)
                if(start > destination.size) destination.writeFullyAt(destination.size,ByteArray((start-destination.size).toInt()))
                var pos = 0
                while(pos < raw.size) { check(); val n = minOf(65536,raw.size-pos)
                    val block = raw.copyOfRange(pos,pos+n)
                    try { destination.writeFullyAt(start+pos,block) } finally { block.fill(0) }; pos += n }
                moved.add(id to PhysicalRef(start,raw.size.toLong(),hash))
            }
            val root: LogicalRef
            if(d.zoneId == old.zoneId) {
                val visited = HashSet<Bytes>()
                fun rewrite(ref: LogicalRef): LogicalRef {
                    check(); checkFormat(visited.add(ref.recordId) && ref.recordId in plan.retained[i])
                    val plain = reader.readRecord(s,old,ref)
                    val changed = try { RekeyEdges.transform(ref,plain,if(ref.kind == 1) d.sequence+1 else null,::rewrite) }
                        finally { plain.fill(0) }
                    try {
                        val record = crypto.encrypt(prepared.key,ref.kind,ref.ownerId,ref.ordinal,changed,reservations)
                        val raw = record.raw.copy()
                        try { append(record.reference.recordId,raw,record.reference.hash) } finally { raw.fill(0) }
                        return record.reference
                    } finally { changed.fill(0) }
                }
                root = rewrite(d.root); checkFormat(visited == plan.retained[i])
            } else {
                root = d.root
                // Opaque preservation includes every obsolete record. No unselected key is passed.
                for((id,p) in s.catalogues[i].toSortedMap()) {
                    check(); val start = aligned(destination.size)
                    if(start > destination.size) destination.writeFullyAt(destination.size,ByteArray((start-destination.size).toInt()))
                    var pos = 0L
                    while(pos < p.length) { check(); val b = source.readFullyAt(p.offset+pos,minOf(65536L,p.length-pos).toInt())
                        try { destination.writeFullyAt(start+pos,b) } finally { b.fill(0) }; pos += b.size }
                    moved.add(id to PhysicalRef(start,p.length,p.hash))
                }
            }
            check(); val catalogue = CowCatalogue(destination,d.zoneId).build(moved)
            if(d.zoneId != old.zoneId) Descriptor(d.zoneId,d.role,d.sequence,d.inputMethod,d.memoryKiB,d.iterations,d.salt,
                d.wrapNonce,d.wrappedKey,d.hint,root,d.logicalMac,catalogue)
            else {
                val e = prepared.envelope
                checkFormat(e.containerId == s.header.containerId && e.zoneId == d.zoneId && e.role == d.role)
                val pending = Descriptor(d.zoneId,d.role,d.sequence+1,e.inputMethod,e.parameters.memoryKiB,e.parameters.iterations,
                    e.salt,e.nonce,e.wrappedKey,hint,root,Bytes(ByteArray(32)),catalogue)
                Descriptor(d.zoneId,d.role,pending.sequence,pending.inputMethod,pending.memoryKiB,pending.iterations,pending.salt,
                    pending.wrapNonce,pending.wrappedKey,pending.hint,root,crypto.descriptorMac(prepared.key,pending),catalogue)
            }
        }
        val h = s.header; val instance = randomId(); checkFormat(instance != h.instanceId)
        val bytes = SuperblockCodec.encode(Superblock(h.generation+1,destination.size,h.containerId,instance,zones,h.minor))
        check(); destination.force(); check(); destination.writeFullyAt(0,bytes); check(); destination.writeFullyAt(65536,bytes)
        return instance
    }

    /** Independent paired traversal derives correspondence from authenticated on-disk edges, never
     * a builder ID map. Positive new-key verification precedes old-key AEAD rejection with valid AAD.
     */
    fun verify(source: RandomAccessStore, plan: CompactionPlan, destination: RandomAccessStore, instance: Bytes,
               old: ZoneKey, new: ZoneKey, expectedEnvelope: Envelope, hint: String, check: ()->Unit): Map<Bytes,VerificationCoverage> {
        validate(plan,old.zoneId); check(); checkFormat(!old.matches(new))
        val reader = ContainerReader(destination,accessCheck=check); val actual = reader.snapshot()
        val s = plan.source; val h = actual.header
        checkFormat(h.containerId == s.header.containerId && h.instanceId == instance && instance != s.header.instanceId &&
            h.generation == s.header.generation+1 && h.minor == s.header.minor && h.zones.size == s.header.zones.size && h.committedLength == destination.size)
        checkFormat(destination.readFullyAt(0,65536).contentEquals(destination.readFullyAt(65536,65536)))
        val sourceIds = s.catalogues.flatMap { it.keys }.toHashSet()
        val sourceReader = ContainerReader(source,accessCheck=check)
        for((i,d) in h.zones.withIndex()) {
            check(); val previous = s.header.zones[i]; checkFormat(d.zoneId == previous.zoneId && d.role == previous.role)
            if(d.zoneId != old.zoneId) {
                checkFormat(DescriptorCodec.logicalBytes(d).contentEquals(DescriptorCodec.logicalBytes(previous)) && d.logicalMac == previous.logicalMac)
                checkFormat(actual.catalogues[i].keys == s.catalogues[i].keys)
                for((id,p) in actual.catalogues[i]) {
                    val before = s.catalogues[i].getValue(id); checkFormat(p.length == before.length && p.hash == before.hash)
                    var pos = 0L
                    while(pos < p.length) { check(); val n = minOf(65536L,p.length-pos).toInt()
                        val x = source.readFullyAt(before.offset+pos,n); val y = destination.readFullyAt(p.offset+pos,n)
                        try { checkFormat(x.contentEquals(y)) } finally { x.fill(0); y.fill(0) }; pos += n }
                }
            } else {
                checkFormat(d.sequence == previous.sequence+1 && d.hint == hint && d.inputMethod == expectedEnvelope.inputMethod &&
                    d.memoryKiB == expectedEnvelope.parameters.memoryKiB && d.iterations == expectedEnvelope.parameters.iterations &&
                    d.salt == expectedEnvelope.salt && d.wrapNonce == expectedEnvelope.nonce && d.wrappedKey == expectedEnvelope.wrappedKey &&
                    d.wrappedKey != previous.wrappedKey)
                val closure = reader.reachable(actual,new)
                checkFormat(closure == actual.catalogues[i].keys && closure.none { it in sourceIds })
                val oldSeen = HashSet<Bytes>(); val newSeen = HashSet<Bytes>()
                fun compare(a: LogicalRef,b: LogicalRef) {
                    check(); checkFormat(a.kind == b.kind && a.ownerId == b.ownerId && a.ordinal == b.ordinal &&
                        oldSeen.add(a.recordId) && newSeen.add(b.recordId))
                    val x = sourceReader.readRecord(s,old,a); val y = reader.readRecord(actual,new,b)
                    try {
                        val p = actual.catalogues[i].getValue(b.recordId)
                        // Positive reader above proved framing/hash/owner/AAD. Only ZDK differs here.
                        var rejected = false
                        try { crypto.decrypt(old,destination.readFullyAt(p.offset,p.length.toInt()),b).use { } }
                        catch(e: CryptoException) { if(e.failure != CryptoFailure.CorruptData) throw e; rejected = true }
                        checkFormat(rejected)
                        val edges = ArrayList<LogicalRef>()
                        val expected = RekeyEdges.transform(a,x,if(a.kind == 1) previous.sequence+1 else null) { edges.add(it); it }
                        var edgeIndex = 0
                        val normalized = try { RekeyEdges.transform(b,y) { edge ->
                            checkFormat(edgeIndex < edges.size); val before = edges[edgeIndex++]; compare(before,edge); before
                        } } catch(e: Throwable) { expected.fill(0); throw e }
                        try { checkFormat(edgeIndex == edges.size && expected.contentEquals(normalized)) }
                        finally { expected.fill(0); normalized.fill(0) }
                    } finally { x.fill(0); y.fill(0) }
                }
                compare(previous.root,d.root)
                checkFormat(oldSeen == plan.retained[i] && newSeen == closure)
            }
        }
        verifyPhysicalPartition(destination,actual,check)
        return SnapshotCopier().verify(destination,actual,mapOf(new.zoneId to new),check)
    }
}

/** Full on-disk partition, shared with future maintenance implementations, without builder ranges. */
internal fun verifyPhysicalPartition(store: RandomAccessStore,s: ReadSnapshot,check: ()->Unit) {
    val ranges = TreeMap<Long,Long>()
    fun range(p: PhysicalRef) { checkFormat(ranges.put(p.offset,p.length) == null) }
    for((i,d) in s.header.zones.withIndex()) {
        s.catalogues[i].values.forEach { check(); range(it) }
        fun pages(p: PhysicalRef) { check(); range(p)
            val page = CatalogueCodec.decode(store.readFullyAt(p.offset,Limits.PAGE),s.header.committedLength)
            if(page.level > 0) page.entries.forEach { pages(it.child!!) } }
        pages(d.catalogue)
    }
    var end = 131072L
    for((offset,length) in ranges) { check(); checkFormat(offset == aligned(end))
        if(offset > end) checkFormat(store.readFullyAt(end,(offset-end).toInt()).all { it == 0.toByte() }); end = offset+length }
    checkFormat(end == s.header.committedLength)
}
