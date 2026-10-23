package com.fantadrom.copperscroll.storage.runtime

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.p6b2harness.P6b2SessionActivity
import com.fantadrom.copperscroll.storage.session.SessionCoordinator
import org.junit.Test
import org.junit.Assert.*

class P6b2HandoffProbe {
    @Test fun actualSystemEventDuringHandoff() {
        val event=InstrumentationRegistry.getArguments().getString("p6b2Event")
        require(event in listOf("home","screenoff"))
        val d=P6b2Driver()
        try {
            d.start(); d.login(); val old=d.host(); val c=old.model.coordinator
            d.ui { old.model.transfer=old.signals.beginConfiguration(); assertTrue(c.snapshot().handoff); assertFalse(c.snapshot().presented) }
            val transition=c.diagnosticHistory().lastOrNull()?.serial ?: 0L
            receipt("HANDOFF_WAITING","event=$event\noldWindowDetached=false\nnewWindowOpen=false\n")
            // Controller sends the REAL system event at this observable boundary. No main-looper block,
            // fake adapter callbacks, animation changes or sleep-to-hit-a-race.
            d.await("SYSTEM_EVENT_NOT_OBSERVED") {
                !c.snapshot().presented && if(event=="home") c.diagnosticHistory().any { it.serial>transition && it.event==SessionCoordinator.DiagnosticEvent.Revoke && it.state.reason==SessionCoordinator.Reason.Home } else old.model.screenOffObserved
            }
            receipt("HANDOFF_EVENT_OBSERVED","event=$event\naccessRevoked=true\n")
            d.ui { old.recreateOwned() }
            d.context.startActivity(Intent(d.context,P6b2SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            // A locked personal device may require explicit user unlock, never a PIN/service change.
            d.await("RECREATION_FOREGROUND_NOT_OBSERVED") { d.host()!==old && d.host().hasWindowFocus() && c.snapshot().foreground.ready }
            d.ui { assertFalse(c.snapshot().presented); assertNull(c.currentCapability()); assertTrue(d.editor().text.isEmpty()) }
            receipt("HANDOFF_RECREATED_LOCKED","event=$event\nactualRecreation=true\nautomaticReentry=false\n")
        } finally { d.finish() }
    }
}
