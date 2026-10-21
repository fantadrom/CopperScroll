package com.fantadrom.copperscroll.storage.format

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.util.VersionInfo

/** Parsing plaintext bytes alone does not prove authentication. P2 must supply verified AEAD plaintext. */
class RootData(val sequence: Long, val normalRoot: Bytes, val trashRoot: Bytes,
               val objects: LogicalRef, val children: LogicalRef, val settings: LogicalRef,
               val nextPinSequence: ULong, val recentOperations: List<Pair<Bytes, Bytes?>>)
object RootCodec {
    fun decode(b: ByteArray, zone: Bytes, expectedSequence: Long? = null): RootData {
        zone.id(16)
        val a = StrictCbor.decode(b).arr(10); a[0].version(); a[1].version()
        val seq = a[2].number(); checkFormat(seq > 0 && (expectedSequence == null || seq == expectedSequence))
        val normal = a[3].ident(); val trash = a[4].ident(); checkFormat(normal != trash)
        val objects = ReferenceCodec.logical(a[5]).also { it.validate(2,zone,0) }
        val children = ReferenceCodec.logical(a[6]).also { it.validate(2,zone,0) }
        val settings = ReferenceCodec.logical(a[7]).also { it.validate(3,zone,0) }
        val pin = a[8].uint(); checkFormat(pin > 0uL)
        val ops = a[9].arr(); checkFormat(ops.size <= 256, Failure.UnsupportedLimit)
        val recent = ops.map { val e = it.arr(2); e[0].ident() to e[1]?.ident() }
        checkFormat(recent.map { it.first }.distinct().size == recent.size)
        return RootData(seq,normal,trash,objects,children,settings,pin,recent)
    }
    fun encode(r: RootData, zone: Bytes): ByteArray {
        checkFormat(r.recentOperations.size <= 256, Failure.UnsupportedLimit)
        val b = StrictCbor.encode(listOf(1,1,r.sequence,r.normalRoot,r.trashRoot,r.objects.wire(),r.children.wire(),
            r.settings.wire(),r.nextPinSequence,r.recentOperations.map { listOf(it.first,it.second) }))
        decode(b,zone); return b
    }
}
class IndexKey(val first: Bytes, val second: Bytes? = null) : Comparable<IndexKey> {
    override fun compareTo(other: IndexKey): Int {
        val c = first.compareTo(other.first)
        return if(c != 0) c else (second?.compareTo(other.second ?: first) ?: 0)
    }
    internal fun wire(): Any = second?.let { listOf(first,it) } ?: first
}
class IndexEntry(val key: IndexKey, val value: LogicalRef?)
class IndexPage(val type: Int, val level: Int, val entries: List<IndexEntry>)
object IndexCodec {
    fun decode(b: ByteArray, zone: Bytes): IndexPage {
        zone.id(16)
        val a = StrictCbor.decode(b).arr(3)
        val type = a[0].number(255).toInt(); checkFormat(type in 1..2,Failure.UnsupportedVersion)
        val level = a[1].number(7).toInt()
        val entries = a[2].arr(); checkFormat(entries.size <= 64,Failure.UnsupportedLimit)
        checkFormat(level == 0 || entries.isNotEmpty())
        val result = entries.map {
            val e = it.arr(2)
            val key = if(type == 1) IndexKey(e[0].ident()) else {
                val k = e[0].arr(2); IndexKey(k[0].ident(), k[1].ident())
            }
            val ref = if(level == 0 && type == 2) { checkFormat(e[1] == null); null }
                else ReferenceCodec.logical(e[1]).also { r ->
                    if(level > 0) r.validate(2,zone,0) else r.validate(3,key.first,0)
                }
            IndexEntry(key,ref)
        }
        checkFormat(result.zipWithNext().all { (x,y) -> x.key < y.key })
        return IndexPage(type,level,result)
    }
    fun encode(p: IndexPage, zone: Bytes): ByteArray {
        checkFormat(p.entries.size <= 64,Failure.UnsupportedLimit)
        val b = StrictCbor.encode(listOf(p.type,p.level,p.entries.map { listOf(it.key.wire(),it.value?.wire()) }))
        decode(b,zone); return b
    }
}
class SettingsData(val rememberFolder: Boolean = false, val lastFolder: Bytes? = null,
                   val screenshotsBlocked: Boolean = true, val copyBlocked: Boolean = true,
                   val sortMode: Long = 1, val sortAscending: Boolean = true, val viewMode: Long = 1) {
    val displaySort: Long get() = if(sortMode in 1..4) sortMode else 1
    val displayView: Long get() = if(viewMode in 1..3) viewMode else 1
}
object SettingsCodec {
    fun decode(b: ByteArray): SettingsData {
        val a = StrictCbor.decode(b).arr(8); a[0].version()
        val remember = a[1].bool(); val folder = a[2]?.ident(); checkFormat(remember || folder == null)
        return SettingsData(remember,folder,a[3].bool(),a[4].bool(),a[5].number(0xffffffffL),a[6].bool(),a[7].number(0xffffffffL))
    }
    fun encode(s: SettingsData): ByteArray {
        val b = StrictCbor.encode(listOf(1,s.rememberFolder,s.lastFolder,s.screenshotsBlocked,s.copyBlocked,s.sortMode,s.sortAscending,s.viewMode))
        decode(b); return b
    }
}
sealed interface ObjectPayload
object FolderPayload : ObjectPayload
class NotePayload(val bodyBytes: Long, chunks: List<LogicalRef>, val cursorFocusUtf16: Long) : ObjectPayload {
    init { checkFormat(chunks.size <= 16,Failure.UnsupportedLimit) }
    val chunks: List<LogicalRef> = java.util.Collections.unmodifiableList(ArrayList(chunks))
    /** Persisted value is preserved; UI can use this explicit clamp after loading the body. */
    fun clampedCursor(body: NoteBody): Int = cursorFocusUtf16.coerceAtMost(body.utf16Length.toLong()).toInt()
}
class MediaPayload(val mime: String, val byteLength: Long, val chunkCount: Long, val chunks: LogicalRef) : ObjectPayload
class CardPayload(val bodyBytes: Long, chunks: List<LogicalRef>, val useAsUserTemplate: Boolean = false) : ObjectPayload {
    init { checkFormat(chunks.size <= 4,Failure.UnsupportedLimit) }
    val chunks: List<LogicalRef> = java.util.Collections.unmodifiableList(ArrayList(chunks))
}
class ObjectData(val id: Bytes, val type: Int, val parent: Bytes, val name: String,
                 val colorArgb: Long = 0xffffffffL, val icon: String, val pinned: Boolean = false,
                 val pinSequence: ULong? = null, val createdAt: Long, val contentModifiedAt: Long,
                 val metadataModifiedAt: Long, val revision: ULong = 1uL, val payload: ObjectPayload) {
    val displayIcon: String get() = if(type == 2 && icon != "note") "note" else if(type == 6 && icon != "card") "card" else icon
}
object ObjectCodec {
    private val iconSyntax = Regex("[a-z][a-z0-9_-]{0,31}")
    fun decode(b: ByteArray, expectedId: Bytes? = null, nextPinSequence: ULong? = null, containerMinor: Int = 1): ObjectData {
        val a = StrictCbor.decode(b).arr(14)
        val id = a[1].ident(); checkFormat(expectedId == null || id == expectedId)
        val type = a[2].number(255).toInt()
        checkFormat(if(type == 6) a[0] == 2uL && containerMinor == 2 else a[0] == 1uL && type in 1..5,Failure.UnsupportedVersion)
        val parent = a[3].ident(); checkFormat(parent != id)
        val name = a[4].text(when(type) { 1 -> 1024; 2,6 -> 400; else -> Limits.PAGE })
        if(type == 2 || type == 6) checkFormat(name.codePointCount(0,name.length) <= 100 && '\r' !in name && '\n' !in name)
        if(type == 1 || type == 6) {
            checkFormat(UCharacter.getUnicodeVersion() == VersionInfo.getInstance(16,0,0,0),Failure.UnsupportedVersion)
            checkFormat(name.codePoints().anyMatch { !UCharacter.hasBinaryProperty(it,UProperty.WHITE_SPACE) })
        }
        val color = a[5].number(0xffffffffL); val icon = a[6].text(32)
        checkFormat(iconSyntax.matches(icon))
        if(type != 2 && type != 6) checkFormat(icon == listOf("","folder","note","image","audio","video")[type])
        val pinned = a[7].bool(); val pin = a[8]?.uint()
        checkFormat(if(pinned) pin != null && pin > 0uL && (nextPinSequence == null || pin < nextPinSequence) else pin == null)
        if(type == 1) checkFormat(!pinned)
        val revision = a[12].uint(); checkFormat(revision > 0uL)
        val payload: ObjectPayload = when(type) {
            1 -> { checkFormat(a[13] == null); FolderPayload }
            2 -> {
                val p = a[13].arr(4); p[0].version()
                val bytes = p[1].number(Limits.NOTE.toLong()); checkFormat(bytes > 0)
                val chunks = p[2].arr(); checkFormat(chunks.size in 1..16,Failure.UnsupportedLimit)
                val refs = chunks.mapIndexed { i, c -> ReferenceCodec.logical(c).also { it.validate(4,id,i.toLong()) } }
                checkFormat(refs.map { it.recordId }.distinct().size == refs.size)
                checkFormat(bytes >= refs.size && bytes <= refs.size.toLong() * Limits.CHUNK)
                NotePayload(bytes,refs,p[3].number(0xffffffffL))
            }
            6 -> {
                val p = a[13].arr(4); p[0].version()
                val bytes = p[1].number(com.fantadrom.copperscroll.storage.cards.CardLimits.BODY.toLong()); checkFormat(bytes > 0)
                val chunks = p[2].arr(); checkFormat(chunks.size in 1..4,Failure.UnsupportedLimit)
                val refs = chunks.mapIndexed { i,c -> ReferenceCodec.logical(c).also { it.validate(4,id,i.toLong()) } }
                checkFormat(refs.map { it.recordId }.distinct().size == refs.size && bytes >= refs.size && bytes <= refs.size.toLong()*Limits.CHUNK)
                checkFormat(!p[3].bool(),Failure.UnsupportedVersion); CardPayload(bytes,refs)
            }
            else -> {
                val p = a[13].arr(5); p[0].version()
                val length = p[2].number(512L * 1024 * 1024 * 1024); val count = p[3].number(524288)
                checkFormat(count == if(length == 0L) 0L else (length-1)/Limits.CHUNK + 1)
                MediaPayload(p[1].text(128),length,count,ReferenceCodec.logical(p[4]).also { it.validate(3,id,0) })
            }
        }
        return ObjectData(id,type,parent,name,color,icon,pinned,pin,a[9].signed(),a[10].signed(),a[11].signed(),revision,payload)
    }
    fun encode(o: ObjectData, containerMinor: Int = 1): ByteArray {
        val p: Any? = when(val value = o.payload) {
            FolderPayload -> null
            is NotePayload -> { checkFormat(value.chunks.size <= 16,Failure.UnsupportedLimit); listOf(1,value.bodyBytes,value.chunks.map { it.wire() },value.cursorFocusUtf16) }
            is MediaPayload -> listOf(1,value.mime,value.byteLength,value.chunkCount,value.chunks.wire())
            is CardPayload -> listOf(1,value.bodyBytes,value.chunks.map { it.wire() },value.useAsUserTemplate)
        }
        val b = StrictCbor.encode(listOf(if(o.type == 6) 2 else 1,o.id,o.type,o.parent,o.name,o.colorArgb,o.icon,o.pinned,o.pinSequence,
            o.createdAt,o.contentModifiedAt,o.metadataModifiedAt,o.revision,p))
        decode(b,containerMinor=containerMinor); return b
    }
}
class NoteParagraph(val text: String, val checked: Boolean? = null)
class NoteStyle(val startUtf16: Long, val endUtf16: Long, val bold: Boolean = false, val italic: Boolean = false,
                val underline: Boolean = false, val foregroundArgb: Long? = null)
class NoteBody(paragraphs: List<NoteParagraph>, styles: List<NoteStyle>) {
    init { checkFormat(paragraphs.size <= 100000 && styles.size <= 100000,Failure.UnsupportedLimit) }
    val paragraphs: List<NoteParagraph> = java.util.Collections.unmodifiableList(ArrayList(paragraphs))
    val styles: List<NoteStyle> = java.util.Collections.unmodifiableList(ArrayList(styles))
    val utf16Length: Int get() = paragraphs.sumOf { it.text.length } + paragraphs.size - 1
    val text: String get() = paragraphs.joinToString("\n") { it.text }
    val fullyEmpty: Boolean get() = paragraphs.size == 1 && paragraphs[0].text.isEmpty() && paragraphs[0].checked == null
}
object NoteCodec {
    fun decode(b: ByteArray): NoteBody {
        val a = StrictCbor.decode(b,Limits.NOTE).arr(3); a[0].version()
        val paragraphs = a[1].arr(); val styles = a[2].arr()
        checkFormat(paragraphs.size in 1..100000 && styles.size <= 100000,Failure.UnsupportedLimit)
        var textBytes = 0L; var units = paragraphs.size - 1L
        val ps = paragraphs.map {
            val p = it.arr(2); val text = p[0].text(8 * Limits.CHUNK)
            checkFormat('\n' !in text); textBytes += utf8(text).size; units += text.length
            checkFormat(textBytes <= 8 * Limits.CHUNK,Failure.UnsupportedLimit)
            NoteParagraph(text, p[1]?.bool())
        }
        val ss = styles.map {
            val s = it.arr(6); val start = s[0].number(0xffffffffL); val end = s[1].number(0xffffffffL)
            checkFormat(start < end && end <= units)
            NoteStyle(start,end,s[2].bool(),s[3].bool(),s[4].bool(),s[5]?.number(0xffffffffL))
        }
        return NoteBody(ps,ss)
    }
    fun encode(n: NoteBody): ByteArray {
        checkFormat(n.paragraphs.size in 1..100000 && n.styles.size <= 100000,Failure.UnsupportedLimit)
        var bytes = 0L
        n.paragraphs.forEach { bytes += utf8(it.text).size; checkFormat(bytes <= 8 * Limits.CHUNK,Failure.UnsupportedLimit) }
        val b = StrictCbor.encode(listOf(1,n.paragraphs.map { listOf(it.text,it.checked) },n.styles.map {
            listOf(it.startUtf16,it.endUtf16,it.bold,it.italic,it.underline,it.foregroundArgb)
        }),Limits.NOTE)
        decode(b); return b
    }
    /** Cross-record length checks once P2 has independently authenticated the supplied chunks. */
    fun decodeChunks(payload: NotePayload, owner: Bytes, chunks: List<ByteArray>): NoteBody {
        checkFormat(chunks.size == payload.chunks.size && chunks.size in 1..16)
        var total = 0L
        chunks.forEachIndexed { i,b -> payload.chunks[i].validate(4,owner,i.toLong()); checkFormat(b.size in 1..Limits.CHUNK); total += b.size }
        checkFormat(total == payload.bodyBytes && total <= Limits.NOTE)
        val out = ByteArray(total.toInt()); var pos = 0
        chunks.forEach { it.copyInto(out,pos); pos += it.size }
        return try { decode(out) } finally { out.fill(0) }
    }
}
class ChunkListEntry(val ordinal: Long, val ref: LogicalRef)
class ChunkListPage(val level: Int, val entries: List<ChunkListEntry>)
object ChunkListCodec {
    fun decode(b: ByteArray, owner: Bytes): ChunkListPage {
        owner.id(16)
        val a = StrictCbor.decode(b).arr(2); val level = a[0].number(7).toInt()
        val entries = a[1].arr(); checkFormat(entries.size <= 64,Failure.UnsupportedLimit)
        checkFormat(level == 0 || entries.isNotEmpty())
        val result = entries.map {
            val e = it.arr(2); val ordinal = e[0].number(524287)
            ChunkListEntry(ordinal,ReferenceCodec.logical(e[1]).also { it.validate(if(level == 0) 5 else 3,owner,if(level == 0) ordinal else 0) })
        }
        checkFormat(result.zipWithNext().all { (x,y) -> x.ordinal < y.ordinal })
        return ChunkListPage(level,result)
    }
    fun encode(p: ChunkListPage, owner: Bytes): ByteArray {
        checkFormat(p.entries.size <= 64,Failure.UnsupportedLimit)
        val b = StrictCbor.encode(listOf(p.level,p.entries.map { listOf(it.ordinal,it.ref.wire()) }))
        decode(b,owner); return b
    }
    /** For a fully traversed, authenticated chunk graph. Does not read/decrypt records. */
    fun validateMediaLengths(media: MediaPayload, lengths: List<Int>) {
        checkFormat(lengths.size.toLong() == media.chunkCount && lengths.size <= 524288)
        var total = 0L
        lengths.forEachIndexed { i,n ->
            checkFormat(n in 1..Limits.CHUNK && (i == lengths.lastIndex || n == Limits.CHUNK)); total += n
        }
        checkFormat(total == media.byteLength)
    }
}
