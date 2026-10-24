package com.fantadrom.copperscroll.storage.runtime

import android.content.Context
import android.os.SystemClock
import android.view.*
import com.fantadrom.copperscroll.session.android.ProtectedEditText

/** Instrumentation-only observer: the gesture is injected at the current view position. */
internal class LongPressEditor(context: Context): ProtectedEditText(context) {
    var completed=0; private set
    private var depth=0
    var atOffset: (() -> Unit)?=null
    var atSelection: (() -> Unit)?=null
    private fun observe(action: ()->Boolean): Boolean {
        depth++
        try { return action() } finally { if(--depth==0) completed++ }
    }
    override fun performLongClick()=observe { super.performLongClick() }
    override fun performLongClick(x: Float,y: Float)=observe { super.performLongClick(x,y) }
    override fun getOffsetForPosition(x: Float,y: Float): Int {
        val position=super.getOffsetForPosition(x,y)
        if(depth>0) { val hook=atOffset; atOffset=null; hook?.invoke() }
        return position
    }
    override fun onSelectionChanged(start: Int,end: Int) {
        super.onSelectionChanged(start,end)
        if(depth>0 && start!=end) { val hook=atSelection; atSelection=null; hook?.invoke() }
    }
}

internal fun P6b2Driver.longPress(editor: LongPressEditor,offset: Int) {
    var before=0; var x=0f; var y=0f
    await("GESTURE_LAYOUT") { editor.isShown && !editor.isLayoutRequested && editor.layout!=null }
    ui {
        // Native scroll/focus may change layout: reveal the requested line first, then remeasure.
        editor.requestFocus(); editor.setSelection(offset.coerceIn(0,editor.length()))
        val line=editor.layout.getLineForOffset(offset.coerceIn(0,editor.length()))
        editor.requestRectangleOnScreen(android.graphics.Rect(0,editor.totalPaddingTop+editor.layout.getLineTop(line),editor.width,editor.totalPaddingTop+editor.layout.getLineBottom(line)),true)
    }
    await("GESTURE_VISIBLE_LAYOUT") { !editor.isLayoutRequested && editor.layout!=null }
    ui {
        val p=offset.coerceIn(0,editor.length()); val line=editor.layout.getLineForOffset(p)
        val at=IntArray(2); editor.getLocationOnScreen(at)
        x=at[0]+editor.totalPaddingLeft+editor.layout.getPrimaryHorizontal(p)-editor.scrollX
        y=at[1]+editor.totalPaddingTop+(editor.layout.getLineTop(line)+editor.layout.getLineBottom(line))/2f-editor.scrollY
        // End-of-line caret can be exactly at the right boundary; keep the touch inside the View.
        x=x.coerceIn(at[0]+editor.paddingLeft+1f,at[0]+editor.width-editor.paddingRight-1f)
        val visible=android.graphics.Rect(); check(editor.getGlobalVisibleRect(visible)) { "GESTURE_NOT_VISIBLE" }
        check(visible.contains(x.toInt(),y.toInt())) { "GESTURE_OUTSIDE_VIEWPORT" }
        before=editor.completed
    }
    val down=SystemClock.uptimeMillis()
    fun inject(action: Int) {
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
        try { check(instrumentation.uiAutomation.injectInputEvent(event,true)) { "GESTURE_INJECTION_FAILED" } }
        finally { event.recycle() }
    }
    inject(MotionEvent.ACTION_DOWN)
    try { await("REAL_LONG_PRESS_NOT_DELIVERED") { editor.completed>before } }
    finally { inject(MotionEvent.ACTION_UP) }
}
