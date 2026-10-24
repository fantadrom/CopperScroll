package com.fantadrom.copperscroll.session.ui

import com.fantadrom.copperscroll.privacytest.ClipboardEvidence
import com.fantadrom.copperscroll.privacytest.GestureCoordinates as Coordinates
import org.junit.Assert.*
import org.junit.Test

class P6b2HarnessRegressionTest {
    private val correct=ClipboardEvidence(true,true,true,true,true,true,true,true,true,false,false,true)
    @Test fun dialogWindowOffsetDoesNotRejectAVisibleTouch() {
        // Root clipping is 40..100; the dialog window starts 128px lower on screen.
        val point=Coordinates.screen(16f,20f,8,8,0,0,32,168)
        assertFalse(point.y in 40f..100f) // The old comparison mixed root and screen coordinates.
        val visibleTop=Coordinates.rootToScreen(32,40,32,168,0,0,32,40)
        assertTrue(point.y>=visibleTop.y && point.y<visibleTop.y+60)
        assertEquals(196f,point.y)
    }
    @Test fun editorScrollAndParentClippingUseOneCoordinateSpace() {
        // View is at window y100/screen y228, with 20px internal scrolling; globalOffset is y80.
        val top=Coordinates.rootToScreen(40,100,50,228,0,20,40,80)
        val point=Coordinates.screen(10f,30f,0,5,0,20,50,228)
        assertEquals(228f,top.y); assertEquals(243f,point.y)
        assertTrue(point.y>=top.y && point.y<top.y+40)
    }
    @Test fun exactCopyNeedsTheSensitiveFlag() { assertNull(correct.failureCode()); assertTrue(correct.preWriteSensitiveVerified) }
    @Test fun declinedCopyDoesNotProvePreWriteSensitive() {
        val e=correct.copy(copyReturned=false); assertFalse(e.preWriteSensitiveVerified); assertEquals("CLIPBOARD_COPY_NOT_ADMITTED",e.failureCode())
    }
    @Test fun missingReadFails() { assertEquals("CLIPBOARD_READ_MISSING",correct.copy(present=false).failureCode()) }
    @Test fun differentItemCannotPassEvenIfItIsSensitive() { assertEquals("CLIPBOARD_ITEM_REPLACED",correct.copy(textMatches=false).failureCode()) }
    @Test fun hostEchoIsDistinguishedFromSameItemFlagLoss() {
        val e=correct.copy(labelMatches=false,hostLabel=true,sensitive=false,overlaySuppressed=true)
        assertEquals("CLIPBOARD_ITEM_REPLACED",e.failureCode()); assertTrue(e.preWriteSensitiveVerified)
    }
    @Test fun lostOrAbsentSensitiveIsAlwaysFailure() {
        assertEquals("CLIPBOARD_SENSITIVE_FLAG_LOST",correct.copy(sensitive=false).failureCode())
        assertEquals("CLIPBOARD_SENSITIVE_FLAG_LOST",correct.copy(sensitivePresent=false).failureCode())
    }
    @Test fun foregroundLossFails() { assertEquals("CLIPBOARD_FOREGROUND_LOST",correct.copy(foreground=false).failureCode()) }
    @Test fun timestampIsDiagnosticAndDoesNotRelaxItemOrFlagChecks() {
        assertNull(correct.copy(freshTimestamp=false).failureCode())
        assertNotNull(correct.copy(freshTimestamp=false,sensitive=false).failureCode())
    }
}
