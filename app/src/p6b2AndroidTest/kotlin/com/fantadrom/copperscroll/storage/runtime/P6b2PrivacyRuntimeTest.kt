package com.fantadrom.copperscroll.storage.runtime

import android.content.*
import android.os.*
import android.view.*
import android.view.accessibility.*
import android.view.inputmethod.*
import com.fantadrom.copperscroll.*
import com.fantadrom.copperscroll.editor.*
import com.fantadrom.copperscroll.session.android.*
import com.fantadrom.copperscroll.privacytest.CanaryAudit
import com.fantadrom.copperscroll.storage.format.SettingsData
import com.fantadrom.copperscroll.storage.session.SessionCoordinator
import com.fantadrom.copperscroll.storage.vfs.ZoneRepository
import com.fantadrom.copperscroll.storage.transaction.WriteCode
import org.junit.*
import org.junit.Assert.*

/** Fourteen exact independent Android boundaries, synthetic only. No OEM/IME-service certification. */
class P6b2PrivacyRuntimeTest {
    private val d=P6b2Driver()
    @Before fun before()=d.start()
    @After fun after()=d.finish()
    @Test fun sessionPoliciesFollowAuthenticatedZones() {
        d.login(); val repo=ZoneRepository(d.host().model.authenticated!!.core)
        val settings=SettingsData(copyBlocked=false,screenshotsBlocked=false)
        assertEquals(WriteCode.Committed,repo.core.submit(repo.issueSettings(repo.stamp(),settings)).code)
        val actual=repo.settings()
        d.ui { assertTrue(d.host().presentation.applySettings(d.host().model.coordinator.snapshot().epoch,actual)); assertTrue(d.lease(actual).mayCopy()) }
        d.ui { d.host().model.revoke() }; d.login(true)
        val decoy=ZoneRepository(d.host().model.authenticated!!.core).settings()
        d.ui { assertTrue(decoy.copyBlocked); assertTrue(decoy.screenshotsBlocked); assertFalse(d.lease(decoy).mayCopy()) }
    }
    @Test fun staleConnectionCannotReadOrMutateNextSession() {
        d.login(); lateinit var old: InputConnection
        d.ui { val e=d.editor(); e.setSelection(e.length()); old=checkNotNull(e.onCreateInputConnection(EditorInfo())); assertTrue(old.commitText("synthetic active",1)) }
        d.ui { d.host().model.revoke() }; d.login(true)
        d.ui {
            val e=d.editor(); val before=e.text.toString()
            assertNull(old.getTextBeforeCursor(100,0)); assertNull(old.getTextAfterCursor(100,0)); assertNull(old.getSelectedText(0))
            assertNull(old.getExtractedText(ExtractedTextRequest(),0)); assertNull(old.getSurroundingText(10,10,0))
            if(Build.VERSION.SDK_INT>=33) assertNull(old.takeSnapshot())
            assertFalse(old.commitText("stale synthetic",1)); assertFalse(old.setComposingText("stale",1)); assertFalse(old.deleteSurroundingText(10,10))
            assertFalse(old.deleteSurroundingTextInCodePoints(1,1)); assertFalse(old.setSelection(0,0)); assertFalse(old.performContextMenuAction(android.R.id.paste))
            assertTrue("STALE_CONNECTION_CHANGED_TEXT",before==e.text.toString())
        }
    }
    @Test fun editorInfoAndCredentialQueryBoundary() {
        d.login(); d.ui {
            val e=d.editor(); val info=EditorInfo(); val c=checkNotNull(e.onCreateInputConnection(info))
            for(flag in listOf(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,EditorInfo.IME_FLAG_NO_EXTRACT_UI,EditorInfo.IME_FLAG_NO_FULLSCREEN)) assertTrue(info.imeOptions and flag!=0)
            assertTrue(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS!=0)
            assertTrue(info.contentMimeTypes?.isEmpty()==true); assertTrue(info.getInitialTextBeforeCursor(100,0).isNullOrEmpty())
            if(Build.VERSION.SDK_INT>=34) {
                var status=-1
                c.requestTextBoundsInfo(android.graphics.RectF(),java.util.concurrent.Executor { it.run() }) { response -> status=response.resultCode; assertNull(response.textBoundsInfo) }
                assertEquals(TextBoundsInfoResult.CODE_CANCELLED,status)
            }
            assertFalse(c.performPrivateCommand("synthetic",Bundle()))
            e.bindAccess(d.lease(),hiddenPassword=true); e.setText("synthetic credential"); e.setSelection(e.length())
            val password=checkNotNull(e.onCreateInputConnection(EditorInfo()))
            assertNull(password.getExtractedText(ExtractedTextRequest(),0)); assertNull(password.getTextBeforeCursor(100,0)); assertNull(password.getSurroundingText(5,5,0))
        }
    }
    @Test fun blockedCopyCutAndIndependentPaste() {
        d.login(); d.ui {
            val clipboard=d.context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic control","synthetic pasted"))
            val e=d.editor(); e.setText("synthetic original"); e.setSelection(0,e.length())
            assertFalse(e.onTextContextMenuItem(android.R.id.cut)); assertTrue("CUT_CHANGED_TEXT",e.text.toString()=="synthetic original")
            assertFalse(e.onKeyShortcut(KeyEvent.KEYCODE_C,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_C)))
            assertTrue(e.onTextContextMenuItem(android.R.id.paste)); assertTrue("PASTE_BLOCKED",e.text.toString()=="synthetic pasted")
            d.host().model.revoke(); assertFalse(e.onTextContextMenuItem(android.R.id.paste))
        }
    }
    @Test fun sensitiveCopyDoesNotEnableOtherExports() {
        d.login(); d.ui {
            val e=d.editor(); e.bindAccess(d.lease(SettingsData(copyBlocked=false))); e.setText("synthetic explicit copy"); e.setSelection(0,e.length())
            assertTrue(e.onTextContextMenuItem(android.R.id.copy))
            val clip=d.context.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            assertTrue("SECRET_LABEL",clip.description.label.toString()=="Copper Scroll")
            assertFalse(e.onTextContextMenuItem(android.R.id.shareText)); assertFalse(e.onTextContextMenuItem(android.R.id.textAssist))
            assertFalse(e.performAccessibilityAction(0x01010000,null))
            assertFalse(e.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY,null))
            assertTrue(e.isLongClickable) // Re-enabling the authenticated editor restores native interaction after hide.
            assertTrue(e.performLongClick()) // intercepted before native outbound drag
            d.host().model.revoke(); assertFalse(e.onTextContextMenuItem(android.R.id.copy))
        }
    }
    @Test fun richTextUtf16AndStaleCallbacks() {
        d.login(); d.ui {
            val document=NoteDocument().replace(0,0,"A🔑\ntodo").copy(styles=listOf(StyledRange(1,3,TextStyle(bold=true))))
            val note=NoteSession(NoteData(document),NoteSaveHook { _,_-> error("UNEXPECTED_SAVE") })
            val rich=RichNoteEditText(d.host()); d.host().addContentView(rich,ViewGroup.LayoutParams(400,250)); rich.bind(note); rich.bindAccess(d.lease())
            rich.setSelection(1,3); rich.toggleSelectedTodos()
            assertTrue("UTF16_TEXT_CHANGED",note.data.document.text=="A🔑\ntodo"); assertEquals(1,rich.selectionStart); assertEquals(3,rich.selectionEnd); assertEquals(document.styles,note.data.document.styles)
            val node=AccessibilityNodeInfo.obtain(); rich.onInitializeAccessibilityNodeInfo(node)
            val oldAction=node.actionList.single { (it.id ushr 24)==0x3e }.id
            rich.bindAccess(d.lease())
            val beforeAction=note.data
            assertFalse(rich.performAccessibilityAction(oldAction,null)); assertTrue("STALE_TODO_ACTION_CHANGED_NOTE",beforeAction==note.data)
            val fresh=AccessibilityNodeInfo.obtain(); rich.onInitializeAccessibilityNodeInfo(fresh)
            val newAction=fresh.actionList.single { (it.id ushr 24)==0x3e }.id
            assertNotEquals(oldAction,newAction); assertTrue(rich.performAccessibilityAction(newAction,null))
            val previous=note.data; rich.invalidateAccess(); rich.setText("stale synthetic callback"); rich.toggleSelectedTodos()
            assertTrue("REVOKED_RICH_CALLBACK",previous==note.data)
            (rich.parent as ViewGroup).removeView(rich)
        }
    }
    @Test fun emptyAssistAutofillCaptureStructures() {
        d.login(); d.ui {
            val e=d.editor(); e.setText(CanaryAudit.markers[0])
            for(kind in 0..4) {
                val s=RecordingStructure()
                when(kind) { 0->e.onProvideStructure(s); 1->e.onProvideVirtualStructure(s); 2->e.onProvideAutofillStructure(s,0); 3->e.onProvideAutofillVirtualStructure(s,0); else->e.onProvideContentCaptureStructure(s,0) }
                assertEquals(0,s.children); assertTrue("STRUCTURE_LEAK",s.captured.isEmpty())
            }
            val before=e.text.toString()
            val value=android.view.autofill.AutofillValue.forText("synthetic stale autofill")
            e.autofill(value); e.autofill(android.util.SparseArray<android.view.autofill.AutofillValue>().apply { put(1,value) })
            val incoming=ContentInfo.Builder(ClipData.newPlainText("Synthetic","stale receive content"),ContentInfo.SOURCE_APP).build()
            assertSame(incoming,e.performReceiveContent(incoming)); assertSame(incoming,e.onReceiveContent(incoming))
            assertTrue("AUTOFILL_OR_RECEIVE_CHANGED_TEXT",before==e.text.toString())
        }
    }
    @Test fun hiddenPasswordAccessibilityAndStaleActions() {
        d.login(); d.ui {
            val e=d.editor()
            var observedEvents=0
            val visible=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
            val queued=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
            e.accessibilityDelegate=object: View.AccessibilityDelegate() {
                override fun sendAccessibilityEventUnchecked(host: View,event: AccessibilityEvent) {
                    if(event===visible) { assertTrue("CURRENT_ACCESSIBILITY_EVENT_LOST",event.text.isNotEmpty()); observedEvents++ }
                    if(event===queued) { assertTrue("STALE_ACCESSIBILITY_EVENT_LEAK",event.text.isEmpty()); observedEvents++ }
                }
            }
            e.onInitializeAccessibilityEvent(visible); visible.text.add("synthetic accessible note"); e.sendAccessibilityEventUnchecked(visible)
            e.onInitializeAccessibilityEvent(queued); queued.text.add(CanaryAudit.markers[0])
            e.bindAccess(d.lease()); e.sendAccessibilityEventUnchecked(queued)
            assertEquals(2,observedEvents); e.accessibilityDelegate=null
            e.bindAccess(d.lease(),hiddenPassword=true); e.setText(CanaryAudit.markers[1])
            val node=AccessibilityNodeInfo.obtain(); e.onInitializeAccessibilityNodeInfo(node)
            assertFalse("PASSWORD_NODE_LEAK",CanaryAudit.contains(node.text.toString().toByteArray()))
            assertNull(node.contentDescription); assertNull(node.hintText); assertNull(node.error); assertFalse(node.isEditable)
            val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED); e.onInitializeAccessibilityEvent(event)
            assertTrue(event.text.isEmpty()); assertNull(event.beforeText)
            var delivered=false
            e.accessibilityDelegate=object: View.AccessibilityDelegate() {
                override fun sendAccessibilityEventUnchecked(host: View,event: AccessibilityEvent) {
                    assertTrue(event.text.isEmpty()); assertNull(event.beforeText); assertNull(event.contentDescription); delivered=true
                }
            }
            event.text.add(CanaryAudit.markers[1]); event.beforeText=CanaryAudit.markers[1]
            e.sendAccessibilityEventUnchecked(event); assertTrue(delivered)
            e.accessibilityDelegate=null
            assertFalse(e.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COPY,null))
            d.host().model.revoke(); assertFalse(e.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"stale") }))
        }
    }
    @Test fun windowPolicyRejectsStaleAllowCallbacks() {
        d.login(); d.ui {
            val a=d.host(); val policy=SessionWindowPolicy(a); val lease=d.lease(SettingsData(screenshotsBlocked=false)); policy.bind(lease)
            assertEquals(0,a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            a.model.revoke(); policy.refresh(); assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
            policy.bind(lease); assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        }
    }
    @Test fun savedStateAndCanaryAudit() {
        d.login(); d.ui {
            d.editor().setText(CanaryAudit.markers[0]); val saved=android.util.SparseArray<Parcelable>(); d.editor().id=View.generateViewId(); d.editor().saveHierarchyState(saved)
            assertEquals(0,saved.size()); assertNull(d.editor().onSaveInstanceState())
            val parcel=Parcel.obtain()
            try { d.host().intent.writeToParcel(parcel,0); assertFalse("INTENT_CANARY",CanaryAudit.contains(parcel.marshall())) } finally { parcel.recycle() }
            val negative=Parcel.obtain()
            try { Intent().putExtra("synthetic",CanaryAudit.markers[2]).writeToParcel(negative,0); assertTrue(CanaryAudit.contains(negative.marshall())) } finally { negative.recycle() }
        }
        for(root in listOf(d.context.filesDir,d.context.cacheDir,d.context.noBackupFilesDir,java.io.File(d.context.applicationInfo.dataDir,"shared_prefs"))) CanaryAudit.scan(root)
        assertFalse("PROCESS_LOG_CANARY",CanaryAudit.contains(d.shell("logcat -d --pid=${android.os.Process.myPid()} -t 2000")))
        // Negative control in RAM only: never pollutes the audited roots or diagnostic ZIP.
        assertTrue(CanaryAudit.contains(CanaryAudit.markers[2].toByteArray(Charsets.UTF_16LE)))
    }
    @Test fun recreationRetiresNativeConnection() {
        d.login(); val old=d.host(); lateinit var connection: InputConnection
        d.ui { connection=checkNotNull(d.editor().onCreateInputConnection(EditorInfo())); old.recreateOwned() }
        d.await("NEW_WINDOW_NOT_PRESENTED") { d.host()!==old && d.host().model.coordinator.snapshot().presented && d.host().editor.visibility==View.VISIBLE }
        d.ui { assertNull(connection.getTextAfterCursor(100,0)); assertFalse(connection.commitText("old window",1)); assertTrue(old.editor.text.isEmpty()) }
        assertEquals(0,d.host().model.savedBundleKeys)
    }
    @Test fun longPressAfterReplacementKeepsUtf16AndSessionBoundary() {
        d.login(); lateinit var e: LongPressEditor
        d.ui { e=LongPressEditor(d.host()).apply { showSoftInputOnFocus=false }; d.host().showOwnDialog(e) }
        fun prepare(value: String) {
            d.ui { e.bindAccess(d.lease(SettingsData(copyBlocked=false))); e.setText(value) }
        }
        fun selected(expected: String) { d.ui {
            val value=e.text.toString(); val start=e.selectionStart; val end=e.selectionEnd
            assertTrue("INVALID_UTF16_SELECTION",com.fantadrom.copperscroll.session.ui.WordSelection.boundary(value,start) && com.fantadrom.copperscroll.session.ui.WordSelection.boundary(value,end))
            assertTrue("UNEXPECTED_WORD_SELECTION",start>=0 && end>=start && value.substring(start,end)==expected)
            assertFalse(e.onTextContextMenuItem(android.R.id.shareText)); assertFalse(e.onTextContextMenuItem(android.R.id.textAssist))
        } }
        prepare("original synthetic text with many more words")
        d.longPress(e,10); selected("synthetic")
        d.ui { e.bindAccess(d.lease(SettingsData(copyBlocked=false))); e.text.replace(0,e.length(),"short end") }
        d.longPress(e,1); selected("short")
        d.ui { e.bindAccess(d.lease(SettingsData(copyBlocked=false))) }
        d.longPress(e,9); selected("end")
        prepare("replacement 🔑")
        d.longPress(e,2); selected("replacement")
        d.ui { e.bindAccess(d.lease(SettingsData(copyBlocked=false))) }
        d.longPress(e,Int.MAX_VALUE); selected("🔑")
        d.ui {
            assertTrue(e.onTextContextMenuItem(android.R.id.copy))
            val clip=d.context.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
        prepare(""); d.longPress(e,0); selected("")
        prepare("first line\nsecond word\nlast 🔑")
        d.longPress(e,14); selected("second")
        d.ui { e.bindAccess(d.lease(SettingsData(copyBlocked=false))) }
        d.longPress(e,Int.MAX_VALUE); selected("🔑")
        // Rebinding synchronously during offset calculation retires the captured attempt.
        prepare("old binding synthetic text")
        d.ui { e.atOffset={ e.bindAccess(d.lease()); e.setText("new 🔑"); e.setSelection(e.length()) } }
        d.longPress(e,2)
        d.ui { assertTrue("BINDING_CHANGED_SELECTION",e.selectionStart==e.length() && e.selectionEnd==e.length()) }
        // A real selection callback replaces text and recursively re-enters the long-click path.
        prepare("selection callback synthetic")
        d.ui { e.atSelection={ e.text.replace(0,e.length(),"x"); e.setSelection(1); e.performLongClick() } }
        d.longPress(e,2)
        d.ui { assertEquals(1,e.length()); assertEquals(1,e.selectionStart); assertEquals(1,e.selectionEnd) }
        prepare("revoked attempt")
        d.ui { e.atOffset={ d.host().model.revoke() } }
        d.longPress(e,2)
        d.ui { assertTrue(e.text.isEmpty()); assertFalse(e.readable()); assertFalse(e.onTextContextMenuItem(android.R.id.copy)); assertNull(d.host().child) }
        d.login(true)
        d.ui { e=LongPressEditor(d.host()).apply { showSoftInputOnFocus=false }; d.host().showOwnDialog(e) }
        prepare("fresh session word"); d.longPress(e,16); selected("word")
        d.ui { d.host().child!!.dismiss() }
    }
    @Test fun harnessInsetsRemainOutsideScrollAcrossOrientations() {
        fun geometry(outer: View,window: Window) {
            val decor=window.decorView; val rootAt=IntArray(2); val decorAt=IntArray(2)
            outer.getLocationOnScreen(rootAt); decor.getLocationOnScreen(decorAt)
            val safe=checkNotNull(decor.rootWindowInsets).getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val minimum=kotlin.math.ceil(40.0*outer.resources.displayMetrics.density).toInt()
            assertTrue("TOP_SAFE_VIEWPORT",rootAt[1]-decorAt[1]+outer.paddingTop>=maxOf(minimum,safe.top))
            assertTrue("BOTTOM_SAFE_VIEWPORT",decorAt[1]+decor.height-rootAt[1]-outer.height+outer.paddingBottom>=maxOf(minimum,safe.bottom))
            assertTrue("LEFT_SAFE_VIEWPORT",rootAt[0]-decorAt[0]+outer.paddingLeft>=safe.left)
            assertTrue("RIGHT_SAFE_VIEWPORT",decorAt[0]+decor.width-rootAt[0]-outer.width+outer.paddingRight>=safe.right)
            assertTrue("VIEWPORT_HAS_NO_SPACE",outer.height>outer.paddingTop+outer.paddingBottom)
        }
        val original=d.host().requestedOrientation
        val originalConfiguration=d.host().resources.configuration.orientation
        fun orient(orientation: Int) {
            val previous=d.host(); val previousOrientation=previous.resources.configuration.orientation
            d.ui { previous.requestedOrientation=orientation }
            val expected=if(orientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) android.content.res.Configuration.ORIENTATION_PORTRAIT else android.content.res.Configuration.ORIENTATION_LANDSCAPE
            d.await("ORIENTATION_FOREGROUND") { (previousOrientation==expected || d.host()!==previous) && d.host().resources.configuration.orientation==expected && d.host().hasWindowFocus() && d.host().model.coordinator.snapshot().foreground.ready && !d.host().root.isLayoutRequested }
        }
        var primary: Throwable?=null
        try {
            for(orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                orient(orientation)
                d.ui {
                    val a=d.host(); a.addProbeControls(a.privacyPanel); geometry(a.root,a.window)
                    assertTrue(a.root.getChildAt(0) is android.widget.ScrollView)
                    a.scroll.fullScroll(View.FOCUS_UP)
                }
                d.await("TOP_SCROLL") { d.host().scroll.scrollY==0 && !d.host().root.isLayoutRequested && d.host().privacyPanel.isLaidOut && !d.host().privacyPanel.isLayoutRequested }
                d.ui { val a=d.host(); val visible=android.graphics.Rect(); assertTrue(a.status.getGlobalVisibleRect(visible)); geometry(a.root,a.window); a.scroll.fullScroll(View.FOCUS_DOWN) }
                d.await("BOTTOM_SCROLL") { !d.host().root.isLayoutRequested && !d.host().scroll.canScrollVertically(1) }
                d.ui {
                    val a=d.host(); val last=a.privacyPanel.getChildAt(a.privacyPanel.childCount-1)
                    val visible=android.graphics.Rect(); assertTrue("BOTTOM_CONTROL_NOT_REACHABLE",last.getGlobalVisibleRect(visible) && visible.height()==last.height)
                    geometry(a.root,a.window); a.privacyPanel.let { (it.parent as ViewGroup).removeView(it) }
                }
                d.login()
                d.ui { d.host().scroll.fullScroll(View.FOCUS_UP) }
                d.await("EDITOR_TOP_LAYOUT") { d.host().scroll.scrollY==0 && !d.host().root.isLayoutRequested && !d.editor().isLayoutRequested }
                d.ui {
                    val visible=android.graphics.Rect(); assertTrue("EDITOR_NOT_REACHABLE",d.editor().getGlobalVisibleRect(visible) && visible.height()==d.editor().height)
                    d.host().showOwnDialog()
                }
                d.await("DIALOG_LAYOUT") { d.host().child?.window?.decorView?.let { it.isAttachedToWindow && it.width>0 && it.height>0 && !it.isLayoutRequested }==true }
                d.ui {
                    val dialog=d.host().child!!; val outer=(dialog.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
                    geometry(outer,dialog.window!!); assertTrue((outer as ViewGroup).getChildAt(0) is android.widget.ScrollView)
                    val scroll=outer.getChildAt(0) as android.widget.ScrollView
                    scroll.fullScroll(View.FOCUS_DOWN)
                }
                d.await("DIALOG_BOTTOM_SCROLL") {
                    val content=d.host().child!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                    (content.getChildAt(0) as android.widget.ScrollView).canScrollVertically(1)==false
                }
                d.ui {
                    val dialog=d.host().child!!
                    val outer=dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                    val content=(outer.getChildAt(0) as android.widget.ScrollView).getChildAt(0) as ViewGroup
                    val close=content.getChildAt(content.childCount-1); val visible=android.graphics.Rect()
                    assertTrue("DIALOG_CLOSE_NOT_REACHABLE",close.getGlobalVisibleRect(visible) && visible.height()==close.height)
                    dialog.dismiss(); d.host().model.revoke()
                }
                d.await("LOCKED_BEFORE_PICKER") { d.host().hasWindowFocus() && d.host().model.coordinator.snapshot().unlockReady }
                val monitor=d.instrumentation.addMonitor(com.fantadrom.copperscroll.p6b1harness.P6b1PickerActivity::class.java.name,null,false)
                try {
                    d.ui { d.host().launchPicker() }
                    val picker=checkNotNull(d.instrumentation.waitForMonitorWithTimeout(monitor,40000)) { "PICKER_MISSING" }
                    d.await("PICKER_LAYOUT") { picker.hasWindowFocus() && picker.window.decorView.width>0 && !picker.window.decorView.isLayoutRequested }
                    d.ui {
                        val outer=picker.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        geometry(outer,picker.window)
                        val scroll=(outer as ViewGroup).getChildAt(0) as android.widget.ScrollView
                        val button=scroll.getChildAt(0); val visible=android.graphics.Rect()
                        assertTrue("PICKER_CANCEL_NOT_REACHABLE",button.getGlobalVisibleRect(visible) && visible.height()==button.height)
                        assertTrue(button.performClick())
                    }
                    d.await("PICKER_RETURN_FOREGROUND") { d.host().hasWindowFocus() && d.host().model.coordinator.snapshot().foreground.ready && !d.host().model.coordinator.snapshot().pendingWork }
                } finally { d.instrumentation.removeMonitor(monitor) }
                // No open session is carried through the physical rotation; no change to handoff rules.
            }
        } catch(failure: Throwable) { primary=failure; throw failure }
        finally {
            try {
                d.ui { d.host().model.revoke() }
                orient(if(originalConfiguration==android.content.res.Configuration.ORIENTATION_PORTRAIT) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                d.ui { d.host().requestedOrientation=original }
            } catch(cleanup: Throwable) { if(primary!=null) primary.addSuppressed(cleanup) else throw cleanup }
        }
    }
    @Test fun maintenanceHidesBeforeServiceWork() {
        d.login(); d.ui {
            val c=d.host().model.coordinator; val old=checkNotNull(d.editor().onCreateInputConnection(EditorInfo()))
            val ticket=c.admit(c.currentCapability()!!,SessionCoordinator.Command.Maintenance)
            assertFalse(c.snapshot().presented); assertTrue(d.editor().text.isEmpty()); assertNull(old.getTextBeforeCursor(100,0))
            assertFalse(old.commitText("service synthetic",1)); c.commandFinished(ticket)
            assertFalse(c.snapshot().presented)
        }
    }
}
