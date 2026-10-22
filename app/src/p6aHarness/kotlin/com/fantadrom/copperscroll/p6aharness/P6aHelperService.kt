package com.fantadrom.copperscroll.p6aharness

import android.app.Service
import android.content.*
import android.os.*
import android.net.Uri
import com.fantadrom.copperscroll.storage.android.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.KdfParameters
import com.fantadrom.copperscroll.storage.format.ContainerReader
import com.fantadrom.copperscroll.storage.maintenance.*
import com.fantadrom.copperscroll.storage.transaction.TransactionCore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Independent Linux process/FD table, same target UID/noBackup namespace. */
class P6aHelperService : Service() {
    private var owner: ResourceOwner?=null
    private var local: AppPrivateLocalState?=null
    private val worker=HandlerThread("p6a-helper-io")
    override fun onCreate() { super.onCreate(); worker.start() }
    private val messenger by lazy { Messenger(object: Handler(worker.looper) {
        override fun handleMessage(message: Message) {
            val out=Bundle(); out.putInt("pid",Process.myPid())
            var die=false
            try {
                when(message.data.getString("action")) {
                    "qualificationStatus" -> out.putAll(QualificationSession.phase(this@P6aHelperService,message.data.getString("name")!!))
                    "qualificationPrepare", "qualificationProcess", "qualificationReboot" -> out.putAll(
                        QualificationSession.execute(this@P6aHelperService,message.data.getString("name")!!,message.data.getString("action")!!))
                    "tryOpen","hold" -> {
                        val store=SafStoreFactory(this@P6aHelperService).openRequired(Uri.parse(message.data.getString("uri")!!))
                        try {
                            val next=ResourceOwner(store)
                            if(message.data.getString("action")=="hold") owner=next else next.close()
                            out.putString("status","opened")
                        } catch(e: Throwable) { try { store.close() } catch(c: Throwable) { e.addSuppressed(c) }; throw e }
                    }
                    "maintenanceDeath" -> {
                        val uri=Uri.parse(message.data.getString("uri")!!)
                        check(uri.authority==packageName+".p6a.documents") // fixed synthetic provider only
                        val name=message.data.getString("name")!!; check(name.startsWith("p6a-test-"))
                        val factory=SafStoreFactory(this@P6aHelperService)
                        local=AppPrivateLocalState.open(this@P6aHelperService,name)
                        val store=factory.openRequired(uri)
                        owner=try { ResourceOwner(store) } catch(e: Throwable) { try { store.close() } catch(c: Throwable) { e.addSuppressed(c) }; throw e }
                        val snapshot=ContainerReader(store).snapshot()
                        val key=SecretEncoding.text("P3 synthetic main").use { CredentialManager().login(snapshot.header.containerId,snapshot.header.zones,it).key }
                        val core=TransactionCore.openBound(owner!!,local!!,key)
                        val source=Artifact(uri.toString(),store.identity)
                        val backup=android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority!!,"b")
                        val candidate=android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority!!,"c")
                        val docs=SafMaintenanceDocuments(factory,source,backup,candidate)
                        val coordinator=MaintenanceCoordinator(core,docs,local!!) { step ->
                            if(step==MaintenanceStep.AcceptSwitch) {
                                check(local!!.read().journal!!.stage==JournalStage.SwitchIntent)
                                message.replyTo.send(Message.obtain().apply { data=Bundle().apply { putInt("pid",Process.myPid()); putString("status","dyingAtSwitchIntent") } })
                                Process.killProcess(Process.myPid()) // real process death; NO exception/finally cleanup
                                error("Expected helper death")
                            }
                        }
                        val request=coordinator.beginChangeSecret(core.authorizeMaintenance(),MaintenanceDestinations(source,backup.toString(),candidate.toString()),
                            snapshot.header.zones.single { it.role==2 }.zoneId,3,parameters=KdfParameters(16384,2))
                        val confirmation=SecretEncoding.text("P3 synthetic decoy").use { old -> SecretEncoding.text(RESTART_SECRET).use { new -> coordinator.confirm(coordinator.credentials(request,old,new,new)) } }
                        coordinator.changeSecret(confirmation)
                        error("Expected switch checkpoint death")
                    }
                    "probeLock" -> {
                        val pfd=contentResolver.openFileDescriptor(Uri.parse(message.data.getString("uri")!!),"rw") ?: error("No FD")
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { stream ->
                            val candidate=stream.channel.tryLock()
                            out.putString("status",if(candidate==null) "Busy" else "opened"); candidate?.release()
                        }
                    }
                    "local" -> { local=AppPrivateLocalState.open(this@P6aHelperService,message.data.getString("name")!!); local!!.read(); out.putString("status","ready") }
                    "release" -> {
                        val previous=owner
                        if(previous!=null) check(previous.awaitQuiescent(0)) { "Helper IO still active" }
                        closeOwned()
                        if(previous!=null && previous.phase!=OwnerPhase.Released) {
                            // Unknown is not resolution. The worker has returned (sequential handler),
                            // so an explicit test-process exit releases its remaining pinned handles.
                            out.putString("status","quiescentUnknownProcessExit"); die=true
                        } else out.putString("status","released")
                    }
                    "die" -> { out.putString("status","dying"); die=true }
                    else -> error("Unknown helper operation")
                }
            } catch(e: StoreException) { out.putString("status",e.code.name) }
            catch(e: Exception) { out.putString("status","error"); out.putString("failureType",e.javaClass.simpleName); e.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) }?.let { out.putString("failureReason",it) } }
            try { message.replyTo.send(Message.obtain().apply { data=out }) }
            finally { if(die) Handler(worker.looper).postDelayed({ Process.killProcess(Process.myPid()) },100) }
        }
    }) }
    companion object { const val RESTART_SECRET="P6a synthetic restart target" }
    override fun onBind(intent: Intent): IBinder=messenger.binder
    private fun closeOwned() {
        var primary: Throwable?=null
        try { owner?.close() } catch(e: Throwable) { primary=e }
        try { local?.close() } catch(e: Throwable) { if(primary==null) primary=e else primary!!.addSuppressed(e) }
        if(owner?.phase==OwnerPhase.Released) owner=null
        local=null; primary?.let { throw it }
    }
    override fun onDestroy() { try { closeOwned() } finally { worker.quitSafely(); super.onDestroy() } }
}

/** Reproducible controller: bounded waits; a timeout is failure and never proof of IO completion. */
internal class RemoteHarness(private val context: Context) : AutoCloseable {
    private val bound=CountDownLatch(1)
    private val dead=CountDownLatch(1)
    private var remote: Messenger?=null
    private val replies=HandlerThread("p6a-replies").also { it.start() }
    private val connection=object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) {
            remote=Messenger(binder); binder.linkToDeath({ dead.countDown() },0); bound.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { remote=null; dead.countDown() }
    }
    init {
        var requested=false
        try {
            requested=context.bindService(Intent(context,P6aHelperService::class.java),connection,Context.BIND_AUTO_CREATE)
            check(requested); check(bound.await(15,TimeUnit.SECONDS)) { "Helper bind timeout" }
        } catch(e: Throwable) {
            if(requested) try { context.unbindService(connection) } catch(c: Throwable) { e.addSuppressed(c) }
            try { replies.quitSafely(); replies.join(5000); check(!replies.isAlive) } catch(c: Throwable) { e.addSuppressed(c) }
            throw e
        }
    }
    fun request(action: String,uri: Uri?=null,name: String?=null): Bundle {
        val done=CountDownLatch(1); var answer: Bundle?=null
        val response=Messenger(object: Handler(replies.looper) { override fun handleMessage(msg: Message) { answer=msg.data; done.countDown() } })
        remote!!.send(Message.obtain().apply { data=Bundle().apply { putString("action",action); uri?.let { putString("uri",it.toString()) }; name?.let { putString("name",it) } }; replyTo=response })
        check(done.await(20,TimeUnit.SECONDS)) { "Helper request timeout; not cancelled" }; return answer!!
    }
    fun maintenanceDeath(uri: Uri,name: String) {
        check(request("maintenanceDeath",uri,name).getString("status")=="dyingAtSwitchIntent")
        check(dead.await(15,TimeUnit.SECONDS)) { "Maintenance helper death not observed" }
    }
    fun die() { check(request("die").getString("status")=="dying"); check(dead.await(10,TimeUnit.SECONDS)) { "Helper death not observed" } }
    override fun close() {
        var primary: Throwable?=null
        fun attempt(block: ()->Unit) { try { block() } catch(e: Throwable) { if(primary==null) primary=e else primary!!.addSuppressed(e) } }
        attempt {
            if(remote?.binder?.isBinderAlive==true) {
                when(request("release").getString("status")) {
                    "released" -> Unit
                    "quiescentUnknownProcessExit" -> check(dead.await(10,TimeUnit.SECONDS))
                    else -> error("Helper cleanup did not complete")
                }
            }
        }
        attempt { context.unbindService(connection) }
        attempt { replies.quitSafely(); replies.join(5000); check(!replies.isAlive) }
        primary?.let { throw it }
    }
}
