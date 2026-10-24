package com.fantadrom.copperscroll.storage.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.privacytest.CanaryAudit
import org.junit.Test

/** Explicit synthetic backup probe only. Owned exact paths; never a cleanup of the whole data dir. */
class P6b2BackupProbe {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun seedCanaries() {
        java.io.File(context.filesDir,"p6b2-backup-canary").writeText(CanaryAudit.markers[0])
        java.io.File(context.noBackupFilesDir,"p6b2-backup-canary").writeText(CanaryAudit.markers[2])
        check(context.getSharedPreferences("p6b2_backup_canary",0).edit().putString("synthetic",CanaryAudit.markers[1]).commit())
        receipt("BACKUP_CANARIES_SEEDED","syntheticOnly=true\n")
    }
    @Test fun removeOwnedCanaries() {
        for(root in listOf(context.filesDir,context.noBackupFilesDir)) {
            val file=java.io.File(root,"p6b2-backup-canary"); if(file.exists()) check(file.delete())
        }
        check(context.deleteSharedPreferences("p6b2_backup_canary") || !java.io.File(context.applicationInfo.dataDir,"shared_prefs/p6b2_backup_canary.xml").exists())
        receipt("BACKUP_OWNED_CANARIES_REMOVED")
    }
}
