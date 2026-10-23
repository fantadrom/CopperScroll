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
        dialogs.add(WeakReference(dialog))
    }
    /** Call only with an admitted read result from this epoch, on the UI thread. */
    fun display(epoch: Long,content: CharSequence): Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(!current(epoch)) return false
        editor.get()?.apply { setText(content); isEnabled=true; visibility=View.VISIBLE }
        return true
    }
    override fun changed(snapshot: SessionCoordinator.Snapshot) {
        val a=activity.get() ?: return
        val update=Runnable {
            secure(a)
            val now=coordinator.snapshot()
            if(!now.presented || !current(now.epoch)) hide() // Queued pre-unlock frames cannot clear a newer authenticated editor.
        }
        if(Looper.myLooper()==Looper.getMainLooper()) update.run() else a.runOnUiThread(update)
    }
    fun hide() {
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
