package com.fantadrom.copperscroll.p6b1harness

import android.app.*
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.fantadrom.copperscroll.session.android.*
import com.fantadrom.copperscroll.storage.session.SessionCoordinator
import com.fantadrom.copperscroll.storage.android.SafOperation

/** Nonexported, opt-in target only. Explicit synthetic login; never connected to WorkspaceScreen. */
open class P6b1SessionActivity: ComponentActivity() {
    val model by lazy { ViewModelProvider(this)[P6b1HostModel::class.java] }
    lateinit var editor: EditText; private set
    lateinit var signals: AndroidSessionSignals; private set
    lateinit var presentation: SecureSessionPresentation; private set
    lateinit var status: TextView; private set
    var child: Dialog?=null; private set
    var lastPickerRejected=false; private set
    var manualFinished=false; private set
    lateinit var root: SessionWindowRoot; private set
    val scroll get()=root.getChildAt(0) as ScrollView
    private val refresh=android.os.Handler(android.os.Looper.getMainLooper())
    private val tick=object: Runnable {
        override fun run() {
            model.coordinator.currentCapability()?.let { capability ->
                if(model.authenticated!==capability.session) {
                    model.authenticated=capability.session; model.draft="P6b1 synthetic editor"
                }
            }
            val s=model.coordinator.snapshot()
            status.text="P6b.1 synthetic: ${s.access}; epoch=${s.epoch}; owner=${s.owner}; pending=${s.pendingWork}; handoff=${s.handoff}"
            if(s.presented && editor.visibility!=View.VISIBLE) presentation.display(s.epoch,model.draft)
            refresh.postDelayed(this,100)
        }
    }
    override fun onCreate(state: Bundle?) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // test host only; POWER/lock still work
        SecureSessionPresentation.secure(this); super.onCreate(null) // no sensitive saved-state restore
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        status=TextView(this); layout.addView(status)
        editor=ProtectedEditText(this).apply { id=View.NO_ID; isSaveEnabled=false; importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS; importantForContentCapture=View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS }
        editor.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
            override fun afterTextChanged(value: android.text.Editable?) {
                if(!::signals.isInitialized || !model.coordinator.ownsWindow(signals.window)) return
                val state=model.coordinator.snapshot()
                if(!state.presented) {
                    if(!state.handoff) model.draft=""
                    if(!value.isNullOrEmpty()) value!!.clear()
                    return
                }
                val text=value.toString()
                if(text==model.draft) return
                val capability=model.coordinator.currentCapability() ?: return
                try {
                    val ticket=model.coordinator.admit(capability,SessionCoordinator.Command.Edit)
                    if(model.coordinator.claimCommand(ticket)) model.draft=text
                    model.coordinator.commandFinished(ticket)
                } catch(_: com.fantadrom.copperscroll.storage.io.StoreException) { editor.setText(model.draft) }
            }
        })
        layout.addView(editor)
        fun button(label: String,action: ()->Unit) { layout.addView(Button(this).apply { text=label; setOnClickListener { action() } }) }
        button("Synthetic Main") { if(model.ready && model.coordinator.snapshot().owner==null && !model.coordinator.snapshot().pendingWork && model.coordinator.snapshot().access!=SessionCoordinator.Access.Unlocking) try { model.login(false) } catch(_: com.fantadrom.copperscroll.storage.io.StoreException) { model.event("LOGIN_BUSY") } }
        button("Synthetic Decoy") { if(model.ready && model.coordinator.snapshot().owner==null && !model.coordinator.snapshot().pendingWork && model.coordinator.snapshot().access!=SessionCoordinator.Access.Unlocking) try { model.login(true) } catch(_: com.fantadrom.copperscroll.storage.io.StoreException) { model.event("LOGIN_BUSY") } }
        button("Revoke") { model.revoke() }
        button("Own dialog") { if(model.coordinator.currentCapability()!=null) showOwnDialog() }
        button("Recreate") { recreateOwned() }
        for(step in listOf("screen_off_lock","shade_system_dialog","ime","multiwindow","rotation_resize"))
            button("Confirm observed: $step") { model.manualSteps.add(step); model.event("USER_ACK_$step") }
        button("Finish manual probe") { model.event("USER_FINISHED"); manualFinished=true }
        root=SessionWindowRoot(this).apply { addView(ScrollView(this@P6b1SessionActivity).apply { addView(layout) }) }; setContentView(root)
        HarnessWindowInsets.install(window,root)
        presentation=SecureSessionPresentation(this,editor,model.coordinator)
        signals=AndroidSessionSignals(this,model.coordinator,model.worker,presentation,model.transfer)
        root.windowSignal={ signals.onWindowVisibilityChanged() }
        model.transfer=null
        refresh.post(tick)
    }
    fun addProbeControls(view: View) {
        val scroll=root.getChildAt(0) as ScrollView
        (scroll.getChildAt(0) as LinearLayout).addView(view)
    }
    fun showOwnDialog(input: ProtectedEditText=ProtectedEditText(this)): ProtectedEditText {
        check(child==null)
        val scope=signals.ownWindowOpened(SessionCoordinator.ScopeKind.Modal)
        input.apply { bindAccess(com.fantadrom.copperscroll.session.ui.UiAccess.session(model.coordinator,signals.window,checkNotNull(model.coordinator.currentCapability()))); setText("P6b1 synthetic dialog"); isSaveEnabled=false; id=View.NO_ID }
        val dialog=Dialog(this)
        dialog.window!!.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; addView(input)
            addView(Button(this@P6b1SessionActivity).apply { text="Close synthetic dialog"; setOnClickListener { dialog.dismiss() } })
        }
        val outer=FrameLayout(this).apply { addView(ScrollView(this@P6b1SessionActivity).apply { addView(content) }) }
        dialog.setContentView(outer); HarnessWindowInsets.install(dialog.window!!,outer)
        presentation.register(dialog)
        dialog.setOnDismissListener { signals.ownWindowClosed(scope); child=null }
        dialog.window!!.decorView.viewTreeObserver.addOnWindowFocusChangeListener { if(it || dialog.isShowing) signals.childFocus(scope,it) }
        child=dialog; dialog.show()
        dialog.window!!.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
        model.event("OWN_DIALOG_SHOWN")
        return input
    }
    fun recreateOwned() { model.transfer=signals.beginConfiguration(); recreate() }
    fun launchPicker() {
        model.picker=model.pickerPort.beginLocked(model.coordinator,SafOperation.OpenOriginal)
        model.worker.drainRevocation(); signals.beforeOwnedPicker()
        startActivityForResult(Intent(this,P6b1PickerActivity::class.java),71)
    }
    @Deprecated("Framework test of real result ordering")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==71) {
            val token=model.picker; model.picker=null
            if(token!=null) try { model.pickerPort.acceptBound(model.coordinator,signals.window,token,resultCode,data,contentResolver) }
                catch(_: com.fantadrom.copperscroll.storage.io.StoreException) { lastPickerRejected=true }
            model.event("PICKER_RETURN_LOCKED")
        }
    }
    override fun onStart() { super.onStart(); signals.onStart(); model.event("ACTUAL_START") }
    override fun onResume() { super.onResume(); signals.onResume(); model.event("ACTUAL_RESUME") }
    override fun onTopResumedActivityChanged(top: Boolean) { super.onTopResumedActivityChanged(top); if(::signals.isInitialized) { signals.onTopResumedActivityChanged(top); model.event("ACTUAL_TOP_$top") } }
    override fun onWindowFocusChanged(focus: Boolean) { super.onWindowFocusChanged(focus); if(::signals.isInitialized) { signals.onWindowFocusChanged(focus); model.event("ACTUAL_FOCUS_$focus") } }
    override fun onUserLeaveHint() { signals.onUserLeaveHint(); model.event("ACTUAL_LEAVE"); super.onUserLeaveHint() }
    override fun onPause() { signals.onPause(); model.event("ACTUAL_PAUSE"); super.onPause() }
    override fun onStop() { signals.onStop(); model.event("ACTUAL_STOP"); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.clear(); model.savedBundleKeys=outState.size() // no content/session restore; handoff is memory-only
    }
    override fun onDestroy() {
        refresh.removeCallbacks(tick)
        if(isChangingConfigurations && model.transfer==null) model.transfer=signals.beginConfiguration()
        root.windowSignal=null; signals.detach(); super.onDestroy()
    }
}
class P6b1PickerActivity: Activity() {
    override fun onCreate(state: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); super.onCreate(null)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val outer=FrameLayout(this).apply {
            addView(ScrollView(this@P6b1PickerActivity).apply {
                addView(Button(this@P6b1PickerActivity).apply { text="Cancel synthetic picker"; setOnClickListener { setResult(RESULT_CANCELED); finish() } })
            })
        }
        setContentView(outer); HarnessWindowInsets.install(window,outer)
    }
}
