package com.fantadrom.copperscroll.p6b1harness

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.fantadrom.copperscroll.storage.session.*
import com.fantadrom.copperscroll.session.android.SessionSystemSignals
import com.fantadrom.copperscroll.sessiontest.SyntheticSession
import com.fantadrom.copperscroll.storage.access.SecretEncoding
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import java.util.concurrent.*

/** Test-only memory bootstrap. No Activity/View, saved state, files, production provider admission,
 * automatic relogin, real user URI, or password prompt. */
class P6b1HostModel(app: Application): AndroidViewModel(app) {
    val coordinator=SessionCoordinator()
    val worker=SessionWorker(coordinator)
    private val system=SessionSystemSignals(app,coordinator,worker) { event(it) }
    @Volatile var fixture: SyntheticSession?=null; private set
    @Volatile var ready=false; private set
    @Volatile var lastFailure: SessionWorker.Failure?=null; private set
    @Volatile var authenticated: AuthenticatedSession?=null
    @Volatile var loginEntered: CountDownLatch?=null
    @Volatile var loginContinue: CountDownLatch?=null
    @Volatile var preparedCheckpoint: FinalCheckpoint?=null
    @Volatile var ioEntered: CountDownLatch?=null
    @Volatile var ioContinue: CountDownLatch?=null
    @Volatile var ioOutcome: WriteCode?=null
    @Volatile var draft="P6b1 synthetic editor"
    @Volatile var savedBundleKeys=-1
    var transfer: SessionCoordinator.Handoff?=null
    var picker: com.fantadrom.copperscroll.storage.android.SafPickerPort.Token?=null
    val pickerPort=com.fantadrom.copperscroll.storage.android.SafPickerPort()
    private val setup=Executors.newSingleThreadExecutor { r -> Thread(r,"p6b1-fixture").apply { isDaemon=true } }
    val manualSteps=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var screenOffObserved=false; private set
    val events=java.util.ArrayDeque<String>()
    @Synchronized fun event(value: String) { require(value.matches(Regex("[A-Za-z0-9_:=-]{1,100}"))); if(value=="ACTUAL_SCREEN_OFF") screenOffObserved=true; if(events.size==128) events.removeFirst(); events.addLast(value) }
    @Synchronized fun eventCopy()=events.toList()
    init { setup.execute { val f=SyntheticSession(); fixture=f; coordinator.selectDocument(f.document); ready=true } }
    fun login(decoy: Boolean) {
        check(ready)
        val f=fixture!!; val attempt=coordinator.beginUnlock()
        authenticated=null
        if(loginEntered==null && loginContinue==null) {
            worker.unlock(attempt,object: SessionWorker.LoginSource {
                private var opened: ResourceOwner?=null
                override fun owner()=f.owner().also { opened=it }
                override fun local()=f.local(checkNotNull(opened).store)
                override fun secret()=SecretEncoding.text(if(decoy) SyntheticSession.DECOY else SyntheticSession.MAIN)
            })
            return
        }
        check(coordinator.submitUnlock(attempt))
        // Controlled KDF-result delivery barrier: real credentials/core, not an injected role.
        setup.execute {
            var owned: ResourceOwner?=null
            try {
                val owner=f.owner(); owned=owner
                if(!coordinator.claimUnlock(attempt,owner)) { owner.revoke(); owner.release(); return@execute }
                val result=SecretEncoding.text(if(decoy) SyntheticSession.DECOY else SyntheticSession.MAIN).use {
                    SessionAuthentication.authenticate(owner,f.local(owner.store),f.document,it)
                }
                loginEntered?.countDown(); loginContinue?.let { check(it.await(30,TimeUnit.SECONDS)) }
                draft="P6b1 synthetic editor"
                if(coordinator.completeUnlock(attempt,result)) authenticated=result
                else { owner.revoke(); owner.release() }
            } catch(e: Throwable) { coordinator.rejectUnlock(attempt); owned?.let { it.revoke(); try { it.release() } catch(clean: Throwable) { e.addSuppressed(clean) } }; lastFailure=SessionWorker.Failure(e.javaClass.name,e.stackTrace.take(12).map { "${it.className}.${it.methodName}:${it.lineNumber}" },e.suppressed.size); event("LOGIN_FAILED") } finally { owned?.let { coordinator.unlockFinished(it) }; coordinator.unlockFailed(attempt); coordinator.unlockWorkFinished(attempt); worker.drainRevocation() }
        }
    }
    fun revoke() { coordinator.hardRevoke(SessionCoordinator.Reason.Explicit); worker.drainRevocation() }
    override fun onCleared() {
        loginContinue?.countDown(); ioContinue?.countDown(); system.close(); worker.stopWhenIdle(); setup.shutdown()
    }
}
