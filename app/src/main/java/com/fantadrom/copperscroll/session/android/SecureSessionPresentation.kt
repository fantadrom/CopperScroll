package com.fantadrom.copperscroll.session.android

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import com.fantadrom.copperscroll.storage.session.SessionCoordinator
import java.lang.ref.WeakReference

/** Holds no content. Both this port and child-window tracking use weak UI references. */
class SecureSessionPresentation(activity: Activity,editor: EditText,private val coordinator: SessionCoordinator) : SessionCoordinator.Presentation {
    private val windowPolicy=SessionWindowPolicy(activity)
    private var access: com.fantadrom.copperscroll.session.ui.UiAccess?=null
    private var policyEpoch: Long?=null
    private val activity=WeakReference(activity)
    private val editor=WeakReference(editor)
    private var window: SessionCoordinator.Window?=null
    internal fun bind(window: SessionCoordinator.Window) { check(this.window==null); this.window=window }
    private fun current(epoch: Long)=window?.let { coordinator.isWindowCurrent(it,epoch) }==true
    private val dialogs=ArrayList<WeakReference<Dialog>>()
    init { secure(activity); hide() }
    fun register(dialog: Dialog) {
        check(Looper.myLooper()==Looper.getMainLooper())
        require(dialogs.size<4)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.let { windowPolicy.register(it); windowPolicy.refresh() }
        dialogs.add(WeakReference(dialog))
    }
    /** Worker-authenticated settings; rebind invalidates all previous editor connections. */
    fun applySettings(epoch: Long,settings: com.fantadrom.copperscroll.storage.format.SettingsData): Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        val w=window ?: return false; val cap=coordinator.currentCapability() ?: return false
        if(!current(epoch)) return false
        access?.retire()
        access=com.fantadrom.copperscroll.session.ui.UiAccess.session(coordinator,w,cap,settings)
        policyEpoch=epoch
        (editor.get() as? ProtectedEditText)?.bindAccess(access!!)
        windowPolicy.bind(access)
        return true
    }
    /** Call only with an admitted read result from this epoch, on the UI thread. */
    fun display(epoch: Long,content: CharSequence): Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(!current(epoch)) return false
        if(policyEpoch!=epoch && !applySettings(epoch,com.fantadrom.copperscroll.storage.format.SettingsData())) return false
        windowPolicy.refresh()
        editor.get()?.apply { setText(content); isEnabled=true; isLongClickable=true; visibility=View.VISIBLE }
        return true
    }
    override fun changed(snapshot: SessionCoordinator.Snapshot) {
        val a=activity.get() ?: return
        val update=Runnable {
            windowPolicy.refresh()
            val now=coordinator.snapshot()
            if(!now.presented || !current(now.epoch)) hide() // Queued pre-unlock frames cannot clear a newer authenticated editor.
        }
        if(Looper.myLooper()==Looper.getMainLooper()) update.run() else a.runOnUiThread(update)
    }
    fun hide() {
        access?.retire(); access=null; policyEpoch=null; windowPolicy.bind(null)
        (editor.get() as? ProtectedEditText)?.invalidateAccess()
        editor.get()?.apply {
            android.view.inputmethod.BaseInputConnection.removeComposingSpans(text)
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(windowToken,0)
            clearFocus(); setText(""); setSelection(0)
            isEnabled=false; isLongClickable=false; visibility=View.GONE
        }
        val copy=dialogs.mapNotNull { it.get() }; dialogs.clear()
        copy.forEach { dialog ->
            // The host must register every sensitive editor separately; dismiss removes its window.
            (dialog.window?.decorView as? android.view.ViewGroup)?.let(::clearEditors)
            dialog.dismiss()
        }
    }
    private fun clearEditors(group: android.view.ViewGroup) {
        for(i in 0 until group.childCount) when(val child=group.getChildAt(i)) {
            is ProtectedEditText -> child.invalidateAccess()
            is EditText -> { child.setText(""); child.clearFocus(); child.isEnabled=false }
            is android.view.ViewGroup -> clearEditors(child)
        }
    }
    companion object {
        fun secure(activity: Activity) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if(android.os.Build.VERSION.SDK_INT>=33) activity.setRecentsScreenshotEnabled(false)
        }
    }
}
