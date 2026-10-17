package com.fantadrom.copperscroll.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import com.fantadrom.copperscroll.NoteSession
import com.fantadrom.copperscroll.R
import kotlin.math.abs

/** Native selection/clipboard/IME, with paragraph metadata and rendered rich text spans. */
class RichNoteEditText(context: Context) : EditText(context) {
    private var session: NoteSession? = null
    private val managedSpans = mutableListOf<Any>()
    private var touchingTodo: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var restoring = false
    private val scale = resources.displayMetrics.density

    init {
        gravity = Gravity.TOP or Gravity.START
        setTextColor(Color.BLACK)
        setHintTextColor(Color.DKGRAY)
        textSize = 18f
        background = null
        setPadding(dp(12), dp(10), dp(12), dp(24))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        isVerticalScrollBarEnabled = false
        isSaveEnabled = false
        contentDescription = context.getString(R.string.note_text)
        setHorizontallyScrolling(false)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (restoring) return
                val current = session ?: return
                val document = current.data.document.replace(
                    start, start + before, s!!.subSequence(start, start + count).toString()
                )
                current.update(current.data.copy(document = document))
            }
            override fun afterTextChanged(s: Editable?) {
                if (!restoring) renderSpans()
            }
        })
    }

    fun bind(next: NoteSession) {
        if (session !== next) {
            val anchor = next.selectionStart
            val focus = next.selectionEnd
            val previousScroll = next.scrollY
            session = next
            restoring = true
            setText(next.data.document.text)
            setSelection(anchor.coerceIn(0, length()), focus.coerceIn(0, length()))
            restoring = false
            renderSpans()
            post { scrollTo(0, previousScroll) }
        } else {
            renderSpans()
        }
        setBackgroundColor(next.data.color)
    }

    fun toggleSelectedTodos() {
        val current = session ?: return
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(0)
        current.update(current.data.copy(document = current.data.document.toggleTodo(start, end)))
        renderSpans()
        setSelection(start, end)
    }

    private fun renderSpans() {
        val document = session?.data?.document ?: return
        val editable = text ?: return
        managedSpans.forEach { editable.removeSpan(it) }
        managedSpans.clear()
        fun attach(span: Any, start: Int, end: Int, flags: Int) {
            editable.setSpan(span, start, end, flags)
            managedSpans += span
        }
        var start = 0
        document.paragraphs.forEachIndexed { index, paragraph ->
            val end = start + paragraph.text.length + if (index < document.paragraphs.lastIndex) 1 else 0
            paragraph.checked?.let { checked ->
                attach(TodoMarginSpan(context, checked, scale), start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
            start = end
        }
        document.styles.forEach { range ->
            val style = range.style
            val typeface = (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
            if (typeface != 0) attach(StyleSpan(typeface), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (style.underline) attach(UnderlineSpan(), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            style.foreground?.let {
                attach(ForegroundColorSpan(it), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        requestLayout()
        invalidate()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!restoring) session?.let { it.selectionStart = selStart; it.selectionEnd = selEnd }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!restoring) session?.scrollY = t
    }

    private fun todoAt(x: Float, y: Float): Int? {
        val document = session?.data?.document ?: return null
        val textLayout = layout ?: return null
        val localY = y.toInt() + scrollY - totalPaddingTop
        if (localY < 0 || localY >= textLayout.height) return null
        val line = textLayout.getLineForVertical(localY)
        val inGutter = if (textLayout.getParagraphDirection(line) > 0)
            x >= paddingLeft && x <= paddingLeft + dp(36)
        else x >= width - paddingRight - dp(36) && x <= width - paddingRight
        if (!inGutter) return null
        val index = document.paragraphAt(textLayout.getLineStart(line))
        if (textLayout.getLineStart(line) != document.paragraphStart(index)) return null
        return index.takeIf { document.paragraphs[it].checked != null }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchingTodo = todoAt(event.x, event.y)
            downX = event.x
            downY = event.y
        }
        touchingTodo?.let { index ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    session?.let { it.update(it.data.copy(document = it.data.document.toggleChecked(index))) }
                    renderSpans()
                    performClick()
                }
                touchingTodo = null
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) touchingTodo = null
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val current = session ?: return
        val index = current.data.document.paragraphAt(selectionStart.coerceAtLeast(0))
        current.data.document.paragraphs[index].checked?.let { checked ->
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                R.id.toggle_current_todo,
                context.getString(if (checked) R.string.mark_incomplete else R.string.mark_complete)
            ))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.toggle_current_todo) {
            session?.let {
                val index = it.data.document.paragraphAt(selectionStart.coerceAtLeast(0))
                it.update(it.data.copy(document = it.data.document.toggleChecked(index)))
                renderSpans()
            }
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun dp(value: Int) = (value * scale).toInt()
}

/** A real stateful Android checkbox drawable in a paragraph's reserved gutter. No text markers. */
private class TodoMarginSpan(context: Context, checked: Boolean, private val density: Float) : LeadingMarginSpan {
    private val checkbox = CheckBox(context).apply { isChecked = checked }.buttonDrawable!!.mutate().apply {
        state = intArrayOf(android.R.attr.state_enabled, if (checked) android.R.attr.state_checked else -android.R.attr.state_checked)
        setTint(Color.DKGRAY)
    }
    override fun getLeadingMargin(first: Boolean): Int = (36 * density).toInt()
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout
    ) {
        if (!first) return
        val size = (24 * density).toInt()
        val left = if (dir > 0) x else x - size
        val centerY = (top + bottom) / 2
        checkbox.bounds = Rect(left, centerY - size / 2, left + size, centerY + size / 2)
        checkbox.draw(c)
    }
}
