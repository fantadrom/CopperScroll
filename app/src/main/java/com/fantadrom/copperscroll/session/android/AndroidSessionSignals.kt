package com.fantadrom.copperscroll.session.android

import android.app.Activity
import android.app.KeyguardManager
import android.content.*
import android.os.PowerManager
import com.fantadrom.copperscroll.storage.session.*
import java.lang.ref.WeakReference

/** Application lifetime receiver, retained beside the memory-only session owner during recreation.
 * External broadcasts can only remove access. They never authenticate or enable presentation. */
class SessionSystemSignals(context: Context,private val coordinator: SessionCoordinator,private val worker: SessionWorker,private val observation: (String)->Unit = {}) : AutoCloseable {
    private val app=context.applicationContext
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            val reason=when(intent.action) {
                Intent.ACTION_SCREEN_OFF -> SessionCoordinator.Reason.ScreenOff
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> when(intent.getStringExtra("reason")) {
                    "homekey","recentapps" -> SessionCoordinator.Reason.Home
                    else -> SessionCoordinator.Reason.FocusLost
                }
                else -> return
            }
            observation(if(intent.action==Intent.ACTION_SCREEN_OFF) "ACTUAL_SCREEN_OFF" else "ACTUAL_SYSTEM_DIALOGS")
            coordinator.hardRevoke(reason); worker.drainRevocation()
        }
    }
    init {
        val filter=IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) }
        androidx.core.content.ContextCompat.registerReceiver(app,receiver,filter,androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
    override fun close() { app.unregisterReceiver(receiver) }
}

/** Explicit Activity callbacks. RESUMED alone grants nothing. No Activity reference is retained
 * by the coordinator/worker; a detached adapter cannot influence a replacement window. */
class AndroidSessionSignals(activity: Activity,private val coordinator: SessionCoordinator,
    private val worker: SessionWorker,presentation: SecureSessionPresentation,transfer: SessionCoordinator.Handoff?=null) {
    private val activity=WeakReference(activity)
    val window=coordinator.attach(presentation,transfer).also { presentation.bind(it) }
    private var seq=0L; private var visible=false; private var resumed=false; private var top=false
    private var focused=false; private var detached=false; private var transfer: SessionCoordinator.Handoff?=null
    private var own: SessionCoordinator.OwnWindow?=null
    private var expectedPickerLeave=false
    private fun publish() {
        val a=activity.get() ?: return
        if(detached) return
        check(seq<Long.MAX_VALUE)
        val power=a.getSystemService(PowerManager::class.java)
        val keyguard=a.getSystemService(KeyguardManager::class.java)
        coordinator.signals(window,++seq,SessionCoordinator.Signals(visible && a.window.decorView.windowVisibility==android.view.View.VISIBLE,
            power.isInteractive,!keyguard.isDeviceLocked,resumed && top,focused,own))
        worker.drainRevocation()
    }
    fun onWindowVisibilityChanged() { configurationIfKnown(); publish() }
    fun onStart() { visible=true; publish() }
    fun onResume() { expectedPickerLeave=false; resumed=true; activity.get()?.let { focused=it.hasWindowFocus() }; publish() }
    fun onTopResumedActivityChanged(value: Boolean) { top=value; configurationIfKnown(); publish() }
    fun onWindowFocusChanged(value: Boolean) { focused=value; configurationIfKnown(); publish() }
    fun onPause() { configurationIfKnown(); resumed=false; top=false; publish() }
    fun onStop() { configurationIfKnown(); visible=false; publish() }
    fun onUserLeaveHint() {
        if(detached || !coordinator.ownsWindow(window)) return
        if(expectedPickerLeave) expectedPickerLeave=false
        else { coordinator.hardRevoke(SessionCoordinator.Reason.Home); worker.drainRevocation() }
    }
    fun beforeOwnedPicker() { expectedPickerLeave=true }
    private fun configurationIfKnown() {
        if(activity.get()?.isChangingConfigurations==true && transfer==null) beginConfiguration()
    }
    fun beginConfiguration(): SessionCoordinator.Handoff = transfer ?: coordinator.beginHandoff(window).also { transfer=it }
    fun ownWindowOpened(kind: SessionCoordinator.ScopeKind): SessionCoordinator.OwnWindow = coordinator.registerOwnWindow(window,kind).also { own=it }
    /** Child window focus must be observed too: a shade over an own dialog is not own focus. */
    fun childFocus(scope: SessionCoordinator.OwnWindow,value: Boolean) {
        if(detached || !coordinator.ownsWindow(window) || own!==scope || transfer!=null) return
        if(!value) { own=null; coordinator.hardRevoke(SessionCoordinator.Reason.FocusLost); worker.drainRevocation() }
    }
    fun ownWindowClosed(scope: SessionCoordinator.OwnWindow) { if(own===scope) own=null; coordinator.closeOwnWindow(scope); publish() }
    fun detach() {
        if(detached) return
        detached=true; coordinator.detach(window,transfer); worker.drainRevocation(); activity.clear()
    }
}
