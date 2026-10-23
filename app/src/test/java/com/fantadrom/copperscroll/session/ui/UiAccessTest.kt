package com.fantadrom.copperscroll.session.ui

import com.fantadrom.copperscroll.storage.session.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.privacytest.CanaryAudit
import org.junit.Test
import org.junit.Assert.*

class UiAccessTest {
    @Test fun policiesRequireCurrentAuthenticatedWindowAndCapability() {
        for(decoy in listOf(false,true)) SessionCoordinatorTest.Fixture().use { f ->
            f.login(decoy)
            val cap=f.c.currentCapability()!!
            val defaults=UiAccess.session(f.c,f.w,cap)
            val allowed=UiAccess.session(f.c,f.w,cap,SettingsData(copyBlocked=false,screenshotsBlocked=false,sortMode=999))
            assertTrue(defaults.active()); assertFalse(defaults.mayCopy()); assertFalse(defaults.mayCapture())
            assertTrue(allowed.mayCopy()); assertTrue(allowed.mayCapture())
            f.c.hardRevoke(SessionCoordinator.Reason.Home)
            assertFalse(defaults.active()); assertFalse(allowed.mayCopy()); assertFalse(allowed.mayCapture())
        }
    }
    @Test fun handoffOldWindowCannotRegainAuthorityAndRetirementIsPermanent() = SessionCoordinatorTest.Fixture().use { f ->
        f.login(); val old=UiAccess.session(f.c,f.w,f.c.currentCapability()!!,SettingsData(copyBlocked=false))
        val handoff=f.c.beginHandoff(f.w); assertFalse(old.active()); f.c.detach(f.w,handoff)
        f.w=f.c.attach({},handoff); f.seq=0; f.signal(); assertTrue(f.c.snapshot().presented); assertFalse(old.active())
        val fresh=UiAccess.session(f.c,f.w,f.c.currentCapability()!!); assertTrue(fresh.active()); fresh.retire(); assertFalse(fresh.active())
    }
    @Test fun maintenanceImmediatelyInvalidatesInputAndScreenshotAuthority() = SessionCoordinatorTest.Fixture().use { f ->
        f.login(); val a=UiAccess.session(f.c,f.w,f.c.currentCapability()!!,SettingsData(copyBlocked=false,screenshotsBlocked=false))
        val command=f.c.admit(f.c.currentCapability()!!,SessionCoordinator.Command.Maintenance)
        assertFalse(a.active()); assertFalse(a.mayCapture()); assertFalse(a.mayCopy()); f.c.commandFinished(command)
        assertFalse(a.active())
    }
    @Test fun realSystemReasonsDuringModelHandoffNeverRestoreUiAuthority() {
        for(reason in listOf(SessionCoordinator.Reason.Home,SessionCoordinator.Reason.ScreenOff)) SessionCoordinatorTest.Fixture().use { f ->
            f.login(); val lease=UiAccess.session(f.c,f.w,f.c.currentCapability()!!,SettingsData(copyBlocked=false,screenshotsBlocked=false))
            val h=f.c.beginHandoff(f.w); assertFalse(lease.active())
            f.c.hardRevoke(reason); f.c.detach(f.w,h)
            f.w=f.c.attach({}); f.seq=0; f.signal()
            assertFalse(f.c.snapshot().presented); assertNull(f.c.currentCapability()); assertFalse(lease.mayCapture()); assertFalse(lease.mayCopy())
        }
    }
    @Test fun unknownEnumsNeverChangeDefaultBooleanPolicies() {
        val decoded=SettingsCodec.decode(SettingsCodec.encode(SettingsData(sortMode=777,viewMode=999)))
        assertTrue(decoded.copyBlocked); assertTrue(decoded.screenshotsBlocked); assertEquals(777,decoded.sortMode)
    }
    @Test fun canaryScannerDetectsEncodingsAndOwnedNegativeControl() {
        assertFalse(CanaryAudit.contains(byteArrayOf()))
        assertFalse(CanaryAudit.contains("allowed metadata only".toByteArray()))
        for(marker in CanaryAudit.markers) for(charset in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)) assertTrue(CanaryAudit.contains(marker.toByteArray(charset)))
        assertTrue(CanaryAudit.contains(CanaryAudit.markers[0].map { if(it.code>127) "\\u%04x".format(it.code) else it.toString() }.joinToString("").toByteArray()))
        val root=kotlin.io.path.createTempDirectory("p6b2-canary-").toFile()
        try {
            val file=java.io.File(root,"synthetic-only"); file.writeText("allowed metadata"); assertEquals(1,CanaryAudit.scan(root))
            file.writeText(CanaryAudit.markers[0]); try { CanaryAudit.scan(root); fail("MISSED_CANARY") } catch(e: IllegalStateException) { assertEquals("CANARY_PERSISTED",e.message) }
        } finally { check(root.deleteRecursively()) }
    }
}
