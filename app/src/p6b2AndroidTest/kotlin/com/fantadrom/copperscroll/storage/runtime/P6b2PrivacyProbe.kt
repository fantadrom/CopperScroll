package com.fantadrom.copperscroll.storage.runtime

import org.junit.Test
import com.fantadrom.copperscroll.privacytest.CanaryAudit

/** Explicit guided observations, no keyboard/service/settings changes and no OEM certification. */
class P6b2PrivacyProbe {
    @Test fun collectPrivacyObservations() {
        val d=P6b2Driver()
        try {
            d.start(); d.login()
            d.ui {
                val a=d.host()
                a.editor.setText(CanaryAudit.markers[0])
                a.addProbeControls(a.privacyPanel)
            }
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(12)
            while(!d.host().finishedPrivacy) {
                val failure=d.host().model.worker.lastFailure
                check(failure==null) { "PRIVACY_WORKER_FAILED; type=${failure?.type}; frames=${failure?.frames}" }
                check(System.nanoTime()<deadline) { "PRIVACY_OBSERVATIONS_TIMEOUT" }
                Thread.sleep(100)
            }
            val results=d.host().observations.toMap()
            val steps=setOf("active_ime","clipboard_preview","accessibility","recents_screenshot","autofill_capture")
            receipt("PRIVACY_OBSERVATIONS","automatedLeakCertification=false\n"+steps.sorted().joinToString("\n") { "$it=${results[it] ?: "NOT_RUN"}" }+"\n")
            check(results.values.none { it=="FAIL" }) { "USER_OBSERVED_PRIVACY_FAILURE" }
        } finally { d.finish() }
    }
}
