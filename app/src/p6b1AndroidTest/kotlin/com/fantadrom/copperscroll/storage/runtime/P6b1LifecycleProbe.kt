package com.fantadrom.copperscroll.storage.runtime

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.p6b1harness.P6b1SessionActivity
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit guided probe; never selected by the automatic correctness suite. No PIN automation. */
class P6b1LifecycleProbe {
    @Test fun guidedLifecycle() {
        val i=InstrumentationRegistry.getInstrumentation(); val run=UUID.randomUUID().toString()
        val a=i.startActivitySync(Intent(i.targetContext,P6b1SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as P6b1SessionActivity
        val model=a.model
        var outcome="NOT_COMPLETED"
        var failure: Throwable?=null
        try {
            val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(15)
            // Recreation replaces the Activity; the retained model records explicit user completion.
            while("USER_FINISHED" !in model.eventCopy()) { check(System.nanoTime()<deadline) { "MANUAL_PROBE_TIMEOUT" }; Thread.sleep(100) }
            check(model.manualSteps.containsAll(listOf("screen_off_lock","shade_system_dialog","ime","multiwindow","rotation_resize"))) { "MANUAL_STEPS_INCOMPLETE" }
            check(model.screenOffObserved) { "NO_ACTUAL_SCREEN_OFF_EVENT" }
            outcome="USER_OBSERVATIONS_COLLECTED"
        } catch(e: Throwable) { failure=e; throw e }
        finally {
            val receipt=buildString {
                append("schema=P6B1_MANUAL_1\ndeviceRunId=$run\nobservationSource=ACTUAL_ANDROID_AND_USER_ACK\n")
                append("outcome=$outcome\nautomatedLeakCertification=false\nproductionAdmission=REFUSED\n")
                append("steps=${model.manualSteps.sorted().joinToString(",")}\nactualScreenOffObserved=${model.screenOffObserved}\n")
                model.eventCopy().forEachIndexed { n,event -> append("event.$n=$event\n") }
                failure?.let { e -> append("failureType=${e.javaClass.name}\n"); e.stackTrace.take(12).forEachIndexed { n,f -> append("failureFrame.$n=${f.className}.${f.methodName}:${f.lineNumber}\n") } }
            }
            i.sendStatus(0,Bundle().apply { putString("p6b1ReceiptBase64",android.util.Base64.encodeToString(receipt.toByteArray(),android.util.Base64.NO_WRAP)) })
            i.runOnMainSync { model.revoke() }
        }
    }
}
