package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.runtimechecks.P3Fixtures as F
import org.junit.Test
import java.util.Random

/** Simple independent logical oracle: command effects are defined here, never obtained by running
 * a second successful engine mutation. Reader/codecs authenticate actual states; the oracle checks
 * their meaning against S0/S1 and the unchanged other-zone raw/logical state.
 */
class P3FaultMatrixTest {
    private enum class Command { Create, Add, Main, Decoy }
    private var cases = 0
    private val seed = 0x50301L
    private fun run(command: Command, image: ByteArray, fault: IoFault?, retention: Int): List<IoEvent> {
        val store = FaultStore(FaultMedium(image))
        val role = if(command == Command.Decoy) 2 else 1
        val owner = ResourceOwner(store); val core = TransactionCore(owner)
        val before = if(command == Command.Create) null else {
            core.attach(F.key(role)); ContainerReader(store).snapshot()
        }
        val request = if(command == Command.Main || command == Command.Decoy)
            SettingsMutation(before!!.header.zones.single { it.role == role }.sequence,0,1,SettingsData(sortMode=3)) else null
        val token = when(command) {
            Command.Create -> core.issueCreate(F.prepared(1))
            Command.Add -> F.preparedAdd(core)
            else -> core.issueMutation(request!!)
        }
        store.resetTrace(); store.fault = fault
        val result = core.submit(token)
        val trace = store.events.toList(); val volatile = store.medium.bytes()
        // Simulated process termination: no syscall is active, RAM requests/keys/leases disappear.
        owner.resolved(); core.close()
        val retained = when(retention) {
            0 -> emptyList()
            1 -> if(volatile.isEmpty()) emptyList() else listOf(0 until volatile.size)
            else -> if(volatile.isEmpty()) emptyList() else {
                val random = Random(seed + (fault?.event ?: 0)); List(3) {
                    val a = random.nextInt(volatile.size); val b = random.nextInt(volatile.size)
                    minOf(a,b)..maxOf(a,b)
                }
            }
        }
        store.medium.crash(retained)
        val reopened = FaultStore(store.medium)
        val actual = try { ContainerReader(reopened).snapshot() } catch(e: Exception) {
            check(e is FormatException || e is StoreException) { "Unexpected parser/IO exception: ${e.javaClass.simpleName}" }
            check(command == Command.Create && result.code != WriteCode.Committed) { "Lost acknowledged/source framing: $command/$cases/${e.javaClass.simpleName}" }
            reopened.close(); cases++; return trace
        }
        val old = before != null && actual.header.generation == before.header.generation
        val new = actual.header.generation == (before?.header?.generation ?: 0)+1
        check(old || new) { "Mixed generation: $command/$cases" }
        if(result.code == WriteCode.Committed) check(new) { "False ACK: $command/$cases" }
        if(result.code in listOf(WriteCode.IoFailure,WriteCode.AccessLost)) check(old) { "Committed state reported as pre-publication failure: $command/$cases" }
        if(new) {
            check(actual.header.zones.size == if(command == Command.Add) 2 else before?.header?.zones?.size ?: 1)
        } else check(actual.header.zones.size == before!!.header.zones.size)
        for(d in actual.header.zones) F.key(d.role).use { key ->
            val view = ContainerReader(reopened).verifyAll(actual,key); val root = view.root!!
            val isTarget = new && d.role == role
            val expectedSort = if(isTarget && request != null) 3L else 1L
            val expectedSeq = if(isTarget && request != null) 2L else 1L
            check(view.settings!!.sortMode == expectedSort && d.sequence == expectedSeq && root.sequence == expectedSeq)
            if(before != null && before.header.zones.any { it.role == d.role }) {
                val previous = before.header.zones.single { it.role == d.role }
                val prevStore = FaultStore(FaultMedium(image))
                val previousRoot = try { ContainerReader(prevStore).openZone(before,key).root!! } finally { prevStore.close() }
                check(root.normalRoot == previousRoot.normalRoot && root.trashRoot == previousRoot.trashRoot)
                check(root.objects.recordId == previousRoot.objects.recordId && root.children.recordId == previousRoot.children.recordId)
                check(root.nextPinSequence == previousRoot.nextPinSequence)
                if(!isTarget || command == Command.Add) {
                    check(DescriptorCodec.logicalBytes(d).contentEquals(DescriptorCodec.logicalBytes(previous)) && d.logicalMac == previous.logicalMac)
                    check(d.catalogue.offset == previous.catalogue.offset && d.catalogue.hash == previous.catalogue.hash)
                    val oldEntries = before.catalogues[before.header.zones.indexOf(previous)]
                    check(actual.catalogues[actual.header.zones.indexOf(d)].keys == oldEntries.keys)
                    for(p in oldEntries.values) check(reopened.readFullyAt(p.offset,p.length.toInt()).contentEquals(image.copyOfRange(p.offset.toInt(),(p.offset+p.length).toInt())))
                }
            }
            if(isTarget && request != null) check(root.recentOperations.last().first == token.opId)
            else check(root.recentOperations.isEmpty())
        }
        reopened.close(); cases++; return trace
    }
    @Test fun everyBoundaryAndSmallWriteBytePrefix() {
        val one = F.image(); val two = F.image(true)
        for(command in Command.entries) {
            val image = when(command) { Command.Create -> ByteArray(0); Command.Add -> one; else -> two }
            val trace = run(command,image,null,0)
            for(e in trace) {
                for(moment in listOf(FaultMoment.Before,FaultMoment.After)) for(retention in 0..2)
                    run(command,image,IoFault(e.index,moment,code=if(e.index%2 == 0) IoCode.NoSpace else IoCode.Eio),retention)
                if(e.action == IoAction.Write || e.action == IoAction.Force) {
                    val prefixes = if(e.action == IoAction.Write && e.length <= 512) (0..e.length).toList() else {
                        val edges = listOf(0,1,7,8,75,76,4095,4096,8191,8192,16383,16384,32768,65503,65504,65535,e.length-1,e.length)
                        val random = Random(seed + e.index + command.ordinal*1000)
                        (edges + List(16) { random.nextInt(e.length+1) }).filter { it in 0..e.length }.distinct()
                    }
                    for(prefix in prefixes) {
                        run(command,image,IoFault(e.index,FaultMoment.Partial,prefix),0)
                        if(e.action == IoAction.Write) run(command,image,IoFault(e.index,FaultMoment.Partial,prefix),1)
                    }
                }
            }
            println("P3 fault matrix command=$command events=${trace.size} seed=$seed cumulativeCases=$cases")
        }
        check(cases > 3000)
    }
    @Test fun orphanTailAndTruncation() {
        val bytes = F.image(true)
        val (store,core) = F.core(bytes)
        store.appendAligned(ByteArray(123) { 0x55 })
        check(ContainerReader(store).snapshot().header.committedLength == bytes.size.toLong())
        core.close()
        val reopened = FaultStore(store.medium); val c = TransactionCore(ResourceOwner(reopened)); c.attach(F.key(1))
        check(c.submit(c.issueMutation(F.mutation(c))).code == WriteCode.Committed)
        check(reopened.medium.bytes().copyOfRange(bytes.size,bytes.size+123).all { it == 0x55.toByte() })
        val complete = reopened.medium.durableBytes(); c.close()
        val boundaries = setOf(0,7,65503,65504,65535,65536,131071,131072,bytes.size-1,complete.size-1)
        for(n in boundaries) {
            val broken = FaultStore(FaultMedium(complete.copyOf(n)))
            var rejected = false
            try { ContainerReader(broken).snapshot() }
            catch(e: Exception) {
                check(e is FormatException || e is StoreException)
                rejected = true
            }
            finally { broken.close() }
            check(rejected) { "Truncated newest snapshot accepted at $n" }
        }
    }
}
