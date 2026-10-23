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

/** Twelve exact independent Android boundaries, synthetic only. No OEM/IME-service certification. */
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
