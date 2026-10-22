package com.fantadrom.copperscroll.storage.runtime

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.storage.android.*
import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.runtimechecks.*
import com.fantadrom.copperscroll.p6aharness.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Android PFD/Os/IPC/noBackup adapters; only the bounded provider contract is test-admitted. */
@RunWith(AndroidJUnit4::class)
class P6aSafRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val factory get()=SafStoreFactory(context)
    private val authority get()=context.packageName+".p6a.documents"
    private fun root(r: String)=DocumentsContract.buildTreeDocumentUri(authority,r)
    private fun control(command: String,arg: String?=null,extras: Bundle?=null): Bundle = context.contentResolver.call(Uri.parse("content://$authority"),"p6a.$command",arg,extras)!!
    private fun mode(uri: Uri,value: String)=control("mode",DocumentsContract.getDocumentId(uri),Bundle().apply { putString("mode",value) })
    private fun reject(block: ()->Unit): Throwable {
        try { block() } catch(e: Exception) { return e }; error("Expected refusal")
    }
    private fun created(): Pair<Artifact,RandomAccessStore> = factory.create(root("a"))
    private fun localName()="p6a-test-"+UUID.randomUUID()
    private fun seeded(image: ByteArray = P4Fixtures.image()): Artifact {
        val (a,s)=created(); s.use { it.writeFullyAt(0,image); it.force(); it.forceIdentity() }; return a
    }
    private fun core(a: Artifact,local: LocalStateStore,secret: String=P3Fixtures.MAIN): TransactionCore {
        val owner=ResourceOwner(factory.openRequired(Uri.parse(a.uri)))
        try {
            val snapshot=ContainerReader(owner.store).snapshot()
            val key=SecretEncoding.text(secret).use { CredentialManager().login(snapshot.header.containerId,snapshot.header.zones,it).key }
            return TransactionCore.openBound(owner,local,key)
        } catch(e: Throwable) { if(!owner.reconciliationRequired) owner.close(); throw e }
    }
    private fun initialize(local: AppPrivateLocalState,a: Artifact) {
        factory.openRequired(Uri.parse(a.uri)).use { check(local.initialize(LocalState(fingerprint(it,a.uri),null))==LocalWriteCode.Committed) }
    }
    @Before fun requireTargetAndReset() {
        check(InstrumentationRegistry.getArguments().getString("p1ExpectedVariant")=="p1Release")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE==0)
        for(type in listOf(AndroidSafStore::class.java,SafStoreFactory::class.java,AppPrivateLocalState::class.java,TransactionCore::class.java))
            check(type===context.classLoader.loadClass(type.name))
        HarnessFaults.clear(); control("reset")
    }
    @After fun clearFaults() { HarnessFaults.clear() }

    @Test fun explicitModesAndNegativeAdmission() {
        val a=seeded()
        check(control("count").getInt("creates")==1)
        val before=control("count").getInt("creates")
        ResourceOwner(factory.openRequired(Uri.parse(a.uri))).use { owner ->
            val snapshot=ContainerReader(owner.store).snapshot()
            control("backend","cloud-deferred-unknown")
            check(factory.gate(root("b")).granted.isEmpty()); reject { factory.create(root("b")) }
            val copier=SafCopyOperations(SafMaintenanceDocuments(factory,a,root("b"),root("c")))
            val permit=owner.accept(); owner.execute(permit) {
                reject { copier.importCopy(owner,permit,snapshot,a,root("b"),emptyMap()) }
                reject { copier.restoreCopy(owner,permit,snapshot,a,root("c"),emptyMap()) }
            }
        }
        check(before==control("count").getInt("creates"))
        factory.openOriginal(Uri.parse(a.uri),true).let { check(!it.writable && !it.store.guarantees.existingWriteDurable); it.store.close() }
        reject { factory.openOriginal(DocumentsContract.buildDocumentUri(authority,"a:"+UUID.randomUUID())) }
        check(before==control("count").getInt("creates"))
    }
    @Test fun createTransactionCopyCompactRekey() {
        val (a,s)=created()
        TransactionCore(ResourceOwner(s)).use { c ->
            check(c.submit(c.issueCreate(P3Fixtures.prepared(1))).code==WriteCode.Committed)
            SecretEncoding.text(P3Fixtures.MAIN).use { old -> SecretEncoding.text(P3Fixtures.DECOY).use { new ->
                check(c.submit(c.issueAdd(old,new,new,3,KdfParameters(16384,2))).code==WriteCode.Committed)
            } }
        }
        AppPrivateLocalState.open(context,localName()).use { local ->
            initialize(local,a)
            val docs=SafMaintenanceDocuments(factory,a,root("b"),root("c"))
            core(a,local).use { c ->
                val request=P3Fixtures.mutation(c,99); check(c.submit(c.issueMutation(request),request).code==WriteCode.Committed)
                val permit=c.owner.accept(); c.owner.execute(permit) {
                    val copies=SafCopyOperations(docs)
                    val imported=copies.importCopy(c.owner,permit,c.currentSnapshot(),a,root("b"),emptyMap())
                    val restored=copies.restoreCopy(c.owner,permit,c.currentSnapshot(),a,root("c"),emptyMap())
                    check(imported.artifact.identity!=a.identity && restored.artifact.identity!=a.identity && imported.fingerprint.fullHash==restored.fingerprint.fullHash)
                    check(local.read().binding!!.uri==a.uri)
                }
                val co=MaintenanceCoordinator(c,docs,local)
                val requestCompact=co.begin(c.authorizeMaintenance(),MaintenanceDestinations(a,root("b").toString(),root("c").toString()))
                val confirm=SecretEncoding.text(P3Fixtures.DECOY).use { co.confirm(co.credentials(requestCompact,it)) }
                val result=co.compact(confirm); check(result.code==MaintenanceCode.MaintenanceCommitted) { result.code.name }
                val candidate=result.candidate!!
                val nextDocs=SafMaintenanceDocuments(factory,candidate,root("b"),root("c"))
                core(candidate,local).use { next ->
                    val rekey=MaintenanceCoordinator(next,nextDocs,local)
                    val req=rekey.beginChangeSecret(next.authorizeMaintenance(),MaintenanceDestinations(candidate,root("b").toString(),root("c").toString()),next.currentSnapshot().header.zones.single { it.role==2 }.zoneId,3,parameters=KdfParameters(16384,2))
                    val conf=SecretEncoding.text(P3Fixtures.DECOY).use { old -> SecretEncoding.text(P5bOracle.NEW).use { new -> rekey.confirm(rekey.credentials(req,old,new,new)) } }
                    val changed=rekey.changeSecret(conf); check(changed.code==MaintenanceCode.MaintenanceCommitted) { changed.code.name }
                    core(changed.candidate!!,local).use { opened -> check(opened.currentSnapshot().header.zones.size==2) }
                    factory.openRequired(Uri.parse(changed.candidate!!.uri)).use { store ->
                        SecretEncoding.text(P5bOracle.NEW).use { secret -> ContainerReader(store).open(secret).use { session -> check(session.key.role==2); ContainerReader(store).verifyAll(session.view.snapshot,session.key) } }
                    }
                }
            }
        }
    }
    @Test fun readOnlyPipeVirtualAndStaleMetadata() {
        val a=seeded(); val uri=Uri.parse(a.uri)
        mode(uri,"readonly"); factory.openOriginal(uri).store.use { check(it.size>131072); reject { it.writeFullyAt(0,byteArrayOf(0)) } }
        reject { factory.openOriginal(uri,true) }
        mode(uri,"readonlyFd"); reject { factory.openOriginal(uri,true) }
        for(m in listOf("pipe","virtual")) { mode(uri,m); reject { factory.openOriginal(uri) } }
        for(m in listOf("staleSize","unknownLength")) { mode(uri,m); factory.openOriginal(uri).store.use { check(it.size>131072); check(it.readFullyAt(0,8).size==8) } }
    }
    @Test fun grantLossReplacementAndProfileMismatch() {
        val a=seeded(); val uri=Uri.parse(a.uri); val opened=factory.openRequired(uri)
        try {
            mode(uri,"denied"); reject { opened.readFullyAt(0,1) }; reject { factory.openOriginal(uri) }; mode(uri,"normal")
            control("backend","version-changed"); reject { opened.readFullyAt(0,1) }; reject { factory.create(root("b")) }
        } finally { opened.close() }
        control("backend","p6a-controlled-v1"); control("replace",DocumentsContract.getDocumentId(uri))
        reject { SafMaintenanceDocuments(factory,a,root("b"),root("c")).checkIdentity(a) }
        mode(uri,"missing"); reject { factory.openOriginal(uri) }
        mode(uri,"death"); reject { factory.openOriginal(uri) }
    }
    @Test fun positionalShortInterruptedAndErrorIo() {
        val (_,s)=created(); s.use {
            val bytes=ByteArray(70001) { (it%251).toByte() }
            HarnessFaults.shortLimit=7; HarnessFaults.eintr=3
            it.writeFullyAt(13,bytes); check(it.readFullyAt(13,bytes.size).contentEquals(bytes))
            HarnessFaults.zero=true; reject { it.readFullyAt(13,1) }; HarnessFaults.zero=false
            HarnessFaults.errno=OsConstants.ENOSPC; check((reject { it.writeFullyAt(0,bytes) } as StoreException).code==IoCode.NoSpace)
            HarnessFaults.clear(); HarnessFaults.syncFailure=true; reject { it.force() }; HarnessFaults.clear()
            reject { it.readFullyAt(it.size,1) }; reject { it.readFullyAt(Long.MAX_VALUE,1) }; reject { it.readFullyAt(0,-1) }
        }
    }
    @Test fun dataForceDoesNotPublishNamespaceAndUnknownCreate() {
        val (a,s)=created(); s.use { it.writeFullyAt(0,byteArrayOf(1,2)); it.force(); it.writeFullyAt(2,byteArrayOf(9,9)) }
        control("dataLoss"); factory.openRequired(Uri.parse(a.uri)).use { check(it.size==2L && it.readFullyAt(0,2).contentEquals(byteArrayOf(1,2))) }
        control("namespaceLoss"); reject { factory.openOriginal(Uri.parse(a.uri)) }
        val (kept,k)=created(); k.use { it.writeFullyAt(0,byteArrayOf(4)); it.force(); it.forceIdentity() }
        control("namespaceLoss"); factory.openRequired(Uri.parse(kept.uri)).use { check(it.size==1L) }
        control("namespace","off"); val (_,unverified)=created(); unverified.use { it.force(); reject { it.forceIdentity() } }
        val documents=control("count").getInt("documents")
        control("mode","create",Bundle().apply { putString("mode","before") }); reject { created() }
        check(control("count").getInt("documents")==documents)
        val before=control("count").getInt("creates")
        control("mode","create",Bundle().apply { putString("mode","after") }); check((reject { created() } as MaintenanceRefusal).code==MaintenanceCode.CopyUnknown)
        check(control("count").getInt("creates")==before+1 && control("count").getInt("documents")==documents+1) // unknown document remains; no name-based deletion
        control("namespace","on"); control("mode","create",Bundle().apply { putString("mode","normal") })
        val source=seeded()
        ResourceOwner(factory.openRequired(Uri.parse(source.uri))).use { owner ->
            val snapshot=ContainerReader(owner.store).snapshot()
            val copier=SafCopyOperations(SafMaintenanceDocuments(factory,source,root("b"),root("c")))
            val permit=owner.accept(); owner.execute(permit) {
                HarnessFaults.syncFailure=true
                val failure=try { reject { copier.importCopy(owner,permit,snapshot,source,root("b"),emptyMap()) } } finally { HarnessFaults.clear() }
                check(failure is SafCopyFailure && failure.code==MaintenanceCode.CopyUnknown && failure.unverifiedArtifact!=null)
                check(ContainerReader(owner.store).snapshot().headerBytes==snapshot.headerBytes)
            }
        }
    }
    @Test fun noBackupPublicationFaultsAndWholeRecords() {
        val a=seeded()
        for(p in listOf("TempWrite","TempForce","Rename","ParentForce","Readback","Ack")) for(m in listOf("Before","Partial","After")) {
            val name=localName()
            AppPrivateLocalState.open(context,name).use { local ->
                initialize(local,a); val old=local.read(); HarnessFaults.localFault=p to m
                val code=local.replace(old); check(code!=LocalWriteCode.Committed)
                if(p in listOf("Rename","ParentForce","Readback","Ack")) check(code==LocalWriteCode.StateUnknown)
                HarnessFaults.clear(); check(LocalStateCodec.encode(local.reconcile()).contentEquals(LocalStateCodec.encode(old)))
            }
            AppPrivateLocalState.open(context,name).use { check(it.presence==LocalPresence.Ready); it.read() }
        }
        for(p in listOf("InitialDirectory","Marker")) for(m in listOf("Before","After")) {
            val name=localName(); AppPrivateLocalState.open(context,name).use { l -> HarnessFaults.localFault=p to m
                factory.openRequired(Uri.parse(a.uri)).use {
                    val initial=LocalState(fingerprint(it,a.uri),null)
                    check(l.initialize(initial)==LocalWriteCode.StateUnknown)
                    HarnessFaults.clear(); check(l.initialize(initial)==LocalWriteCode.StateUnknown)
                }
            }; HarnessFaults.clear(); AppPrivateLocalState.open(context,name).use { check(it.reconciliationRequired); reject { it.read() } }
        }
    }
    @Test fun aliasInterprocessLocksAndContinuousReopen() {
        val a=seeded(); val uri=Uri.parse(a.uri); val alias=DocumentsContract.buildDocumentUri(authority,"alias:"+DocumentsContract.getDocumentId(uri))
        val store=factory.openRequired(uri); ResourceOwner(store).use { owner ->
            check(control("pid").getInt("pid")!=Process.myPid())
            reject { factory.openRequired(alias) }
            RemoteHarness(context).use { remote ->
                check(remote.request("tryOpen",uri).getString("status")=="Busy")
                val permit=owner.accept(); owner.execute(permit) {
                    owner.reopen(permit) {
                        // This exact close/open gap must remain protected across Linux processes.
                        check(remote.request("tryOpen",alias).getString("status")=="Busy")
                        factory.openRequired(uri)
                    }
                }
            }
        }
        RemoteHarness(context).use { check(it.request("tryOpen",alias).getString("status")=="opened") }
        factory.openRequired(alias).use { check(it.identity==a.identity) }
    }
    @Test fun blockingIoRevokeRetainsOwnerUntilQuiescence() {
        val (a,s)=created(); val owner=ResourceOwner(s); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>(); HarnessFaults.blockingSync=entered to release
        val permit=owner.accept(); val thread=Thread { try { owner.execute(permit) { s.force(); owner.check(permit) } } catch(e: Throwable) { failure.set(e) } }
        try {
            thread.start(); check(entered.await(10,TimeUnit.SECONDS)); owner.revoke(); check(!owner.awaitQuiescent(25)); check(!owner.release())
            RemoteHarness(context).use { check(it.request("tryOpen",Uri.parse(a.uri)).getString("status")=="Busy") }
        } finally { release.countDown(); thread.join(15000); HarnessFaults.clear(); check(!thread.isAlive); check(owner.release()) }
        check(failure.get() is StoreException)
    }
    @Test fun processRestartLocalLockAndBoundGenerationProgress() {
        val a=seeded(); val name=localName()
        AppPrivateLocalState.open(context,name).use { local ->
            initialize(local,a)
            RemoteHarness(context).use { check(it.request("local",name=name).getString("status")=="Busy") }
            core(a,local).use { c -> val r=P3Fixtures.mutation(c,91); check(c.submit(c.issueMutation(r),r).code==WriteCode.Committed) }
            // The checkpoint is intentionally not republished on ordinary T.
            core(a,local).use { check(it.currentSnapshot().header.generation>local.read().binding!!.generation) }
        }
        var previous=0
        RemoteHarness(context).use { remote ->
            previous=remote.request("hold",Uri.parse(a.uri)).getInt("pid"); check(remote.request("local",name=name).getString("status")=="ready"); remote.die()
        }
        RemoteHarness(context).use { remote -> check(remote.request("tryOpen",Uri.parse(a.uri)).getInt("pid")!=previous) }
        AppPrivateLocalState.open(context,name).use { local -> core(a,local).close() }
    }
    @Test fun pickerCancellationStaleAndRequiredFlags() {
        val port=SafPickerPort(); val resolver=context.contentResolver
        val cancelled=port.beginLocked(SafOperation.Create); check(port.accept(cancelled,Activity.RESULT_CANCELED,null,resolver)==null)
        reject { port.accept(cancelled,Activity.RESULT_OK,Intent().setData(root("a")),resolver) }
        val stale=port.beginLocked(SafOperation.ImportCopy); port.invalidate(); reject { port.accept(stale,Activity.RESULT_OK,null,resolver) }
        val readOnly=port.beginLocked(SafOperation.Create)
        reject { port.accept(readOnly,Activity.RESULT_OK,Intent().setData(root("a")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),resolver) }
        val old=port.beginLocked(SafOperation.RestoreCopy); port.beginLocked(SafOperation.Create); reject { port.accept(old,Activity.RESULT_OK,null,resolver) }
        check(control("count").getInt("creates")==0)
    }
    @Test fun corruptLostAndVersionedLocalStateNeverReset() {
        val a=seeded(); val name=localName()
        AppPrivateLocalState.open(context,name).use { initialize(it,a) }
        val file=File(File(context.noBackupFilesDir,name),"state.bin")
        val original=file.readBytes()
        val unsupportedFields=StrictCbor.decode(original.copyOfRange(12,original.size-32),LocalStateCodec.MAX_BYTES).arr().toMutableList().also { it[0]=99uL }
        val body=StrictCbor.encode(unsupportedFields,LocalStateCodec.MAX_BYTES)
        val prefix=java.nio.ByteBuffer.allocate(12+body.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).put("CSLOCAL1".toByteArray()).putInt(body.size).put(body).array()
        val unsupported=prefix+sha(prefix).copy()
        for(b in listOf(byteArrayOf(1,2,3),original.copyOf(20),unsupported,original.clone().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() })) {
            file.writeBytes(b); AppPrivateLocalState.open(context,name).use { local -> reject { local.read() }; check(local.reconciliationRequired); check(local.replace(LocalState(null,null))==LocalWriteCode.StateUnknown) }
        }
        file.delete(); AppPrivateLocalState.open(context,name).use { check(it.presence==LocalPresence.Unresolved); reject { it.read() }; check(it.initialize(LocalState(null,null))!=LocalWriteCode.Committed) }
        val testAssets=InstrumentationRegistry.getInstrumentation().context.assets
        for(f in listOf("local-v1-prepared.hex","local-v2-rekey-prepared.hex")) {
            val raw=testAssets.open("p6a/$f").bufferedReader().use { it.readText().trim().chunked(2).map { v -> v.toInt(16).toByte() }.toByteArray() }
            file.writeBytes(raw); AppPrivateLocalState.open(context,name).use { check(it.read().journal!!.stage==JournalStage.Prepared) }
        }
    }
    @Test fun switchRevocationLostAckAndDistinctRecoveryCredentials() {
        for(lostAck in listOf(false,true)) {
            val a=seeded(P4Fixtures.image(true)); AppPrivateLocalState.open(context,localName()).use { local ->
                initialize(local,a); val docs=SafMaintenanceDocuments(factory,a,root("b"),root("c"))
                val c=core(a,local)
                val co=MaintenanceCoordinator(c,docs,local) { step -> if(step==MaintenanceStep.AcceptSwitch) {
                    if(lostAck) HarnessFaults.localFault="Ack" to "After" else c.owner.revoke()
                } }
                val d=MaintenanceDestinations(a,root("b").toString(),root("c").toString())
                val req=co.beginChangeSecret(c.authorizeMaintenance(),d,P3Fixtures.id(2),3,parameters=KdfParameters(16384,2))
                val before=control("count").getInt("creates")
                SecretEncoding.text("P6a synthetic wrong").use { wrong -> SecretEncoding.text(P5bOracle.NEW).use { new -> reject { co.credentials(req,wrong,new,new) } } }
                check(control("count").getInt("creates")==before)
                // Failed credentials invalidate the previous proposal, so issue a fresh request.
                val fresh=co.beginChangeSecret(c.authorizeMaintenance(),d,P3Fixtures.id(2),3,parameters=KdfParameters(16384,2))
                val confirmed=SecretEncoding.text(P3Fixtures.DECOY).use { old -> SecretEncoding.text(P5bOracle.NEW).use { new -> co.confirm(co.credentials(fresh,old,new,new)) } }
                val result=co.changeSecret(confirmed); HarnessFaults.clear()
                if(!lostAck) { check(result.code==MaintenanceCode.Cancelled); check(local.read().binding!!.uri==a.uri); c.close() }
                else {
                    check(result.code==MaintenanceCode.SwitchUnknown && c.owner.reconciliationRequired)
                    SecretEncoding.text(P3Fixtures.MAIN).use { main -> SecretEncoding.text(P3Fixtures.DECOY).use { old -> SecretEncoding.text(P5bOracle.NEW).use { new ->
                        val source=mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to old)
                        check(co.reconcileChangeSecret(RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,source)).code==RecoveryCode.CredentialsRejected)
                        val candidate=mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to new)
                        check(co.reconcileChangeSecret(RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,candidate)).code==RecoveryCode.CandidateConfirmed)
                    } } }
                    core(result.candidate!!,local).close()
                }
            }
        }
        // Abrupt death of a real engine worker after durable SwitchIntent; no finally path.
        val restartedSource=seeded(P4Fixtures.image(true)); val restartedName=localName()
        AppPrivateLocalState.open(context,restartedName).use { initialize(it,restartedSource) }
        RemoteHarness(context).use { it.maintenanceDeath(Uri.parse(restartedSource.uri),restartedName) }
        AppPrivateLocalState.open(context,restartedName).use { local ->
            check(local.read().journal!!.stage==JournalStage.SwitchIntent)
            val docs=SafMaintenanceDocuments(factory,restartedSource,root("b"),root("c"))
            SecretEncoding.text(P3Fixtures.MAIN).use { main -> SecretEncoding.text(P3Fixtures.DECOY).use { old -> SecretEncoding.text(P6aHelperService.RESTART_SECRET).use { new ->
                val source=mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to old)
                val recovery=MaintenanceRecovery()
                check(recovery.resolveChangeSecret(local,docs,RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,source)).code==RecoveryCode.CredentialsRejected)
                val candidate=mapOf(P3Fixtures.id(1) to main,P3Fixtures.id(2) to new)
                val resolved=recovery.resolveChangeSecret(local,docs,RecoveryChoice.Candidate,RekeyRecoveryCredentials(source,candidate))
                check(resolved.code==RecoveryCode.CandidateConfirmed)
                core(Artifact(resolved.binding!!.uri,resolved.binding!!.identity!!),local).close()
            } } }
        }

    }

}
