package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.runtimechecks.*
import java.nio.file.Files
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class P4BoundaryTest {
    private fun reject(work: ()->Unit) {
        try { work() } catch(e: Exception) { if(e is FormatException || e is StoreException || e is CryptoException) return; throw e }
        error("Expected rejection")
    }
    private fun done(r: ZoneRepository,t: OperationToken) = P4Fixtures.committed(r,t)
    @Test fun persistedDepth63RestoreOverflowAndIndexCollapse() = P4Fixtures.fresh().use { f ->
        val r=f.repo; val root=r.roots().first; var parent=root; var top: Bytes?=null
        repeat(63) { n ->
            val id=ZoneRepository.newId(); done(r,r.issueCreateFolder(r.stamp(),parent,"f$n",id=id,now=n.toLong()))
            if(top == null) top=id; parent=id
        }
        val size=f.store.size
        check(r.submit(r.issueCreateFolder(r.stamp(),parent,"overflow")).code == WriteCode.ValidationError && f.store.size == size)
        val note=ZoneRepository.newId(); done(r,r.issueSaveNote(r.stamp(),note,parent,"leaf",P4Fixtures.note("leaf")))
        val card=ZoneRepository.newId(); done(r,r.issueSaveCard(r.stamp(),card,parent,"leaf card",CardBody()))
        val media=ZoneRepository.newId(); done(r,r.issueMedia(r.stamp(),media,parent,"leaf media","image/test",P4Fixtures.generatedSource(0)))
        done(r,r.issueDelete(r.stamp(),listOf(r.handle(top!!))))
        val restored=done(r,r.issueRestoreAll(r.stamp())); check(restored.overflow == listOf(top) && r.metadata(top!!).parent == root)
        check(r.list(root).none { it.name == "Восстановленное" })
        val short=ZoneRepository.newId(); done(r,r.issueCreateFolder(r.stamp(),root,"short",id=short))
        done(r,r.issueDelete(r.stamp(),listOf(r.handle(top!!),r.handle(short))))
        val mixed=done(r,r.issueRestoreAll(r.stamp())); check(mixed.overflow == listOf(top))
        val recovery=r.list(root).single { it.name == "Восстановленное" }
        check(r.metadata(short).parent == recovery.id && r.metadata(note).parent == parent)
        check(r.readCard(card).body.fields.isEmpty() && r.readMediaRange(media,0,0).isEmpty())
        f.core.readZone { s,k,v,reader,_ ->
            val bytes=reader.readRecord(s,k,v.root!!.objects)
            try { check(IndexCodec.decode(bytes,k.zoneId).level > 0) } finally { bytes.fill(0) }
            reader.verifyAll(s,k)
        }
        val extra=ArrayList<Bytes>()
        repeat(70) { val id=ZoneRepository.newId(); extra.add(id); done(r,r.issueCreateFolder(r.stamp(),root,"extra",id=id)) }
        done(r,r.issueDelete(r.stamp(),extra.map { r.handle(it) })); done(r,r.issueClearTrash(r.stamp()))
        check(extra.none { id -> r.allMetadata().any { it.id == id } })
        done(r,r.issueDelete(r.stamp(),r.list(root).map { r.handle(it.id) })); done(r,r.issueClearTrash(r.stamp()))
        check(r.allMetadata().isEmpty())
        f.core.readZone { s,k,v,reader,_ ->
            val bytes=reader.readRecord(s,k,v.root!!.objects)
            try { val p=IndexCodec.decode(bytes,k.zoneId); check(p.level == 0 && p.entries.isEmpty()) } finally { bytes.fill(0) }
        }
        println("P4 persisted depth63/overflow/mixed restore and COW split/collapse: PASS")
    }
    /** Independent test author for a validly encrypted future schema, not a writer bypass API. */
    private fun installVariant(store: FaultStore,id: Bytes,variant: Int) {
        val reader=ContainerReader(store); val snapshot=reader.snapshot()
        P3Fixtures.key(1).use { key ->
            val view=reader.openZone(snapshot,key); val known=snapshot.catalogues.flatMap { it.keys }.toHashSet()
            val additions=ArrayList<Pair<Bytes,PhysicalRef>>()
            fun add(kind: Int,owner: Bytes,bytes: ByteArray): LogicalRef {
                val record=try { RecordCrypto().encrypt(key,kind,owner,0,bytes,RecordIdReservation { known.add(it) }) } finally { bytes.fill(0) }
                val offset=store.appendAligned(record.raw.copy()); additions.add(record.reference.recordId to PhysicalRef(offset,record.raw.size.toLong(),record.reference.hash)); return record.reference
            }
            val body=if(variant == 0) StrictCbor.encode(listOf(2,emptyList<Any>())) else
                if(variant == 1) StrictCbor.encode(listOf(1,listOf(listOf(P3Fixtures.id(90),99,"",null,null)))) else CardCodec.encode(CardBody())
            val length=body.size; val chunk=add(4,id,body)
            val old=view.objects.getValue(id); val obj=VfsMutation.withPayload(old,CardPayload(length.toLong(),listOf(chunk)))
            val objectFields=StrictCbor.decode(ObjectCodec.encode(obj,2)).arr().toMutableList()
            if(variant == 2) objectFields[0]=99
            if(variant == 3) { val payload=objectFields[13].arr().toMutableList(); payload[3]=true; objectFields[13]=payload }
            val objectRef=add(3,id,StrictCbor.encode(objectFields))
            val entries=view.objectRefs.map { (objectId,ref) -> IndexEntry(IndexKey(objectId),if(objectId == id) objectRef else ref) }.sortedBy { it.key }
            val objects=add(2,key.zoneId,IndexCodec.encode(IndexPage(1,0,entries),key.zoneId))
            val root=view.root!!
            val nextRoot=RootData(root.sequence+1,root.normalRoot,root.trashRoot,objects,root.children,root.settings,root.nextPinSequence,root.recentOperations)
            val rootRef=add(1,key.zoneId,RootCodec.encode(nextRoot,key.zoneId)); var catalogue=view.descriptor.catalogue
            for((recordId,physical) in additions) catalogue=CowCatalogue(store,key.zoneId).insert(catalogue,recordId,physical)
            val d=P3Fixtures.mac(P3Fixtures.descriptor(view.descriptor,rootRef,catalogue,nextRoot.sequence),key)
            store.force(); val h=snapshot.header
            val header=Superblock(h.generation+1,store.size,h.containerId,h.instanceId,h.zones.map { if(it.role == 1) d else it },h.minor)
            store.writeFullyAt((1-snapshot.slot)*65536L,SuperblockCodec.encode(header)); store.force()
        }
    }
    @Test fun unknownCardSchemasAndForeignRecovery() {
        for(variant in 0..3) {
            val image=P4Fixtures.fresh(two=true).use { f ->
                val r=f.repo; val id=ZoneRepository.newId(); done(r,r.issueSaveCard(r.stamp(),id,r.roots().first,"card",CardBody()))
                installVariant(f.store,id,variant); f.store.medium.durableBytes()
            }
            val store=FaultStore(FaultMedium(image)); val reader=ContainerReader(store); val snapshot=reader.snapshot()
            P3Fixtures.key(1).use { key -> check(reader.openZone(snapshot,key).readOnly); reject { reader.verifyAll(snapshot,key) } }
            P3Fixtures.key(2).use { key ->
                val recovery=reader.recoverSnapshot(snapshot.slot,snapshot.header.generation,2)
                val view=reader.verifyAll(recovery,key); check(view.readOnly && view.root != null)
            }
            val core=TransactionCore(ResourceOwner(store)); check(core.attach(P3Fixtures.key(1)).readOnly)
            val size=store.size; val seq=snapshot.header.zones[0].sequence
            check(core.submit(core.issueMutation(SettingsMutation(seq,0,1,SettingsData()))).code == WriteCode.UnsupportedVersion && store.size == size)
            core.close()
        }
        P4Fixtures.fresh(two=true).use { f ->
            val r=f.repo; val id=ZoneRepository.newId(); done(r,r.issueSaveCard(r.stamp(),id,r.roots().first,"card",P4Fixtures.card()))
            val snapshot=f.core.currentSnapshot(); val bad=snapshot.headerBytes.copy(); bad[4172]=0xff.toByte(); P3Fixtures.rehashHeader(bad)
            f.store.writeFullyAt(snapshot.slot*65536L,bad)
            val reader=ContainerReader(f.store); reject { reader.snapshot() }
            P3Fixtures.key(1).use { key ->
                val recovered=reader.recoverSnapshot(snapshot.slot,snapshot.header.generation,1)
                check(reader.verifyAll(recovered,key).objects[id]!!.type == 6)
            }
            check(f.store.medium.bytes().copyOfRange(snapshot.slot*65536,(snapshot.slot+1)*65536).contentEquals(bad))
        }
    }
    @Test fun largeCardLimitsImmutableTypesAndQueueBudgets() = P4Fixtures.fresh().use { f ->
        val r=f.repo; val text="x".repeat(32768); val fields=(0 until 120).map { CardField.create(2).withValue(TextValue(text)) }
        val body=CardBody(fields); val id=ZoneRepository.newId(); val root=r.roots().first
        done(r,r.issueSaveCard(r.stamp(),id,root,"large",body)); val payload=r.metadata(id).payload as CardPayload
        check(payload.bodyBytes > 3L*Limits.CHUNK && payload.bodyBytes <= CardLimits.BODY && payload.chunks.size == 4)
        check((r.readCard(id).body.fields.last().value as TextValue).text.length == 32768)
        val tooLarge=CardBody((0 until 256).map { CardField.create(2).withValue(TextValue(text)) })
        check(!CardCodec.validate("large",tooLarge).valid); reject { CardCodec.encode(tooLarge) }
        reject { CardBody(listOf(CardField.create(2).withValue(TextValue("x".repeat(131073))))) }
        val first=fields.first(); val changed=CardBody(listOf(CardField(first.id,3,first.name,TextParameters(),first.value))+fields.drop(1))
        val size=f.store.size
        check(r.submit(r.issueSaveCard(r.stamp(),id,root,"large",changed,r.handle(id).revision)).code == WriteCode.ValidationError && f.store.size == size)
        val small=ZoneRepository.newId(); val token=r.issueSaveCard(r.stamp(),small,root,"small",P4Fixtures.card())
        done(r,token)
        val retained=token.request.javaClass.getDeclaredField("body").apply { isAccessible=true }
        check(retained.get(token.request) == null && r.submit(token).code == WriteCode.Committed)
        val session=CardDraftSession(r,ZoneRepository.newId(),root)
        repeat(16) { session.edit("queued$it",CardBody()); check(session.prepareSave() != null) }
        session.edit("seventeenth",CardBody()); reject { session.prepareSave() }
        reject { session.edit("x".repeat(4097),CardBody()) }
        session.revoke()
        println("P4 large card bytes=${payload.bodyBytes} chunks=${payload.chunks.size}; limits/queue/cleanup PASS")
    }
    @Test fun fileChannelStreamingAndUninterruptibleSourceOwnership() {
        val directory=Files.createTempDirectory("p4-stream-"); val path=directory.resolve("synthetic.cscroll")
        try {
            val store=FileChannelStore.open(path,true); store.writeFullyAt(0,P4Fixtures.image()); store.force()
            val core=TransactionCore(ResourceOwner(store)); core.attach(P3Fixtures.key(1)); val r=ZoneRepository(core)
            val id=ZoneRepository.newId(); val length=8L*Limits.CHUNK+17
            done(r,r.issueMedia(r.stamp(),id,r.roots().first,"stream","video/test",P4Fixtures.generatedSource(length,257)))
            val digest=MessageDigest.getInstance("SHA-256"); var count=0L
            r.copyMedia(id,object:OutputStream() {
                override fun write(value: Int) { error("Not streaming") }
                override fun write(b: ByteArray,off: Int,len: Int) { check(len <= Limits.CHUNK); count += len; digest.update(b,off,len) }
            })
            check(count == length && Bytes(digest.digest()) == P4Fixtures.generatedDigest(length)); core.close()
            println("P4 FileChannel generated stream bytes=$length, input blocks<=8192/output<=1048576 PASS")
        } finally { Files.deleteIfExists(path); Files.delete(directory) }
        val f=P4Fixtures.fresh(); val entered=CountDownLatch(1); val finish=CountDownLatch(1); var closed=0
        val source=BinarySource(32,P4Fixtures.generatedDigest(32),{
            object:InputStream() {
                override fun read() = -1
                override fun read(b: ByteArray,off: Int,len: Int): Int {
                    entered.countDown(); while(true) try { finish.await(); break } catch(_: InterruptedException) { }
                    repeat(32) { b[off+it]=(it*13+7).toByte() }; return 32
                }
                override fun close() { closed++ }
            }
        })
        val r=f.repo; val token=r.issueMedia(r.stamp(),ZoneRepository.newId(),r.roots().first,"blocked","audio/test",source)
        val result=AtomicReference<WriteOutcome>(); val thread=Thread { result.set(r.submit(token)) }.apply { isDaemon=true }
        thread.start(); check(entered.await(10,TimeUnit.SECONDS)); f.core.revokeForClosingSave(); thread.interrupt()
        check(!f.core.owner.awaitQuiescent(5) && !f.core.owner.release() && closed == 0 && f.store.closeCount == 0)
        reject { ResourceOwner(FaultStore(f.store.medium,f.store.identity)) }
        finish.countDown(); thread.join(10000); check(!thread.isAlive && result.get().code == WriteCode.AccessLost && closed == 1)
        f.close(); check(f.store.closeCount == 1)
        P4Fixtures.open(f.store.medium.durableBytes()).use { check(it.repo.allMetadata().isEmpty()) }
    }
    @Test fun draftUnknownCannotPublishOpenSessionOrLoseRevisionBinding() {
        val notices=ZoneNoticeMailbox(); val image=P4Fixtures.image()
        P4Fixtures.open(image,hook={ throw StoreException(IoCode.Eio) }).use { f ->
            val r=f.repo; val id=ZoneRepository.newId(); val session=CardDraftSession(r,id,r.roots().first,notices=notices)
            session.edit("accepted",P4Fixtures.card()); val save=session.prepareSave()!!
            session.edit("later",P4Fixtures.card("different"))
            check(session.submit(save).code == WriteCode.CommitUnknown && !session.saved && session.dirty)
            reject { session.snapshot() }; check(f.core.owner.accessRevoked && !f.core.owner.release())
            check(session.reconcile(save).code == WriteCode.Committed && !session.saved)
            reject { notices.consumeAfterAuthenticatedZoneOpen(r) }
            val committed=f.store.medium.durableBytes(); f.close()
            P4Fixtures.open(committed).use { check(notices.consumeAfterAuthenticatedZoneOpen(it.repo)) }
        }
        P4Fixtures.fresh().use { f ->
            val r=f.repo; val id=ZoneRepository.newId(); val session=CardDraftSession(r,id,r.roots().first)
            session.edit("saved",P4Fixtures.card()); val save=session.prepareSave()!!; check(session.submit(save).code == WriteCode.Committed)
            val bad=CardBody(listOf(CardField.create(6).withValue(DateValue(CardDate(2019,2,29)))))
            session.edit("bad",bad); check(session.prepareSave() == null && session.prepareSave(lastWhollyValid=true) == null)
            check(r.readCard(id).metadata.name == "saved")
            val values=listOf(TextValue("0012"),P4Fixtures.card(),CardDraft("0012",CardBody(),1))
            check(values.none { it.toString().contains("0012") }); session.revoke()
        }
    }
}
