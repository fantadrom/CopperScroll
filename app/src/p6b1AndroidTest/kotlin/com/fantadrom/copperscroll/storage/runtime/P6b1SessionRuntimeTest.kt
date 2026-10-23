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
    private val callbacks=object: Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if(a is P6b1SessionActivity) current.set(a) }
        override fun onActivityCreated(a: Activity,b: Bundle?) { if(a is P6b1PickerActivity) pickerActivity.set(a) }
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
    private fun ui(action: ()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(code: String,condition: ()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(!condition()) { current.get()?.model?.let { it.lastFailure ?: it.worker.lastFailure }?.let { throw AssertionError("$code; ${it.type}; ${it.frames.joinToString(";")}") }; check(System.nanoTime()<end) { code }; Thread.sleep(20) }
    }
    private fun host()=current.get() ?: error("HOST_MISSING")
    private fun returnHost() {
        context.startActivity(Intent(context,P6b1SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("HOST_FOREGROUND") { host().hasWindowFocus() }
    }
    private fun home() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use { it.readBytes() }
        await("HOME_REVOKE") { !host().model.coordinator.snapshot().presented }
    }
    private fun login(decoy: Boolean=false) {
        await("OWNER_NOT_RELEASED") { host().model.coordinator.snapshot().owner==null && !host().model.coordinator.snapshot().pendingWork && host().hasWindowFocus() }
        ui { host().model.login(decoy) }
        await("LOGIN_NOT_PRESENTED") { host().model.authenticated!=null && host().model.coordinator.snapshot().presented && host().editor.visibility==View.VISIBLE }
    }
    @Before fun start() {
        check(context.packageName.endsWith(".p1runtime"))
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(callbacks)
        instrumentation.startActivitySync(Intent(context,P6b1SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("HOST_READY") { current.get()?.model?.ready==true && host().hasWindowFocus() }
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
        await("DIALOG_NOT_SHOWN") { host().child?.isShowing==true }
        ui { assertTrue(host().child!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0); assertTrue(host().model.coordinator.snapshot().presented) }
        home(); returnHost(); ui { assertNull(host().child); assertTrue("EDITOR_NOT_EMPTY",host().editor.text.isEmpty()) }
    }
    @Test fun configurationHandoffRetainsMemoryOwner() {
        login(); ui { host().editor.setText("P6b1 synthetic modified draft") }; val old=host(); val model=old.model; val epoch=model.coordinator.snapshot().epoch; val owner=model.authenticated!!.core.owner
        ui { old.recreateOwned() }
        await("RECREATION_NOT_COMPLETED") { current.get()!==old && host().hasWindowFocus() && model.coordinator.snapshot().presented && host().editor.visibility==View.VISIBLE }
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
        await("PICKER_NOT_FOREGROUND") { !host().hasWindowFocus() }
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
