package com.fantadrom.copperscroll.storage.vfs

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.ZoneWriteContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A length/digest-bound, single-use source. Caller supplies synthetic streams in P4.
 * Cancellation never closes a stream from another thread; the current IO owner does cleanup.
 */
class BinarySource(val length: Long, val digest: Bytes, openStream: () -> InputStream,
                   cancelled: () -> Boolean = { false }) : AutoCloseable {
    private var used = false
    @Volatile private var closed = false
    private var factory: (()->InputStream)? = openStream
    private var cancelCheck: (()->Boolean)? = cancelled
    init { checkFormat(length in 0..512L*1024*1024*1024,Failure.UnsupportedLimit); digest.sized(32) }
    @Synchronized internal fun open(): InputStream {
        requireIo(!used && !closed,IoCode.Closed); used = true
        val create = factory ?: throw StoreException(IoCode.Closed); factory = null
        return try { create() } catch(_: Exception) { throw StoreException(IoCode.Eio) }
    }
    internal fun check() { requireIo(!closed,IoCode.AccessLost); if(cancelCheck?.invoke() == true) throw StoreException(IoCode.AccessLost) }
    @Synchronized override fun close() { used = true; closed = true; factory = null; cancelCheck = null }
    override fun toString() = "BinarySource(redacted)"
}
internal object MediaStreams {
    fun validateEmpty(read: (LogicalRef)->ByteArray,objectId: Bytes,media: MediaPayload) {
        media.chunks.validate(3,objectId,0)
        val b = read(media.chunks)
        val page = try { ChunkListCodec.decode(b,objectId) } finally { b.fill(0) }
        checkFormat(page.level == 0 && page.entries.isEmpty() && media.byteLength == 0L && media.chunkCount == 0L)
    }
    fun write(context: ZoneWriteContext, objectId: Bytes, source: BinarySource, mime: String): MediaPayload {
        val levels = Array(8) { ArrayList<ChunkListEntry>(64) }
        fun page(level: Int, entries: List<ChunkListEntry>): ChunkListEntry {
            val ref = context.record(3,objectId,0,ChunkListCodec.encode(ChunkListPage(level,entries),objectId))
            return ChunkListEntry(entries.lastOrNull()?.ordinal ?: 0,ref)
        }
        fun add(level: Int, entry: ChunkListEntry) {
            checkFormat(level < levels.size,Failure.UnsupportedLimit)
            val entries = levels[level]; entries.add(entry)
            if(entries.size == 64) {
                val parent = page(level,entries); entries.clear(); add(level+1,parent)
            }
        }
        var ordinal = 0L; var remaining = source.length
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            source.open().use { input ->
                while(remaining > 0) {
                    context.check(); source.check()
                    val b = ByteArray(minOf(remaining,Limits.CHUNK.toLong()).toInt()); var position = 0
                    try {
                        while(position < b.size) {
                            context.check(); source.check()
                            val n = try { input.read(b,position,minOf(8192,b.size-position)) } catch(_: Exception) { throw StoreException(IoCode.Eio) }
                            requireIo(n > 0 && n <= b.size-position,IoCode.Eio); position += n
                        }
                        digest.update(b)
                        add(0,ChunkListEntry(ordinal,context.record(5,objectId,ordinal,b)))
                        ordinal++; remaining -= b.size
                    } finally { b.fill(0) }
                }
                context.check(); source.check()
                requireIo(input.read() == -1,IoCode.InvalidRange)
            }
            checkFormat(Bytes(digest.digest()) == source.digest,Failure.ValidationError)
            val root: LogicalRef
            if(ordinal == 0L) root = page(0,emptyList()).ref
            else {
                var result: LogicalRef? = null
                while(result == null) {
                    val nonempty = levels.indices.filter { levels[it].isNotEmpty() }
                    checkFormat(nonempty.isNotEmpty())
                    val low = nonempty.first(); val high = nonempty.last()
                    if(low == high && high > 0 && levels[high].size == 1) result = levels[high].single().ref
                    else if(low == high && high == 0) result = page(0,levels[0]).ref
                    else { val parent = page(low,levels[low]); levels[low].clear(); add(low+1,parent) }
                }
                root = result
            }
            return MediaPayload(mime,source.length,ordinal,root)
        } finally { source.close() }
    }
    fun chunk(read: (LogicalRef)->ByteArray, objectId: Bytes, media: MediaPayload, ordinal: Long): ByteArray {
        checkFormat(ordinal in 0 until media.chunkCount)
        var ref = media.chunks; var expected: Int? = null; val visited = HashSet<Bytes>()
        while(true) {
            checkFormat(visited.add(ref.recordId) && visited.size <= 8)
            ref.validate(3,objectId,0); val b = read(ref)
            val p = try { ChunkListCodec.decode(b,objectId) } finally { b.fill(0) }
            checkFormat(expected == null || p.level == expected)
            checkFormat(p.entries.all { it.ordinal < media.chunkCount })
            if(p.level == 0) {
                val e = p.entries.singleOrNull { it.ordinal == ordinal } ?: throw FormatException(Failure.CorruptData)
                e.ref.validate(5,objectId,ordinal); val bytes = read(e.ref)
                val size = if(ordinal == media.chunkCount-1) (media.byteLength-ordinal*Limits.CHUNK).toInt() else Limits.CHUNK
                if(bytes.size != size) { bytes.fill(0); throw FormatException(Failure.CorruptData) }
                return bytes
            }
            val e = p.entries.firstOrNull { it.ordinal >= ordinal } ?: throw FormatException(Failure.CorruptData)
            ref = e.ref; expected = p.level-1
        }
    }
    fun copy(read: (LogicalRef)->ByteArray, check: ()->Unit, objectId: Bytes, media: MediaPayload, output: OutputStream) {
        if(media.chunkCount == 0L) { check(); validateEmpty(read,objectId,media); return }
        for(ordinal in 0 until media.chunkCount) {
            check(); val b = chunk(read,objectId,media,ordinal)
            try { check(); output.write(b) } catch(e: StoreException) { throw e }
            catch(_: Exception) { throw StoreException(IoCode.Eio) } finally { b.fill(0) }
        }
    }
}
