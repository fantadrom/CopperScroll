package com.fantadrom.copperscroll.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.fantadrom.copperscroll.NoteSession
import com.fantadrom.copperscroll.R
import kotlin.math.max

/** The scroll rail has its own width, so it can never cover the document or todo gutter. */
class NoteTextArea(context: Context) : LinearLayout(context) {
    val editor = RichNoteEditText(context)
    private val rail = ScrollRail(context, editor)
    init {
        orientation = HORIZONTAL
        addView(editor, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(rail, LayoutParams((28 * resources.displayMetrics.density).toInt(), LayoutParams.MATCH_PARENT))
        editor.viewTreeObserver.addOnScrollChangedListener { rail.invalidate() }
        editor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rail.invalidate() }
    }
    fun bind(session: NoteSession) {
        editor.bind(session)
        rail.setBackgroundColor(session.data.color)
        rail.invalidate()
    }
}

private class ScrollRail(context: Context, private val editor: RichNoteEditText) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var dragOffset = 0f
    private var dragging = false
    init {
        contentDescription = context.getString(R.string.scroll_note)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }
    private fun contentHeight(): Int = (editor.layout?.height ?: 0) + editor.totalPaddingTop + editor.totalPaddingBottom
    private fun maxScroll() = max(0, contentHeight() - editor.height)
    private fun thumbHeight() = max(32f * density, height.toFloat() * editor.height / max(1, contentHeight())).coerceAtMost(height.toFloat())
    private fun thumbTop(): Float = if (maxScroll() == 0) 0f else
        (height - thumbHeight()) * editor.scrollY.coerceIn(0, maxScroll()) / maxScroll()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (maxScroll() == 0) return
        paint.color = Color.LTGRAY
        canvas.drawRoundRect(width / 2f - density, 0f, width / 2f + density, height.toFloat(), density, density, paint)
        paint.color = Color.DKGRAY
        canvas.drawRoundRect(width * .3f, thumbTop(), width * .7f, thumbTop() + thumbHeight(), 4 * density, 4 * density, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (maxScroll() == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent.requestDisallowInterceptTouchEvent(true)
                val top = thumbTop()
                dragOffset = if (event.y in top..(top + thumbHeight())) event.y - top else thumbHeight() / 2
                scrollToPointer(event.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) scrollToPointer(event.y)
            MotionEvent.ACTION_UP -> { dragging = false; performClick() }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
    private fun scrollToPointer(y: Float) {
        val travel = height - thumbHeight()
        if (travel > 0) editor.scrollTo(0, (((y - dragOffset) / travel).coerceIn(0f, 1f) * maxScroll()).toInt())
        invalidate()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = maxScroll() > 0
        if (maxScroll() > 0) {
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val forward = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val backward = android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (action == forward || action == backward) {
            val step = editor.height * if (action == forward) 1 else -1
            editor.scrollTo(0, (editor.scrollY + step).coerceIn(0, maxScroll()))
            invalidate()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
