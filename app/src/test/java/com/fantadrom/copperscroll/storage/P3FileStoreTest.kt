package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.format.ContainerReader
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.runtimechecks.P3Fixtures as F
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Test

class P3FileStoreTest {
    @Test fun createNamespaceFailureBeforeAndAfterIdentityForce() {
        val baseline = FaultStore(); val baseCore = TransactionCore(ResourceOwner(baseline))
        check(baseCore.submit(baseCore.issueCreate(F.prepared(1))).code == WriteCode.Committed)
        val point = baseline.events.single { it.action == IoAction.Identity }.index; baseCore.close()
        for(moment in listOf(FaultMoment.Before,FaultMoment.After)) {
            val namespace = FaultNamespace(); val store = namespace.create("container")
            val core = TransactionCore(ResourceOwner(store)); val token = core.issueCreate(F.prepared(1))
            store.fault = IoFault(point,moment)
            check(core.submit(token).code == WriteCode.CommitUnknown)
            core.owner.resolved(); core.close() // simulated process death discards the live request
            namespace.crash()
            check(namespace.exists("container") == (moment == FaultMoment.After))
            if(namespace.exists("container")) namespace.open("container").use { reopened ->
                check(ContainerReader(reopened).snapshot().header.generation == 1L)
            }
        }
    }
    private fun probe(path: Path): Int {
        val classes = listOf(javaClass,FileChannelStore::class.java,P3LockProbe::class.java,kotlin.Unit::class.java)
        val cp = classes.map { Path.of(it.protectionDomain!!.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        val executable = if(System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-cp",cp,
            P3LockProbe::class.java.name,path.toString()).redirectErrorStream(true).start()
        check(process.waitFor(10,TimeUnit.SECONDS)) { "Lock probe timeout" }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(output.trim() in listOf("BUSY","ACQUIRED")) { "Probe failed: $output" }
        return process.exitValue()
    }
    @Test fun fileTransactionsAndCooperativeProcessLocks() {
        val directory = Files.createTempDirectory("p3-file-"); val path = directory.resolve("synthetic.cscroll")
        try {
            // Host file creation is fixture setup, not a cross-platform directory durability certificate.
            val store = FileChannelStore.open(path,true)
            store.writeFullyAt(0,F.image()); store.force()
            val core = TransactionCore(ResourceOwner(store)); core.attach(F.key(1))
            check(probe(path) == 23)
            var busy = false
            try { FileChannelStore.open(path).close() } catch(e: StoreException) { busy = e.code == IoCode.Busy }
            check(busy && probe(path) == 23) // rejected second open must not close an FD that drops the first lock
            val hardlink = directory.resolve("alias")
            val linked = try { Files.createLink(hardlink,path); true }
                catch(_: UnsupportedOperationException) { false }
                catch(_: java.nio.file.FileSystemException) { false }
            if(linked) {
                busy = false
                try { FileChannelStore.open(hardlink).close() } catch(e: StoreException) { busy = e.code == IoCode.Busy }
                check(busy && probe(path) == 23)
                println("P3 cooperative file lock: hardlink branch executed")
            } else println("P3 cooperative file lock: hardlink unsupported on host; branch NOT RUN")
            check(core.submit(core.issueMutation(F.mutation(core))).code == WriteCode.Committed); core.close()
            check(probe(path) == 0)
            FileChannelStore.open(path).use { reopened ->
                val reader = ContainerReader(reopened); val snapshot = reader.snapshot()
                F.key(1).use { check(reader.verifyAll(snapshot,it).settings!!.sortMode == 2L) }
                val committed = snapshot.header.committedLength
                reopened.appendAligned(byteArrayOf(1,2,3)); check(reopened.truncate(committed)); reopened.force()
                check(reopened.size == committed)
            }
        } finally { Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory) }
    }
    @Test fun localStatePartialBytesAndChecksumLimits() {
        fun fp(name: String,n: Int) = DocumentFingerprint("synthetic:///$name",F.container,F.id(n),n.toLong(),131072,F.id(n,32),F.id(n+1,32))
        val source = fp("source",1); val candidate = fp("candidate",2); val backup = fp("backup",3)
        val old = LocalState(source,null)
        val next = LocalState(candidate,StateJournal(F.id(31),"synthetic",listOf(F.id(1),F.id(2)),source,backup,candidate,JournalStage.Activated,source))
        val bytes = LocalStateCodec.encode(next)
        for(n in 0..bytes.size) {
            val local = FaultLocalStateStore(old); local.fault = LocalFault(LocalPoint.TempWrite,FaultMoment.Partial,n)
            check(local.replace(next) == LocalWriteCode.IoFailure); local.crash(true)
            check(local.bytes().contentEquals(LocalStateCodec.encode(old)))
        }
        for(n in bytes.indices) {
            val corrupt = bytes.clone(); corrupt[n] = (corrupt[n].toInt() xor 1).toByte(); var rejected = false
            try { LocalStateCodec.decode(corrupt) } catch(_: Exception) { rejected = true }
            check(rejected)
        }
        val local = FaultLocalStateStore(next); check(local.replace(old) == LocalWriteCode.Committed)
        check(local.read().binding!!.generation == 1L) // checksum is deliberately NOT anti-rollback
        println("P3 local state: exhaustive temp-write prefixes=${bytes.size+1}, checksum corruptions=${bytes.size}")
    }
}
