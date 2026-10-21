package com.fantadrom.copperscroll.storage.format

import java.util.TreeMap

/** Positional read contract only. No Android, URI, file creation or mutation in the core. */
interface ReadOnlySource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}
class InspectionLimits(val maxMapBytes: Long = 32L * 1024 * 1024, val maxRecords: Long = 16777216,
                       val maxReadBytes: Long = 1024L * 1024 * 1024, val maxMillis: Long = 30000) {
    init { require(maxMapBytes in 1024..(32L*1024*1024) && maxRecords in 1..16777216 && maxReadBytes > 0 && maxMillis > 0) }
}
class StructuralSummary(val generation: Long, val committedLength: Long, val fileLength: Long,
                        val zones: Int, val selectedSlot: Int, val checksumValidSlots: List<Boolean>,
                        val cataloguePages: Long, val records: Long, val bytesRead: Long, val minor: Int = 1) {
    override fun toString(): String = "container=1.$minor generation=$generation committedLength=$committedLength fileLength=$fileLength " +
        "zones=$zones slot=$selectedSlot headerChecksums=$checksumValidSlots cataloguePages=$cataloguePages records=$records bytesRead=$bytesRead " +
        "cryptographicVerification=NONE durabilityVerification=NONE"
}
/** Complete public catalogue framing/hash walk. Public SHA256 is never authentication or live-set proof. */
object StructuralInspector {
    @Throws(FormatException::class)
    fun inspect(source: ReadOnlySource, limits: InspectionLimits = InspectionLimits(), cancelled: () -> Boolean = { false }): StructuralSummary {
        checkFormat(source.size in 131072..Limits.FILE_MAX,Failure.UnsupportedLimit)
        val start = System.nanoTime(); var readBytes = 0L; var mapBytes = 0L
        fun tick() { checkFormat(!cancelled() && (System.nanoTime()-start)/1000000 <= limits.maxMillis,Failure.ResourceLimit) }
        fun read(offset: Long, n: Int): ByteArray {
            tick(); checkFormat(offset >= 0 && n >= 0 && offset <= source.size && n.toLong() <= source.size-offset)
            checkFormat(n.toLong() <= limits.maxReadBytes-readBytes,Failure.ResourceLimit); readBytes += n
            val b = source.read(offset,n); checkFormat(b.size == n); return b
        }
        val selection = SuperblockCodec.select(read(0,65536),read(65536,65536),source.size)
        val header = selection.header
        val ranges = TreeMap<Long,Long>()
        fun range(ref: PhysicalRef) {
            ref.validate(header.committedLength)
            val end = checkedEnd(ref.offset,ref.length,header.committedLength)
            checkFormat(ranges.floorEntry(ref.offset)?.value?.let { it <= ref.offset } ?: true)
            checkFormat(ranges.ceilingKey(ref.offset)?.let { it >= end } ?: true)
            checkFormat(mapBytes + 256 <= limits.maxMapBytes,Failure.ResourceLimit)
            ranges[ref.offset] = end; mapBytes += 256
        }
        var pages = 0L; var records = 0L
        for (zone in header.zones) {
            var foundRoot = false; var previous: Bytes? = null; var zoneRecords = 0L
            fun walk(ref: PhysicalRef, expectedLevel: Int?, root: Boolean): Pair<Bytes,Bytes> {
                tick(); ref.validate(header.committedLength,Limits.PAGE.toLong()); range(ref)
                val b = read(ref.offset,Limits.PAGE); checkFormat(sha(b) == ref.hash)
                val page = CatalogueCodec.decode(b,header.committedLength); pages++
                checkFormat(page.zoneId == zone.zoneId && (expectedLevel == null || page.level == expectedLevel))
                checkFormat(page.entries.isNotEmpty()) // A usable zone necessarily contains its root record.
                var first: Bytes? = null; var last: Bytes? = null
                for(e in page.entries) {
                    if(page.level == 0) {
                        checkFormat(previous?.let { it < e.key } ?: true)
                        previous = e.key
                        checkFormat(++zoneRecords <= limits.maxRecords,Failure.ResourceLimit); records++
                        val physical = e.record!!
                        checkFormat(physical.length in 92..(Limits.CHUNK + 92).toLong())
                        range(physical)
                        // Validate declared plaintext length before reading the opaque payload.
                        val rh = RecordCodec.decodeHeader(read(physical.offset,76))
                        checkFormat(rh.recordId == e.key && rh.zoneId == zone.zoneId && physical.length == rh.plaintextLength + 92L)
                        val raw = read(physical.offset,physical.length.toInt())
                        checkFormat(sha(raw) == physical.hash)
                        if(e.key == zone.root.recordId) {
                            checkFormat(physical.hash == zone.root.hash && rh.kind == zone.root.kind)
                            foundRoot = true
                        }
                        if(first == null) first = e.key; last = e.key
                    } else {
                        val (lo,hi) = walk(e.child!!,page.level-1,false)
                        checkFormat(hi == e.key && (last == null || last < lo))
                        if(first == null) first = lo; last = hi
                    }
                }
                checkFormat(root || first != null)
                return first!! to last!!
            }
            walk(zone.catalogue,null,true); checkFormat(foundRoot)
        }
        return StructuralSummary(header.generation,header.committedLength,source.size,header.zones.size,
            selection.selectedSlot,selection.checksumValidSlots,pages,records,readBytes,header.minor)
    }
}
