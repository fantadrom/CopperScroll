package com.fantadrom.copperscroll.storage.android

import com.fantadrom.copperscroll.p6aharness.QualificationDiagnostics
import org.junit.Assert.*
import org.junit.Test

class QualificationDiagnosticsTest {
    private fun failed(d: QualificationDiagnostics, action: () -> Unit): Map<String,String> {
        val error=try { action(); throw AssertionError("Expected invariant failure") } catch(e: IllegalStateException) { e }
        return d.failure(error)
    }
    @Test fun independentInvariantCodesAndValues() {
        for(action in listOf<(QualificationDiagnostics)->Unit>({ it.mode(true,0,false) }, { it.mode(false,1,false) }, { it.mode(false,0,true) })) {
            val d=QualificationDiagnostics(); val p=failed(d) { action(d) }
            assertEquals("VERIFY_MODE",p["failureCode"])
        }
        val d=QualificationDiagnostics()
        val size=failed(d) { d.size(0) }; assertEquals("VERIFY_SIZE",size["failureCode"]); assertEquals("0",size["observedSize"])
        assertEquals("262144",size["expectedSize"])
        val hash=failed(d) { d.hash(false) }; assertEquals("VERIFY_HASH",hash["failureCode"]); assertEquals("false",hash["contentHashMatches"])
        val identity=failed(d) { d.identity("123:456","123:789") }
        assertEquals("VERIFY_IDENTITY",identity["failureCode"]); assertEquals("true",identity["identityDeviceMatches"])
        assertEquals("false",identity["identityInodeMatches"]); assertFalse(identity.toString().contains("123:456"))
        // Preserve strict original identity equality, including representation differences.
        assertEquals("VERIFY_IDENTITY",failed(d) { d.identity("01:2","1:2") }["failureCode"])
    }
    @Test fun successfulChecksRecordLastCompletedInvariant() {
        val d=QualificationDiagnostics(); d.mode(false,0,false); d.size(262144); d.hash(true); d.identity("1:2","1:2")
        assertEquals("VERIFY_IDENTITY",d.snapshot()["lastVerifiedCheck"])
        assertFalse(d.snapshot().containsKey("failureCode"))
    }
    @Test fun preservesOriginalLocationCauseAndSuppressedWithoutMessages() {
        val primary=IllegalStateException("content://private/user-note-and-PIN")
        primary.stackTrace=arrayOf(StackTraceElement("safe.Helper","verifyBytes","QualificationSession.kt",123))
        primary.initCause(IllegalArgumentException("secret"))
        primary.addSuppressed(java.io.IOException("/private/file"))
        val p=QualificationDiagnostics().failure(primary)
        assertEquals("safe.Helper.verifyBytes(QualificationSession.kt:123)",p["failureOrigin0"])
        assertEquals("CAUSE_OF_0",p["failureRelation1"]); assertEquals("SUPPRESSED_BY_0",p["failureRelation2"])
        for(privateValue in listOf("content://","user-note","PIN","secret","/private/file")) assertFalse(p.toString().contains(privateValue))
    }
    @Test fun boundsAndRedactsThrowableFramesAndCycles() {
        val a=IllegalStateException("hidden"); val b=IllegalArgumentException("hidden")
        a.initCause(b); b.initCause(a)
        a.stackTrace=Array(20) { StackTraceElement("content://private","bad/secret","/private/user.txt",1) }
        val p=QualificationDiagnostics().failure(a)
        assertEquals("true",p["failureTruncated"]); assertFalse(p.toString().contains("private")); assertFalse(p.toString().contains("secret"))
        assertTrue(p.values.all { it.length <= 2000 })
    }
    @Test fun closeFailureIsSuppressedBehindTheFailedInvariant() {
        val d=QualificationDiagnostics()
        val resource=java.io.Closeable { throw java.io.IOException("content://private/close") }
        val p=failed(d) { resource.use { d.hash(false); d.stage=QualificationDiagnostics.Stage.CLOSE_ADAPTER } }
        assertEquals("VERIFY_HASH",p["failureCode"])
        assertEquals("SUPPRESSED_BY_0",p["failureRelation1"])
        assertFalse(p.toString().contains("content://"))
        d.stage=QualificationDiagnostics.Stage.CLOSE_ADAPTER
        assertEquals("CLOSE_ADAPTER",d.failure(java.io.IOException("hidden"))["failureCode"])
    }
    @Test fun failedInvariantRetainsPreviousSuccessfulCheck() {
        val d=QualificationDiagnostics(); d.mode(false,0,false); d.size(262144)
        val p=failed(d) { d.hash(false) }
        assertEquals("VERIFY_SIZE",p["lastVerifiedCheck"]); assertEquals("VERIFY_HASH",p["failureCode"])
    }
}
