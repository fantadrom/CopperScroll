package com.fantadrom.copperscroll.storage.runtimechecks

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test-only normative author. Fixed keys/entropy are never compiled into the target APK. */
object P3Fixtures {
    fun id(n: Int, size: Int = 16) = Bytes(ByteArray(size) { i -> if(i == size-1) n.toByte() else 0 })
    val container = id(91)
    const val MAIN = "P3 synthetic main"
    const val DECOY = "P3 synthetic decoy"
    private val envelopes = HashMap<Int,Envelope>()
    fun key(role: Int) = ZoneKey(container,id(role),role,ByteArray(32) { (it+role).toByte() })
    @Synchronized fun prepared(role: Int): PreparedZone {
        val envelope = envelopes.getOrPut(role) {
            val salt = id(role+10); val nonce = Bytes(ByteArray(12) { (it+role).toByte() })
            val pending = Envelope(container,id(role),role,3,KdfParameters(16384,2),salt,nonce,Bytes(ByteArray(48)))
            val secret = (if(role == 1) MAIN else DECOY).toByteArray()
            val raw = CryptoPrimitives.argon(secret,salt.copy(),16384,2,1)
            try {
                val wrap = CryptoPrimitives.hkdf(raw,container.copy(),"CS-wrap-key-v1\u0000".toByteArray()+id(role).copy())
                try { Envelope(container,id(role),role,3,pending.parameters,salt,nonce,
                    Bytes(CryptoPrimitives.aes(true,wrap,nonce.copy(),pending.aad(),ByteArray(32) { (it+role).toByte() }))) }
                finally { wrap.fill(0) }
            } finally { raw.fill(0); secret.fill(0) }
        }
        return PreparedZone(key(role),envelope)
    }
    /** Reuse the real private construction path with a pre-authorized synthetic proposal for fault
     * enumeration; production Add authentication is tested separately through issueAdd. No hook/API
     * bypass is added to production. Only this test helper imports known fixture key material.
     */
    fun preparedAdd(core: TransactionCore): OperationToken {
        val method = TransactionCore::class.java.getDeclaredMethod("issueNew",PreparedZone::class.java,String::class.java,Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(core,prepared(2),"",true) as OperationToken
    }
    fun image(two: Boolean = false): ByteArray {
        val store = FaultStore(); val core = TransactionCore(ResourceOwner(store))
        check(core.submit(core.issueCreate(prepared(1))).code == WriteCode.Committed)
        if(two) check(core.submit(preparedAdd(core)).code == WriteCode.Committed)
        val bytes = store.medium.durableBytes(); core.close(); return bytes
    }
    fun core(image: ByteArray, role: Int = 1, hook: () -> Unit = {}): Pair<FaultStore,TransactionCore> {
        val store = FaultStore(FaultMedium(image)); val core = TransactionCore(ResourceOwner(store),hook)
        core.attach(key(role)); return store to core
    }
    fun mutation(core: TransactionCore, sort: Long = 2, expectedRevision: Long = 0, revision: Long = 1): SettingsMutation {
        val s = core.currentSnapshot()
        return SettingsMutation(s.header.zones.first().sequence,expectedRevision,revision,SettingsData(sortMode=sort))
    }
    fun replaceDescriptor(store: FaultStore, selected: ReadSnapshot, descriptor: Descriptor) {
        val h = selected.header
        val header = Superblock(h.generation+1,store.size,h.containerId,h.instanceId,h.zones.map { if(it.role == descriptor.role) descriptor else it })
        store.writeFullyAt((1-selected.slot)*65536L,SuperblockCodec.encode(header)); store.force()
    }
    fun descriptor(d: Descriptor, root: LogicalRef = d.root, catalogue: PhysicalRef = d.catalogue,
                   sequence: Long = d.sequence, wrapped: Bytes = d.wrappedKey): Descriptor =
        Descriptor(d.zoneId,d.role,sequence,d.inputMethod,d.memoryKiB,d.iterations,d.salt,d.wrapNonce,wrapped,
            d.hint,root,Bytes(ByteArray(32)),catalogue)
    fun mac(d: Descriptor, key: ZoneKey): Descriptor = Descriptor(d.zoneId,d.role,d.sequence,d.inputMethod,d.memoryKiB,d.iterations,
        d.salt,d.wrapNonce,d.wrappedKey,d.hint,d.root,RecordCrypto().descriptorMac(key,d),d.catalogue)
    fun rehashHeader(bytes: ByteArray) { sha(bytes.copyOfRange(0,65504)).copy().copyInto(bytes,65504) }
    fun sequence(store: FaultStore, sequence: Long) {
        val reader = ContainerReader(store); val s = reader.snapshot()
        key(1).use { key ->
            val view = reader.openZone(s,key); val r = view.root!!
            val root = RootData(sequence,r.normalRoot,r.trashRoot,r.objects,r.children,r.settings,r.nextPinSequence,r.recentOperations)
            val bytes = RootCodec.encode(root,key.zoneId)
            val encrypted = try { RecordCrypto().encrypt(key,1,key.zoneId,0,bytes,RecordIdReservation { it !in s.catalogues[0] }) }
                finally { bytes.fill(0) }
            val physical = PhysicalRef(store.appendAligned(encrypted.raw.copy()),encrypted.raw.size.toLong(),encrypted.reference.hash)
            val catalogue = CowCatalogue(store,key.zoneId).insert(view.descriptor.catalogue,encrypted.reference.recordId,physical)
            store.force(); replaceDescriptor(store,s,mac(descriptor(view.descriptor,encrypted.reference,catalogue,sequence),key))
        }
    }
    /** Normative one-note graph, independent of any future VFS writer. Body remains lazy at open. */
    fun noteGraph(store: FaultStore, unsupportedRoot: Boolean = false): PhysicalRef {
        val reader = ContainerReader(store); val s = reader.snapshot(); val key = key(1)
        try {
            val view = reader.openZone(s,key); val root = view.root!!; val known = s.catalogues.flatMap { it.keys }.toHashSet()
            val additions = ArrayList<Pair<Bytes,PhysicalRef>>()
            fun add(kind: Int, owner: Bytes, bytes: ByteArray): LogicalRef {
                val record = try { RecordCrypto().encrypt(key,kind,owner,0,bytes,RecordIdReservation { known.add(it) }) }
                    finally { bytes.fill(0) }
                val offset = store.appendAligned(record.raw.copy())
                additions.add(record.reference.recordId to PhysicalRef(offset,record.raw.size.toLong(),record.reference.hash))
                return record.reference
            }
            val noteId = id(77)
            val chunk = add(4,noteId,NoteCodec.encode(NoteBody(listOf(NoteParagraph("Synthetic")),emptyList())))
            val chunkPhysical = additions.last().second
            val obj = ObjectData(noteId,2,root.normalRoot,"",icon="note",createdAt=1,contentModifiedAt=1,metadataModifiedAt=1,
                payload=NotePayload(chunkPhysical.length-92,listOf(chunk),0))
            val objectRef = add(3,noteId,ObjectCodec.encode(obj))
            val objects = add(2,key.zoneId,IndexCodec.encode(IndexPage(1,0,listOf(IndexEntry(IndexKey(noteId),objectRef))),key.zoneId))
            val children = add(2,key.zoneId,IndexCodec.encode(IndexPage(2,0,listOf(IndexEntry(IndexKey(root.normalRoot,noteId),null))),key.zoneId))
            val newRoot = RootData(root.sequence+1,root.normalRoot,root.trashRoot,objects,children,root.settings,root.nextPinSequence,root.recentOperations)
            var bytes = RootCodec.encode(newRoot,key.zoneId)
            if(unsupportedRoot) {
                val fields = StrictCbor.decode(bytes).arr().toMutableList(); fields[0] = 2
                bytes.fill(0); bytes = StrictCbor.encode(fields)
            }
            val rootRef = add(1,key.zoneId,bytes)
            var catalogue = view.descriptor.catalogue
            for((id,p) in additions) catalogue = CowCatalogue(store,key.zoneId).insert(catalogue,id,p)
            store.force()
            replaceDescriptor(store,s,mac(descriptor(view.descriptor,rootRef,catalogue,newRoot.sequence),key))
            return chunkPhysical
        } finally { key.close() }
    }
}

class P3Checks {
    private fun rejects(block: () -> Unit) {
        try { block() } catch(e: Exception) {
            if(e is FormatException || e is CryptoException || e is StoreException) return
            throw e
        }
        error("Expected bounded rejection")
    }
    fun createAddMutateReopen() {
        val store = FaultStore(); val core = TransactionCore(ResourceOwner(store))
        val created = core.submit(core.issueCreate(P3Fixtures.prepared(1))); check(created.code == WriteCode.Committed && created.generation == 1L)
        val mainBefore = core.currentSnapshot().header.zones.single()
        SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P3Fixtures.DECOY).use { next ->
            SecretEncoding.text(P3Fixtures.MAIN).use { same -> rejects { core.issueAdd(old,same,same,3,KdfParameters(16384,2)) } }
            val add = core.issueAdd(old,next,next,3,KdfParameters(16384,2))
            check(core.submit(add).code == WriteCode.Committed)
        } }
        val afterAdd = core.currentSnapshot()
        check(afterAdd.header.zones.size == 2 && afterAdd.header.zones[1].sequence == 1L)
        check(DescriptorCodec.logicalBytes(mainBefore).contentEquals(DescriptorCodec.logicalBytes(afterAdd.header.zones[0])))
        check(mainBefore.logicalMac == afterAdd.header.zones[0].logicalMac && mainBefore.catalogue.hash == afterAdd.header.zones[0].catalogue.hash)
        val decoyBefore = afterAdd.header.zones[1]
        val request = P3Fixtures.mutation(core,0xffffffffL); val token = core.issueMutation(request)
        check(core.submit(token,request).code == WriteCode.Committed)
        check(DescriptorCodec.logicalBytes(core.currentSnapshot().header.zones[1]).contentEquals(DescriptorCodec.logicalBytes(decoyBefore)))
        check(core.currentSnapshot().header.zones[1].catalogue.hash == decoyBefore.catalogue.hash)
        core.close()
        val reopened = FaultStore(store.medium)
        SecretEncoding.text(P3Fixtures.MAIN).use { secret -> ContainerReader(reopened).open(secret).use { session ->
            check(session.view.settings!!.sortMode == 0xffffffffL && session.view.settings.displaySort == 1L)
            ContainerReader(reopened).verifyAll(session.view.snapshot,session.key)
        } }
        SecretEncoding.text(P3Fixtures.DECOY).use { secret -> ContainerReader(reopened).open(secret).use { session ->
            check(session.view.descriptor.role == 2 && session.view.settings!!.sortMode == 1L)
            ContainerReader(reopened).verifyAll(session.view.snapshot,session.key)
        } }
        reopened.close()
    }
    fun commitUnknownReconciliationAndRevision() {
        var fail = true
        val (store,core) = P3Fixtures.core(P3Fixtures.image(),hook={ if(fail) { fail=false; throw StoreException(IoCode.Eio) } })
        val request = P3Fixtures.mutation(core); val token = core.issueMutation(request)
        check(core.submit(token).code == WriteCode.CommitUnknown)
        check(core.submit(token).code == WriteCode.CommitUnknown)
        rejects { core.issueMutation(request) }
        check(!core.owner.release())
        val resolved = core.reconcile(); check(resolved.code == WriteCode.Committed)
        check(core.submit(token,request) === resolved)
        check(core.submit(token,SettingsMutation(request.expectedZoneSequence,0,1,SettingsData(sortMode=3))).code == WriteCode.InvalidOperationToken)
        val tracker = RevisionTracker(); tracker.edited(); tracker.edited(); tracker.acknowledge(resolved)
        check(tracker.dirty && tracker.acknowledged == 1L)
        val persisted = store.medium.durableBytes(); core.close()
        val (_,restarted) = P3Fixtures.core(persisted)
        check(restarted.submit(token).code == WriteCode.UnknownOperation && restarted.appliedHint(token.opId) != null)
        val live = restarted.issueMutation(P3Fixtures.mutation(restarted))
        for(i in 0 until 256) restarted.issueMutation(P3Fixtures.mutation(restarted))
        check(restarted.submit(live).code == WriteCode.UnknownOperation)
        restarted.close()
        val (s,c) = P3Fixtures.core(P3Fixtures.image()); val t = c.issueMutation(P3Fixtures.mutation(c))
        s.resetTrace(); s.observer = { e -> if(e.action == IoAction.Write && e.offset < 131072) throw StoreException(IoCode.Eio) }
        check(c.submit(t).code == WriteCode.CommitUnknown); s.observer = {}
        check(c.reconcile().code == WriteCode.NotApplied); check(c.submit(t).code == WriteCode.NotApplied); c.close()
    }
    fun recoveryIsolationAndNoPasswordFallback() {
        val original = P3Fixtures.image(true)
        for(role in 1..2) {
            val store = FaultStore(FaultMedium(original)); val reader = ContainerReader(store); val s = reader.snapshot()
            val header = s.headerBytes.copy(); val start = 72 + (2-role)*4096
            java.util.Arrays.fill(header,start,start+4096,0.toByte())
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(start,1); header[start+4] = 0xff.toByte()
            P3Fixtures.rehashHeader(header); store.writeFullyAt(s.slot*65536L,header); store.force()
            rejects { reader.snapshot() }
            P3Fixtures.key(role).use { key ->
                val recovery = reader.recoverSnapshot(s.slot,s.header.generation,role)
                check(reader.openZone(recovery,key).readOnly); reader.verifyAll(recovery,key)
            }
            check(store.medium.bytes().contentEquals(store.medium.durableBytes())); store.close()
        }
        val store = FaultStore(FaultMedium(original)); val reader = ContainerReader(store); val s = reader.snapshot()
        val closedCatalogue = s.header.zones[1].catalogue
        store.medium.corrupt(closedCatalogue.offset.toInt(),0)
        rejects { reader.snapshot() }
        P3Fixtures.key(1).use { check(reader.openZone(reader.recoverSnapshot(s.slot,s.header.generation,1),it).readOnly) }
        store.close()
        val current = FaultStore(FaultMedium(P3Fixtures.image())); val r = ContainerReader(current); val old = r.snapshot()
        P3Fixtures.key(1).use { k -> P3Fixtures.replaceDescriptor(current,old,P3Fixtures.mac(P3Fixtures.descriptor(old.header.zones[0],wrapped=Bytes(ByteArray(48))),k)) }
        SecretEncoding.text(P3Fixtures.MAIN).use { secret ->
            rejects { r.open(secret) }
            r.recover(old.slot,old.header.generation,1,secret).use { check(it.view.readOnly) }
        }
        val bad = r.snapshot().headerBytes.copy(); bad[10] = 99; P3Fixtures.rehashHeader(bad)
        current.writeFullyAt((1-old.slot)*65536L,bad); current.force()
        rejects { r.snapshot() }; rejects { r.recoverSnapshot(old.slot,old.header.generation,1) }; current.close()
    }
    fun lazyVerificationAndUnsupportedSchema() {
        val store = FaultStore(FaultMedium(P3Fixtures.image())); val physical = P3Fixtures.noteGraph(store)
        val reader = ContainerReader(store)
        P3Fixtures.key(1).use { key ->
            val s = reader.snapshot(); check(!reader.openZone(s,key).readOnly); reader.verifyAll(s,key)
            val n = physical.offset.toInt()+80; store.medium.corrupt(n,(store.medium.bytes()[n].toInt() xor 1).toByte())
            val current = reader.snapshot(); check(!reader.openZone(current,key).readOnly)
            rejects { reader.verifyAll(current,key) }
        }; store.close()
        val unknown = FaultStore(FaultMedium(P3Fixtures.image())); P3Fixtures.noteGraph(unknown,true)
        val core = TransactionCore(ResourceOwner(unknown)); core.attach(P3Fixtures.key(1)).also { check(it.readOnly && it.root == null) }
        val t = core.issueMutation(P3Fixtures.mutation(core))
        check(core.submit(t).code == WriteCode.UnsupportedVersion); core.close()
    }
    fun ownerRevokeAndClosingSave() {
        val (queuedStore,queuedCore) = P3Fixtures.core(P3Fixtures.image())
        val checkpoint = queuedCore.issueMutation(P3Fixtures.mutation(queuedCore))
        val other = queuedCore.issueMutation(P3Fixtures.mutation(queuedCore))
        queuedCore.owner.revoke(checkpoint.permit)
        check(queuedCore.submit(other).code == WriteCode.AccessLost)
        rejects { queuedCore.issueMutation(P3Fixtures.mutation(queuedCore)) }
        check(queuedCore.submit(checkpoint).code == WriteCode.Committed)
        check(queuedCore.owner.accessRevoked && queuedCore.submit(checkpoint).code == WriteCode.AccessLost)
        rejects { queuedCore.currentSnapshot() }
        P3Fixtures.key(1).use { key ->
            val reader = ContainerReader(queuedStore)
            check(reader.verifyAll(reader.snapshot(),key).settings!!.sortMode == 2L)
        }
        queuedCore.close(); check(queuedStore.closeCount == 1)
        for(phase in 0..3) for(closing in listOf(false,true)) {
            val delegate = FaultStore(FaultMedium(P3Fixtures.image()))
            val entered = CountDownLatch(1); val release = CountDownLatch(1); var armed = false
            val blocked = object : RandomAccessStore by delegate {
                private var forces = 0
                private fun block() {
                    if(!armed) return
                    armed = false; entered.countDown(); var interrupted = false
                    while(true) try { release.await(); break } catch(_: InterruptedException) { interrupted = true }
                    if(interrupted) Thread.currentThread().interrupt()
                }
                override fun appendAligned(bytes: ByteArray): Long {
                    val old = size; val start = aligned(old)
                    if(start > old) writeFullyAt(old,ByteArray((start-old).toInt()))
                    writeFullyAt(start,bytes); return start
                }
                override fun writeFullyAt(offset: Long,bytes: ByteArray) {
                    if((phase == 0 && offset >= 131072) || (phase == 2 && offset < 131072)) block()
                    delegate.writeFullyAt(offset,bytes)
                }
                override fun force() { forces++; if((phase == 1 && forces == 1) || (phase == 3 && forces == 2)) block(); delegate.force() }
            }
            val owner = ResourceOwner(blocked); val core = TransactionCore(owner); core.attach(P3Fixtures.key(1))
            val token = core.issueMutation(P3Fixtures.mutation(core)); val result = AtomicReference<WriteOutcome>()
            armed = true; val thread = Thread { result.set(core.submit(token)) }.apply { isDaemon = true }; thread.start()
            check(entered.await(10,TimeUnit.SECONDS)); owner.revoke(if(closing) token.permit else null)
            thread.interrupt(); check(!owner.awaitQuiescent(5)); check(!owner.release() && delegate.closeCount == 0)
            rejects { owner.accept() }; rejects { ResourceOwner(FaultStore(delegate.medium,delegate.identity)) }
            release.countDown(); thread.join(10000); check(!thread.isAlive && owner.accessRevoked)
            val expected = if(closing) WriteCode.Committed else if(phase >= 2) WriteCode.CommitUnknown else WriteCode.AccessLost
            check(result.get().code == expected) { "phase=$phase closing=$closing code=${result.get().code}" }
            if(expected == WriteCode.CommitUnknown) { check(!owner.release()); check(core.reconcile().code == WriteCode.Committed) }
            check(owner.phase == OwnerPhase.Quiescent && owner.release() && delegate.closeCount == 1)
            check(owner.release() && delegate.closeCount == 1)
        }
    }
    fun namespaceAndLocalStateDurability() {
        val ns = FaultNamespace(); val store = ns.create("first")
        store.writeFullyAt(0,ByteArray(131072)); store.force(); ns.crash(); check(!ns.exists("first")); store.close()
        rejects { FaultNamespace(false).create("denied") }
        val created = ns.create("second"); val core = TransactionCore(ResourceOwner(created))
        check(core.submit(core.issueCreate(P3Fixtures.prepared(1))).code == WriteCode.Committed); core.close(); ns.crash()
        check(ns.exists("second")); ns.open("second").use { check(ContainerReader(it).snapshot().header.generation == 1L) }
        fun fp(name: String, n: Int) = DocumentFingerprint("synthetic:///$name",P3Fixtures.container,P3Fixtures.id(n),n.toLong(),131072,P3Fixtures.id(n,32),P3Fixtures.id(n+1,32))
        val source = fp("source",1); val candidate = fp("candidate",2); val backup = fp("backup",3)
        val old = LocalState(source,null)
        val stages = JournalStage.entries
        for(stage in stages) {
            val next = LocalState(if(stage == JournalStage.Activated) candidate else source,
                StateJournal(P3Fixtures.id(50),"synthetic",listOf(P3Fixtures.id(1)),source,backup,candidate,stage,source))
            val encoded = LocalStateCodec.encode(next); check(LocalStateCodec.encode(LocalStateCodec.decode(encoded)).contentEquals(encoded))
            for(point in LocalPoint.entries) for(moment in FaultMoment.entries) for(retain in listOf(false,true)) {
                val local = FaultLocalStateStore(old); local.fault = LocalFault(point,moment,1)
                val outcome = local.replace(next); check(outcome != LocalWriteCode.Committed)
                local.crash(retain); val bytes = local.bytes()
                check(bytes.contentEquals(LocalStateCodec.encode(old)) || bytes.contentEquals(encoded))
            }
            val local = FaultLocalStateStore(old); check(local.replace(next) == LocalWriteCode.Committed); local.crash()
            check(local.bytes().contentEquals(encoded))
        }
        val corrupt = LocalStateCodec.encode(old); corrupt[15] = (corrupt[15].toInt() xor 1).toByte(); rejects { LocalStateCodec.decode(corrupt) }
        rejects { LocalStateCodec.decode(ByteArray(LocalStateCodec.MAX_BYTES+1)) }
        rejects { LocalStateCodec.encode(LocalState(fp("bad",1),StateJournal(P3Fixtures.id(9),"synthetic",listOf(P3Fixtures.id(1)),source,null,null,JournalStage.Activated,source))) }
        val local = FaultLocalStateStore(old); local.fault = LocalFault(LocalPoint.Ack,FaultMoment.Before)
        check(local.replace(LocalState(candidate,null)) == LocalWriteCode.StateUnknown)
        check(local.replace(old) == LocalWriteCode.StateUnknown && local.read().binding!!.uri == candidate.uri)
        local.fault = null; check(local.reconcile().binding!!.uri == candidate.uri && !local.reconciliationRequired)
    }
    fun catalogueSplitsAndLimits() {
        val store = FaultStore(FaultMedium(P3Fixtures.image())); val s = ContainerReader(store).snapshot(); var root = s.header.zones[0].catalogue
        val model = java.util.TreeMap<Bytes,PhysicalRef>(); model.putAll(s.catalogues[0])
        val catalogue = CowCatalogue(store,P3Fixtures.id(1)); val crypto = RecordCrypto()
        P3Fixtures.key(1).use { key ->
            repeat(140) {
                val record = crypto.encrypt(key,3,key.zoneId,0,SettingsCodec.encode(SettingsData()),RecordIdReservation { it !in model })
                val p = PhysicalRef(store.appendAligned(record.raw.copy()),record.raw.size.toLong(),record.reference.hash)
                model[record.reference.recordId] = p; root = catalogue.insert(root,record.reference.recordId,p)
            }
        }
        val actual = java.util.TreeMap<Bytes,PhysicalRef>()
        fun walk(ref: PhysicalRef) {
            val bytes = store.readFullyAt(ref.offset,16384); check(sha(bytes) == ref.hash)
            val p = CatalogueCodec.decode(bytes,store.size)
            for(e in p.entries) if(p.level == 0) check(actual.put(e.key,e.record!!) == null) else walk(e.child!!)
        }
        walk(root); check(actual.keys == model.keys); for(id in model.keys) check(actual[id]!!.hash == model[id]!!.hash)
        val first = model.firstEntry()!!; rejects { catalogue.insert(root,first.key,first.value) }
        store.close()
        for(counter in listOf("generation","sequence")) {
            val image = P3Fixtures.image(); val (st,core) = P3Fixtures.core(image); core.close()
            val raw = FaultStore(st.medium); val previous = ContainerReader(raw).snapshot(); val d = previous.header.zones[0]
            if(counter == "generation") {
                val h = previous.header; raw.writeFullyAt((1-previous.slot)*65536L,SuperblockCodec.encode(Superblock(Long.MAX_VALUE,h.committedLength,h.containerId,h.instanceId,h.zones))); raw.force()
                val c = TransactionCore(ResourceOwner(raw)); c.attach(P3Fixtures.key(1)); raw.resetTrace()
                check(c.submit(c.issueMutation(P3Fixtures.mutation(c))).code == WriteCode.Limit)
                check(raw.events.none { it.action == IoAction.Write }); c.close()
            } else {
                P3Fixtures.sequence(raw,Long.MAX_VALUE)
                val c = TransactionCore(ResourceOwner(raw)); c.attach(P3Fixtures.key(1)); raw.resetTrace()
                check(c.submit(c.issueMutation(P3Fixtures.mutation(c))).code == WriteCode.Limit)
                check(raw.events.none { it.action == IoAction.Write }); c.close()
            }
        }
    }
    fun boundedStoreAndConflict() {
        val store = FaultStore(); rejects { store.writeFullyAt(Long.MAX_VALUE,byteArrayOf(1)) }; rejects { store.readFullyAt(-1,1) }
        rejects { aligned(Limits.FILE_MAX) }; rejects { endAt(Limits.FILE_MAX,1) }
        val bytes = ByteBuffer.allocate(17); var calls = 0
        FullIo.transfer(bytes,0) { b,_ -> val n = minOf(3,b.remaining()); repeat(n) { b.put(1) }; calls++; n }
        check(calls == 6); rejects { FullIo.transfer(ByteBuffer.allocate(1),0) { _,_ -> 0 } }
        rejects { FullIo.transfer(ByteBuffer.allocate(1),0) { _,_ -> -1 } }; store.close()
        val (s,c) = P3Fixtures.core(P3Fixtures.image()); val token = c.issueMutation(P3Fixtures.mutation(c))
        s.appendAligned(byteArrayOf(0)); check(c.submit(token).code == WriteCode.Conflict && c.owner.accessRevoked); c.close()
        val denied = FaultStore(guarantees=StoreGuarantees(true,false)); val core = TransactionCore(ResourceOwner(denied))
        rejects { core.issueCreate(P3Fixtures.prepared(1)) }; check(denied.size == 0L); core.close()
    }
}
