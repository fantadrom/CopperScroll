package com.fantadrom.copperscroll.session.android

import android.content.*
import android.os.*
import android.view.*
import android.view.accessibility.*
import android.view.inputmethod.*
import android.widget.EditText
import com.fantadrom.copperscroll.session.ui.UiAccess
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** No external export except explicit policy-admitted sensitive clipboard copy. A connection owns
 * a binding generation, never a dynamic reference to the next session's authority. */
open class ProtectedEditText(context: Context): EditText(context) {
    private var authority: UiAccess?=null
    private var generation=0L
    private var hiddenPassword=false
    private var mode: ActionMode?=null
    private var pasteGeneration: Long?=null
    private var eventBindings: java.util.WeakHashMap<AccessibilityEvent,Long>?=null
    private var downX=0f; private var downY=0f
    protected fun bindingGeneration()=generation
    fun readable()=isAttachedToWindow && authority?.active()==true
    fun bindAccess(access: UiAccess, hiddenPassword: Boolean=false) {
        check(Looper.myLooper()==Looper.getMainLooper())
        generation++; authority=null; eventBindings?.clear(); mode?.finish(); mode=null; authority=access; this.hiddenPassword=hiddenPassword
        isSaveEnabled=false; isSaveFromParentEnabled=false
        importantForAutofill=IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture=IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        if(Build.VERSION.SDK_INT>=34) setAccessibilityDataSensitive(ACCESSIBILITY_DATA_SENSITIVE_YES)
        imeOptions=imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        contentDescription=null; hint=null; error=null
        if(!hiddenPassword) setRawInputType(inputType or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        if(hiddenPassword) inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    fun invalidateAccess() {
        check(Looper.myLooper()==Looper.getMainLooper())
        generation++; authority=null; eventBindings?.clear(); mode?.finish(); mode=null; hiddenPassword=false
        BaseInputConnection.removeComposingSpans(text)
        // Do not finish a stale connection: that could commit composition or schedule a late save.
        context.getSystemService(InputMethodManager::class.java).restartInput(this)
        clearFocus(); setText(""); contentDescription=null; hint=null; error=null
        isEnabled=false
    }
    init {
        isSaveEnabled=false; isSaveFromParentEnabled=false
        importantForAutofill=IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture=IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        // Do not call Editor's ProcessText/assist callback: in AOSP it fires intents BEFORE
        // customSelectionActionModeCallback. Filtering a menu alone is not an action boundary.
        setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
        setOnLongClickListener {
            if(readable() && !hiddenPassword) {
                if(selectionStart==selectionEnd && length()>0) {
                    val offset=getOffsetForPosition(downX,downY).coerceIn(0,length()-1)
                    val words=java.text.BreakIterator.getWordInstance().apply { setText(text.toString()) }
                    var from=words.preceding(offset+1).coerceAtLeast(0)
                    var to=words.following(offset).let { if(it<0) length() else it }
                    if(from>0 && Character.isLowSurrogate(text[from])) from--
                    if(to<length() && Character.isLowSurrogate(text[to])) to++
                    setSelection(from,to)
                }
                startActionMode(object: ActionMode.Callback {
                    override fun onCreateActionMode(m: ActionMode,menu: Menu)=true
                    override fun onPrepareActionMode(m: ActionMode,menu: Menu)=true
                    override fun onActionItemClicked(m: ActionMode,item: MenuItem)=false
                    override fun onDestroyActionMode(m: ActionMode) {}
                },ActionMode.TYPE_FLOATING)
            }
            true // Never enter Editor's outbound-drag long-press implementation.
        } // Native drag initiation is disabled separately.
    }
    private fun populate(menu: Menu) {
        menu.clear()
        if(!readable()) return
        val items=linkedMapOf(android.R.id.selectAll to android.R.string.selectAll,
            android.R.id.paste to android.R.string.paste)
        if(!hiddenPassword && authority?.mayCopy()==true) {
            items[android.R.id.copy]=android.R.string.copy; items[android.R.id.cut]=android.R.string.cut
        }
        for((id,label) in items) menu.add(0,id,0,label)
    }
    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = startActionMode(callback,ActionMode.TYPE_PRIMARY)
    override fun startActionMode(callback: ActionMode.Callback,type: Int): ActionMode? {
        if(!readable()) return null
        val captured=generation
        val created=super.startActionMode(object: ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode,menu: Menu): Boolean { populate(menu); return readable() }
            override fun onPrepareActionMode(mode: ActionMode,menu: Menu): Boolean { populate(menu); return true }
            override fun onActionItemClicked(mode: ActionMode,item: MenuItem): Boolean = captured==generation && onTextContextMenuItem(item.itemId)
            override fun onDestroyActionMode(mode: ActionMode) { callback.onDestroyActionMode(mode) }
        },type)
        mode=created; return created
    }
    override fun onCreateContextMenu(menu: ContextMenu) {
        populate(menu)
        val captured=generation
        for(i in 0 until menu.size()) menu.getItem(i).setOnMenuItemClickListener { captured==generation && onTextContextMenuItem(it.itemId) }
    }
    override fun onTextContextMenuItem(id: Int): Boolean {
        if(!readable()) return false
        if(id==android.R.id.copy || id==android.R.id.cut) {
            if(hiddenPassword || authority?.mayCopy()!=true) return false
            val from=minOf(selectionStart,selectionEnd); val to=maxOf(selectionStart,selectionEnd)
            if(from<0 || to<=from) return false
            val clip=ClipData.newPlainText("Copper Scroll",text.subSequence(from,to))
            clip.description.extras=PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) } // literal works on API31/32 too
            if(authority?.mayCopy()!=true) return false
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            if(id==android.R.id.cut && readable()) text.delete(from,to)
            return true
        }
        if(id==android.R.id.selectAll) return super.onTextContextMenuItem(id)
        if(id in setOf(android.R.id.paste,android.R.id.pasteAsPlainText)) {
            pasteGeneration=generation
            return try { super.onTextContextMenuItem(id) } finally { pasteGeneration=null }
        }
        return false
    }
    override fun autofill(value: android.view.autofill.AutofillValue) {} // No autofill contract, even while open.
    override fun autofill(values: android.util.SparseArray<android.view.autofill.AutofillValue>) {}
    override fun performReceiveContent(payload: ContentInfo): ContentInfo? =
        if(readable() && pasteGeneration==generation && payload.source==ContentInfo.SOURCE_CLIPBOARD) super.performReceiveContent(payload) else payload
    override fun onReceiveContent(payload: ContentInfo): ContentInfo? =
        if(readable() && pasteGeneration==generation && payload.source==ContentInfo.SOURCE_CLIPBOARD) super.onReceiveContent(payload) else payload
    override fun onKeyShortcut(code: Int,event: KeyEvent): Boolean = when(code) {
        KeyEvent.KEYCODE_C -> onTextContextMenuItem(android.R.id.copy)
        KeyEvent.KEYCODE_X -> onTextContextMenuItem(android.R.id.cut)
        KeyEvent.KEYCODE_V -> onTextContextMenuItem(android.R.id.paste)
        else -> if(readable()) super.onKeyShortcut(code,event) else false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!readable()) return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN) { downX=event.x; downY=event.y }
        // Editor has a second outbound drag path for mouse MOVE over selected text, bypassing
        // long-click. Cancel that gesture rather than enabling implicit ClipData export.
        if(event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && event.actionMasked==MotionEvent.ACTION_MOVE) {
            val cancel=MotionEvent.obtain(event); cancel.action=MotionEvent.ACTION_CANCEL
            try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
            return true
        }
        return super.onTouchEvent(event)
    }
    override fun onDragEvent(event: DragEvent)=false // No implicit import or export contract.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if(!readable()) return null
        val delegate=super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions=outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.setInitialSurroundingText("") // Never cache a plaintext snapshot in EditorInfo.
        outAttrs.contentMimeTypes=emptyArray(); outAttrs.extras=null
        val captured=generation; val lease=authority
        return Proxy.newProxyInstance(InputConnection::class.java.classLoader,arrayOf(InputConnection::class.java)) { _,method,args ->
            val active=Looper.myLooper()==Looper.getMainLooper() && captured==generation && lease===authority && readable()
            when {
                method.name=="toString" -> "ProtectedInputConnection(redacted)"
                method.name=="hashCode" -> System.identityHashCode(delegate)
                method.name=="equals" -> false
                method.name=="requestTextBoundsInfo" -> {
                    if(Build.VERSION.SDK_INT>=34) denyBounds(args)
                    null
                }
                method.name=="performHandwritingGesture" -> {
                    val executor=args?.getOrNull(1) as? java.util.concurrent.Executor
                    val consumer=args?.getOrNull(2) as? java.util.function.IntConsumer
                    if(executor!=null && consumer!=null) executor.execute { consumer.accept(2) } // API34 UNSUPPORTED (constant value),, no text.
                    null
                }
                !active || method.name in setOf("performPrivateCommand","commitContent","requestTextBoundsInfo","performHandwritingGesture","previewHandwritingGesture","requestCursorUpdates","performSpellCheck","takeSnapshot") || method.name=="getExtractedText" && ((args?.get(1) as? Int ?: 0) and InputConnection.GET_EXTRACTED_TEXT_MONITOR)!=0 ||
                    hiddenPassword && (method.name.startsWith("get") || method.name in setOf("getTextBeforeCursor","getTextAfterCursor","getSelectedText","getExtractedText","getSurroundingText")) ->
                    when(method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; else -> null }
                method.name=="sendKeyEvent" -> {
                    val event=args!![0] as KeyEvent
                    if(event.action==KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode,event) else if(event.action==KeyEvent.ACTION_UP) onKeyUp(event.keyCode,event) else false
                }
                method.name=="performContextMenuAction" -> onTextContextMenuItem(args!![0] as Int)
                else -> try { method.invoke(delegate,*(args ?: emptyArray())) } catch(e: InvocationTargetException) { throw e.targetException }
            }
        } as InputConnection
    }
    @androidx.annotation.RequiresApi(34)
    @Suppress("UNCHECKED_CAST")
    private fun denyBounds(args: Array<out Any?>?) {
        val executor=args?.getOrNull(1) as? java.util.concurrent.Executor ?: return
        val consumer=args.getOrNull(2) as? java.util.function.Consumer<TextBoundsInfoResult> ?: return
        executor.execute { consumer.accept(TextBoundsInfoResult(TextBoundsInfoResult.CODE_CANCELLED)) }
    }
    // An explicitly empty real structure, including virtual descendants; flags alone are insufficient.
    private fun emptyStructure(s: ViewStructure) { s.setClassName(javaClass.name); s.setChildCount(0); s.setText("") }
    override fun onProvideStructure(s: ViewStructure)=emptyStructure(s)
    override fun onProvideVirtualStructure(s: ViewStructure)=emptyStructure(s)
    override fun onProvideAutofillStructure(s: ViewStructure,flags: Int)=emptyStructure(s)
    override fun onProvideAutofillVirtualStructure(s: ViewStructure,flags: Int)=emptyStructure(s)
    override fun onProvideContentCaptureStructure(s: ViewStructure,flags: Int)=emptyStructure(s)
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if(!readable() || hiddenPassword) {
            info.text=if(readable()) "●●●●●" else ""; info.contentDescription=null; info.hintText=null; info.error=null
            info.isPassword=hiddenPassword; info.isEditable=false; info.setTextSelection(-1,-1)
            info.actionList.toList().forEach { info.removeAction(it) }
        } else {
            // Accessibility actions have no epoch token. Permit reading/navigation; editing/export
            // remains on the versioned input/native explicit-action paths, never a stale node action.
            info.actionList.toList().filter { it.id !in allowedAccessibility }.forEach { info.removeAction(it) }
        }
    }
    private fun scrubEvent(event: AccessibilityEvent) {
        event.text.clear(); event.beforeText=null; event.contentDescription=null
        event.fromIndex=-1; event.toIndex=-1; event.itemCount=0; event.addedCount=0; event.removedCount=0
    }
    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        event.text.clear(); event.beforeText=null; event.contentDescription=null
        super.onInitializeAccessibilityEvent(event)
        val bindings=eventBindings ?: java.util.WeakHashMap<AccessibilityEvent,Long>().also { eventBindings=it }
        if(bindings.size>=128) bindings.clear()
        bindings[event]=generation
        event.isPassword=hiddenPassword
        if(!readable() || hiddenPassword) scrubEvent(event)
    }
    override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
        // TextView can add text AFTER initialization. Permit normal current note speech/events,
        // but never a hidden password, uninitialised event or queued event from another binding.
        val eventGeneration=eventBindings?.get(event)
        if(eventGeneration!=generation || !readable() || hiddenPassword) scrubEvent(event)
        try { super.sendAccessibilityEventUnchecked(event) } finally { eventBindings?.remove(event) }
    }
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        if(eventBindings?.get(event)!=generation || !readable() || hiddenPassword) { scrubEvent(event); return false }
        return super.dispatchPopulateAccessibilityEvent(event)
    }
    override fun performAccessibilityAction(action: Int,arguments: Bundle?): Boolean =
        if(!readable() || hiddenPassword || action !in allowedAccessibility) false else super.performAccessibilityAction(action,arguments)
    // TextView.super constructs a plaintext snapshot; disabled saving must remain empty even
    // for an explicit caller. No global lint suppression and no saved-state restore contract.
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onSaveInstanceState(): Parcelable? = null
    override fun onDetachedFromWindow() { invalidateAccess(); super.onDetachedFromWindow() }
    companion object {
        private val allowedAccessibility=setOf(AccessibilityNodeInfo.ACTION_FOCUS,AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
}
