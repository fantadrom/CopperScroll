package com.fantadrom.copperscroll.storage.runtimechecks

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.io.*

/** Independent small fixture author: flat object-index page, source keys fixed only in test APK. */
object P5bImages {
    fun rich(): ByteArray {
        var image = P5aImages.rich()
        for(role in 1..2) {
            image = P4Fixtures.open(image,role).use { f ->
                val r = f.repo; val note = r.metadata(P3Fixtures.id(104))
                val body = NoteBody(listOf(NoteParagraph("e\u0301🙂"),NoteParagraph("todo",true)),
                    listOf(NoteStyle(0,2,true,false,false,0xff123456),NoteStyle(2,4,false,true,true)))
                P4Fixtures.committed(r,r.issueSaveNote(r.stamp(),note.id,note.parent,note.name,body,cursorFocusUtf16=3,
                    expectedObjectRevision=note.revision,now=19))
                f.store.medium.durableBytes()
            }
            image = rewrite(image,role)
        }
        return image
    }
    fun rewrite(image: ByteArray,role: Int,unknown: Boolean = false,sequence: Long? = null): ByteArray =
        FaultStore(FaultMedium(image,128*1024*1024)).use { store -> P3Fixtures.key(role).use { key ->
            val reader = ContainerReader(store); val s = reader.snapshot(); val view = reader.verifyAll(s,key)
            val reserved = s.catalogues.flatMap { it.keys }.toHashSet(); val added = ArrayList<Pair<Bytes,PhysicalRef>>()
            fun add(kind: Int,owner: Bytes,plain: ByteArray): LogicalRef {
                val r = try { RecordCrypto().encrypt(key,kind,owner,0,plain,RecordIdReservation { reserved.add(it) }) }
                    finally { plain.fill(0) }
                added.add(r.reference.recordId to PhysicalRef(store.appendAligned(r.raw.copy()),r.raw.size.toLong(),r.reference.hash))
                return r.reference
            }
            val refs = view.objectRefs.toMutableMap()
            for(o in view.objects.values) if(o.type in listOf(2,6)) {
                val a = StrictCbor.decode(ObjectCodec.encode(o,2)).arr().toMutableList()
                val payload = a[13].arr().toMutableList()
                if(o.type == 6) payload[3] = true else { a[6] = "future-note-icon"; payload[3] = 0xffffffffL }
                a[13] = payload; refs[o.id] = add(3,o.id,StrictCbor.encode(a))
            }
            val objects = add(2,key.zoneId,IndexCodec.encode(IndexPage(1,0,refs.entries.sortedBy { it.key }.map {
                IndexEntry(IndexKey(it.key),it.value)
            }),key.zoneId))
            val root = view.root!!; val seq = sequence ?: root.sequence
            val raw = RootCodec.encode(RootData(seq,root.normalRoot,root.trashRoot,objects,root.children,root.settings,
                root.nextPinSequence,root.recentOperations),key.zoneId)
            val fields = StrictCbor.decode(raw).arr().toMutableList(); raw.fill(0); if(unknown) fields[0] = 99
            val rootRef = add(1,key.zoneId,StrictCbor.encode(fields))
            val cat = CowCatalogue(store,key.zoneId).build(s.catalogues[role-1].map { it.key to it.value }+added)
            val descriptor = P3Fixtures.mac(P3Fixtures.descriptor(view.descriptor,rootRef,cat,seq),key)
            val h = Superblock(s.header.generation+1,store.size,s.header.containerId,s.header.instanceId,
                s.header.zones.map { if(it.role == role) descriptor else it },s.header.minor)
            val bytes = SuperblockCodec.encode(h); store.writeFullyAt(0,bytes); store.writeFullyAt(65536,bytes); store.force()
            store.medium.durableBytes()
        } }
}
