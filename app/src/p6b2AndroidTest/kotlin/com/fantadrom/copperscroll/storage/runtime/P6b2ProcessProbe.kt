package com.fantadrom.copperscroll.storage.runtime

import org.junit.Test
import org.junit.Assert.*

/** First runner is deliberately killed externally. It must never produce an ordinary suite PASS. */
class P6b2ProcessProbe {
    @Test fun preparePrimary() {
        val phase=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("p6b2ProcessState")
        require(phase in listOf("open","picker"))
        val d=P6b2Driver(); d.start(); d.login()
        d.ui {
            val c=d.host().model.coordinator
            check(c.currentCapability()!=null)
            d.editor().setText(com.fantadrom.copperscroll.privacytest.CanaryAudit.markers[0])
            if(phase=="picker") {
                c.beginPicker("OpenOriginal")
                check(c.snapshot().pickerPending && !c.snapshot().presented && c.currentCapability()==null)
            } else {
                check(c.snapshot().presented && c.currentCapability()!=null && !c.snapshot().pickerPending)
                check(d.editor().text.toString()==com.fantadrom.copperscroll.privacytest.CanaryAudit.markers[0])
            }
        }
        receipt("PRIMARY_PREPARED","wasAuthenticated=true\npreparedState=$phase\ncapabilityPresent=${phase=="open"}\nplaintextPresented=${phase=="open"}\npickerPending=${phase=="picker"}\n")
        // Main looper stays live. A finite barrier is only for deliberate external termination.
        try { check(java.util.concurrent.CountDownLatch(1).await(120,java.util.concurrent.TimeUnit.SECONDS)) { "EXTERNAL_KILL_NOT_OBSERVED" } }
        finally { d.finish() } // NOT assumed to run after force-stop/SIGKILL.
    }
    @Test fun verifyFreshPrimary() {
        val phase=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("p6b2ProcessState")
        require(phase in listOf("open","picker"))
        val d=P6b2Driver()
        try {
            d.start(); d.ui {
                val c=d.host().model.coordinator; val s=c.snapshot()
                assertNull(c.currentCapability()); assertFalse(s.presented); assertFalse(s.pickerPending); assertFalse(s.pendingWork)
                assertTrue(d.editor().text.isEmpty()); assertFalse(d.editor().isEnabled)
                assertNull(d.editor().onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            }
            receipt("FRESH_PRIMARY_LOCKED","attemptState=$phase\ncapabilityPresent=false\npickerPending=false\nplaintextRestored=false\n")
        } finally { d.finish() }
    }
}
