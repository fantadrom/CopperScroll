package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.access.*
import java.util.Random
import org.junit.Test

class P5aFaultTest {
    /** Real coordinator credentials/unwrap/MAC/root on every case. Cache only the repeated test
     * Argon derivation for fixed synthetic inputs; real KDF is exercised by shared/runtime tests. */
    private val derived = HashMap<String,ByteArray>()
    private fun coordinator(f: P5aFixture,local: LocalStateStore = f.local): MaintenanceCoordinator {
        val manager = CredentialManager(SystemEntropy(),ArgonDerivation { secret,envelope ->
            val key = envelope.parameters.memoryKiB.toString()+":"+envelope.parameters.iterations+":"+
                (envelope.salt.copy()+secret).joinToString("") { "%02x".format(it.toInt() and 255) }
            derived.getOrPut(key) { BcArgon.derive(secret,envelope) }.clone()
        })
        return MaintenanceCoordinator(f.core,f.docs,local,manager,{})
    }
    private fun confirmation(f: P5aFixture,c: MaintenanceCoordinator): MaintenanceConfirmation {
        val r = c.begin(f.core.authorizeMaintenance(),f.destinations)
        return SecretEncoding.text(P3Fixtures.DECOY).use { c.confirm(c.credentials(r,it)) }
    }
    @Test fun allIoBoundariesAndDurableRestarts() {
        val image = P4Fixtures.image(true)
        val trace = ArrayList<IoEvent>()
        P5aFixture(image).use { f ->
            val c = coordinator(f); val confirmed = confirmation(f,c)
            f.docs.onEvent = { _,_,e,_ -> trace.add(e) }
            check(c.compact(confirmed).code == MaintenanceCode.MaintenanceCommitted)
        }
        var count = 0; val random = Random(0x503a)
        for(index in trace.indices) for(moment in FaultMoment.entries) {
            // Reads have no partial side effect; keep distinct before/after only.
            if(moment == FaultMoment.Partial && trace[index].action == IoAction.Read) continue
            for(retain in listOf(false,true)) P5aFixture(image).use { f ->
                val c = coordinator(f); val confirmed = confirmation(f,c); var cursor = 0; var injected = false
                f.docs.onEvent = { _,store,e,_ ->
                    if(cursor++ == index) {
                        injected = true
                        val prefix = if(e.length == 0) 0 else random.nextInt(e.length+1)
                        store.fault = IoFault(e.index,moment,prefix,if(count%2 == 0) IoCode.Eio else IoCode.NoSpace)
                    }
                }
                val r = c.compact(confirmed)
                check(injected && r.code != MaintenanceCode.MaintenanceCommitted) { "event=$index moment=$moment ${r.code}" }
                check(f.docs.bytes(f.docs.source).contentEquals(image))
                check(f.local.read().binding!!.uri == f.docs.source.uri)
                // Simulated process death: release volatile handles, then lose/retain unforced bytes.
                f.core.owner.resolved(); f.core.close()
                f.docs.media.values.forEach { (_,m) ->
                    val ranges = if(retain && m.bytes().isNotEmpty()) listOf(0 until m.bytes().size) else emptyList()
                    m.crash(ranges)
                }
                f.local.crash(retain)
                check(f.local.read().binding!!.uri == f.docs.source.uri)
                check(f.docs.bytes(f.docs.source).contentEquals(image)); count++
            }
        }
        println("P5a IO fault matrix seed=20538 events=${trace.size} cases=$count; before/after/partial + two crash retention models")
    }
    @Test fun everyJournalWriteForceRenameSyncReadbackAck() {
        val image = P4Fixtures.image(true)
        var writes = 0
        P5aFixture(image).use { f ->
            val port = RecordingLocal(f.local) { _,_-> writes++ }
            val c = coordinator(f,port)
            check(c.compact(confirmation(f,c)).code == MaintenanceCode.MaintenanceCommitted)
        }
        var cases = 0
        for(write in 0 until writes) for(point in LocalPoint.entries) for(moment in FaultMoment.entries) for(retain in listOf(false,true)) P5aFixture(image).use { f ->
            val port = RecordingLocal(f.local) { n,_ -> if(n == write) f.local.fault = LocalFault(point,moment,if(retain) 1 else 0) }
            val c = coordinator(f,port)
            val r = c.compact(confirmation(f,c))
            check(r.code != MaintenanceCode.MaintenanceCommitted)
            f.core.owner.resolved(); f.core.close(); f.docs.crash(); f.local.crash(retain)
            val state = f.local.read(); val activated = state.journal?.stage == JournalStage.Activated
            check((state.binding!!.uri != f.docs.source.uri) == activated)
            if(activated) {
                check(write == writes-1 && r.code == MaintenanceCode.SwitchUnknown)
                val fp = state.journal!!.candidate!!; val artifact = Artifact(fp.uri,fp.identity!!)
                check(P5aImages.logical(f.docs.bytes(artifact),1) == P5aImages.logical(image,1))
                check(P5aImages.logical(f.docs.bytes(artifact),2) == P5aImages.logical(image,2))
            }
            check(f.docs.bytes(f.docs.source).contentEquals(image)); cases++
        }
        println("P5a journal fault matrix writes=$writes stages=Prepared..Activated cases=$cases; local write/force/rename/parent-sync/readback/ACK")
    }
    @Test fun capturedDurableStagesRestartWithoutReplay() {
        val image = P4Fixtures.image(true); val captured = ArrayList<Pair<ByteArray,P5aDocuments>>()
        P5aFixture(image).use { f ->
            val port = object : LocalStateStore {
                override val durableLocalState = true
                override val reconciliationRequired get() = f.local.reconciliationRequired
                override fun read() = f.local.read()
                override fun reconcile() = f.local.reconcile()
                override fun replace(state: LocalState): LocalWriteCode {
                    val result = f.local.replace(state)
                    if(result == LocalWriteCode.Committed) {
                        val docs = P5aDocuments(f.docs.source)
                        for((uri,pair) in f.docs.media) if(uri in f.docs.durableNames) {
                            docs.media[uri] = pair.first to FaultMedium(pair.second.durableBytes(),128*1024*1024)
                            docs.durableNames.add(uri)
                        }
                        captured.add(f.local.bytes() to docs)
                    }
                    return result
                }
            }
            val c = coordinator(f,port); check(c.compact(confirmation(f,c)).code == MaintenanceCode.MaintenanceCommitted)
        }
        var cases = 0
        for((bytes,docs) in captured) {
            val local = FaultLocalStateStore(LocalStateCodec.decode(bytes)); val initial = local.read()
            val inspector = MaintenanceRecovery()
            check(inspector.resolve(local,docs,RecoveryChoice.Source,emptyMap()).code == RecoveryCode.NeedsCredentials)
            val secrets = (1..2).associate { P3Fixtures.id(it) to SecretEncoding.text(if(it == 1) P3Fixtures.MAIN else P3Fixtures.DECOY) }
            try {
                val r = inspector.resolve(local,docs,RecoveryChoice.Source,secrets)
                check(r.code == RecoveryCode.SourceConfirmed) { "stage=${initial.journal!!.stage} code=${r.code}" }
                check(r.locked && local.read().binding!!.uri == docs.source.uri && docs.creates == 0)
            } finally { secrets.values.forEach { it.close() } }; cases++
        }
        println("P5a restart snapshots=$cases, every durable metadata publication, explicit source+fresh credentials, no create replay")
    }
    @Test fun crashAtCopyBuildAndNamespaceTransitions() {
        val image = P4Fixtures.image(true)
        val snapshots = ArrayList<Pair<ByteArray,P5aDocuments>>()
        var boundaries = 0
        P5aFixture(image).use { f ->
            val c = coordinator(f); val confirmed = confirmation(f,c)
            fun capture() {
                boundaries++
                for(retain in listOf(false,true)) {
                    val docs = P5aDocuments(f.docs.source)
                    for((uri,pair) in f.docs.media) if(retain || uri in f.docs.durableNames) {
                        docs.media[uri] = pair.first to FaultMedium(if(retain) pair.second.bytes() else pair.second.durableBytes(),128*1024*1024)
                        docs.durableNames.add(uri)
                    }
                    // Independent crash image BEFORE exception handling/abort/cleanup. This is not
                    // the post-exception state exercised by the fault matrix above.
                    snapshots.add(f.local.bytes() to docs)
                }
            }
            var previousMutated = false
            f.docs.onEvent = { _,_,event,_ ->
                if(previousMutated) capture() // after the previous completed write/force/identity
                previousMutated = event.action in listOf(IoAction.Write,IoAction.Force,IoAction.Identity)
                if(previousMutated) capture() // before this IO, including the boundary between zones
            }
            check(c.compact(confirmed).code == MaintenanceCode.MaintenanceCommitted)
        }
        for((bytes,docs) in snapshots) {
            val local = FaultLocalStateStore(LocalStateCodec.decode(bytes))
            val secrets = (1..2).associate { P3Fixtures.id(it) to SecretEncoding.text(if(it == 1) P3Fixtures.MAIN else P3Fixtures.DECOY) }
            try {
                val result = MaintenanceRecovery().resolve(local,docs,RecoveryChoice.Source,secrets)
                check(result.code == RecoveryCode.SourceConfirmed && result.locked) { "${local.read().journal?.stage} ${result.code}" }
                check(docs.creates == 0 && docs.bytes(docs.source).contentEquals(image))
                check(local.read().binding!!.uri == docs.source.uri)
            } finally { secrets.values.forEach { it.close() } }
        }
        println("P5a immediate crash checkpoints=$boundaries images=${snapshots.size}; before/after write/force/identity, lost/retained volatile bytes AND namespace, no exception cleanup")
    }
    private class RecordingLocal(val local: FaultLocalStateStore,val before: (Int,LocalState)->Unit) : LocalStateStore {
        var n = 0
        override val durableLocalState = true
        override val reconciliationRequired get() = local.reconciliationRequired
        override fun read() = local.read()
        override fun reconcile() = local.reconcile()
        override fun replace(state: LocalState): LocalWriteCode { before(n++,state); return local.replace(state) }
    }
}
