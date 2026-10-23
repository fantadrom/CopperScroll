package com.fantadrom.copperscroll.storage.runtime

import android.app.*
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.p6b2harness.P6b2SessionActivity
import com.fantadrom.copperscroll.session.android.ProtectedEditText
import com.fantadrom.copperscroll.session.ui.UiAccess
import com.fantadrom.copperscroll.storage.format.SettingsData
import com.fantadrom.copperscroll.storage.session.SessionCoordinator
import java.util.concurrent.TimeUnit

internal open class P6b2Driver {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val context=instrumentation.targetContext
    @Volatile var current: P6b2SessionActivity?=null
    fun host()=checkNotNull(current) { "HOST_MISSING" }
    fun editor()=host().editor as ProtectedEditText
    fun ui(action: ()->Unit)=instrumentation.runOnMainSync(action)
    fun await(code: String,condition: ()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(true) {
            var ready=false; ui { ready=condition() }
            if(ready) return
            val failure=current?.model?.let { it.lastFailure ?: it.worker.lastFailure }
            check(failure==null) { "$code; worker=${failure?.type}; frames=${failure?.frames}" }
            check(System.nanoTime()<end) { "$code; access=${current?.model?.coordinator?.snapshot()?.access}; reason=${current?.model?.coordinator?.snapshot()?.reason}" }
            Thread.sleep(20) // Condition polling only, never a stabilization delay.
        }
    }
    private val callbacks=object: Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if(a is P6b2SessionActivity) current=a }
        override fun onActivityCreated(a: Activity,b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
    fun start() {
        check(context.packageName.endsWith(".p1runtime"))
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
        instrumentation.startActivitySync(Intent(context,P6b2SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("HOST_FOREGROUND") { current?.let { it.model.ready && it.model.coordinator.snapshot().foreground.ready && it.hasWindowFocus() }==true }
    }
    fun finish() {
        current?.let { a -> ui { a.model.revoke(); a.finish() } }
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
    }
    fun login(decoy: Boolean=false) {
        var epoch: Long?=null
        await("LOGIN_READY") {
            val a=host()
            if(!a.hasWindowFocus() || !a.model.coordinator.snapshot().unlockReady) false
            else { epoch=a.model.login(decoy); true }
        }
        await("LOGIN_PRESENTED") {
            val a=host(); val s=a.model.coordinator.snapshot()
            if(s.epoch!=epoch || s.access==SessionCoordinator.Access.Revoked) error("LOGIN_REVOKED")
            s.presented && a.editor.visibility==View.VISIBLE
        }
    }
    fun lease(settings: SettingsData=SettingsData())=UiAccess.session(host().model.coordinator,host().signals.window,
        checkNotNull(host().model.coordinator.currentCapability()),settings)
    fun shell(command: String): ByteArray = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
}
