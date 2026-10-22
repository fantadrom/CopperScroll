package com.fantadrom.copperscroll.storage.android

import com.fantadrom.copperscroll.p6aharness.QualificationDiagnostics
import com.fantadrom.copperscroll.p6aharness.QualificationIdentity
import com.fantadrom.copperscroll.p6aharness.QualificationIdentity.Comparison
import org.junit.Assert.*
import org.junit.Test

class QualificationIdentityTest {
    @Test fun deviceAndInodeAreBothRequired() {
        val original=QualificationIdentity(101,202)
        for(other in listOf(QualificationIdentity(102,202),QualificationIdentity(101,203))) {
            assertEquals("false",original.compare(Comparison.R_TO_R,other)["identityRToR.matches"])
        }
        val p=original.compare(Comparison.RW_TO_R,QualificationIdentity(102,202))
        assertEquals("false",p["identityRwToR.deviceMatches"]); assertEquals("true",p["identityRwToR.inodeMatches"])
        assertTrue(p.values.all { it in setOf("true","false") })
    }
    @Test fun separatesModeDependentSamplesFromStableReopens() {
        val rw=QualificationIdentity(101,202); val r=QualificationIdentity(303,202)
        val d=QualificationDiagnostics()
        d.identity(Comparison.RW_TO_R,rw,r)
        d.identity(Comparison.R_TO_R,r,QualificationIdentity(303,202))
        d.identity(Comparison.RW_TO_RW,rw,QualificationIdentity(101,202))
        d.restartIdentityNotRun()
        val p=d.snapshot()
        assertEquals("false",p["identityRwToR.matches"])
        assertEquals("true",p["identityRToR.matches"]); assertEquals("true",p["identityRwToRw.matches"])
        assertEquals("NOT_RUN",p["identityRAfterRestart.matches"]); assertFalse(p.containsKey("failureCode"))
        assertFalse(p.toString().contains("101")); assertFalse(p.toString().contains("202")); assertFalse(p.toString().contains("303"))
    }
    @Test fun persistedModeBaselinesCompareFreshSamplesWithoutRebaselining() {
        val savedR=QualificationIdentity(101,202).privateCheckpointValue()
        val savedRw=QualificationIdentity(303,202).privateCheckpointValue()
        val d=QualificationDiagnostics()
        d.identity(Comparison.R_AFTER_RESTART,QualificationIdentity.fromCheckpoint(savedR),QualificationIdentity(101,202))
        d.identity(Comparison.RW_AFTER_RESTART,QualificationIdentity.fromCheckpoint(savedRw),QualificationIdentity(404,202))
        assertEquals("true",d.snapshot()["identityRAfterRestart.matches"])
        assertEquals("false",d.snapshot()["identityRwAfterRestart.matches"])
        assertEquals("true",d.snapshot()["identityRwAfterRestart.inodeMatches"])
        assertEquals("303:202",savedRw); assertFalse(d.snapshot().containsKey("failureCode"))
    }
    @Test fun rejectsMissingMalformedNoncanonicalAndOverflowBaselines() {
        for(encoded in listOf(null,"1","1:2:3","01:2","-0:1","9223372036854775808:2","content://private")) {
            try { QualificationIdentity.fromCheckpoint(encoded); fail("Invalid private sample accepted") } catch(_: Exception) { }
        }
        val encoded=QualificationIdentity(Long.MIN_VALUE,Long.MAX_VALUE).privateCheckpointValue()
        assertEquals(encoded,QualificationIdentity.fromCheckpoint(encoded).privateCheckpointValue())
        assertEquals("PRIVATE_FSTAT_SAMPLE",QualificationIdentity(101,202).toString())
    }
}
