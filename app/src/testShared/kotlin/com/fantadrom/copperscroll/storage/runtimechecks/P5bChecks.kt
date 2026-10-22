package com.fantadrom.copperscroll.storage.runtimechecks

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.cards.*
import java.security.MessageDigest
import java.io.OutputStream

/** Independent test reader. Does not use RekeyBuilder/RekeyEdges or its ID correspondence. */
object P5bOracle {
    const val NEW = "P5b synthetic new e\u0301"
    fun old(role: Int) = if(role == 1) P3Fixtures.MAIN else P3Fixtures.DECOY
    fun refs(reader: ContainerReader,s: ReadSnapshot,key: ZoneKey): List<LogicalRef> {
        val result = ArrayList<LogicalRef>(); val seen = HashSet<Bytes>()
        fun visit(ref: LogicalRef) {
            check(seen.add(ref.recordId)); result.add(ref)
            val bytes = reader.readRecord(s,key,ref)
            try {
                when(ref.kind) {
                    1 -> RootCodec.decode(bytes,key.zoneId).let { visit(it.objects); visit(it.children); visit(it.settings) }
                    2 -> IndexCodec.decode(bytes,key.zoneId).entries.forEach { it.value?.let(::visit) }
                    3 -> when(StrictCbor.decode(bytes).arr().size) {
                        8 -> SettingsCodec.decode(bytes)
                        14 -> when(val p = ObjectCodec.decode(bytes,ref.ownerId,containerMinor=s.header.minor).payload) {
                            is NotePayload -> p.chunks.forEach(::visit)
                            is CardPayload -> p.chunks.forEach(::visit)
                            is MediaPayload -> visit(p.chunks)
                            else -> Unit
                        }
                        2 -> ChunkListCodec.decode(bytes,ref.ownerId).entries.forEach { visit(it.ref) }
                        else -> error("Unexpected supported fixture schema")
                    }
                }
            } finally { bytes.fill(0) }
        }
        visit(s.header.zones.single { it.zoneId == key.zoneId }.root); return result
    }
    fun logical(bytes: ByteArray,secret: String): List<Any?> = FaultStore(FaultMedium(bytes,128*1024*1024)).use { store ->
        val reader = ContainerReader(store); SecretEncoding.text(secret).use { s1 -> reader.open(s1).use { opened ->
            val s = reader.snapshot(); val view = reader.verifyAll(s,opened.key); val root = view.root!!
            val values = arrayListOf<Any?>(root.normalRoot,root.trashRoot,root.nextPinSequence,root.recentOperations,Bytes(SettingsCodec.encode(view.settings!!)))
            for(o in view.objects.values.sortedBy { it.id }) {
                // Raw metadata, not displayIcon/displaySort or UI fallback.
                values.add(listOf(o.id,o.type,o.parent,o.name,o.colorArgb,o.icon,o.pinned,o.pinSequence,
                    o.createdAt,o.contentModifiedAt,o.metadataModifiedAt,o.revision))
                when(val p = o.payload) {
                    is NotePayload -> { values.add(listOf(p.bodyBytes,p.cursorFocusUtf16)); values.add(Bytes(reader.readDocument(s,opened.key,o.id,p.chunks,p.bodyBytes,Limits.NOTE))) }
                    is CardPayload -> { values.add(listOf(p.bodyBytes,p.useAsUserTemplate)); values.add(Bytes(reader.readDocument(s,opened.key,o.id,p.chunks,p.bodyBytes,CardLimits.BODY,true))) }
                    is MediaPayload -> {
                        val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
                        MediaStreams.copy({ reader.readRecord(s,opened.key,it) },{},o.id,p,object: OutputStream() {
                            override fun write(b: Int) { digest.update(b.toByte()); length++ }
                            override fun write(b: ByteArray,off: Int,len: Int) { digest.update(b,off,len); length += len }
                        }); values.add(listOf(p.mime,p.byteLength,p.chunkCount,length,Bytes(digest.digest())))
                    }
                    else -> Unit
                }
            }
            values
        } }
    }
    fun oldKeyRejection(bytes: ByteArray,secret: String,role: Int): Int = FaultStore(FaultMedium(bytes,128*1024*1024)).use { store ->
        val reader = ContainerReader(store); SecretEncoding.text(secret).use { s1 -> reader.open(s1).use { opened ->
            val s = reader.snapshot(); reader.verifyAll(s,opened.key)
            P3Fixtures.key(role).use { old ->
                val refs = refs(reader,s,opened.key)
                for(ref in refs) {
                    reader.readRecord(s,opened.key,ref).fill(0) // valid current ref/hash/owner/AAD and positive AEAD first
                    val physical = s.catalogues[role-1].getValue(ref.recordId)
                    var rejected = false
                    try { RecordCrypto().decrypt(old,store.readFullyAt(physical.offset,physical.length.toInt()),ref).use { } }
                    catch(e: CryptoException) { check(e.failure == CryptoFailure.CorruptData); rejected = true }
                    check(rejected)
                }
                check(refs.isNotEmpty()); refs.size
            }
        } }
    }
    fun confirmed(f: P5aFixture,c: MaintenanceCoordinator,role: Int,new: String = NEW): MaintenanceConfirmation {
        val r = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(role),3,parameters=KdfParameters(16384,2))
        return SecretEncoding.text(old(role)).use { old -> SecretEncoding.text(new).use { next -> c.confirm(c.credentials(r,old,next,next)) } }
    }
    fun success(r: MaintenanceResult) { check(r.code == MaintenanceCode.MaintenanceCommitted) { "${r.code}: ${r.failure?.stackTraceToString()}" } }
    fun reject(block: ()->Unit) { try { block() } catch(_: Exception) { return }; error("Expected rejection") }
    fun core(docs: P5aDocuments,artifact: Artifact,local: LocalStateStore,secret: String): TransactionCore {
        val owner = ResourceOwner(docs.open(artifact))
        try {
            val s = ContainerReader(owner.store).snapshot()
            val key = SecretEncoding.text(secret).use { CredentialManager().login(s.header.containerId,s.header.zones,it).key }
            return TransactionCore.openBound(owner,local,key)
        } catch(e: Throwable) { owner.resolved(); owner.close(); throw e }
    }
}

class P5bChecks {
    fun mainOnlyAndCompatibility() {
        for(minor in 1..2) P5aFixture(P5aImages.rich(false,large=true,minor=minor)).use { f ->
            val before = f.docs.bytes(f.docs.source); val c = f.coordinator(); val r = c.changeSecret(P5bOracle.confirmed(f,c,1))
            P5bOracle.success(r); val bytes = f.docs.bytes(r.candidate!!)
            check(P5bOracle.logical(before,P3Fixtures.MAIN) == P5bOracle.logical(bytes,P5bOracle.NEW))
            check(r.kind == "rekey" && r.targetZoneId == P3Fixtures.id(1) && r.retainedFilesKeepOldProtection && r.locked)
            check(f.docs.creates == 2 && f.docs.handles.all { it.closeCount == 1 })
            FaultStore(FaultMedium(bytes,128*1024*1024)).use { check(ContainerReader(it).snapshot().header.minor == minor) }
            check(P5bOracle.oldKeyRejection(bytes,P5bOracle.NEW,1) > 10)
        }
    }
    fun mainRekeyPreservesDecoy() = twoZones(1)
    fun decoyRekeyPreservesMain() = twoZones(2)
    private fun twoZones(role: Int) = P5aFixture(P5bImages.rich()).use { f ->
        val before = f.docs.bytes(f.docs.source); val c = f.coordinator(); val confirmation = P5bOracle.confirmed(f,c,role)
        val r = c.changeSecret(confirmation); P5bOracle.success(r); P5bOracle.reject { c.changeSecret(confirmation) }
        val after = f.docs.bytes(r.candidate!!)
        check(P5bOracle.logical(before,P5bOracle.old(role)) == P5bOracle.logical(after,P5bOracle.NEW))
        val other = 3-role; check(P5bOracle.logical(before,P5bOracle.old(other)) == P5bOracle.logical(after,P5bOracle.old(other)))
        check(f.docs.bytes(r.backup!!.artifact).contentEquals(before) && f.docs.bytes(f.docs.source).contentEquals(before))
        P5bOracle.logical(f.docs.bytes(r.backup!!.artifact),P5bOracle.old(role))
        FaultStore(FaultMedium(before,128*1024*1024)).use { source -> FaultStore(FaultMedium(after,128*1024*1024)).use { candidate ->
            val s = ContainerReader(source).snapshot(); val a = ContainerReader(candidate).snapshot()
            check(candidate.readFullyAt(0,65536).contentEquals(candidate.readFullyAt(65536,65536)))
            check(a.header.generation == s.header.generation+1 && a.header.instanceId != s.header.instanceId)
            check(a.header.zones[role-1].sequence == s.header.zones[role-1].sequence+1)
            check(a.catalogues[role-1].keys.intersect(s.catalogues.flatMap { it.keys }.toSet()).isEmpty())
            check(a.catalogues[other-1].keys == s.catalogues[other-1].keys)
            for((id,p) in s.catalogues[other-1]) {
                val q = a.catalogues[other-1].getValue(id)
                check(source.readFullyAt(p.offset,p.length.toInt()).contentEquals(candidate.readFullyAt(q.offset,q.length.toInt())))
            }
            check(DescriptorCodec.logicalBytes(s.header.zones[other-1]).contentEquals(DescriptorCodec.logicalBytes(a.header.zones[other-1])))
            SecretEncoding.text(P5bOracle.old(role)).use { secret -> P5bOracle.reject { ContainerReader(candidate).open(secret).close() } }
        } }
        P5bOracle.oldKeyRejection(after,P5bOracle.NEW,role)
    }
    fun credentialsAndNoArtifacts() {
        val image = P4Fixtures.image(true)
        val cases = listOf(Triple("wrong",P5bOracle.NEW,P5bOracle.NEW) to MaintenanceCode.CredentialsRejected,
            Triple(P3Fixtures.MAIN,P5bOracle.NEW,P5bOracle.NEW) to MaintenanceCode.CredentialsRejected,
            Triple(P3Fixtures.DECOY,P5bOracle.NEW,"different") to MaintenanceCode.SecretMismatch,
            Triple(P3Fixtures.DECOY,P3Fixtures.DECOY,P3Fixtures.DECOY) to MaintenanceCode.UnchangedSecret,
            Triple(P3Fixtures.DECOY,P3Fixtures.MAIN,P3Fixtures.MAIN) to MaintenanceCode.CredentialsRejected)
        for((inputs,code) in cases) P5aFixture(image).use { f ->
            val c = f.coordinator(); val r = c.beginChangeSecret(f.core.authorizeMaintenance(),f.destinations,P3Fixtures.id(2),3,parameters=KdfParameters(16384,2))
            SecretEncoding.text(inputs.first).use { old -> SecretEncoding.text(inputs.second).use { new -> SecretEncoding.text(inputs.third).use { repeat ->
                try { c.credentials(r,old,new,repeat); error("Expected credentials refusal") }
                catch(e: MaintenanceRefusal) { check(e.code == code) }
            } } }
            check(f.docs.creates == 0 && f.docs.bytes(f.docs.source).contentEquals(image) && f.local.read().journal == null)
            try { c.credentials(r,null,null,null); error("Expected cancellation") } catch(e: MaintenanceRefusal) { check(e.code == MaintenanceCode.Cancelled) }
        }
        P5aFixture(image,2).use { f ->
            val c = f.coordinator(); val checks = f.docs.checks
            P5bOracle.reject { c.beginChangeSecret(TransactionCore.MaintenanceCapability(),f.destinations,P3Fixtures.id(2),3) }
            check(f.docs.checks == checks && f.docs.creates == 0)
        }
        P5bOracle.reject { SecretEncoding.text("\u0000") }
    }
    fun oldKeyCannotReadRekeyOrFutureTransactions() {
        for(role in 1..2) P5aFixture(P5aImages.rich()).use { f ->
        val c = f.coordinator(); val r = c.changeSecret(P5bOracle.confirmed(f,c,role)); P5bOracle.success(r)
        val candidate = r.candidate!!; P5bOracle.oldKeyRejection(f.docs.bytes(candidate),P5bOracle.NEW,role)
        P5bOracle.core(f.docs,candidate,f.local,P5bOracle.NEW).use { core ->
            val repo = ZoneRepository(core); val root = repo.roots().first
            P4Fixtures.committed(repo,repo.issueSaveNote(repo.stamp(),P3Fixtures.id(170),root,"later",P4Fixtures.note("synthetic later"),now=500))
            P4Fixtures.committed(repo,repo.issueSaveCard(repo.stamp(),P3Fixtures.id(171),root,"later card",P4Fixtures.card(),now=501))
            P4Fixtures.committed(repo,repo.issueMedia(repo.stamp(),P3Fixtures.id(172),root,"later media","image/png",P4Fixtures.generatedSource(2L*Limits.CHUNK+31),now=502))
        }
        val later = f.docs.bytes(candidate); check(P5bOracle.oldKeyRejection(later,P5bOracle.NEW,role) > 15)
        P5bOracle.core(f.docs,candidate,f.local,P5bOracle.NEW).use { core ->
            val repo = ZoneRepository(core); check(repo.readNote(P3Fixtures.id(170)).body.text == "synthetic later")
            check(repo.readCard(P3Fixtures.id(171)).body.fields.size == 1)
            val digest = MessageDigest.getInstance("SHA-256")
            repo.copyMedia(P3Fixtures.id(172),object: OutputStream() {
                override fun write(b: Int) { digest.update(b.toByte()) }
                override fun write(b: ByteArray,off: Int,len: Int) { digest.update(b,off,len) }
            }); check(Bytes(digest.digest()) == P4Fixtures.generatedDigest(2L*Limits.CHUNK+31))
        }
    }
    }
    fun revokeSwitchAndLease() {
        for(after in listOf(false,true)) P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator { step -> if(step == MaintenanceStep.AcceptSwitch) {
                f.docs.store(f.docs.source).use { attempted -> P5bOracle.reject { ResourceOwner(attempted) } }
                if(!after) f.core.owner.revoke() else f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.After)
            } }
            val r = c.changeSecret(P5bOracle.confirmed(f,c,2))
            check(r.code == if(after) MaintenanceCode.SwitchUnknown else MaintenanceCode.Cancelled)
            check(r.locked && f.core.owner.accessRevoked)
            if(after) check(f.local.read().journal!!.stage == JournalStage.Activated)
            else check(f.local.read().binding!!.uri == f.docs.source.uri)
        }
    }
    fun restartWithDistinctCredentialContexts() {
        for(role in 1..2) P5aFixture(P4Fixtures.image(true)).use { f ->
            val c = f.coordinator { if(it == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.After) }
            val r = c.changeSecret(P5bOracle.confirmed(f,c,role)); check(r.code == MaintenanceCode.SwitchUnknown)
            f.core.owner.resolved(); f.core.close(); f.docs.crash(); f.local.crash()
            val creates = f.docs.creates
            SecretEncoding.text(P3Fixtures.MAIN).use { main -> SecretEncoding.text(P5bOracle.old(role)).use { old -> SecretEncoding.text(P5bOracle.NEW).use { new ->
                val source = mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(role) to old)
                check(MaintenanceRecovery().resolveChangeSecret(f.local,f.docs,RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,emptyMap())).code == RecoveryCode.NeedsCredentials)
                val wrong = source
                check(MaintenanceRecovery().resolveChangeSecret(f.local,f.docs,RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,wrong)).code == RecoveryCode.CredentialsRejected)
                val candidate = mapOf(P3Fixtures.id(1) to if(role == 1) new else main,P3Fixtures.id(role) to new)
                check(MaintenanceRecovery().resolveChangeSecret(f.local,f.docs,RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,candidate)).code == RecoveryCode.CandidateConfirmed)
                check(f.docs.creates == creates)
            } } }
            P5bOracle.logical(f.docs.bytes(r.candidate!!),P5bOracle.NEW)
        }
    }
    fun preferredInputAndRepeatedChange() = P5aFixture(P4Fixtures.image()).use { f ->
        val unchanged = f.docs.bytes(f.docs.source)
        val memory = object: NoBackupInputPreferences {
            var method: Int? = null
            override fun read(container: Bytes,zone: Bytes) = method
            override fun write(container: Bytes,zone: Bytes,method: Int) { this.method = method }
        }
        PreferredInputMethods.set(memory,P3Fixtures.container,P3Fixtures.id(1),1)
        check(memory.method == 1 && f.docs.creates == 0 && f.docs.bytes(f.docs.source).contentEquals(unchanged))
        SecretEncoding.pin4("0012").use { pin -> SecretEncoding.text("0012").use { text -> check(pin.sameAs(text)) } }
        val c = f.coordinator(); val first = c.changeSecret(P5bOracle.confirmed(f,c,1)); P5bOracle.success(first)
        P5bOracle.core(f.docs,first.candidate!!,f.local,P5bOracle.NEW).use { core ->
            val next = MaintenanceCoordinator(core,f.docs,f.local)
            val dest = MaintenanceDestinations(first.candidate,"synthetic:/backup","synthetic:/candidate")
            val r = next.beginChangeSecret(core.authorizeMaintenance(),dest,P3Fixtures.id(1),3,parameters=KdfParameters(16384,2))
            SecretEncoding.text("P5b synthetic new é").use { old ->
                try { next.credentials(r,old,old,old); error("Expected same-S1 rejection") }
                catch(e: MaintenanceRefusal) { check(e.code == MaintenanceCode.UnchangedSecret && f.docs.creates == 2) }
                SecretEncoding.text("second synthetic secret").use { second ->
                    val result = next.changeSecret(next.confirm(next.credentials(r,old,second,second))); P5bOracle.success(result)
                    check(f.docs.creates == 4); P5bOracle.logical(f.docs.bytes(result.candidate!!),"second synthetic secret")
                }
            }
        }
    }
}
