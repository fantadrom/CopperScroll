package com.fantadrom.copperscroll.storage.runtimechecks

import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.editor.*
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed synthetic keys only in test APK/JVM. Container1.2 is explicitly CREATED, never migrated. */
object P4Fixtures {
    class Fixture(val store: FaultStore,val core: TransactionCore) : AutoCloseable {
        val repo = ZoneRepository(core)
        override fun close() = core.close()
    }
    fun fresh(minor: Int = 2,two: Boolean = false): Fixture {
        val store = FaultStore(FaultMedium(limit=128*1024*1024)); val core = TransactionCore(ResourceOwner(store))
        check(core.submit(core.issueCreate(P3Fixtures.prepared(1),containerMinor=minor)).code == WriteCode.Committed)
        if(two) check(core.submit(P3Fixtures.preparedAdd(core)).code == WriteCode.Committed)
        return Fixture(store,core)
    }
    fun image(two: Boolean = false): ByteArray = fresh(two=two).use { it.store.medium.durableBytes() }
    fun open(image: ByteArray,role: Int = 1,hook: ()->Unit = {}): Fixture {
        val store = FaultStore(FaultMedium(image,128*1024*1024)); val core = TransactionCore(ResourceOwner(store),hook)
        core.attach(P3Fixtures.key(role)); return Fixture(store,core)
    }
    fun committed(repo: ZoneRepository,token: OperationToken): WriteOutcome = repo.submit(token).also {
        check(it.code == WriteCode.Committed || it.code == WriteCode.NoOp) { "Unexpected code ${it.code}" }
    }
    fun note(text: String) = NoteBody(listOf(NoteParagraph(text)),emptyList())
    fun card(value: String = "0012") = CardBody(listOf(CardField(P3Fixtures.id(44),3,"hidden",TextParameters(),TextValue(value))))
    fun generatedDigest(length: Long): Bytes {
        val d = MessageDigest.getInstance("SHA-256"); var pos = 0L; val b = ByteArray(8192)
        while(pos < length) {
            val n = minOf(b.size.toLong(),length-pos).toInt()
            repeat(n) { b[it] = ((pos+it)*13+7).toByte() }; d.update(b,0,n); pos += n
        }
        b.fill(0); return Bytes(d.digest())
    }
    fun generatedSource(length: Long,short: Int = 8192,onRead: (Long)->Unit = {}): BinarySource =
        BinarySource(length,generatedDigest(length),{
            object : InputStream() {
                private var pos = 0L
                override fun read(): Int = if(pos >= length) -1 else (((pos++*13+7).toInt()) and 255).also { onRead(pos) }
                override fun read(b: ByteArray,off: Int,len: Int): Int {
                    if(pos >= length) return -1
                    val n = minOf(short,len,minOf(length-pos,Int.MAX_VALUE.toLong()).toInt())
                    repeat(n) { b[off+it] = ((pos+it)*13+7).toByte() }; pos += n; onRead(pos); return n
                }
            }
        })
}
class P4Checks(private val resource: (String)->String = { name ->
    P4Checks::class.java.getResourceAsStream("/p4/$name")!!.bufferedReader().use { it.readText() }
}) {
    private fun reject(block: ()->Unit) {
        try { block() } catch(e: Exception) {
            if(e is FormatException || e is StoreException || e is CryptoException || e is MutationRefusal) return
            throw e
        }
        error("Expected bounded rejection")
    }
    private fun done(r: ZoneRepository,t: OperationToken) = P4Fixtures.committed(r,t)
    fun vfsBatchDepthTrash() = P4Fixtures.fresh().use { f ->
        val r = f.repo; val root = r.roots().first
        val a = ZoneRepository.newId(); val b = ZoneRepository.newId(); val c = ZoneRepository.newId()
        done(r,r.issueCreateFolder(r.stamp(),root,"same",id=a,now=1))
        done(r,r.issueCreateFolder(r.stamp(),root,"same",id=b,now=2))
        done(r,r.issueCreateFolder(r.stamp(),a,"child",id=c,now=3))
        val before = r.metadata(c)
        done(r,r.issueMove(r.stamp(),listOf(r.handle(a),r.handle(c),r.handle(a)),b,now=4))
        check(r.metadata(c).parent == a && r.metadata(c).revision == before.revision)
        val seq = r.stamp().sequence
        check(r.submit(r.issueMove(r.stamp(),listOf(r.handle(a)),b,now=5)).code == WriteCode.NoOp && r.stamp().sequence == seq)
        val cycle = r.submit(r.issueMove(r.stamp(),listOf(r.handle(b)),c,now=6)); check(cycle.code == WriteCode.ValidationError)
        val stale = r.handle(a); done(r,r.issueMetadata(r.stamp(),r.handle(a),color=7,now=7))
        check(r.submit(r.issueDelete(r.stamp(),listOf(r.handle(b),stale),now=8)).code == WriteCode.Conflict)
        check(r.metadata(b).parent == root)
        done(r,r.issueCreateFolder(r.stamp(),root,"Восстановленное",now=9))
        done(r,r.issueDelete(r.stamp(),listOf(r.handle(b),r.handle(c)),now=10))
        check(r.list(root).size == 1 && r.list(r.roots().second,trash=true).size == 1)
        done(r,r.issueRestoreAll(r.stamp(),now=11)); check(r.list(root).any { it.name == "Восстановленное 2" })
        done(r,r.issueDelete(r.stamp(),listOf(r.handle(b)),now=12)); done(r,r.issueClearTrash(r.stamp()))
        check(r.allMetadata().none { it.id in setOf(a,b,c) })
        val v = f.core.readZone { _,_,view,_,_ -> view }
        val map = LinkedHashMap<Bytes,ObjectData>(); var parent = root
        repeat(63) { n -> val id = P3Fixtures.id(100+n); map[id] = ObjectData(id,1,parent,"f",icon="folder",createdAt=0,contentModifiedAt=0,metadataModifiedAt=0,payload=FolderPayload); parent=id }
        VfsMutation.validateGraph(map,v.root!!)
        val leaf = P3Fixtures.id(230)
        map[leaf] = ObjectData(leaf,2,parent,"",icon="note",createdAt=0,contentModifiedAt=0,metadataModifiedAt=0,payload=FolderPayload)
        VfsMutation.validateGraph(map,v.root)
        val extra = P3Fixtures.id(231); map[extra] = ObjectData(extra,1,parent,"f",icon="folder",createdAt=0,contentModifiedAt=0,metadataModifiedAt=0,payload=FolderPayload)
        reject { VfsMutation.validateGraph(map,v.root) }
    }
    fun noteRichTextRevision() = P4Fixtures.fresh(minor=1).use { f ->
        val r = f.repo; val root = r.roots().first; val id = ZoneRepository.newId()
        check(r.submit(r.issueSaveNote(r.stamp(),id,root,"",P4Fixtures.note(""),now=10)).code == WriteCode.NoOp)
        check(r.allMetadata().isEmpty())
        val input = NoteDocument(listOf(Paragraph("🙂e\u0301",null),Paragraph("",false),Paragraph("tail",true),Paragraph("")),
            listOf(StyledRange(0,4,TextStyle(bold=true)),StyledRange(1,5,TextStyle(italic=true,foreground=0xff123456.toInt()))))
        val body = NoteStorageAdapter.fromEditor(input); val request = r.issueSaveNote(r.stamp(),id,root,"title",body,4,now=20)
        val result = done(r,request); check(result.objectId == id)
        val saved = r.readNote(id); check(NoteStorageAdapter.toEditor(saved.body) == input && saved.cursorFocusUtf16 == 4L)
        check(r.submit(request).code == WriteCode.Committed && r.allMetadata().size == 1)
        val t = saved.metadata.contentModifiedAt
        done(r,r.issueSaveNote(r.stamp(),id,root,"title",body,1,saved.metadata.revision,now=30))
        check(r.metadata(id).contentModifiedAt == t && r.metadata(id).metadataModifiedAt == 30L)
        done(r,r.issueSaveNote(r.stamp(),id,root,"",P4Fixtures.note(""),0,r.metadata(id).revision,now=40))
        check(r.metadata(id).id == id && r.readNote(id).body.fullyEmpty)
        val white = ZoneRepository.newId(); done(r,r.issueSaveNote(r.stamp(),white,root,"",P4Fixtures.note(" ")))
        val todo = ZoneRepository.newId(); done(r,r.issueSaveNote(r.stamp(),todo,root,"",NoteBody(listOf(NoteParagraph("",false)),emptyList())))
        check(r.allMetadata().size == 3)
        val tracker = RevisionTracker(); tracker.edited(); tracker.edited(); tracker.acknowledge(WriteOutcome(WriteCode.Committed,revision=1)); check(tracker.dirty)
        check(r.submit(r.issueSaveNote(r.stamp(),ZoneRepository.newId(),root,"x\n",body)).code == WriteCode.ValidationError)
    }
    fun cardFieldRoundTripAndGolden() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        for(line in resource("golden.tsv").lineSequence().filter { it.isNotEmpty() }) {
            val (name,value) = line.split('\t'); val b = hex(value)
            if(name.startsWith("object")) {
                val o = ObjectCodec.decode(b,containerMinor=2); check(o.type == 6 && o.name == "Карточка" && o.icon == "future-card")
                check(ObjectCodec.encode(o,2).contentEquals(b)); reject { ObjectCodec.decode(b,containerMinor=1) }
            } else {
                val card = CardCodec.decode(b); check(CardCodec.encode(card).contentEquals(b))
                if(name == "card_all_fields") {
                    check(card.fields.map { it.type } == (1..10).toList())
                    check((card.fields[0].value as TextValue).text == "0012")
                    check((card.fields[2].value as TextValue).text == "e\u0301")
                    check(ExactNumbers.format(card.fields[8].value as DecimalValue) == "-12.340")
                    check(CardCodec.validate("golden",card).warnings.size == 2)
                }
            }
        }
        for(line in resource("negative.tsv").lineSequence().filter { it.isNotEmpty() }) {
            val a = line.split('\t'); var code: Failure? = null
            try { CardCodec.decode(hex(a[1])) } catch(e: FormatException) { code=e.failure }
            check(code?.name == a[2]) { "Negative fixture code: ${a[0]}/$code" }
        }
        val fields = arrayListOf(CardField.create(1,"duplicate"),CardField.create(3,"duplicate")); val frozen = CardBody(fields); fields.clear()
        check(frozen.fields.size == 2)
        val first = frozen.fields[0]; val edited = frozen.replace(first.renamed("renamed")).reorder(frozen.fields.map { it.id }.reversed())
        check(edited.fields.last().id == first.id)
        reject { frozen.replace(CardField(first.id,3,first.name,TextParameters(),TextValue())) }
    }
    fun cardAtomicSaveAndDraft() = P4Fixtures.fresh(two=true).use { f ->
        val r = f.repo; val root = r.roots().first; val id = ZoneRepository.newId(); val notices = ZoneNoticeMailbox()
        val session = CardDraftSession(r,id,root,notices=notices)
        check(session.prepareSave() == null && r.allMetadata().isEmpty())
        session.edit("named",P4Fixtures.card("0012")); val save = session.prepareSave()!!
        session.edit("newer",P4Fixtures.card("0000")); check(session.submit(save).code == WriteCode.Committed && session.dirty)
        check(r.readCard(id).metadata.name == "named")
        val invalid = P4Fixtures.card().fields[0].withParameters(TextParameters(digitsOnly=true)).withValue(TextValue("X"))
        session.edit("invalid",CardBody(listOf(invalid))); check(!session.validation().valid && session.prepareSave() == null)
        val correct = session.prepareSave(lastWhollyValid=true)!!; session.revoke(correct)
        check(session.submit(correct).code == WriteCode.Committed); reject { session.snapshot() }
        reject { notices.consumeAfterAuthenticatedZoneOpen(r) }
        val reader = ContainerReader(f.store); P3Fixtures.key(1).use { key ->
            val v = reader.verifyAll(reader.snapshot(),key); check(v.objects[id]!!.name == "newer")
        }
        val image=f.store.medium.durableBytes(); f.close()
        P4Fixtures.open(image,2).use { check(!notices.consumeAfterAuthenticatedZoneOpen(it.repo)) }
        P4Fixtures.open(image).use { check(notices.consumeAfterAuthenticatedZoneOpen(it.repo)) }
    }
    fun masksDatesAndConstraints() {
        val m = DigitMask.parse("+0(000)000-0000")
        check(m.format(m.paste("","79218528201")) == "+7(921)852-8201")
        check(m.paste("","+7(921)852-8201") == "79218528201")
        check(m.format(m.paste("","123")) == "+1(23 )   -    ")
        check(m.format(m.paste("","Номер счета: 1111 2222 3333 4444")) == "+1(111)222-2333")
        check(m.delete("123456",1,3) == "1456")
        reject { DigitMask.parse("00A0") }; reject { DigitMask.parse("\\0") }; reject { DigitMask.parse("00").validateRaw("123") }
        check(DateMask.parse("yy/mm").format(CardDate(2006,11,25)) == "06/11")
        check(DateMask.parse("mm/yy").format(CardDate(2019,12,22)) == "12/19")
        check(DateMask.parse("dd.mm.yyyy").format(null).isEmpty())
        DateMask.validateDate(CardDate(2000,2,29)); reject { DateMask.validateDate(CardDate(2019,2,29)) }
        reject { DateMask.parse("yy/yyyy") }; reject { DateMask.parse("dd:MM") }
        check(ExactNumbers.format(ExactNumbers.parseDecimal("-0.50")) == "-0.50")
        reject { ExactNumbers.parseInteger("0012") }; reject { ExactNumbers.parseDecimal("1e3") }; reject { ExactNumbers.parseDecimal("") }
        val upper = CardField.create(1,parameters=TextParameters(uppercaseOnly=true))
        check(CardCodec.validate("x",CardBody(listOf(upper.withValue(TextValue("АБC"))))).valid)
        check(!CardCodec.validate("x",CardBody(listOf(upper.withValue(TextValue("a"))))).valid)
        val contradictory = upper.withParameters(TextParameters(digitsOnly=true,uppercaseOnly=true))
        check(!CardCodec.validate("x",CardBody(listOf(contradictory))).valid)
        val url=CardBody(listOf(CardField.create(4).withValue(TextValue("bad\nurl"))))
        val validation=CardCodec.validate("x",url); check(validation.valid && validation.warnings.size == 1)
        check((CardCodec.decode(CardCodec.encode(url)).fields.single().value as TextValue).text == "bad\nurl")
        val points = arrayListOf(1,2,3); val value = PatternValue(points); points.clear(); check(value.points.size == 3)
    }
    fun templatesAndGlobalMessagePolicy() = P4Fixtures.fresh().use { f ->
        val a = CardTemplates.bankCard.instantiate(); val b = CardTemplates.bankCard.instantiate()
        check(a.fields.size == 6 && a.fields.map { it.id }.intersect(b.fields.map { it.id }.toSet()).isEmpty())
        check((a.fields[2].parameters as TextParameters).mask == "0000 0000 0000 0000")
        check(CardTemplates.empty.instantiate().fields.isEmpty())
        val definitions = arrayListOf(BuiltInField(1,"initial")); val definition = BuiltInCardTemplate(definitions); definitions.clear()
        check(definition.instantiate().fields.single().name == "initial")
        val prefs = MemoryAppMessagePreferences(); val p = CardMessagePolicy(prefs); val second = CardMessagePolicy(prefs)
        p.dismissCardEditorWarning(); check(!second.shouldShow(AppMessage.CardEditorWarning) && second.shouldShow(AppMessage.UnsavedChanges) && second.shouldShow(AppMessage.Confirmation))
        second.restoreHiddenSystemMessages(); check(p.shouldShow(AppMessage.CardEditorWarning))
        val r = f.repo; val session = CardDraftSession(r,ZoneRepository.newId(),r.roots().first)
        session.edit("draft",a); val id = a.fields[4].id
        check(session.display(id) == "●●●●●"); reject { session.copyDisplay(id) }
        done(r,r.issueSettings(r.stamp(),SettingsData(copyBlocked=false))); reject { session.copyDisplay(id) }
        session.reveal(id,true); check(session.copyDisplay(id).isEmpty()); session.reveal(id,false)
        session.revoke(); reject { session.display(id) }
    }
    fun mediaStreamingAndCow() = P4Fixtures.fresh().use { f ->
        val r = f.repo; val root = r.roots().first; val id = ZoneRepository.newId(); val length = 2L*Limits.CHUNK+13
        done(r,r.issueMedia(r.stamp(),id,root,"synthetic","video/test",P4Fixtures.generatedSource(length,137)))
        val bytesBefore = ObjectCodec.encode(r.metadata(id),2); val s0 = f.core.currentSnapshot()
        val rawBefore=s0.catalogues[0].mapValues { (_,p) -> sha(f.store.readFullyAt(p.offset,p.length.toInt())) }
        var count = 0L; val digest = MessageDigest.getInstance("SHA-256"); var maxWrite = 0
        r.copyMedia(id,object : OutputStream() {
            override fun write(value: Int) { error("Unexpected single byte output") }
            override fun write(b: ByteArray,off: Int,len: Int) { count += len; maxWrite=maxOf(maxWrite,len); digest.update(b,off,len) }
        })
        check(count == length && maxWrite <= Limits.CHUNK && Bytes(digest.digest()) == P4Fixtures.generatedDigest(length))
        val range = r.readMediaRange(id,Limits.CHUNK-20L,57)
        check(range.indices.all { range[it] == ((Limits.CHUNK-20L+it)*13+7).toByte() })
        f.store.resetTrace()
        val note = ZoneRepository.newId(); done(r,r.issueSaveNote(r.stamp(),note,root,"short",P4Fixtures.note("short")))
        val noteWrites=f.store.events.filter { it.action == IoAction.Write }.sumOf { it.length.toLong() }
        val noteReads=f.store.events.filter { it.action == IoAction.Read }.sumOf { it.length.toLong() }
        f.store.resetTrace()
        val card = ZoneRepository.newId(); done(r,r.issueSaveCard(r.stamp(),card,root,"card",P4Fixtures.card()))
        val cardWrites=f.store.events.filter { it.action == IoAction.Write }.sumOf { it.length.toLong() }
        val cardReads=f.store.events.filter { it.action == IoAction.Read }.sumOf { it.length.toLong() }
        check(noteWrites < Limits.CHUNK && cardWrites < Limits.CHUNK)
        check(ObjectCodec.encode(r.metadata(id),2).contentEquals(bytesBefore))
        val after = f.core.currentSnapshot()
        for((key,p) in s0.catalogues[0]) {
            val q = after.catalogues[0][key]!!
            check(p.offset == q.offset && p.hash == q.hash && sha(f.store.readFullyAt(p.offset,p.length.toInt())) == rawBefore.getValue(key))
        }
        println("P4 immutable media bytes=$length short-note read=$noteReads write=$noteWrites; short-card read=$cardReads write=$cardWrites PASS")
        val empty = ZoneRepository.newId(); done(r,r.issueMedia(r.stamp(),empty,root,"empty","image/test",P4Fixtures.generatedSource(0)))
        check(r.readMediaRange(empty,0,0).isEmpty())
        val bad = BinarySource(1,Bytes(ByteArray(32)),{ object : InputStream() { override fun read() = -1 } })
        check(r.submit(r.issueMedia(r.stamp(),ZoneRepository.newId(),root,"bad","audio/test",bad)).code == WriteCode.IoFailure)
        var read = 0L; val cancel = BinarySource(32,P4Fixtures.generatedDigest(32),{ object : InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray,off: Int,len: Int): Int { b[off]=1; read++; return 1 }
        } },{ read > 5 })
        check(r.submit(r.issueMedia(r.stamp(),ZoneRepository.newId(),root,"cancel","image/test",cancel)).code == WriteCode.AccessLost)
    }
    fun faultCommitUnknownAndRevoke() {
        val image = P4Fixtures.image()
        P4Fixtures.open(image,hook={ throw StoreException(IoCode.Eio) }).use { f ->
            val r = f.repo; val id = ZoneRepository.newId(); val t = r.issueSaveCard(r.stamp(),id,r.roots().first,"card",P4Fixtures.card())
            check(r.submit(t).code == WriteCode.CommitUnknown && f.core.owner.reconciliationRequired)
            reject { r.stamp() }; check(r.reconcile().code == WriteCode.Committed)
            check(r.submit(t).code == WriteCode.Committed && r.allMetadata().size == 1)
        }
        val delegate = FaultStore(FaultMedium(image)); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        var armed = true
        val blocked = object : RandomAccessStore by delegate {
            override fun force() { if(armed) { armed=false; entered.countDown(); finish.await() }; delegate.force() }
        }
        val core = TransactionCore(ResourceOwner(blocked)); core.attach(P3Fixtures.key(1)); val r = ZoneRepository(core)
        val id = ZoneRepository.newId(); val token = r.issueSaveCard(r.stamp(),id,r.roots().first,"card",P4Fixtures.card())
        val result = AtomicReference<WriteOutcome>(); val thread = Thread { result.set(r.submit(token)) }.apply { isDaemon=true }
        thread.start(); check(entered.await(10,TimeUnit.SECONDS)); core.revokeForClosingSave(token)
        check(!core.owner.awaitQuiescent(5) && !core.owner.release()); finish.countDown(); thread.join(10000)
        check(result.get().code == WriteCode.Committed && core.owner.accessRevoked); reject { r.readCard(id) }; core.close()
    }
    fun compatibilityRecoveryAndBudgets() {
        P4Fixtures.fresh(minor=1).use { f ->
            val r = f.repo; val size = f.store.size
            val t = r.issueSaveCard(r.stamp(),ZoneRepository.newId(),r.roots().first,"x",CardBody())
            check(r.submit(t).code == WriteCode.UnsupportedVersion && f.store.size == size && f.core.currentSnapshot().header.minor == 1)
        }
        P4Fixtures.fresh(two=true).use { f ->
            val r = f.repo; val id = ZoneRepository.newId(); done(r,r.issueSaveCard(r.stamp(),id,r.roots().first,"large",
                CardBody(listOf(CardField.create(2).withValue(TextValue("x".repeat(20000)))))))
            check((r.metadata(id).payload as CardPayload).bodyBytes > Limits.PAGE && r.readCard(id).body.fields.size == 1)
            val before = f.core.currentSnapshot(); val decoy = before.header.zones[1]
            val t = r.issueMetadata(r.stamp(),r.handle(id),icon="future-card")
            done(r,t); check(r.metadata(id).icon == "future-card")
            check(DescriptorCodec.logicalBytes(decoy).contentEquals(DescriptorCodec.logicalBytes(f.core.currentSnapshot().header.zones[1])))
            val fields = (0..256).map { CardField.create(1) }; reject { CardBody(fields) }
            reject { CardCodec.encode(CardBody(listOf(CardField.create(2).withValue(TextValue("x".repeat(32769)))))) }
            val snap = f.core.currentSnapshot(); val bad = snap.headerBytes.copy(); bad[10]=99; P3Fixtures.rehashHeader(bad)
            f.store.writeFullyAt(snap.slot*65536L,bad)
            reject { ContainerReader(f.store).snapshot() }; f.store.writeFullyAt(snap.slot*65536L,snap.headerBytes.copy())
        }
    }
    fun sortingSettingsAndZoneIsolation() = P4Fixtures.fresh(two=true).use { f ->
        val r = f.repo; val root = r.roots().first; val a = ZoneRepository.newId(); val b = ZoneRepository.newId()
        done(r,r.issueSaveCard(r.stamp(),a,root,"b",CardBody(),now=30)); done(r,r.issueSaveCard(r.stamp(),b,root,"a",CardBody(),now=20))
        done(r,r.issuePin(r.stamp(),r.handle(a),true,now=100)); done(r,r.issuePin(r.stamp(),r.handle(b),true,now=-100))
        done(r,r.issueSettings(r.stamp(),SettingsData(sortMode=0xffffffffL,viewMode=0xffffffffL,sortAscending=false)))
        check(r.list(root).map { it.id } == listOf(a,b))
        val content = r.metadata(a).contentModifiedAt; done(r,r.issueMetadata(r.stamp(),r.handle(a),color=0xff112233,now=200))
        check(r.metadata(a).contentModifiedAt == content && r.settings().sortMode == 0xffffffffL)
        val folder = ZoneRepository.newId(); done(r,r.issueCreateFolder(r.stamp(),root,"folder",id=folder))
        done(r,r.issueSettings(r.stamp(),SettingsData(rememberFolder=true,lastFolder=folder,sortMode=0xffffffffL)))
        done(r,r.issueDelete(r.stamp(),listOf(r.handle(folder)))); check(r.rememberedFolder() == root)
        val before = f.core.currentSnapshot(); val bytes = f.store.medium.durableBytes()
        P4Fixtures.open(bytes,2).use { decoy ->
            val d = decoy.repo; val id = ZoneRepository.newId(); done(d,d.issueSaveCard(d.stamp(),id,d.roots().first,"decoy",CardBody()))
            check(d.submit(d.issueDelete(d.stamp(),listOf(d.handle(id)))).code == WriteCode.Committed)
            done(d,d.issueRestoreAll(d.stamp())); done(d,d.issueDelete(d.stamp(),listOf(d.handle(id)))); done(d,d.issueClearTrash(d.stamp()))
            check(d.allMetadata().none { it.id == id })
            check(d.submit(d.issueMove(d.stamp(),listOf(ObjectHandle(r.stamp().zone,a,1u)),d.roots().first)).code == WriteCode.ValidationError)
            check(DescriptorCodec.logicalBytes(before.header.zones[0]).contentEquals(DescriptorCodec.logicalBytes(decoy.core.currentSnapshot().header.zones[0])))
        }
    }
}
