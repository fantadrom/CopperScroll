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
    var before=0; var x=0f; var y=0f; lateinit var window: Window
    ui { window=windowFor(editor) }
    val outer=window.viewport()
    drawnViewport(window,outer,"GESTURE_INITIAL_LAYOUT") { editor.isShown && editor.layout!=null }
    ui { editor.requestFocus(); editor.setSelection(offset.coerceIn(0,editor.length())) }
    // setSelection can queue TextView's own scrolling; wait for its pre-draw before revealing the line.
    drawnViewport(window,outer,"GESTURE_FOCUS_LAYOUT") { editor.layout!=null }
    fun requestedLine(): android.graphics.Rect {
        val layout=editor.layout; val p=offset.coerceIn(0,editor.length()); val line=layout.getLineForOffset(p)
        val left=layout.getPrimaryHorizontal(p).toInt()+editor.totalPaddingLeft
        return android.graphics.Rect(left,editor.totalPaddingTop+layout.getLineTop(line),left+2,editor.totalPaddingTop+layout.getLineBottom(line))
    }
    ui { editor.requestRectangleOnScreen(requestedLine(),true) }
    drawnViewport(window,outer,"GESTURE_LINE_NOT_VISIBLE") {
        // Check in screen coordinates on every sample; retry only geometry, never the long press.
        val r=requestedLine(); val at=IntArray(2); editor.getLocationOnScreen(at)
        val point=com.fantadrom.copperscroll.privacytest.GestureCoordinates.screen(
            editor.layout.getPrimaryHorizontal(offset.coerceIn(0,editor.length())),
            (r.top+r.bottom)/2f-editor.totalPaddingTop,editor.totalPaddingLeft,editor.totalPaddingTop,
            editor.scrollX,editor.scrollY,at[0],at[1])
        val visible=visibleOnScreen(editor)
        val touchX=point.x.coerceIn(at[0]+editor.paddingLeft+1f,at[0]+editor.width-editor.paddingRight-1f)
        val valid=visible!=null && visible.contains(touchX.toInt(),point.y.toInt())
        if(!valid) editor.requestRectangleOnScreen(r,true)
        valid
    }
    ui {
        check(com.fantadrom.copperscroll.p6b1harness.HarnessWindowInsets.settled(window,outer)) { "GESTURE_LAYOUT_CHANGED" }
        val p=offset.coerceIn(0,editor.length()); val line=editor.layout.getLineForOffset(p)
        val at=IntArray(2); editor.getLocationOnScreen(at)
        val point=com.fantadrom.copperscroll.privacytest.GestureCoordinates.screen(editor.layout.getPrimaryHorizontal(p),
            (editor.layout.getLineTop(line)+editor.layout.getLineBottom(line))/2f,editor.totalPaddingLeft,editor.totalPaddingTop,
            editor.scrollX,editor.scrollY,at[0],at[1])
        val visible=checkNotNull(visibleOnScreen(editor)) { "GESTURE_NOT_VISIBLE; ${geometryCode(editor)}" }
        x=point.x.coerceIn(at[0]+editor.paddingLeft+1f,at[0]+editor.width-editor.paddingRight-1f); y=point.y
        check(visible.contains(x.toInt(),y.toInt())) { "GESTURE_OUTSIDE_VIEWPORT; ${geometryCode(editor)}" }
        val actual=editor.getOffsetForPosition(x-at[0],y-at[1])
        val snapshot=editor.text.toString()
        check(com.fantadrom.copperscroll.session.ui.WordSelection.range(snapshot,actual)==com.fantadrom.copperscroll.session.ui.WordSelection.range(snapshot,p)) { "GESTURE_TARGET_WORD_MISMATCH" }
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
