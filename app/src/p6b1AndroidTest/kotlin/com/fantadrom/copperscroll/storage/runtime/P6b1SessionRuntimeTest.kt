package com.fantadrom.copperscroll.storage.runtime

import android.app.*
import android.content.*
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.p6b1harness.*
import com.fantadrom.copperscroll.p6aharness.RemoteHarness
import com.fantadrom.copperscroll.storage.session.*
import com.fantadrom.copperscroll.sessiontest.RuntimeForegroundFence
import com.fantadrom.copperscroll.sessiontest.RuntimeSessionDiagnostics
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.format.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Actual Activity/window/Home/recreation and helper IPC, with synthetic core/credential inputs.
 * Controlled IO/KDF-result barriers are explicit; screen-off/lock/shade are a separate manual probe. */
class P6b1SessionRuntimeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val current=AtomicReference<P6b1SessionActivity>()
    private val pickerActivity=AtomicReference<P6b1PickerActivity>()
    private val lifecycle=java.util.IdentityHashMap<Activity,RuntimeForegroundFence>() // UI thread only
    private var returnAfter: Pair<RuntimeForegroundFence,Long>?=null
    private var attemptEpoch: Long?=null
    private fun observe(a: Activity,stage: RuntimeForegroundFence.Stage) {
        if(a is P6b1SessionActivity || a is P6b1PickerActivity) lifecycle.getOrPut(a) { RuntimeForegroundFence() }.observe(stage)
    }
    private val callbacks=object: Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            observe(a,RuntimeForegroundFence.Stage.Resumed)
            if(a is P6b1SessionActivity && a.model.coordinator.ownsWindow(a.signals.window)) current.set(a)
        }
        override fun onActivityCreated(a: Activity,b: Bundle?) { observe(a,RuntimeForegroundFence.Stage.Created); if(a is P6b1PickerActivity) pickerActivity.set(a) }
        override fun onActivityStarted(a: Activity) { observe(a,RuntimeForegroundFence.Stage.Started) }
        override fun onActivityPaused(a: Activity) { observe(a,RuntimeForegroundFence.Stage.Paused) }
        override fun onActivityStopped(a: Activity) { observe(a,RuntimeForegroundFence.Stage.Stopped) }
        override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) { observe(a,RuntimeForegroundFence.Stage.Destroyed); lifecycle.remove(a) }
    }
    private fun ui(action: ()->Unit)=instrumentation.runOnMainSync(action)
    private fun safeTrace(phase: String): String {
        var result="P6B1_SAFE_TRACE; phase=$phase; host=missing"
        ui { current.get()?.let { a ->
            val c=a.model.coordinator; val diagnostic=c.diagnostics()
            result="phase=$phase; activityStage=${lifecycle[a]?.stage}; editorVisible=${a.editor.visibility==View.VISIBLE}; editorEnabled=${a.editor.isEnabled}\n"+
                RuntimeSessionDiagnostics.render(diagnostic.history,diagnostic.current,a.model.eventCopy(),attemptEpoch)
        } }
        return result
    }
    private fun failure(code: String): Nothing = throw AssertionError("$code\n${safeTrace("at_failure")}")
    private fun annotate(error: Throwable,phase: String) {
        if(error.message?.contains("P6B1_SAFE_TRACE")==true || error.suppressed.any { it.message?.contains("P6B1_SAFE_TRACE")==true }) return
        try { error.addSuppressed(AssertionError(safeTrace(phase))) } catch(diagnostic: Throwable) { error.addSuppressed(diagnostic) }
    }
    @get:Rule val diagnostics=object: TestWatcher() {
        override fun failed(e: Throwable,description: Description) { annotate(e,"after_cleanup") }
    }
    private fun await(code: String,condition: ()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(!condition()) {
            current.get()?.model?.let { it.lastFailure ?: it.worker.lastFailure }?.let { failure("$code; worker=${it.type}; frames=${it.frames.joinToString(";")}") }
            if(System.nanoTime()>=end) failure(code)
            Thread.sleep(20) // Polling interval; no stabilization delay or automatic login retry.
        }
    }
    private fun awaitUi(code: String,condition: ()->Boolean)=await(code) { var ready=false; ui { ready=condition() }; ready }
    private fun host()=current.get() ?: error("HOST_MISSING")
    private fun foreground(a: P6b1SessionActivity): Boolean {
        val c=a.model.coordinator; val f=lifecycle[a] ?: return false
        val fence=returnAfter
        return c.ownsWindow(a.signals.window) &&
            (fence==null || f!==fence.first || f.resumedAt>fence.second) && f.foreground(c.snapshot(),a.hasWindowFocus())
    }
    private fun returnHost() {
        context.startActivity(Intent(context,P6b1SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        awaitUi("HOST_FOREGROUND") { foreground(host()) }
        returnAfter=null
    }
    private fun home() {
        lateinit var front: Activity; lateinit var record: RuntimeForegroundFence; var mark=0L
        ui {
            val a=host(); val hostRecord=checkNotNull(lifecycle[a]); returnAfter=hostRecord to hostRecord.resumedAt
            val picker=pickerActivity.get()
            front=if(picker!=null && lifecycle[picker]?.stage==RuntimeForegroundFence.Stage.Resumed) picker else a
            record=checkNotNull(lifecycle[front]); mark=record.sequence
        }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use { it.readBytes() }
        awaitUi("HOME_NOT_DEPARTED") {
            record.departedAfter(mark,front.hasWindowFocus()) && !host().model.coordinator.snapshot().presented
        }
    }
    private fun login(decoy: Boolean=false) {
        attemptEpoch=null
        try {
            awaitUi("LOGIN_NOT_READY") {
                val a=host(); val state=a.model.coordinator.snapshot()
                if(!a.model.ready || !foreground(a) || lifecycle[a]?.loginReady(state,a.hasWindowFocus())!=true) false
                else { attemptEpoch=a.model.login(decoy); true } // Readiness + one attempt in the same UI dispatch.
            }
            awaitUi("LOGIN_NOT_PRESENTED") {
                val a=host(); val c=a.model.coordinator; val diagnostic=c.diagnostics(); val state=diagnostic.current
                val outcome=RuntimeSessionDiagnostics.outcome(checkNotNull(attemptEpoch),diagnostic.history,state)
                if(outcome in setOf(RuntimeSessionDiagnostics.Outcome.SUBMISSION_REJECTED,RuntimeSessionDiagnostics.Outcome.CLAIM_REJECTED,
                    RuntimeSessionDiagnostics.Outcome.COMPLETION_REJECTED,RuntimeSessionDiagnostics.Outcome.FAILED,
                    RuntimeSessionDiagnostics.Outcome.REVOKED_BEFORE_OPEN,RuntimeSessionDiagnostics.Outcome.OPENED_THEN_REVOKED))
                    throw AssertionError("LOGIN_NOT_PRESENTED; outcome=$outcome")
                a.model.authenticated!=null && state.epoch==attemptEpoch && state.presented && a.editor.visibility==View.VISIBLE
            }
        } catch(e: Throwable) { annotate(e,"at_login_failure"); throw e }
    }
    @Before fun start() {
        check(context.packageName.endsWith(".p1runtime"))
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
        instrumentation.startActivitySync(Intent(context,P6b1SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitUi("HOST_READY") { current.get()?.model?.ready==true && foreground(host()) }
    }
    @After fun finish() {
        current.get()?.let { a -> ui { a.model.loginContinue?.countDown(); a.model.ioContinue?.countDown(); a.model.revoke(); a.finish() } }
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(callbacks)
        instrumentation.waitForIdleSync()
    }
    @Test fun secureLoginRolesAndCommands() {
        ui { assertTrue(host().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0); assertTrue("EDITOR_NOT_EMPTY",host().editor.text.isEmpty()); assertFalse(host().editor.isEnabled) }
        login(true); ui { assertTrue(host().editor.text.isNotEmpty()) }; val c=host().model.coordinator; assertEquals(SessionCoordinator.Access.OpenDecoy,c.snapshot().access)
        try { c.admit(c.currentCapability()!!,SessionCoordinator.Command.Maintenance); fail("DECOY_BYPASS") } catch(_: StoreException) {}
        ui { host().model.revoke() }; login(); assertEquals(SessionCoordinator.Access.OpenMain,c.snapshot().access)
    }
    @Test fun homeRevokesWithoutAutomaticReentry() {
        login(); home(); returnHost()
        ui { assertFalse(host().model.coordinator.snapshot().presented); assertTrue("EDITOR_NOT_EMPTY",host().editor.text.isEmpty()); assertFalse(host().editor.isEnabled) }
        login(); assertTrue(host().model.coordinator.snapshot().presented)
    }
    @Test fun ownedDialogIsSecureAndHomeRevokes() {
        login(); ui { host().showOwnDialog() }
        awaitUi("DIALOG_NOT_SHOWN") { host().child?.isShowing==true }
        ui { assertTrue(host().child!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0); assertTrue(host().model.coordinator.snapshot().presented) }
        home(); returnHost(); ui { assertNull(host().child); assertTrue("EDITOR_NOT_EMPTY",host().editor.text.isEmpty()) }
    }
    @Test fun configurationHandoffRetainsMemoryOwner() {
        login(); ui { host().editor.setText("P6b1 synthetic modified draft") }; val old=host(); val model=old.model; val epoch=model.coordinator.snapshot().epoch; val owner=model.authenticated!!.core.owner
        ui { old.recreateOwned() }
        awaitUi("RECREATION_NOT_COMPLETED") { current.get()!==old && host().hasWindowFocus() && model.coordinator.snapshot().presented && host().editor.visibility==View.VISIBLE }
        ui { assertTrue("DRAFT_NOT_RETAINED",host().editor.text.toString()=="P6b1 synthetic modified draft") }
        ui { old.editor.setText("P6b1 stale synthetic callback"); assertTrue("STALE_WINDOW_CHANGED_DRAFT",model.draft=="P6b1 synthetic modified draft") }
        assertEquals("SAVED_BUNDLE_NOT_EMPTY",0,model.savedBundleKeys)
        assertSame(model,host().model); assertSame(owner,host().model.authenticated!!.core.owner); assertEquals(epoch,model.coordinator.snapshot().epoch)
        home(); returnHost(); assertFalse(model.coordinator.snapshot().presented)
    }
    @Test fun lateCredentialCompletionCannotReopen() {
        val model=host().model; model.loginEntered=CountDownLatch(1); model.loginContinue=CountDownLatch(1)
        ui { model.login(false) }; assertTrue(model.loginEntered!!.await(40,TimeUnit.SECONDS))
        home(); returnHost(); model.loginContinue!!.countDown()
        await("LATE_OWNER_NOT_RELEASED") { model.coordinator.snapshot().owner==null && !model.coordinator.snapshot().pendingWork }
        assertFalse(model.coordinator.snapshot().presented); assertNull(model.authenticated)
    }
    @Test fun finalCardWaitsForIoWithoutDelayingHide() {
        login(); val model=host().model; val session=model.authenticated!!; val repo=ZoneRepository(session.core)
        val id=ZoneRepository.newId(); val parent=repo.roots().first; val draft=CardDraftSession(repo,id,parent)
        draft.edit("first",CardBody()); val first=draft.prepareSave()!!
        draft.edit("last valid",CardBody()); val checkpoint=draft.prepareFinalCheckpoint()!!
        draft.edit("invalid",CardBody(),icon="INVALID")
        model.coordinator.offerCheckpoint(model.coordinator.currentCapability()!!,checkpoint)
        val entered=CountDownLatch(1); val resume=CountDownLatch(1); model.ioContinue=resume
        model.fixture!!.lastStore!!.observer={ if(it.action==IoAction.Force && entered.count>0) { entered.countDown(); check(resume.await(40,TimeUnit.SECONDS)) } }
        val ticket=model.coordinator.admit(model.coordinator.currentCapability()!!,SessionCoordinator.Command.Save)
        model.worker.command(ticket) { model.ioOutcome=draft.submit(first).code }
        assertTrue(entered.await(40,TimeUnit.SECONDS)); home()
        ui { assertTrue("EDITOR_NOT_EMPTY",host().editor.text.isEmpty()); assertFalse(host().editor.isEnabled) }
        assertFalse(session.core.owner.awaitQuiescent(0)); assertFalse(session.core.owner.release())
        try { model.fixture!!.owner(); fail("SECOND_WRITER") } catch(e: StoreException) { assertEquals(IoCode.Busy,e.code) }
        resume.countDown(); await("IO_NOT_RELEASED") { model.coordinator.snapshot().owner==null && !model.coordinator.snapshot().pendingWork }
        assertEquals(WriteCode.Committed,model.ioOutcome); assertEquals(WriteCode.Committed,model.worker.lastOutcome)
        returnHost(); assertFalse(model.coordinator.snapshot().presented); login()
        assertTrue("FINAL_CARD_WRONG",ZoneRepository(model.authenticated!!.core).readCard(id).metadata.name=="last valid")
    }
    @Test fun boundPickerRejectsLateWithoutGrant() {
        login(); val model=host().model; val before=context.contentResolver.persistedUriPermissions.size
        ui { host().launchPicker() }
        awaitUi("PICKER_NOT_FOREGROUND") { !host().hasWindowFocus() }
        // Actual Home while own external Activity is foreground: no token may survive it.
        home()
        await("ACTUAL_PICKER_HOME_NOT_OBSERVED") { !model.coordinator.snapshot().pickerPending }
        ui { pickerActivity.get().finish() }
        returnHost()
        await("LATE_PICKER_RESULT_NOT_REJECTED") { host().lastPickerRejected }
        assertEquals(before,context.contentResolver.persistedUriPermissions.size); assertFalse(model.coordinator.snapshot().presented)
    }
    @Test fun helperRestartStartsLocked() {
        val first=RemoteHarness(context).use { remote ->
            val status=remote.request("sessionStatus");
            check(status.getString("status")=="Locked") { "HELPER_FAILURE;type=${status.getString("failureType")};frames=${status.getString("failureFrames")};cause=${status.getString("failureCauseFrames")}" }; remote.die(); status
        }
        // The bind waits for actual process death; do not infer it from a fixed delay.
        var second: Bundle?=null
        await("HELPER_NOT_RESTARTED") {
            second=RemoteHarness(context).use { it.request("sessionStatus") }
            second!!.getInt("pid")!=first.getInt("pid")
        }
        assertEquals("Locked",second!!.getString("status")); assertNotEquals(first.getString("session"),second!!.getString("session"))
    }
}
