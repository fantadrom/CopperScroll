package com.fantadrom.copperscroll.storage.vfs

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.transaction.*
import java.util.Collections
import java.security.MessageDigest

internal class VfsMutation(private val stamp: ZoneStamp, private val command: String,
    private val objectId: Bytes? = null, private val parent: Bytes? = null, name: String? = null,
    private val color: Long? = null, icon: String? = null, expected: List<ObjectHandle> = emptyList(),
    private val destination: Bytes? = null, private val now: Long, body: ByteArray? = null,
    private val cursor: Long = 0, private val source: BinarySource? = null, mime: String? = null,
    private val pin: Boolean? = null, settings: ByteArray? = null,
    override val revision: Long = stamp.nextRevision) : ZoneMutation {
    init { checkFormat(expected.size <= 4096,Failure.ResourceLimit) }
    private var name = name; private var icon = icon; private var mime = mime
    private var body = body?.clone(); private var settings = settings?.clone()
    private val expected = Collections.unmodifiableList(ArrayList(expected))
    private val bodyHash = body?.let { sha(it) }
    override val expectedZoneSequence get() = stamp.sequence
    override val expectedRevision get() = stamp.requestRevision
    override val resultObjectId get() = if(command in listOf("Folder","Note","Card","Media","Metadata","Pin")) objectId else null
    override val checkpoint get() = command == "Note" || command == "Card"
    override val retainedBytes get() = (body?.size ?: 0).toLong()+(settings?.size ?: 0)+expected.size*128L+
        ((name?.length ?: 0)+(icon?.length ?: 0)+(mime?.length ?: 0))*2+512
    private val frozenBinding: Bytes
    init {
        checkFormat(expected.size <= 4096,Failure.ResourceLimit)
        val bytes = StrictCbor.encode(listOf(command,stamp.zone,stamp.sequence,stamp.requestRevision,revision,
            objectId,parent,name,color,icon,expected.map { listOf(it.zone,it.id,it.revision) },destination,now,
            bodyHash,cursor,source?.length,source?.digest,mime,pin,settings?.let { Bytes(it) }),1024*1024)
        try { frozenBinding = sha(bytes) } finally { bytes.fill(0) }
    }
    override fun binding() = frozenBinding.copy()
    override fun close() { body?.fill(0); body = null; settings?.fill(0); settings = null; name = null; icon = null; mime = null; source?.close() }
    override fun toString() = "VfsMutation(redacted)"
    private fun reject(code: WriteCode): Nothing = throw MutationRefusal(code)
    private fun ensure(ok: Boolean, code: WriteCode = WriteCode.ValidationError) { if(!ok) reject(code) }
    override fun apply(context: ZoneWriteContext): ZoneEdit {
        ensure(context.zone == stamp.zone,WriteCode.Conflict)
        objectId?.id(16)
        val view = context.view; val root = view.root!!
        val objects = LinkedHashMap(view.objects); val changed = LinkedHashMap<Bytes,ObjectData>(); val removed = LinkedHashSet<Bytes>()
        var nextPin = root.nextPinSequence; var settingsRef = root.settings; var overflow: List<Bytes> = emptyList()
        for(handle in expected) {
            ensure(handle.zone == context.zone)
            val o = objects[handle.id] ?: reject(WriteCode.Conflict)
            ensure(o.revision == handle.revision,WriteCode.Conflict)
        }
        fun objectAt(id: Bytes) = objects[id] ?: reject(WriteCode.ValidationError)
        fun inTrash(id: Bytes): Boolean {
            var current = id; var n = 0
            while(current != root.normalRoot && current != root.trashRoot) {
                ensure(++n <= 64); current = objectAt(current).parent
            }
            return current == root.trashRoot
        }
        fun validParent(id: Bytes, allowTrash: Boolean = false) {
            if(id == root.normalRoot) return
            if(id == root.trashRoot) { ensure(allowTrash); return }
            ensure(objectAt(id).type == 1 && (allowTrash || !inTrash(id)))
        }
        fun revised(o: ObjectData, parent: Bytes = o.parent, name: String = o.name,
                    color: Long = o.colorArgb, icon: String = o.icon, pinned: Boolean = o.pinned,
                    pinSequence: ULong? = o.pinSequence, payload: ObjectPayload = o.payload,
                    contentChanged: Boolean = false): ObjectData {
            ensure(o.revision < ULong.MAX_VALUE,WriteCode.Limit)
            val metadataChanged = parent != o.parent || color != o.colorArgb || icon != o.icon ||
                pinned != o.pinned || pinSequence != o.pinSequence || (o.type != 2 && o.type != 6 && name != o.name) ||
                (payload is NotePayload && o.payload is NotePayload && payload.cursorFocusUtf16 != o.payload.cursorFocusUtf16)
            return ObjectData(o.id,o.type,parent,name,color,icon,pinned,pinSequence,o.createdAt,
                if(contentChanged) now else o.contentModifiedAt,if(metadataChanged) now else o.metadataModifiedAt,o.revision+1u,payload)
        }
        fun update(o: ObjectData) { objects[o.id] = o; changed[o.id] = o }
        fun normalized(): List<ObjectData> {
            val selected = expected.map { it.id }.toSet()
            return expected.map { it.id }.distinct().filter { id ->
                var p = objectAt(id).parent; var keep = true; var n = 0
                while(p != root.normalRoot && p != root.trashRoot) {
                    ensure(++n <= 63); if(p in selected) { keep = false; break }; p = objectAt(p).parent
                }
                keep
            }.map { objectAt(it) }
        }
        fun digest(refs: List<LogicalRef>, length: Long, owner: Bytes): Bytes {
            val d = MessageDigest.getInstance("SHA-256"); var count = 0L
            refs.forEachIndexed { i,ref ->
                ref.validate(4,owner,i.toLong()); val b = context.read(ref)
                try { checkFormat(b.size in 1..Limits.CHUNK); count += b.size; d.update(b) } finally { b.fill(0) }
            }
            checkFormat(count == length); return Bytes(d.digest())
        }
        fun document(refs: List<LogicalRef>, length: Long, owner: Bytes): ByteArray {
            checkFormat(length in 1..CardLimits.BODY.toLong()); val out = ByteArray(length.toInt()); var pos = 0
            try {
                refs.forEachIndexed { i,ref ->
                    ref.validate(4,owner,i.toLong()); val b = context.read(ref)
                    try { checkFormat(b.size in 1..minOf(Limits.CHUNK,out.size-pos)); b.copyInto(out,pos); pos += b.size } finally { b.fill(0) }
                }
                checkFormat(pos == out.size); return out
            } catch(e: Throwable) { out.fill(0); throw e }
        }
        fun writeBody(bytes: ByteArray, owner: Bytes): List<LogicalRef> {
            val refs = ArrayList<LogicalRef>(); var pos = 0
            while(pos < bytes.size) {
                context.check(); val end = minOf(bytes.size,pos+Limits.CHUNK)
                refs.add(context.record(4,owner,refs.size.toLong(),bytes.copyOfRange(pos,end))); pos = end
            }
            return refs
        }
        fun properties(type: Int, name: String, color: Long, icon: String) {
            ensure(color in 0..0xffffffffL && icon.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
            ensure(validUtf16(name))
            when(type) {
                1 -> ensure(name.length <= 1024 && utf8(name).size <= 1024 && notBlank(name) && icon == "folder")
                2,6 -> ensure(name.length <= 200 && name.codePointCount(0,name.length) <= 100 && utf8(name).size <= 400 &&
                    '\n' !in name && '\r' !in name && (type == 2 || notBlank(name)))
                else -> ensure(name.length <= Limits.PAGE && utf8(name).size <= Limits.PAGE && icon == listOf("","","","image","audio","video")[type])
            }
        }
        // Everything in the plan is validated before appending any object/chunk/index records.
        var pendingBody: ByteArray? = null; var pendingBodyObject: ObjectData? = null
        var pendingMedia: ObjectData? = null
        when(command) {
            "Folder","Note","Card","Media" -> {
                val id = objectId!!; id.id(16); val existing = objects[id]; val type = when(command) { "Folder" -> 1; "Note" -> 2; "Card" -> 6; else -> mediaType(mime!!) }
                if(existing != null) {
                    ensure(command == "Note" || command == "Card",WriteCode.Conflict)
                    ensure(existing.type == type && expected.singleOrNull()?.id == id,WriteCode.Conflict)
                    ensure(parent == existing.parent)
                } else ensure(expected.isEmpty(),WriteCode.Conflict)
                ensure(id != root.normalRoot && id != root.trashRoot)
                validParent(parent!!)
                val title = name!!; val c = color ?: existing?.colorArgb ?: 0xffffffffL
                val i = icon ?: existing?.icon ?: listOf("","folder","note","image","audio","video","card")[type]
                properties(type,title,c,i)
                if(type == 6) ensure(context.snapshot.header.minor == 2,WriteCode.UnsupportedVersion)
                var contentChanged = existing == null || existing.name != title
                var payload = existing?.payload ?: FolderPayload
                if(type == 2 || type == 6) {
                    val b = body ?: reject(WriteCode.ValidationError)
                    if(type == 2) {
                        val note = NoteCodec.decode(b)
                        ensure(cursor in 0..0xffffffffL)
                        if(existing == null && title.isEmpty() && note.fullyEmpty) return ZoneEdit(root,true,noObject=true)
                        val previous = existing?.payload as? NotePayload
                        val same = previous != null && previous.bodyBytes == b.size.toLong() && digest(previous.chunks,previous.bodyBytes,id) == bodyHash
                        contentChanged = contentChanged || !same
                        if(same) payload = NotePayload(previous!!.bodyBytes,previous.chunks,cursor)
                        else {
                            pendingBody = b
                            if(previous != null) payload = NotePayload(previous.bodyBytes,previous.chunks,cursor)
                        }
                    } else {
                        val card = CardCodec.decode(b); ensure(CardCodec.validate(title,card).valid)
                        val previous = existing?.payload as? CardPayload
                        if(previous != null) {
                            val bytes = document(previous.chunks,previous.bodyBytes,id)
                            val old = try { CardCodec.decode(bytes) } finally { bytes.fill(0) }
                            val types = old.fields.associate { it.id to it.type }
                            ensure(card.fields.all { types[it.id] == null || types[it.id] == it.type })
                        }
                        val same = previous != null && previous.bodyBytes == b.size.toLong() && digest(previous.chunks,previous.bodyBytes,id) == bodyHash
                        contentChanged = contentChanged || !same
                        if(!same) pendingBody = b
                    }
                }
                if(existing != null && !contentChanged && c == existing.colorArgb && i == existing.icon &&
                    (type != 2 || cursor == (existing.payload as NotePayload).cursorFocusUtf16)) return ZoneEdit(root,true)
                val obj = if(existing == null) ObjectData(id,type,parent,title,c,i,createdAt=now,contentModifiedAt=now,metadataModifiedAt=now,payload=payload)
                    else revised(existing,name=title,color=c,icon=i,payload=payload,contentChanged=contentChanged)
                update(obj)
                if(pendingBody != null) pendingBodyObject = obj
                if(type in 3..5) pendingMedia = obj
            }
            "Metadata" -> {
                val old = objectAt(objectId!!); ensure(expected.singleOrNull()?.id == old.id,WriteCode.Conflict)
                val n = name ?: old.name; val c = color ?: old.colorArgb; val i = icon ?: old.icon
                properties(old.type,n,c,i)
                if(n != old.name || c != old.colorArgb || i != old.icon)
                    update(revised(old,name=n,color=c,icon=i,contentChanged=(old.type == 2 || old.type == 6) && n != old.name))
            }
            "Pin" -> {
                val old = objectAt(objectId!!); ensure(old.type != 1 && expected.singleOrNull()?.id == old.id)
                if(old.pinned != pin!!) {
                    val position = if(pin) { ensure(nextPin < ULong.MAX_VALUE,WriteCode.Limit); nextPin++ } else null
                    update(revised(old,pinned=pin,pinSequence=position))
                }
            }
            "Move","Delete" -> {
                val dest = if(command == "Delete") root.trashRoot else destination!!
                validParent(dest,command == "Delete")
                for(o in normalized()) {
                    ensure(!inTrash(o.id))
                    if(o.parent != dest) update(revised(o,parent=dest))
                }
            }
            "Restore" -> {
                val roots = objects.values.filter { it.parent == root.trashRoot }
                val fits = ArrayList<ObjectData>(); val tooDeep = ArrayList<Bytes>()
                val heights = HashMap<Bytes,Int>()
                for(child in objects.values) {
                    context.check(); var top = child.id; var p = child.parent
                    var depth = if(child.type == 1) 1 else 0; var n = 0
                    while(p != root.normalRoot && p != root.trashRoot) {
                        ensure(++n <= 63); depth++; top = p; p = objectAt(p).parent
                    }
                    if(p == root.trashRoot) heights[top] = maxOf(heights[top] ?: 0,depth)
                }
                roots.forEach { if(heights.getValue(it.id)+1 > 63) tooDeep.add(it.id) else fits.add(it) }
                if(fits.isNotEmpty()) {
                    val names = objects.values.filter { it.parent == root.normalRoot && it.type == 1 }.map { it.name }.toSet()
                    var n = 1; var name = "Восстановленное"
                    while(name in names) { ensure(n <= objects.size+1,WriteCode.Limit); n++; name = "Восстановленное $n" }
                    val id = objectId!!; ensure(id !in objects && id != root.normalRoot && id != root.trashRoot,WriteCode.Conflict)
                    update(ObjectData(id,1,root.normalRoot,name,icon="folder",createdAt=now,contentModifiedAt=now,metadataModifiedAt=now,payload=FolderPayload))
                    fits.forEach { update(revised(it,parent=id)) }
                }
                tooDeep.forEach { update(revised(objectAt(it),parent=root.normalRoot)) }; overflow = tooDeep
            }
            "Clear" -> {
                val ids = objects.keys.filter { inTrash(it) }
                ids.forEach { objects.remove(it); removed.add(it) }
            }
            "Settings" -> Unit
            else -> reject(WriteCode.ValidationError)
        }
        validateGraph(objects,root) { context.check() }
        if(changed.isEmpty() && removed.isEmpty() && command != "Settings") return ZoneEdit(root,true,overflow)
        if(command == "Settings") {
            val b = settings!!; SettingsCodec.decode(b)
            val previous = context.read(root.settings)
            val same = try { previous.contentEquals(b) } finally { previous.fill(0) }
            if(same) return ZoneEdit(root,true)
        }
        // All metadata/graph/field checks above are complete. IO below only builds immutable COW.
        pendingBodyObject?.let { o ->
            val b = pendingBody!!; val refs = writeBody(b,o.id)
            val payload: ObjectPayload = if(o.type == 2) NotePayload(b.size.toLong(),refs,cursor) else CardPayload(b.size.toLong(),refs,(o.payload as? CardPayload)?.useAsUserTemplate ?: false)
            update(withPayload(o,payload))
        }
        pendingMedia?.let { o -> update(withPayload(o,MediaStreams.write(context,o.id,source!!,mime!!))) }
        var objectsRoot = root.objects; var childrenRoot = root.children
        val oi = CowIndex(context,1); val ci = CowIndex(context,2)
        for(id in removed) {
            val old = view.objects.getValue(id); objectsRoot = oi.remove(objectsRoot,IndexKey(id))
            childrenRoot = ci.remove(childrenRoot,IndexKey(old.parent,id))
        }
        for(o in changed.values) {
            val ref = context.record(3,o.id,0,ObjectCodec.encode(o,context.snapshot.header.minor))
            objectsRoot = oi.put(objectsRoot,IndexKey(o.id),ref)
            val old = view.objects[o.id]
            if(old == null || old.parent != o.parent) {
                if(old != null) childrenRoot = ci.remove(childrenRoot,IndexKey(old.parent,o.id))
                childrenRoot = ci.put(childrenRoot,IndexKey(o.parent,o.id),null)
            }
        }
        if(command == "Settings") settingsRef = context.record(3,context.zone,0,settings!!.clone())
        return ZoneEdit(RootData(root.sequence,root.normalRoot,root.trashRoot,objectsRoot,childrenRoot,settingsRef,nextPin,root.recentOperations),overflow=overflow)
    }
    companion object {
        internal fun mediaType(mime: String): Int {
            checkFormat(mime.length <= 128 && utf8(mime).size <= 128,Failure.ValidationError)
            return when { mime.startsWith("image/") -> 3; mime.startsWith("audio/") -> 4; mime.startsWith("video/") -> 5; else -> throw FormatException(Failure.ValidationError) }
        }
        internal fun withPayload(o: ObjectData,payload: ObjectPayload) = ObjectData(o.id,o.type,o.parent,o.name,o.colorArgb,o.icon,
            o.pinned,o.pinSequence,o.createdAt,o.contentModifiedAt,o.metadataModifiedAt,o.revision,payload)
        internal fun validateGraph(objects: Map<Bytes,ObjectData>,root: RootData,check: ()->Unit = {}) {
            checkFormat(objects.size <= 1000000 && objects.size*1024L <= 32L*1024*1024,Failure.ResourceLimit)
            for(o in objects.values) {
                check()
                var parent = o.parent; var depth = if(o.type == 1) 1 else 0; val seen = HashSet<Bytes>()
                while(parent != root.normalRoot && parent != root.trashRoot) {
                    if(!seen.add(parent) || ++depth > 63) throw MutationRefusal(WriteCode.ValidationError)
                    val p = objects[parent] ?: throw MutationRefusal(WriteCode.ValidationError)
                    if(p.type != 1) throw MutationRefusal(WriteCode.ValidationError)
                    parent = p.parent
                }
                if(depth > 63) throw MutationRefusal(WriteCode.ValidationError)
            }
        }
    }
}
