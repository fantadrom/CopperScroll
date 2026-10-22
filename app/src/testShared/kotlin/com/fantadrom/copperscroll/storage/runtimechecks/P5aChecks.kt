package com.fantadrom.copperscroll.storage.runtimechecks

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.cards.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Namespace and IO are separate fault models. Only synthetic images and secrets. */
class P5aDocuments(val source: Artifact = Artifact("synthetic:/source",StoreIdentity("p5a",UUID.randomUUID().toString()))) : MaintenanceDocuments {
    override val profile = MaintenanceProfile.Synthetic
    val media = LinkedHashMap<String,Pair<Artifact,FaultMedium>>()
    val durableNames = HashSet<String>()
    val handles = ArrayList<FaultStore>()
    var creates = 0; var opens = 0; var checks = 0; var eventCount = 0
    var allowed = true; var free: Long? = Long.MAX_VALUE
    var onEvent: (Artifact,FaultStore,IoEvent,Int)->Unit = { _,_,_,_-> }
    var onCreate: (Artifact)->Unit = {}
    var reopenFailure = false
    var identitySupported = true
    var alias: Artifact? = null
    fun load(bytes: ByteArray) { media[source.uri] = source to FaultMedium(bytes,128*1024*1024); durableNames.add(source.uri) }
    fun store(a: Artifact): FaultStore {
        val entry = media[a.uri] ?: throw StoreException(IoCode.AccessLost)
        requireIo(entry.first.identity == a.identity,IoCode.AccessLost)
        lateinit var store: FaultStore
        store = FaultStore(entry.second,a.identity,StoreGuarantees(true,identitySupported)) { durableNames.add(a.uri) }
        store.observer = { e -> onEvent(a,store,e,eventCount++) }; handles.add(store); return store
    }
    override fun checkSource(source: Artifact) { checks++; requireIo(allowed,IoCode.AccessLost); checkIdentity(source) }
    override fun checkDestination(destination: String) { checks++; requireIo(allowed,IoCode.AccessLost); require(destination in listOf("synthetic:/backup","synthetic:/candidate")) }
    override fun availableBytes(destination: String) = free
    override fun createNew(destination: String,name: String): Pair<Artifact,RandomAccessStore> {
        checkDestination(destination); requireIo(identitySupported,IoCode.UnsupportedDurability)
        creates++
        alias?.let { return it to store(it) }
        val uri = "$destination/$name"; check(uri !in media)
        val artifact = Artifact(uri,StoreIdentity("p5a",UUID.randomUUID().toString()))
        media[uri] = artifact to FaultMedium(limit=128*1024*1024); onCreate(artifact)
        return artifact to store(artifact)
    }
    override fun open(artifact: Artifact): RandomAccessStore { opens++; requireIo(!reopenFailure,IoCode.Eio); requireIo(allowed,IoCode.AccessLost); return store(artifact) }
    override fun checkIdentity(artifact: Artifact) {
        requireIo(allowed && artifact.uri in media,IoCode.AccessLost)
        refuse(media[artifact.uri]!!.first.identity == artifact.identity,MaintenanceCode.Conflict)
    }
    override fun deleteOwned(artifact: Artifact) { checkIdentity(artifact); check(artifact !== source); media.remove(artifact.uri); durableNames.remove(artifact.uri) }
    fun bytes(a: Artifact) = media.getValue(a.uri).second.bytes()
    fun crash() {
        media.values.forEach { it.second.crash() }
        media.keys.retainAll(durableNames)
    }
}
class P5aFixture(image: ByteArray, role: Int = 1) : AutoCloseable {
    val docs = P5aDocuments().also { it.load(image) }
    val store = docs.store(docs.source)
    val core = TransactionCore(ResourceOwner(store)).also { it.attach(P3Fixtures.key(role)) }
    val destinations = MaintenanceDestinations(docs.source,"synthetic:/backup","synthetic:/candidate")
    val local = FaultLocalStateStore(LocalState(fingerprint(store,docs.source.uri),null))
    fun coordinator(progress: (MaintenanceStep)->Unit = {}) = MaintenanceCoordinator(core,docs,local,progress)
    fun confirmed(c: MaintenanceCoordinator,selected: Set<Bytes>? = null): MaintenanceConfirmation {
        val r = c.begin(core.authorizeMaintenance(),destinations,selected)
        return SecretEncoding.text(P3Fixtures.DECOY).use { secret -> c.confirm(c.credentials(r,secret)) }
    }
    override fun close() { core.owner.resolved(); core.close() }
}
object P5aImages {
    /** Every zone has live/trash/deleted and obsolete records; P4 objects use real VFS/T. */
    fun rich(two: Boolean = true, large: Boolean = false, minor: Int = 2): ByteArray {
        var image = P4Fixtures.fresh(minor,two).use { it.store.medium.durableBytes() }
        for(role in 1..if(two) 2 else 1) {
            image = P4Fixtures.open(image,role).use { f ->
                val r = f.repo; val root = r.roots().first
                fun done(t: OperationToken) { P4Fixtures.committed(r,t) }
                val folder = P3Fixtures.id(101); done(r.issueCreateFolder(r.stamp(),root,"folder",id=folder,now=10))
                val deleted = P3Fixtures.id(102); done(r.issueSaveNote(r.stamp(),deleted,folder,"gone",P4Fixtures.note("obsolete"),now=11))
                done(r.issueDelete(r.stamp(),listOf(r.handle(deleted)),now=12)); done(r.issueClearTrash(r.stamp()))
                val trash = P3Fixtures.id(103); done(r.issueSaveNote(r.stamp(),trash,root,"trash",P4Fixtures.note("retained"),now=13))
                done(r.issueDelete(r.stamp(),listOf(r.handle(trash)),now=14))
                val note = P3Fixtures.id(104); done(r.issueSaveNote(r.stamp(),note,folder,"note",NoteBody(listOf(NoteParagraph("e\u0301🙂"),NoteParagraph("",false)),emptyList()),now=15))
                done(r.issueSaveNote(r.stamp(),note,folder,"updated",NoteBody(listOf(NoteParagraph("e\u0301🙂"),NoteParagraph("checked",true)),emptyList()),expectedObjectRevision=r.handle(note).revision,now=16))
                if(minor == 2) {
                    val fields = listOf(CardField(P3Fixtures.id(11),1,"masked",TextParameters("0000 0000"),TextValue("00123456")),
                        CardField(P3Fixtures.id(12),6,"date",DateParameters("mm/yy"),DateValue(CardDate(2026,10,21))),
                        CardField(P3Fixtures.id(13),3,"hidden",TextParameters(),TextValue("synthetic")))
                    done(r.issueSaveCard(r.stamp(),P3Fixtures.id(105),folder,"card",CardBody(fields),now=17))
                }
                done(r.issueMedia(r.stamp(),P3Fixtures.id(106),folder,"binary","image/png",
                    P4Fixtures.generatedSource(if(large) 3L*Limits.CHUNK+117 else 101),now=18))
                done(r.issueSettings(r.stamp(),SettingsData(sortMode=901,viewMode=902)))
                f.store.medium.durableBytes()
            }
        }
        return image
    }
    /** Independent reader/oracle checks decoded metadata AND actual note/card/media contents. */
    fun logical(bytes: ByteArray,role: Int): List<Any?> = FaultStore(FaultMedium(bytes,128*1024*1024)).use { store ->
        val reader = ContainerReader(store)
        SecretEncoding.text(if(role == 1) P3Fixtures.MAIN else P3Fixtures.DECOY).use { secret -> reader.open(secret).use { session ->
            val s = reader.snapshot(); val view = reader.verifyAll(s,session.key)
            val result = ArrayList<Any?>()
            result.add(Bytes(RootCodec.encode(view.root!!,session.key.zoneId))); result.add(Bytes(SettingsCodec.encode(view.settings!!)))
            for(o in view.objects.values.sortedBy { it.id }) {
                result.add(Bytes(ObjectCodec.encode(o,s.header.minor)))
                when(val p = o.payload) {
                    is NotePayload -> result.add(Bytes(reader.readDocument(s,session.key,o.id,p.chunks,p.bodyBytes,Limits.NOTE)))
                    is CardPayload -> result.add(Bytes(reader.readDocument(s,session.key,o.id,p.chunks,p.bodyBytes,CardLimits.BODY,true)))
                    is MediaPayload -> {
                        val d = java.security.MessageDigest.getInstance("SHA-256"); var n = 0L
                        MediaStreams.copy({ reader.readRecord(s,session.key,it) },{},o.id,p,object: java.io.OutputStream() {
                            override fun write(b: Int) { d.update(b.toByte()); n++ }
                            override fun write(b: ByteArray,off: Int,len: Int) { d.update(b,off,len); n += len }
                        }); result.add(n); result.add(Bytes(d.digest()))
                    }
                    else -> Unit
                }
            }
            result
        } }
    }
}
class P5aChecks {
    private fun reject(block: ()->Unit) { var caught = false; try { block() } catch(_: Exception) { caught = true }; check(caught) }
    fun selectiveZonesAndIndependentReaders() {
        val image = P5aImages.rich()
        val logical = (1..2).map { P5aImages.logical(image,it) }
        for(selection in listOf(setOf(P3Fixtures.id(1)),setOf(P3Fixtures.id(2)),null)) P5aFixture(image).use { f ->
            val c = f.coordinator(); val confirmation = f.confirmed(c,selection); val result = c.compact(confirmation)
            reject { c.compact(confirmation) }
            check(result.code == MaintenanceCode.MaintenanceCommitted) { "${result.code}: ${result.failure?.stackTraceToString()}" }
            check(f.docs.creates == 2 && f.core.owner.phase == OwnerPhase.Released && result.locked)
            check(f.docs.handles.all { it.closeCount == 1 })
            val candidate = f.docs.bytes(result.candidate!!)
            for(role in 1..2) check(P5aImages.logical(candidate,role) == logical[role-1])
            check(f.docs.bytes(result.backup!!.artifact).contentEquals(image)); check(f.docs.bytes(f.docs.source).contentEquals(image))
            val original = FaultStore(FaultMedium(image)); val compacted = FaultStore(FaultMedium(candidate))
            original.use { old -> compacted.use { new ->
                val s = ContainerReader(old).snapshot(); val a = ContainerReader(new).snapshot()
                check(a.header.generation == s.header.generation+1 && a.header.instanceId != s.header.instanceId && a.header.minor == 2)
                for(i in 0..1) {
                    check(a.header.zones[i].sequence == s.header.zones[i].sequence)
                    if(P3Fixtures.id(i+1) !in result.selected) check(a.catalogues[i].keys == s.catalogues[i].keys)
                    else check(a.catalogues[i].size < s.catalogues[i].size)
                }
            } }
        }
    }
    fun authorizationCredentialsAndNoEffects() {
        val image = P4Fixtures.image(true)
        P5aFixture(image,2).use { f -> reject { f.core.authorizeMaintenance() }; check(f.docs.creates == 0 && f.docs.checks == 0) }
        P5aFixture(image).use { f ->
            val cap = f.core.authorizeMaintenance(); f.core.owner.revoke()
            reject { f.coordinator().begin(cap,f.destinations) }; check(f.docs.checks == 0)
        }
        P5aFixture(image).use { f ->
            val c = f.coordinator(); reject { c.begin(TransactionCore.MaintenanceCapability(),f.destinations) }; check(f.docs.checks == 0)
            reject { c.begin(f.core.authorizeMaintenance(),f.destinations,emptySet()) }
            reject { c.begin(f.core.authorizeMaintenance(),f.destinations,setOf(P3Fixtures.id(99))) }
            val request = c.begin(f.core.authorizeMaintenance(),f.destinations)
            reject { c.credentials(request) }
            for(text in listOf("wrong synthetic",P3Fixtures.MAIN)) SecretEncoding.text(text).use { secret -> reject { c.credentials(request,secret) } }
            check(f.docs.creates == 0 && f.local.read().journal == null)
            val proposal = SecretEncoding.text(P3Fixtures.DECOY).use { c.credentials(request,it) }
            proposal.close(); reject { c.confirm(proposal) }
        }
    }
    fun committedLengthCopyAndCoverage() {
        val image = P4Fixtures.image(true)
        P5aFixture(image+ByteArray(77) { 91 }).use { f ->
            val copy = f.coordinator().backup(f.core.authorizeMaintenance(),f.destinations)
            check(f.docs.bytes(copy.artifact).contentEquals(image))
            check(copy.coverage[P3Fixtures.id(1)] == VerificationCoverage.AeadReachable)
            check(copy.coverage[P3Fixtures.id(2)] == VerificationCoverage.RelativeToSnapshot)
            check(f.store.size == image.size+77L)
        }
    }
    fun restartAndLostAcknowledgement() {
        val image = P4Fixtures.image(true)
        P5aFixture(image).use { f ->
            val c = f.coordinator { step -> if(step == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(LocalPoint.Ack,FaultMoment.Before) }
            val r = c.compact(f.confirmed(c)); check(r.code == MaintenanceCode.SwitchUnknown && f.core.owner.reconciliationRequired)
            check(f.core.owner.phase != OwnerPhase.Released && !f.core.owner.release())
            reject { ResourceOwner(f.docs.store(f.docs.source)) }
            f.local.fault = null
            check(c.reconcile(RecoveryChoice.Candidate,emptyMap()).code == RecoveryCode.NeedsCredentials)
            val secrets = (1..2).associate { P3Fixtures.id(it) to SecretEncoding.text(if(it == 1) P3Fixtures.MAIN else P3Fixtures.DECOY) }
            try {
                val resolved = c.reconcile(RecoveryChoice.Candidate,secrets)
                check(resolved.code == RecoveryCode.CandidateConfirmed && resolved.locked)
                check(f.local.read().journal!!.outcome == JournalOutcome.Confirmed && resolved.priorOutcome == JournalOutcome.Unreported)
            } finally { secrets.values.forEach { it.close() } }
            check(f.core.owner.phase == OwnerPhase.Released && f.docs.creates == 2)
        }
    }
    fun revokeSwitchAndExclusiveLease() {
        for(before in listOf(true,false)) P5aFixture(P4Fixtures.image()).use { f ->
            val c = f.coordinator { step ->
                if(step == MaintenanceStep.BuildCandidate) reject { ResourceOwner(f.docs.store(f.docs.source)) }
                if(step == MaintenanceStep.AcceptSwitch && before) f.core.owner.revoke()
            }
            val r = c.compact(f.confirmed(c))
            check(r.code == if(before) MaintenanceCode.Cancelled else MaintenanceCode.MaintenanceCommitted)
            check((f.local.read().binding!!.uri == f.docs.source.uri) == before)
        }
        P5aFixture(P4Fixtures.image()).use { f ->
            val c = f.coordinator(); val confirmation = f.confirmed(c)
            val entered = CountDownLatch(1); val resume = CountDownLatch(1); var blocked = false
            f.docs.onEvent = { a,_,e,_ -> if(a.uri.contains("candidate") && e.action == IoAction.Force && !blocked) {
                blocked = true; entered.countDown(); check(resume.await(15,TimeUnit.SECONDS))
            } }
            val result = AtomicReference<MaintenanceResult>(); val worker = Thread { result.set(c.compact(confirmation)) }; worker.start()
            check(entered.await(15,TimeUnit.SECONDS)); f.core.owner.revoke()
            check(!f.core.owner.awaitQuiescent(1) && !f.core.owner.release())
            reject { ResourceOwner(f.docs.store(f.docs.source)) }
            resume.countDown(); worker.join(15000); check(!worker.isAlive)
            check(result.get().code != MaintenanceCode.MaintenanceCommitted && f.core.owner.phase == OwnerPhase.Released)
        }
    }
    fun faultCopyBuildAndJournal() {
        val image = P4Fixtures.image(true)
        for(kind in listOf("backup","candidate")) for(action in listOf(IoAction.Write,IoAction.Force,IoAction.Identity)) P5aFixture(image).use { f ->
            var injected = false
            f.docs.onEvent = { a,store,e,_ -> if(!injected && a.uri.contains(kind) && e.action == action) {
                injected = true; store.fault = IoFault(e.index,FaultMoment.Partial,7,IoCode.NoSpace)
            } }
            val c = f.coordinator(); val r = c.compact(f.confirmed(c))
            check(injected && r.code != MaintenanceCode.MaintenanceCommitted && f.local.read().binding!!.uri == f.docs.source.uri)
            check(f.docs.bytes(f.docs.source).contentEquals(image))
        }
        for(point in LocalPoint.entries) for(moment in listOf(FaultMoment.Before,FaultMoment.After)) P5aFixture(image).use { f ->
            val c = f.coordinator { step -> if(step == MaintenanceStep.AcceptSwitch) f.local.fault = LocalFault(point,moment) }
            val r = c.compact(f.confirmed(c)); check(r.code == MaintenanceCode.SwitchUnknown)
            f.local.crash(); f.docs.crash()
            val state = f.local.read()
            check(state.journal!!.stage in listOf(JournalStage.SwitchIntent,JournalStage.Activated))
            check((state.binding!!.uri != f.docs.source.uri) == (state.journal.stage == JournalStage.Activated))
        }
    }
    fun retentionSizesAndFailures() {
        P5aFixture(P4Fixtures.image(true)).use { f ->
            val cap = f.core.authorizeMaintenance(); val c = f.coordinator()
            val copies = (0..3).map { RetainedCopy(c.backup(cap,f.destinations),it.toLong(),true) }
            check(CopyRetention.removable(copies.take(3),copies.last()).size == 1)
            check(CopyRetention.removable(copies.take(2),copies[2]).isEmpty())
            reject { CopyRetention.removable(copies.take(3),RetainedCopy(copies.last().copy,4,false)) }
            f.docs.free = 1; val r = c.compact(f.confirmed(c)); check(r.code == MaintenanceCode.ResourceLimit && f.docs.creates == 4)
        }
        for(candidate in listOf(99L,100L,101L)) {
            val sizes = MaintenanceSizes(100,candidate,oldBackups=7,scratch=3,partial=2,reserve=1)
            check(sizes.workingReduction == 100-candidate && sizes.retainedTotal == 200+candidate &&
                sizes.growth == 100+candidate && sizes.totalWithExtras == 213+candidate && sizes.allocatedBytes == null)
        }
    }
    fun largeMediaAndMainOnlyCompatibility() {
        for(minor in 1..2) {
            val image = P5aImages.rich(two=false,large=true,minor=minor)
            P5aFixture(image).use { f ->
                val expected = P5aImages.logical(image,1); val c = f.coordinator(); val r = c.compact(f.confirmed(c))
                check(r.code == MaintenanceCode.MaintenanceCommitted) { "${r.code}: ${r.failure?.stackTraceToString()}" }
                check(P5aImages.logical(f.docs.bytes(r.candidate!!),1) == expected)
                check(f.docs.handles.flatMap { it.events }.filter { it.action == IoAction.Write }.all { it.length <= 131072 })
            }
        }
    }
}
