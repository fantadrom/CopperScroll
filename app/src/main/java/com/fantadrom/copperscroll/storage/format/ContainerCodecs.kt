package com.fantadrom.copperscroll.storage.format

import java.nio.ByteBuffer
import java.nio.ByteOrder

class PhysicalRef(val offset: Long, val length: Long, val hash: Bytes) {
    internal fun wire(): List<Any?> = listOf(offset, length, hash)
    fun validate(committedLength: Long, expectedLength: Long? = null) {
        checkFormat(committedLength in 131072..Limits.FILE_MAX)
        checkFormat(length <= 0xffffffffL && (expectedLength == null || length == expectedLength))
        checkedEnd(offset, length, committedLength); hash.sized(32)
    }
}
class LogicalRef(val recordId: Bytes, val hash: Bytes, val kind: Int, val ownerId: Bytes, val ordinal: Long) {
    internal fun wire(): List<Any?> = listOf(recordId, hash, kind, ownerId, ordinal)
    fun validate(kind: Int? = null, owner: Bytes? = null, ordinal: Long? = null) {
        recordId.id(32); hash.sized(32); ownerId.id(16)
        checkFormat(this.kind in 1..5, Failure.UnsupportedVersion)
        checkFormat(this.ordinal in 0..0xffffffffL)
        checkFormat((kind == null || this.kind == kind) && (owner == null || ownerId == owner) && (ordinal == null || this.ordinal == ordinal))
    }
}
object ReferenceCodec {
    internal fun physical(v: Any?, committed: Long): PhysicalRef {
        val a = v.arr(3)
        return PhysicalRef(a[0].number(Limits.FILE_MAX), a[1].number(0xffffffffL), a[2].bin(32)).also { it.validate(committed) }
    }
    internal fun logical(v: Any?): LogicalRef {
        val a = v.arr(5)
        return LogicalRef(a[0].ident(32), a[1].bin(32), a[2].number(255).toInt(), a[3].ident(), a[4].number(0xffffffffL)).also { it.validate() }
    }
    fun decodePhysical(bytes: ByteArray, committed: Long) = physical(StrictCbor.decode(bytes), committed)
    fun encodePhysical(ref: PhysicalRef, committed: Long): ByteArray { ref.validate(committed); return StrictCbor.encode(ref.wire()) }
    fun decodeLogical(bytes: ByteArray) = logical(StrictCbor.decode(bytes))
    fun encodeLogical(ref: LogicalRef): ByteArray { ref.validate(); return StrictCbor.encode(ref.wire()) }
}
class Descriptor(
    val zoneId: Bytes, val role: Int, val sequence: Long, val inputMethod: Int,
    val memoryKiB: Int, val iterations: Int, val salt: Bytes, val wrapNonce: Bytes,
    val wrappedKey: Bytes, val hint: String, val root: LogicalRef, val logicalMac: Bytes,
    val catalogue: PhysicalRef
) {
    internal fun logicalFields(): List<Any?> = listOf(zoneId, role, sequence, 1, inputMethod, 1, 1, 19,
        memoryKiB, iterations, 1, salt, wrapNonce, wrappedKey, hint, root.wire())
    internal fun wire() = logicalFields() + listOf(logicalMac, catalogue.wire())
}
object DescriptorCodec {
    fun decode(bytes: ByteArray, committed: Long): Descriptor = from(StrictCbor.decode(bytes, 4092), committed)
    private fun from(v: Any?, committed: Long): Descriptor {
        val a = v.arr(18)
        a[3].version(); a[5].version(); a[6].version()
        checkFormat(a[7] == 19uL, Failure.UnsupportedVersion)
        checkFormat(a[10] == 1uL, Failure.UnsupportedLimit)
        val zone = a[0].ident(); val role = a[1].number(2).toInt(); checkFormat(role in 1..2)
        val seq = a[2].number(); checkFormat(seq > 0)
        val method = a[4].number(255).toInt(); checkFormat(method in 1..4, Failure.UnsupportedVersion)
        val memory = a[8].number(65536).toInt(); val iterations = a[9].number(4).toInt()
        checkFormat(memory in listOf(16384, 32768, 65536) && iterations in 2..4, Failure.UnsupportedLimit)
        val root = ReferenceCodec.logical(a[15]); root.validate(1, zone, 0)
        val physical = ReferenceCodec.physical(a[17], committed); physical.validate(committed, Limits.PAGE.toLong())
        return Descriptor(zone, role, seq, method, memory, iterations, a[11].bin(16), a[12].bin(12),
            a[13].bin(48), a[14].text(512), root, a[16].bin(32), physical)
    }
    fun encode(value: Descriptor, committed: Long): ByteArray {
        val b = StrictCbor.encode(value.wire(), 4092); decode(b, committed); return b
    }
    /** Exact bytes to be authenticated by P2. This method does NOT authenticate them. */
    fun logicalBytes(value: Descriptor): ByteArray = StrictCbor.encode(value.logicalFields(), 4092)
}
class Superblock(val generation: Long, val committedLength: Long, val containerId: Bytes,
                 val instanceId: Bytes, val zones: List<Descriptor>)
class HeaderSelection(val header: Superblock, val selectedSlot: Int, val checksumValidSlots: List<Boolean>)
class SuperblockFrame(val generation: Long, val committedLength: Long, val containerId: Bytes,
                      val instanceId: Bytes, val zoneCount: Int, val bytes: Bytes)
internal fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
internal fun zero(bytes: ByteArray, from: Int, until: Int) { checkFormat((from until until).all { bytes[it] == 0.toByte() }) }
internal fun magic(bytes: ByteArray, text: String) { checkFormat(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(text.toByteArray(Charsets.US_ASCII))) }
object SuperblockCodec {
    private fun hashValid(b: ByteArray): Boolean = b.size == Limits.HEADER && sha(b.copyOfRange(0, 65504)) == Bytes(b.copyOfRange(65504, 65536))
    private fun framing(b: ByteArray, fileSize: Long): ByteBuffer {
        checkFormat(b.size == Limits.HEADER, Failure.CorruptHeader)
        magic(b, "CSCRL001")
        val x = le(b)
        checkFormat(x.getShort(8).toInt() == 1 && x.getShort(10).toInt() == 1 && x.getInt(12) == 0, Failure.UnsupportedVersion)
        checkFormat(x.getLong(16) > 0, Failure.CorruptHeader)
        checkFormat(fileSize in 131072..Limits.FILE_MAX, Failure.UnsupportedLimit)
        checkFormat(x.getLong(24) in 131072..fileSize, Failure.CorruptHeader)
        return x
    }
    /** Common framing for explicit recovery; deliberately does not parse the other descriptor. */
    fun recoveryFrame(bytes: ByteArray, fileSize: Long): SuperblockFrame {
        checkFormat(hashValid(bytes), Failure.CorruptHeader)
        val x = framing(bytes, fileSize)
        val count = bytes[64].toInt(); checkFormat(count in 1..2)
        zero(bytes,65,72); zero(bytes,8264,65504)
        if(count == 1) zero(bytes,4168,8264)
        return SuperblockFrame(x.getLong(16), x.getLong(24), Bytes(bytes.copyOfRange(32,48)).id(16),
            Bytes(bytes.copyOfRange(48,64)).id(16), count, Bytes(bytes))
    }
    fun recoveryDescriptor(frame: SuperblockFrame, role: Int): Descriptor {
        checkFormat(role in 1..frame.zoneCount)
        val b = frame.bytes.copy(); val start = 72 + (role-1)*4096
        val n = le(b).getInt(start).toLong() and 0xffffffffL
        checkFormat(n in 1..4092); zero(b,start+4+n.toInt(),start+4096)
        return DescriptorCodec.decode(b.copyOfRange(start+4,start+4+n.toInt()),frame.committedLength)
            .also { checkFormat(it.role == role) }
    }
    fun decode(bytes: ByteArray, fileSize: Long): Superblock {
        checkFormat(hashValid(bytes), Failure.CorruptHeader)
        val x = framing(bytes, fileSize)
        val count = bytes[64].toInt(); checkFormat(count in 1..2)
        zero(bytes, 65, 72); zero(bytes, 8264, 65504)
        val length = x.getLong(24)
        val zones = (0 until count).map { i ->
            val start = 72 + i * 4096
            val n = x.getInt(start).toLong() and 0xffffffffL
            checkFormat(n in 1..4092); val end = start + 4 + n.toInt()
            zero(bytes, end, start + 4096)
            DescriptorCodec.decode(bytes.copyOfRange(start + 4, end), length).also { checkFormat(it.role == i + 1) }
        }
        if (count == 1) zero(bytes, 4168, 8264)
        checkFormat(zones.map { it.zoneId }.distinct().size == count)
        return Superblock(x.getLong(16), length, Bytes(bytes.copyOfRange(32, 48)).id(16), Bytes(bytes.copyOfRange(48, 64)).id(16), zones)
    }
    fun encode(value: Superblock): ByteArray {
        checkFormat(value.zones.size in 1..2)
        val b = ByteArray(Limits.HEADER); val x = le(b)
        x.put("CSCRL001".toByteArray(Charsets.US_ASCII)); x.putShort(1); x.putShort(1); x.putInt(0)
        x.putLong(value.generation); x.putLong(value.committedLength)
        x.put(value.containerId.sized(16).copy()); x.put(value.instanceId.sized(16).copy()); x.put(value.zones.size.toByte())
        value.zones.forEachIndexed { i, zone ->
            val payload = DescriptorCodec.encode(zone, value.committedLength)
            x.position(72 + i * 4096); x.putInt(payload.size); x.put(payload)
        }
        x.position(65504); x.put(sha(b.copyOfRange(0,65504)).copy())
        decode(b, value.committedLength); return b
    }
    /** Select by checksum/generation first. A bad selected descriptor/root is never an older-slot fallback. */
    fun select(a: ByteArray, b: ByteArray, fileSize: Long): HeaderSelection {
        checkFormat(a.size == Limits.HEADER && b.size == Limits.HEADER, Failure.CorruptHeader)
        val slots = listOf(a,b); val good = slots.map { hashValid(it) }
        checkFormat(good.any { it }, Failure.CorruptHeader)
        val valid = slots.indices.filter { good[it] }
        valid.forEach { framing(slots[it], fileSize) } // Unknown checksum-valid framing blocks fallback.
        if (valid.size == 2) {
            checkFormat(a.copyOfRange(32,64).contentEquals(b.copyOfRange(32,64)), Failure.CorruptHeader)
            if (le(a).getLong(16) == le(b).getLong(16)) checkFormat(a.contentEquals(b), Failure.CorruptHeader)
        }
        val chosen = valid.maxBy { le(slots[it]).getLong(16) }
        return HeaderSelection(decode(slots[chosen], fileSize), chosen, good)
    }
}
class RecordHeader(val kind: Int, val zoneId: Bytes, val recordId: Bytes, val nonce: Bytes, val plaintextLength: Int)
class OpaqueRecord(val header: RecordHeader, val ciphertextAndTag: Bytes)
object RecordCodec {
    fun decodeHeader(b: ByteArray): RecordHeader {
        checkFormat(b.size == 76); magic(b, "CSREC001"); val x = le(b)
        checkFormat(x.getShort(8).toInt() == 1, Failure.UnsupportedVersion)
        val kind = b[10].toInt() and 255; checkFormat(kind in 1..5, Failure.UnsupportedVersion)
        checkFormat(b[11] == 0.toByte())
        val n = x.getInt(72).toLong() and 0xffffffffL
        checkFormat(n <= if (kind <= 3) Limits.PAGE else Limits.CHUNK, Failure.UnsupportedLimit)
        if (kind >= 4) checkFormat(n > 0)
        return RecordHeader(kind, Bytes(b.copyOfRange(12,28)).id(16), Bytes(b.copyOfRange(28,60)).id(32), Bytes(b.copyOfRange(60,72)), n.toInt())
    }
    fun encodeHeader(h: RecordHeader): ByteArray {
        checkFormat(h.kind in 1..5, Failure.UnsupportedVersion)
        checkFormat(h.plaintextLength in 0..(if(h.kind <= 3) Limits.PAGE else Limits.CHUNK), Failure.UnsupportedLimit)
        val b = ByteArray(76); val x = le(b)
        x.put("CSREC001".toByteArray(Charsets.US_ASCII)); x.putShort(1); x.put(h.kind.toByte()); x.put(0)
        x.put(h.zoneId.sized(16).copy()); x.put(h.recordId.sized(32).copy()); x.put(h.nonce.sized(12).copy()); x.putInt(h.plaintextLength)
        decodeHeader(b); return b
    }
    fun decode(b: ByteArray): OpaqueRecord {
        checkFormat(b.size in 92..(Limits.CHUNK + 92))
        val h = decodeHeader(b.copyOfRange(0,76)); checkFormat(b.size == h.plaintextLength + 92)
        return OpaqueRecord(h, Bytes(b.copyOfRange(76,b.size)))
    }
    fun encode(r: OpaqueRecord): ByteArray {
        val h = encodeHeader(r.header); checkFormat(r.ciphertextAndTag.size == r.header.plaintextLength + 16)
        return h + r.ciphertextAndTag.copy()
    }
    fun match(r: OpaqueRecord, zone: Bytes, ref: LogicalRef) {
        ref.validate(); checkFormat(r.header.zoneId == zone && r.header.recordId == ref.recordId && r.header.kind == ref.kind)
        checkFormat(sha(encode(r)) == ref.hash)
    }
}
class CatalogueEntry(val key: Bytes, val record: PhysicalRef? = null, val child: PhysicalRef? = null)
class CataloguePage(val zoneId: Bytes, val level: Int, val entries: List<CatalogueEntry>)
object CatalogueCodec {
    fun decode(b: ByteArray, committed: Long): CataloguePage {
        checkFormat(b.size == Limits.PAGE); magic(b,"CSCAT001"); zero(b,9,12)
        val level = b[8].toInt() and 255; checkFormat(level in 0..7, Failure.UnsupportedLimit)
        val n = le(b).getInt(12).toLong() and 0xffffffffL; checkFormat(n in 1..(Limits.PAGE - 16).toLong())
        zero(b,16+n.toInt(),b.size)
        val a = StrictCbor.decode(b.copyOfRange(16,16+n.toInt())).arr(2)
        val entries = a[1].arr(); checkFormat(entries.size <= 128, Failure.UnsupportedLimit)
        checkFormat(level == 0 || entries.isNotEmpty())
        val result = entries.map {
            val e = it.arr(if(level == 0) 4 else 2); val key = e[0].ident(32)
            if (level == 0) CatalogueEntry(key, ReferenceCodec.physical(listOf(e[1],e[2],e[3]),committed))
            else CatalogueEntry(key, child = ReferenceCodec.physical(e[1],committed).also { it.validate(committed, Limits.PAGE.toLong()) })
        }
        ordered(result.map { it.key }); return CataloguePage(a[0].ident(),level,result)
    }
    fun encode(p: CataloguePage, committed: Long): ByteArray {
        checkFormat(p.level in 0..7 && p.entries.size <= 128, Failure.UnsupportedLimit)
        val entries = p.entries.map {
            if(p.level == 0) { checkFormat(it.record != null && it.child == null); listOf(it.key,it.record!!.offset,it.record.length,it.record.hash) }
            else { checkFormat(it.child != null && it.record == null); listOf(it.key,it.child!!.wire()) }
        }
        val payload = StrictCbor.encode(listOf(p.zoneId,entries),Limits.PAGE-16)
        val b = ByteArray(Limits.PAGE); val x = le(b)
        x.put("CSCAT001".toByteArray(Charsets.US_ASCII)); x.put(p.level.toByte()); x.position(12); x.putInt(payload.size); x.put(payload)
        decode(b,committed); return b
    }
}
