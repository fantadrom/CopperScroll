package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.runtimechecks.*
import java.util.Random
import org.junit.Test

/** S1 is a pure update of the source model below, NEVER a successful production mutation. */
class P4FaultMatrixTest {
    private enum class Command { Folder, Note, Card, MoveBatch, DeleteBatch, Restore, Clear, Pin, Settings }
    private data class Item(val type: Int,val parent: Bytes,val name: String,val created: Long,val content: Long,
        val metadata: Long,val revision: ULong,val pinned: Boolean,val pin: ULong?,val value: Any?)
    private data class State(val generation: Long,val sequence: Long,val counter: ULong,val objects: Map<Bytes,Item>,
        val operations: List<Pair<Bytes,Bytes?>>,val settings: List<Any?>)
    private val a=P3Fixtures.id(70); private val b=P3Fixtures.id(71); private val child=P3Fixtures.id(72)
    private val note=P3Fixtures.id(73); private val card=P3Fixtures.id(74); private val trashed=P3Fixtures.id(75)
    private val newId=P3Fixtures.id(76); private val recovery=P3Fixtures.id(77)
    private val now=9000L; private val seed=328707L; private var cases=0
    private fun cardValue(body: CardBody): List<Any?> = body.fields.map { f ->
        listOf(f.id,f.type,f.name,when(val v=f.value) { is TextValue -> v.text; is BooleanValue -> v.enabled; else -> error("Fixture value") })
    }
    private fun noteValue(body: NoteBody,cursor: Long): List<Any?> = listOf(body.paragraphs.map { listOf(it.text,it.checked) },
        body.styles.map { listOf(it.startUtf16,it.endUtf16,it.bold,it.italic,it.underline,it.foregroundArgb) },cursor)
    private fun newNote() = NoteBody(listOf(NoteParagraph("new🙂"),NoteParagraph("",false)),listOf(NoteStyle(1,4,true)))
    private fun newCard() = CardBody(P4Fixtures.card("new").fields+CardField(P3Fixtures.id(45),10,"switch",NoParameters,BooleanValue(true)))
    private fun settings(s: SettingsData): List<Any?> = listOf(s.rememberFolder,s.lastFolder,s.screenshotsBlocked,s.copyBlocked,s.sortMode,s.sortAscending,s.viewMode)
    private fun state(f: P4Fixtures.Fixture): State = f.core.readZone { snapshot,key,view,reader,_ ->
        val objects=view.objects.mapValues { (_,o) ->
            val value: Any?=when(val p=o.payload) {
                FolderPayload -> null
                is NotePayload -> {
                    val bytes=reader.readDocument(snapshot,key,o.id,p.chunks,p.bodyBytes,Limits.NOTE)
                    try { noteValue(NoteCodec.decode(bytes),p.cursorFocusUtf16) } finally { bytes.fill(0) }
                }
                is CardPayload -> {
                    val bytes=reader.readDocument(snapshot,key,o.id,p.chunks,p.bodyBytes,CardLimits.BODY,true)
                    try { cardValue(CardCodec.decode(bytes)) } finally { bytes.fill(0) }
                }
                is MediaPayload -> listOf(p.mime,p.byteLength,p.chunkCount,p.chunks.recordId,p.chunks.hash)
            }
            Item(o.type,o.parent,o.name,o.createdAt,o.contentModifiedAt,o.metadataModifiedAt,o.revision,o.pinned,o.pinSequence,value)
        }
        State(snapshot.header.generation,view.root!!.sequence,view.root.nextPinSequence,objects,view.root.recentOperations,settings(view.settings!!))
    }
    private fun baseline(): ByteArray = P4Fixtures.fresh(two=true).use { f ->
        val r=f.repo; val root=r.roots().first
        P4Fixtures.committed(r,r.issueCreateFolder(r.stamp(),root,"a",id=a,now=1))
        P4Fixtures.committed(r,r.issueCreateFolder(r.stamp(),root,"b",id=b,now=2))
        P4Fixtures.committed(r,r.issueCreateFolder(r.stamp(),a,"child",id=child,now=3))
        P4Fixtures.committed(r,r.issueSaveNote(r.stamp(),note,root,"old",P4Fixtures.note("old"),now=4))
        P4Fixtures.committed(r,r.issueSaveCard(r.stamp(),card,root,"old",P4Fixtures.card(),now=5))
        P4Fixtures.committed(r,r.issueSaveCard(r.stamp(),trashed,root,"trash",CardBody(),now=6))
        P4Fixtures.committed(r,r.issueDelete(r.stamp(),listOf(r.handle(trashed)),now=7))
        f.store.medium.durableBytes()
    }
    private fun issue(command: Command,f: P4Fixtures.Fixture): OperationToken {
        val r=f.repo; val root=r.roots().first
        return when(command) {
            Command.Folder -> r.issueCreateFolder(r.stamp(),root,"new",id=newId,now=now)
            Command.Note -> r.issueSaveNote(r.stamp(),note,root,"changed",newNote(),3,r.handle(note).revision,now=now)
            Command.Card -> r.issueSaveCard(r.stamp(),card,root,"changed",newCard(),r.handle(card).revision,now=now)
            Command.MoveBatch -> r.issueMove(r.stamp(),listOf(r.handle(a),r.handle(child),r.handle(note),r.handle(a)),b,now=now)
            Command.DeleteBatch -> r.issueDelete(r.stamp(),listOf(r.handle(a),r.handle(child),r.handle(note)),now=now)
            Command.Restore -> r.issueRestoreAll(r.stamp(),now=now,recoveryFolderId=recovery)
            Command.Clear -> r.issueClearTrash(r.stamp(),now=now)
            Command.Pin -> r.issuePin(r.stamp(),r.handle(note),true,now=now)
            Command.Settings -> r.issueSettings(r.stamp(),SettingsData(sortMode=2))
        }
    }
    private fun expected(command: Command,s0: State,root: Bytes,trash: Bytes,token: OperationToken): State {
        val objects=s0.objects.toMutableMap(); var counter=s0.counter; var settings=s0.settings
        fun moved(id: Bytes,destination: Bytes) { val o=objects.getValue(id); objects[id]=o.copy(parent=destination,metadata=now,revision=o.revision+1u) }
        when(command) {
            Command.Folder -> objects[newId]=Item(1,root,"new",now,now,now,1u,false,null,null)
            Command.Note -> { val o=objects.getValue(note); objects[note]=o.copy(name="changed",content=now,metadata=now,revision=o.revision+1u,
                value=listOf(listOf(listOf("new🙂",null),listOf("",false)),listOf(listOf(1L,4L,true,false,false,null)),3L)) }
            Command.Card -> { val o=objects.getValue(card); objects[card]=o.copy(name="changed",content=now,revision=o.revision+1u,
                value=listOf(listOf(P3Fixtures.id(44),3,"hidden","new"),listOf(P3Fixtures.id(45),10,"switch",true))) }
            Command.MoveBatch -> { moved(a,b); moved(note,b) }
            Command.DeleteBatch -> { moved(a,trash); moved(note,trash) }
            Command.Restore -> { objects[recovery]=Item(1,root,"Восстановленное",now,now,now,1u,false,null,null); moved(trashed,recovery) }
            Command.Clear -> objects.remove(trashed)
            Command.Pin -> { val o=objects.getValue(note); objects[note]=o.copy(pinned=true,pin=counter++,metadata=now,revision=o.revision+1u) }
            Command.Settings -> settings=listOf(false,null,true,true,2L,true,1L)
        }
        val resultId=when(command) { Command.Folder -> newId; Command.Note,Command.Pin -> note; Command.Card -> card; else -> null }
        return State(s0.generation+1,s0.sequence+1,counter,objects,(s0.operations+(token.opId to resultId)).takeLast(256),settings)
    }
    private fun run(command: Command,image: ByteArray,fault: IoFault?,retention: Int): List<IoEvent> {
        val f=P4Fixtures.open(image); val s0=state(f); val (root,trash)=f.repo.roots(); val token=issue(command,f)
        val s1=expected(command,s0,root,trash,token); val other=f.core.currentSnapshot().header.zones[1]
        val otherRecords=f.core.currentSnapshot().catalogues[1]
        f.store.resetTrace(); f.store.fault=fault
        val result=f.repo.submit(token); val trace=f.store.events.toList(); val volatile=f.store.medium.bytes()
        f.core.owner.resolved(); f.close() // Model process death, not a production way to dismiss Unknown.
        val ranges=when(retention) {
            0 -> emptyList()
            1 -> listOf(0 until volatile.size)
            else -> Random(seed+(fault?.event ?: 0)).let { random -> List(3) {
                val a=random.nextInt(volatile.size); val b=random.nextInt(volatile.size); minOf(a,b)..maxOf(a,b)
            } }
        }
        f.store.medium.crash(ranges)
        P4Fixtures.open(f.store.medium.bytes()).use { reopened ->
            val actual=state(reopened); check(actual == s0 || actual == s1) { "Mixed state command=$command case=$cases" }
            if(result.code == WriteCode.Committed) check(actual == s1) { "False ACK command=$command case=$cases" }
            if(result.code in listOf(WriteCode.IoFailure,WriteCode.AccessLost)) check(actual == s0)
            val snapshot=reopened.core.currentSnapshot(); val d=snapshot.header.zones[1]
            check(DescriptorCodec.logicalBytes(d).contentEquals(DescriptorCodec.logicalBytes(other)) && d.catalogue.hash == other.catalogue.hash)
            for((id,p) in otherRecords) {
                val actualRef=snapshot.catalogues[1][id]!!
                check(actualRef.offset == p.offset && actualRef.hash == p.hash && sha(reopened.store.readFullyAt(p.offset,p.length.toInt())) == p.hash)
            }
        }
        cases++; return trace
    }
    @Test fun vfsNoteCardBatchFaultBoundariesAndBytePrefixes() {
        val image=baseline()
        for(command in Command.entries) {
            val trace=run(command,image,null,0)
            for(event in trace) {
                for(moment in listOf(FaultMoment.Before,FaultMoment.After)) for(retention in 0..2)
                    run(command,image,IoFault(event.index,moment,code=if(event.index%2 == 0) IoCode.NoSpace else IoCode.Eio),retention)
                if(event.action == IoAction.Write || event.action == IoAction.Force) {
                    val prefixes=if(event.action == IoAction.Write && event.length <= 512) (0..event.length).toList() else {
                        val random=Random(seed+event.index+1000*command.ordinal)
                        (listOf(0,1,7,8,75,76,4095,4096,8191,8192,16383,16384,65503,65504,65535,event.length-1,event.length)+
                            List(8) { random.nextInt(event.length+1) }).filter { it in 0..event.length }.distinct()
                    }
                    for(prefix in prefixes) {
                        run(command,image,IoFault(event.index,FaultMoment.Partial,prefix),0)
                        if(event.action == IoAction.Write) run(command,image,IoFault(event.index,FaultMoment.Partial,prefix),1)
                    }
                }
            }
            println("P4 fault command=$command events=${trace.size} seed=$seed cumulativeCases=$cases PASS")
        }
    }
}
