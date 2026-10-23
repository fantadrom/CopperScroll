package com.fantadrom.copperscroll.storage.runtime

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** External public-UI observer runs in the OPT-IN target process, never inside ordinary release.
 * No ordinary target ABI/run-as/backend calls, raw UI dumps, screenshots or foreign node text. */
class P6b2OrdinaryReleaseProbe {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation=instrumentation.uiAutomation
    private val ordinary="com.fantadrom.copperscroll"
    private fun root()=automation.rootInActiveWindow?.takeIf { it.packageName?.toString()==ordinary }
    private fun launch() {
        instrumentation.targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ordinary,"$ordinary.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun find(node: AccessibilityNodeInfo?,predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        if(node==null || node.packageName?.toString()!=ordinary) return null
        if(predicate(node)) return node
        for(i in 0 until node.childCount) find(node.getChild(i),predicate)?.let { return it }
        return null
    }
    private fun waitFor(code: String,condition: ()->Boolean) {
        val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(35)
        while(!condition()) { check(System.nanoTime()<end) { code }; Thread.sleep(30) }
    }
    private fun click(label: String) {
        var match: AccessibilityNodeInfo?=null
        waitFor("ORDINARY_PUBLIC_CONTROL_MISSING") { match=find(root()) { it.text?.toString()==label || it.contentDescription?.toString()==label }; match!=null }
        check(match!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "ORDINARY_PUBLIC_CLICK_FAILED" }
    }
    private fun shell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd)).use { it.readBytes() }
    @Test fun publicLauncherEditorAndHomeReturn() {
        check(instrumentation.targetContext.packageName=="com.fantadrom.copperscroll.p1runtime")
        launch(); waitFor("ORDINARY_NOT_FOREGROUND") { root()!=null }
        click("Создать"); click("Новая заметка")
        waitFor("ORDINARY_EDITOR_DIALOG_NOT_PRESENTED") { find(root()) { it.contentDescription?.toString()=="Закрыть редактор" }!=null }
        val node=find(root()) { it.className?.toString()=="android.widget.EditText" }
        var input="NOT_CONFIRMED_SENSITIVE_NODE_NOT_EXPOSED"
        if(node!=null) {
            val bounds=Rect(); node.getBoundsInScreen(bounds)
            val now=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0)
            val up=MotionEvent.obtain(now,now,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0)
            try { check(automation.injectInputEvent(down,true)); check(automation.injectInputEvent(up,true)) } finally { down.recycle(); up.recycle() }
            shell("input text P6B2_RELEASE_ASCII_93df")
            waitFor("ORDINARY_SYNTHETIC_INPUT_NOT_OBSERVED") { find(root()) { it.className?.toString()=="android.widget.EditText" }?.text?.toString()?.contains("P6B2_RELEASE_ASCII_93df")==true }
            input="OBSERVED_SYNTHETIC_NATIVE_EDITOR_INPUT"
        }
        shell("input keyevent KEYCODE_HOME"); waitFor("ORDINARY_HOME_NOT_DEPARTED") { root()==null }
        launch(); waitFor("ORDINARY_RETURN_NOT_FOREGROUND") { root()!=null }
        receipt("ORDINARY_RELEASE_PUBLIC_UI","observedPackage=$ordinary\nlauncherObserved=true\nhomeReturnObserved=true\nnativeEditor=$input\nnoOrdinaryTargetAbi=true\n")
    }
}
