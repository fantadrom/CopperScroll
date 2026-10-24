package com.fantadrom.copperscroll.session.android

import android.app.Activity
import android.os.*
import android.view.*
import com.fantadrom.copperscroll.session.ui.UiAccess
import java.lang.ref.WeakReference

/** Must be installed before the first sensitive frame on every Activity/Dialog/Popup window.
 * A queued allow result cannot disable protection after revoke, rebind, or old-window retirement. */
class SessionWindowPolicy(activity: Activity) {
    private val activity=WeakReference(activity)
    private val windows=ArrayList<WeakReference<Window>>()
    private var authority: UiAccess?=null
    init { register(activity.window) }
    fun register(window: Window) {
        check(Looper.myLooper()==Looper.getMainLooper())
        windows.removeAll { it.get()==null }
        require(windows.size<16)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        windows.add(WeakReference(window))
    }
    fun bind(access: UiAccess?) { check(Looper.myLooper()==Looper.getMainLooper()); authority=access; refresh() }
    fun refresh() {
        val a=activity.get() ?: return
        val apply=Runnable {
            val allowed=authority?.mayCapture()==true
            windows.mapNotNull { it.get() }.forEach {
                if(allowed) it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) else it.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            if(Build.VERSION.SDK_INT>=33) a.setRecentsScreenshotEnabled(allowed)
        }
        if(Looper.myLooper()==Looper.getMainLooper()) apply.run() else a.runOnUiThread(apply)
    }
}
