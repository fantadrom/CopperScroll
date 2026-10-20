package com.fantadrom.copperscroll.storage.format

import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.access.CanonicalSecret
import com.fantadrom.copperscroll.storage.access.CredentialManager
import com.fantadrom.copperscroll.storage.io.RandomAccessStore
import java.util.TreeMap

class ReadSnapshot internal constructor(val header: Superblock, val slot: Int, val headerBytes: Bytes,
                                       val observedLength: Long, val catalogues: List<Map<Bytes,PhysicalRef>>,
                                       val recovery: Boolean = false)
class ZoneView internal constructor(val snapshot: ReadSnapshot, val descriptor: Descriptor,
                                   val root: RootData?, val settings: SettingsData?, val readOnly: Boolean,
                                   val unsupported: Failure? = null) {
    override fun toString() = "ZoneView(redacted,readOnly=$readOnly,unsupported=$unsupported)"
}
/** Owner of an authenticated read-only key handle; no writer capability is granted. */
class ReadSession internal constructor(val view: ZoneView, val key: ZoneKey) : AutoCloseable {
    override fun close() = key.close()
    override fun toString() = "ReadSession(redacted)"
}

/** Bounded reader for normative 1.1 graphs. Ordinary open authenticates metadata; content chunks
 * remain lazy. VerifyAll additionally reads every catalogue record/hash and all reachable AEAD.
 * No key, plaintext or session is published by mere public framing selection.
 */
class ContainerReader(private val store: RandomAccessStore, private val maxMapBytes: Long = 32L*1024*1024) {
    init { require(maxMapBytes in 1024..32L*1024*1024) }
    private val crypto = RecordCrypto()
    fun open(secret: CanonicalSecret): ReadSession {
        val s = snapshot()
        val match = CredentialManager().login(s.header.containerId,s.header.zones,secret)
        try { return ReadSession(openZone(s,match.key),match.key) }
        catch(e: Throwable) { match.close(); throw e }
    }
    fun recover(slot: Int, generation: Long, role: Int, secret: CanonicalSecret): ReadSession {
        val s = recoverSnapshot(slot,generation,role)
        val key = CredentialManager().unwrap(Envelope.fromDescriptor(s.header.containerId,s.header.zones.single()),secret)
        try { return ReadSession(openZone(s,key),key) } catch(e: Throwable) { key.close(); throw e }
    }
    fun snapshot(): ReadSnapshot {
        val size = store.size
        val a = store.readFullyAt(0,Limits.HEADER); val b = store.readFullyAt(65536,Limits.HEADER)
        val selected = SuperblockCodec.select(a,b,size)
        val bytes = if(selected.selectedSlot == 0) a else b
        return inspect(selected.header, selected.selectedSlot, bytes, size, false)
    }
    /** Explicit slot AND role. Foreign descriptors are not parsed. Other hash-valid common framing
     * must still be supported and identities/equal-generation rules must not conflict.
     */
    fun recoverSnapshot(slot: Int, generation: Long, role: Int): ReadSnapshot {
        checkFormat(slot in 0..1)
        val size = store.size; val slots = listOf(store.readFullyAt(0,65536),store.readFullyAt(65536,65536))
        val frames = slots.map { bytes ->
            val checksum = sha(bytes.copyOfRange(0,65504)) == Bytes(bytes.copyOfRange(65504,65536))
            if(checksum) SuperblockCodec.recoveryFrame(bytes,size) else null
        }
        val frame = frames[slot] ?: throw FormatException(Failure.CorruptHeader)
        checkFormat(frame.generation == generation,Failure.CorruptHeader)
        frames[1-slot]?.let {
            checkFormat(it.containerId == frame.containerId && it.instanceId == frame.instanceId,Failure.CorruptHeader)
            if(it.generation == generation) checkFormat(it.bytes == frame.bytes,Failure.CorruptHeader)
        }
        val descriptor = SuperblockCodec.recoveryDescriptor(frame,role)
        val h = Superblock(frame.generation,frame.committedLength,frame.containerId,frame.instanceId,listOf(descriptor))
        return inspect(h,slot,slots[slot],size,true)
    }
    private fun inspect(header: Superblock, slot: Int, bytes: ByteArray, size: Long, recovery: Boolean): ReadSnapshot {
        val ranges = TreeMap<Long,Long>(); var used = 0L
        fun reserve(ref: PhysicalRef) {
            ref.validate(header.committedLength)
            val end = checkedEnd(ref.offset,ref.length,header.committedLength)
            checkFormat(ranges.floorEntry(ref.offset)?.value?.let { it <= ref.offset } ?: true)
            checkFormat(ranges.ceilingKey(ref.offset)?.let { it >= end } ?: true)
            checkFormat(used + 320 <= maxMapBytes,Failure.ResourceLimit); used += 320
            ranges[ref.offset] = end
        }
        val catalogues = header.zones.map { descriptor ->
            val records = TreeMap<Bytes,PhysicalRef>(); var previous: Bytes? = null
            fun walk(ref: PhysicalRef, level: Int?): Pair<Bytes,Bytes> {
                reserve(ref); ref.validate(header.committedLength,Limits.PAGE.toLong())
                val raw = store.readFullyAt(ref.offset,Limits.PAGE); checkFormat(sha(raw) == ref.hash)
                val p = CatalogueCodec.decode(raw,header.committedLength)
                checkFormat(p.zoneId == descriptor.zoneId && (level == null || p.level == level) && p.entries.isNotEmpty())
                var first: Bytes? = null; var last: Bytes? = null
                for(e in p.entries) {
                    if(p.level == 0) {
                        checkFormat(previous?.let { it < e.key } ?: true); previous = e.key
                        val record = e.record!!; reserve(record)
                        checkFormat(record.length in 92..(Limits.CHUNK+92).toLong())
                        val rh = RecordCodec.decodeHeader(store.readFullyAt(record.offset,76))
                        checkFormat(rh.zoneId == descriptor.zoneId && rh.recordId == e.key && record.length == rh.plaintextLength+92L)
                        records[e.key] = record; if(first == null) first = e.key; last = e.key
                    } else {
                        val (lo,hi) = walk(e.child!!,p.level-1)
                        checkFormat(hi == e.key && (last == null || last < lo)); if(first == null) first = lo; last = hi
                    }
                }
                return first!! to last!!
            }
            walk(descriptor.catalogue,null)
            val root = records[descriptor.root.recordId] ?: throw FormatException(Failure.CorruptData)
            checkFormat(root.hash == descriptor.root.hash)
            val raw = store.readFullyAt(root.offset,root.length.toInt())
            RecordCodec.match(RecordCodec.decode(raw),descriptor.zoneId,descriptor.root)
            java.util.Collections.unmodifiableMap(records)
        }
        return ReadSnapshot(header,slot,Bytes(bytes),size,java.util.Collections.unmodifiableList(catalogues),recovery)
    }
    fun openZone(snapshot: ReadSnapshot, key: ZoneKey): ZoneView = zone(snapshot,key,false)
    fun verifyAll(snapshot: ReadSnapshot, key: ZoneKey): ZoneView = zone(snapshot,key,true)
    private fun zone(snapshot: ReadSnapshot, key: ZoneKey, full: Boolean): ZoneView {
        val i = snapshot.header.zones.indexOfFirst { it.zoneId == key.zoneId && it.role == key.role }
        checkFormat(i >= 0 && snapshot.header.containerId == key.containerId)
        val d = snapshot.header.zones[i]; crypto.verifyDescriptor(key,d)
        val catalogue = snapshot.catalogues[i]; val visited = HashSet<Bytes>(); var mapBytes = 0L
        fun budget(extra: Long) {
            checkFormat(extra >= 0 && mapBytes + visited.size*128L <= maxMapBytes-extra,Failure.ResourceLimit)
            mapBytes += extra
        }
        fun plain(ref: LogicalRef): ByteArray {
            checkFormat(visited.add(ref.recordId)) // cycles/repeated graph edges, not catalogue history
            checkFormat(mapBytes + visited.size*128L <= maxMapBytes,Failure.ResourceLimit)
            val physical = catalogue[ref.recordId] ?: throw FormatException(Failure.CorruptData)
            checkFormat(physical.hash == ref.hash)
            return crypto.decrypt(key,store.readFullyAt(physical.offset,physical.length.toInt()),ref).use { it.copyBytes() }
        }
        fun <T> decode(ref: LogicalRef, codec: (ByteArray) -> T): T {
            val b = plain(ref); try { return codec(b) } finally { b.fill(0) }
        }
        try {
            val root = decode(d.root) { RootCodec.decode(it,d.zoneId,d.sequence) }
            val settings = decode(root.settings,SettingsCodec::decode)
            val objects = TreeMap<Bytes,ObjectData>(); val children = HashSet<Pair<Bytes,Bytes>>()
            fun index(ref: LogicalRef, type: Int, expected: Int?): Pair<IndexKey?,IndexKey?> {
                val p = decode(ref) { IndexCodec.decode(it,d.zoneId) }
                checkFormat(p.type == type && (expected == null || p.level == expected))
                var first: IndexKey? = null; var last: IndexKey? = null
                for(e in p.entries) {
                    if(p.level == 0) {
                        checkFormat(last == null || last < e.key)
                        if(type == 1) {
                            val obj = decode(e.value!!) {
                                budget(it.size*4L+768); ObjectCodec.decode(it,e.key.first,root.nextPinSequence)
                            }
                            checkFormat(objects.put(obj.id,obj) == null && objects.size <= 1000000)
                        } else { budget(256); checkFormat(children.add(e.key.first to e.key.second!!)) }
                        if(first == null) first = e.key; last = e.key
                    } else {
                        val (lo,hi) = index(e.value!!,type,p.level-1)
                        checkFormat(lo != null && hi != null && hi.compareTo(e.key) == 0 && (last == null || last < lo))
                        if(first == null) first = lo; last = hi
                    }
                }
                return first to last
            }
            index(root.objects,1,null); index(root.children,2,null)
            checkFormat(objects.keys.none { it == root.normalRoot || it == root.trashRoot })
            checkFormat(children == objects.values.map { it.parent to it.id }.toSet())
            for(o in objects.values) {
                var parent = o.parent; var depth = if(o.type == 1) 1 else 0; val ancestors = HashSet<Bytes>()
                while(parent != root.normalRoot && parent != root.trashRoot) {
                    checkFormat(ancestors.add(parent) && ++depth <= 63)
                    val folder = objects[parent] ?: throw FormatException(Failure.CorruptData)
                    checkFormat(folder.type == 1); parent = folder.parent
                }
                if(full) when(val payload = o.payload) {
                    FolderPayload -> Unit
                    is NotePayload -> {
                        val chunks = ArrayList<ByteArray>()
                        try { payload.chunks.forEach { chunks.add(plain(it)) }; NoteCodec.decodeChunks(payload,o.id,chunks) }
                        finally { chunks.forEach { it.fill(0) } }
                    }
                    is MediaPayload -> {
                        var count = 0L; var total = 0L
                        fun chunks(ref: LogicalRef, expected: Int?): Long {
                            val p = decode(ref) { ChunkListCodec.decode(it,o.id) }
                            checkFormat(expected == null || p.level == expected)
                            var max = -1L
                            for(e in p.entries) {
                                if(p.level == 0) {
                                    checkFormat(e.ordinal == count++)
                                    val b = plain(e.ref)
                                    try { checkFormat(b.size in 1..Limits.CHUNK && (count == payload.chunkCount || b.size == Limits.CHUNK)); total += b.size }
                                    finally { b.fill(0) }
                                    max = e.ordinal
                                } else { max = chunks(e.ref,p.level-1); checkFormat(max == e.ordinal) }
                            }
                            return max
                        }
                        chunks(payload.chunks,null); checkFormat(count == payload.chunkCount && total == payload.byteLength)
                    }
                }
            }
            if(full) for(p in catalogue.values) checkFormat(sha(store.readFullyAt(p.offset,p.length.toInt())) == p.hash)
            return ZoneView(snapshot,d,root,settings,snapshot.recovery)
        } catch(e: FormatException) {
            if(!full && e.failure in listOf(Failure.UnsupportedVersion,Failure.UnsupportedLimit))
                return ZoneView(snapshot,d,null,null,true,e.failure)
            throw e
        }
    }
}
