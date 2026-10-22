package com.fantadrom.copperscroll.storage.android

import com.fantadrom.copperscroll.p6aharness.ProviderRestartFaults
import com.fantadrom.copperscroll.p6aharness.ProviderRestartFaults.Action
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProviderRestartFaultsTest {
    @get:Rule val temporary=TemporaryFolder.builder().assureDeletion().build()
    private val id="a:11111111-1111-1111-1111-111111111111"
    @Test fun oneShotIsConsumedBeforeDeathAndNeverKillsTheRestartedInstance() {
        val dir=temporary.newFolder()
        ProviderRestartFaults(dir).arm(id,false)
        assertEquals(Action.Die,ProviderRestartFaults(dir).beforeOpen(id))
        repeat(3) { assertEquals(Action.Allow,ProviderRestartFaults(dir).beforeOpen(id)) }
        assertEquals("once-consumed",ProviderRestartFaults(dir).state(id))
    }
    @Test fun persistentUnavailabilitySurvivesEveryNewInstance() {
        val dir=temporary.newFolder()
        ProviderRestartFaults(dir).arm(id,true)
        assertEquals(Action.Die,ProviderRestartFaults(dir).beforeOpen(id))
        repeat(3) {
            val restarted=ProviderRestartFaults(dir)
            assertEquals("unavailable",restarted.state(id)); assertEquals(Action.Unavailable,restarted.beforeOpen(id))
        }
    }
    @Test fun onlyExplicitClearAllowsRecoveryAndOtherDocumentsAreUnaffected() {
        val dir=temporary.newFolder(); val faults=ProviderRestartFaults(dir)
        faults.arm(id,true); faults.beforeOpen(id)
        assertEquals(Action.Allow,faults.beforeOpen("b"))
        ProviderRestartFaults(dir).clear(id)
        assertEquals(Action.Allow,ProviderRestartFaults(dir).beforeOpen(id))
    }
    @Test fun corruptStateFailsClosedInsteadOfResetting() {
        val dir=temporary.newFolder(); val record=File(dir,id.replace(':','_')); record.writeText("corrupt")
        assertThrows(IllegalStateException::class.java) { ProviderRestartFaults(dir).beforeOpen(id) }
        assertEquals("corrupt",record.readText())
    }
    @Test fun recordNamesCannotEscapeTheOwnedDirectory() {
        val dir=temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) { ProviderRestartFaults(dir).arm("../outside",true) }
        assertTrue(dir.listFiles()!!.isEmpty()); assertFalse(File(dir.parentFile,"outside").exists())
    }
}
