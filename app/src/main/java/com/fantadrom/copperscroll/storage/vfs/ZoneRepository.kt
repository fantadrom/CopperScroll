package com.fantadrom.copperscroll.storage.vfs

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.editor.*
import java.io.OutputStream
import java.util.Collections

class ZoneStamp(val zone: Bytes, val sequence: Long, val requestRevision: Long) {
    val nextRevision: Long get() { checkFormat(requestRevision < Long.MAX_VALUE,Failure.UnsupportedLimit); return requestRevision+1 }
    override fun toString() = "ZoneStamp(sequence=$sequence,requestRevision=$requestRevision)"
}
class ObjectHandle(val zone: Bytes, val id: Bytes, val revision: ULong) { override fun toString() = "ObjectHandle(redacted)" }
class StorageNote(val metadata: ObjectData, val body: NoteBody, val cursorFocusUtf16: Long) {
    override fun toString() = "StorageNote(redacted)"
}
class StoredCard(val metadata: ObjectData, val body: CardBody) { override fun toString() = "StoredCard(redacted)" }

/** Headless, zone-scoped API. Admission can throw content-free validation/limit errors;
 * submit/reconcile use P3 WriteOutcome. No editor hook, SAF or user files are connected.
 */
class ZoneRepository(val core: TransactionCore) {
    internal fun authenticatedIdentity(): Pair<Bytes,Bytes> = core.readZone { s,_,view,_,_ -> s.header.containerId to view.descriptor.zoneId }
    fun stamp(): ZoneStamp = core.readZone { _,_,view,_,_ -> ZoneStamp(view.descriptor.zoneId,view.descriptor.sequence,core.requestRevision()) }
    fun roots(): Pair<Bytes,Bytes> = core.readZone { _,_,view,_,_ -> view.root!!.normalRoot to view.root.trashRoot }
    fun metadata(id: Bytes): ObjectData = core.readZone { _,_,view,_,_ -> view.objects[id] ?: throw FormatException(Failure.ValidationError) }
    fun handle(id: Bytes): ObjectHandle = core.readZone { _,_,view,_,_ ->
        val o = view.objects[id] ?: throw FormatException(Failure.ValidationError); ObjectHandle(view.descriptor.zoneId,id,o.revision)
    }
    fun allMetadata(): List<ObjectData> = core.readZone { _,_,view,_,_ -> Collections.unmodifiableList(ArrayList(view.objects.values)) }
    fun settings(): SettingsData = core.readZone { _,_,view,_,_ -> view.settings!! }
    fun rememberedFolder(): Bytes = core.readZone { _,_,view,_,_ ->
        val root = view.root!!; val id = view.settings!!.lastFolder
        if(!view.settings.rememberFolder || id == null || view.objects[id]?.type != 1 || isTrash(view,id)) root.normalRoot else id
    }
    fun readNote(id: Bytes): StorageNote = core.readZone { s,k,view,reader,check ->
        val o = view.objects[id] ?: throw FormatException(Failure.ValidationError)
        val p = o.payload as? NotePayload ?: throw FormatException(Failure.ValidationError)
        check(); val b = reader.readDocument(s,k,id,p.chunks,p.bodyBytes,Limits.NOTE)
        try { StorageNote(o,NoteCodec.decode(b),p.cursorFocusUtf16) } finally { b.fill(0) }
    }
    fun readCard(id: Bytes): StoredCard = core.readZone { s,k,view,reader,check ->
        val o = view.objects[id] ?: throw FormatException(Failure.ValidationError)
        val p = o.payload as? CardPayload ?: throw FormatException(Failure.ValidationError)
        check(); val b = reader.readDocument(s,k,id,p.chunks,p.bodyBytes,CardLimits.BODY)
        try { StoredCard(o,CardCodec.decode(b)) } finally { b.fill(0) }
    }
    fun copyMedia(id: Bytes, output: OutputStream) = core.readZone { s,k,view,reader,check ->
        val o = view.objects[id] ?: throw FormatException(Failure.ValidationError)
        val p = o.payload as? MediaPayload ?: throw FormatException(Failure.ValidationError)
        MediaStreams.copy({ ref -> check(); reader.readRecord(s,k,ref) },check,id,p,output)
    }
    fun readMediaRange(id: Bytes, offset: Long, length: Int): ByteArray = core.readZone { s,k,view,reader,check ->
        val o = view.objects[id] ?: throw FormatException(Failure.ValidationError)
        val p = o.payload as? MediaPayload ?: throw FormatException(Failure.ValidationError)
        checkFormat(length in 0..Limits.CHUNK && offset >= 0 && offset <= p.byteLength && length <= p.byteLength-offset,Failure.ValidationError)
        if(p.chunkCount == 0L) MediaStreams.validateEmpty({ ref -> check(); reader.readRecord(s,k,ref) },id,p)
        val out = ByteArray(length); var pos = 0
        try {
            while(pos < length) {
                check(); val absolute = offset+pos; val ordinal = absolute/Limits.CHUNK
                val b = MediaStreams.chunk({ ref -> check(); reader.readRecord(s,k,ref) },id,p,ordinal)
                try {
                    val start = (absolute%Limits.CHUNK).toInt(); val n = minOf(length-pos,b.size-start)
                    checkFormat(n > 0); b.copyInto(out,pos,start,start+n); pos += n
                } finally { b.fill(0) }
            }
            out
        } catch(e: Throwable) { out.fill(0); throw e }
    }
    fun list(parent: Bytes, trash: Boolean = false, offset: Int = 0, limit: Int = 256): List<ObjectData> = core.readZone { s,k,view,reader,check ->
        checkFormat(offset >= 0 && limit in 1..4096,Failure.ValidationError)
        val root = view.root!!
        checkFormat(parent == root.normalRoot || parent == root.trashRoot || view.objects[parent]?.type == 1,Failure.ValidationError)
        checkFormat(isTrash(view,parent) == trash,Failure.ValidationError)
        val settings = view.settings!!; val titles = HashMap<Bytes,String>(); var budget = 0L
        fun title(o: ObjectData): String {
            if(o.type != 2 || o.name.isNotEmpty()) return o.name
            return titles.getOrPut(o.id) {
                val p = o.payload as NotePayload
                checkFormat(p.bodyBytes*4 <= 8L*1024*1024-budget,Failure.ResourceLimit)
                val bytes = reader.readDocument(s,k,o.id,p.chunks,p.bodyBytes,Limits.NOTE)
                val body = try { NoteCodec.decode(bytes) } finally { bytes.fill(0) }
                val t = if(body.fullyEmpty) "(Пустая заметка)" else body.text
                budget += t.length*2L+128; t
            }
        }
        fun scalar(a: String,b: String): Int {
            var x = 0; var y = 0
            while(x < a.length && y < b.length) {
                val ca = a.codePointAt(x); val cb = b.codePointAt(y); if(ca != cb) return ca.compareTo(cb)
                x += Character.charCount(ca); y += Character.charCount(cb)
            }
            return (a.length-x).compareTo(b.length-y)
        }
        fun group(o: ObjectData) = if(o.type == 1) 0 else if(o.pinned) 1 else 2
        val result = view.objects.values.filter { it.parent == parent }.sortedWith { a,b ->
            check(); val group = group(a).compareTo(group(b))
            if(group != 0) group else if(a.pinned && b.pinned && a.pinSequence != b.pinSequence) a.pinSequence!!.compareTo(b.pinSequence!!)
            else {
                var c = when(settings.displaySort) {
                    2L -> (if(a.type == 1) a.metadataModifiedAt else a.contentModifiedAt).compareTo(if(b.type == 1) b.metadataModifiedAt else b.contentModifiedAt)
                    3L -> { val t = a.type.compareTo(b.type); if(t != 0) t else if(a.type == 2 || a.type == 6) a.icon.compareTo(b.icon) else 0 }
                    4L -> (a.colorArgb and 0xffffff).compareTo(b.colorArgb and 0xffffff)
                    else -> 0
                }
                if(c == 0) c = scalar(title(a),title(b))
                if(!settings.sortAscending) c = -c
                if(c == 0) a.id.compareTo(b.id) else c
            }
        }
        Collections.unmodifiableList(result.drop(offset).take(limit))
    }
    fun issueCreateFolder(stamp: ZoneStamp,parent: Bytes,name: String,color: Long = 0xffffffffL,
                          id: Bytes = newId(),now: Long = System.currentTimeMillis()): OperationToken =
        issue(VfsMutation(stamp,"Folder",id,parent,name,color,now=now))
    fun issueSaveNote(stamp: ZoneStamp,id: Bytes,parent: Bytes,title: String,body: NoteBody,cursorFocusUtf16: Long = 0,
                      expectedObjectRevision: ULong? = null,color: Long? = null,icon: String? = null,
                      revision: Long = stamp.nextRevision,now: Long = System.currentTimeMillis()): OperationToken {
        val bytes = NoteCodec.encode(body)
        return try { issue(VfsMutation(stamp,"Note",id,parent,title,color,icon,
            expected=expectedObjectRevision?.let { listOf(ObjectHandle(stamp.zone,id,it)) } ?: emptyList(),
            now=now,body=bytes,cursor=cursorFocusUtf16,revision=revision)) } finally { bytes.fill(0) }
    }
    fun issueSaveCard(stamp: ZoneStamp,id: Bytes,parent: Bytes,title: String,body: CardBody,
                      expectedObjectRevision: ULong? = null,color: Long? = null,icon: String? = null,
                      revision: Long = stamp.nextRevision,now: Long = System.currentTimeMillis()): OperationToken {
        checkFormat(CardCodec.validate(title,body).valid,Failure.ValidationError)
        val bytes = CardCodec.encode(body)
        return try { issue(VfsMutation(stamp,"Card",id,parent,title,color,icon,
            expected=expectedObjectRevision?.let { listOf(ObjectHandle(stamp.zone,id,it)) } ?: emptyList(),now=now,body=bytes,revision=revision)) }
        finally { bytes.fill(0) }
    }
    fun issueMedia(stamp: ZoneStamp,id: Bytes,parent: Bytes,name: String,mime: String,source: BinarySource,
                   now: Long = System.currentTimeMillis()): OperationToken {
        VfsMutation.mediaType(mime)
        return issue(VfsMutation(stamp,"Media",id,parent,name,now=now,source=source,mime=mime))
    }
    fun issueMetadata(stamp: ZoneStamp,objectHandle: ObjectHandle,name: String? = null,color: Long? = null,
                      icon: String? = null,now: Long = System.currentTimeMillis()): OperationToken =
        issue(VfsMutation(stamp,"Metadata",objectHandle.id,name=name,color=color,icon=icon,expected=listOf(objectHandle),now=now))
    fun issuePin(stamp: ZoneStamp,objectHandle: ObjectHandle,pinned: Boolean,now: Long = System.currentTimeMillis()): OperationToken =
        issue(VfsMutation(stamp,"Pin",objectHandle.id,expected=listOf(objectHandle),now=now,pin=pinned))
    fun issueMove(stamp: ZoneStamp,objects: List<ObjectHandle>,destination: Bytes,now: Long = System.currentTimeMillis()): OperationToken =
        issue(VfsMutation(stamp,"Move",expected=objects,destination=destination,now=now))
    fun issueDelete(stamp: ZoneStamp,objects: List<ObjectHandle>,now: Long = System.currentTimeMillis()): OperationToken =
        issue(VfsMutation(stamp,"Delete",expected=objects,now=now))
    fun issueRestoreAll(stamp: ZoneStamp,now: Long = System.currentTimeMillis(),recoveryFolderId: Bytes = newId()): OperationToken =
        issue(VfsMutation(stamp,"Restore",objectId=recoveryFolderId,now=now))
    fun issueClearTrash(stamp: ZoneStamp,now: Long = System.currentTimeMillis()): OperationToken =
        issue(VfsMutation(stamp,"Clear",now=now))
    fun issueSettings(stamp: ZoneStamp,settings: SettingsData): OperationToken {
        val bytes = SettingsCodec.encode(settings)
        return try { issue(VfsMutation(stamp,"Settings",now=0,settings=bytes)) } finally { bytes.fill(0) }
    }
    private fun issue(request: VfsMutation): OperationToken = try { core.issueZoneMutation(request) }
        catch(e: Throwable) { request.close(); throw e }
    fun submit(token: OperationToken): WriteOutcome = core.submit(token)
    fun reconcile(): WriteOutcome = core.reconcile()
    companion object {
        fun newId() = Bytes(SystemEntropy().bytes(16,true))
        internal fun isTrash(view: ZoneView,id: Bytes): Boolean {
            val root = view.root!!; var p = id; var n = 0
            while(p != root.normalRoot && p != root.trashRoot) {
                checkFormat(++n <= 64); p = view.objects[p]?.parent ?: throw FormatException(Failure.ValidationError)
            }
            return p == root.trashRoot
        }
    }
}
object NoteStorageAdapter {
    fun fromEditor(document: NoteDocument) = NoteBody(document.paragraphs.map { NoteParagraph(it.text,it.checked) },
        document.styles.map { NoteStyle(it.start.toLong(),it.end.toLong(),it.style.bold,it.style.italic,it.style.underline,it.style.foreground?.toLong()?.and(0xffffffffL)) })
    fun toEditor(body: NoteBody): NoteDocument = NoteDocument(body.paragraphs.map { Paragraph(it.text,it.checked) },
        body.styles.map { StyledRange(it.startUtf16.toInt(),it.endUtf16.toInt(),TextStyle(it.bold,it.italic,it.underline,it.foregroundArgb?.toInt())) })
}
