package com.fantadrom.copperscroll.storage.runtime

import android.graphics.Rect
import android.view.*
import com.fantadrom.copperscroll.p6b1harness.HarnessWindowInsets

/** A pre-draw condition, never a fixed delay or a retry of a security operation. */
internal fun P6b2Driver.drawnViewport(window: Window,outer: View,code: String,condition: ()->Boolean={ true }) {
    var previous: List<Int>?=null; var stable=0; var callbackFailure: Throwable?=null
    val listener=ViewTreeObserver.OnPreDrawListener {
        try {
        if(HarnessWindowInsets.settled(window,outer) && condition()) {
            val geometry=buildList {
                val at=IntArray(2); outer.getLocationOnScreen(at); addAll(at.toList())
                fun visit(v: View) {
                    if(v.visibility==View.GONE) return
                    addAll(listOf(System.identityHashCode(v),v.left,v.top,v.right,v.bottom,v.paddingLeft,v.paddingTop,v.paddingRight,v.paddingBottom,v.scrollX,v.scrollY))
                    if(v is ViewGroup) for(i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(outer)
            }
            stable=if(previous==geometry) stable+1 else 1; previous=geometry
        } else { previous=null; stable=0 }
        if(stable<2) outer.postInvalidateOnAnimation()
        } catch(failure: Throwable) { callbackFailure=failure }
        true
    }
    ui { outer.viewTreeObserver.addOnPreDrawListener(listener); outer.postInvalidateOnAnimation() }
    try {
        await(code) { callbackFailure!=null || stable>=2 && HarnessWindowInsets.settled(window,outer) && condition() }
        callbackFailure?.let { throw it } // Preserve the original callback stack as a test failure, not a runner crash.
    }
    catch(failure: Throwable) {
        ui { failure.addSuppressed(IllegalStateException("SAFE_VIEWPORT; ${geometryCode(outer)}")) }
        throw failure
    }
    finally { ui { if(outer.viewTreeObserver.isAlive) outer.viewTreeObserver.removeOnPreDrawListener(listener) } }
}
internal fun P6b2Driver.windowFor(view: View): Window {
    val a=host()
    if(view.rootView===a.window.decorView) return a.window
    val own=a.child?.window
    check(own!=null && view.rootView===own.decorView) { "UNKNOWN_OWNED_WINDOW" }
    return own
}
internal fun Window.viewport()=findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)

/** Convert root-view clipping to screen coordinates explicitly (dialogs can have a window offset). */
internal fun visibleOnScreen(view: View): Rect? {
    val visible=Rect(); val offset=android.graphics.Point()
    if(!view.getGlobalVisibleRect(visible,offset)) return null
    val onScreen=IntArray(2); view.getLocationOnScreen(onScreen)
    // globalOffset includes -view.scroll, whereas locationOnScreen is the unscrolled View origin.
    val origin=com.fantadrom.copperscroll.privacytest.GestureCoordinates.rootToScreen(visible.left,visible.top,
        onScreen[0],onScreen[1],view.scrollX,view.scrollY,offset.x,offset.y)
    visible.offsetTo(origin.x.toInt(),origin.y.toInt())
    return visible
}
internal fun geometryCode(view: View): String {
    val at=IntArray(2); view.getLocationOnScreen(at)
    val visible=visibleOnScreen(view)
    return "size=${view.width}x${view.height}; at=${at[0]},${at[1]}; visible=$visible; scroll=${view.scrollX},${view.scrollY}; layoutPending=${view.isLayoutRequested}"
}
internal fun fullyReachable(view: View): Boolean {
    val r=visibleOnScreen(view) ?: return false
    return view.isShown && view.width>0 && view.height>0 && r.width()==view.width && r.height()==view.height
}

internal fun P6b2Driver.tapReachable(view: View) {
    var x=0f; var y=0f
    ui {
        check(fullyReachable(view) && view.isEnabled && view.isClickable) { "TAP_NOT_REACHABLE; ${geometryCode(view)}" }
        val visible=checkNotNull(visibleOnScreen(view)); x=visible.exactCenterX(); y=visible.exactCenterY()
    }
    val down=android.os.SystemClock.uptimeMillis()
    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
        val event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
        try { check(instrumentation.uiAutomation.injectInputEvent(event,true)) { "TAP_INJECTION_FAILED" } }
        finally { event.recycle() }
    }
}
