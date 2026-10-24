package com.fantadrom.copperscroll.backup

import android.app.backup.*
import android.os.ParcelFileDescriptor
import java.io.IOException

/** Defense in depth if an OEM invokes the agent despite allowBackup=false. Never calls default
 * traversal/restore, never exports files and never trusts a restore destination. No iOS counterpart
 * or fabricated platform-specific identity is registered in XML. XML exclusions remain mandatory. */
class RefuseBackupAgent: BackupAgent() {
    override fun onBackup(old: ParcelFileDescriptor?,out: BackupDataOutput?,state: ParcelFileDescriptor?) {}
    override fun onFullBackup(out: FullBackupDataOutput?) {} // Empty for every transport, including 36.1+.
    override fun onRestore(input: BackupDataInput?,version: Int,state: ParcelFileDescriptor?) { throw IOException("RESTORE_REFUSED") }
    override fun onRestoreFile(data: ParcelFileDescriptor?,size: Long,destination: java.io.File?,type: Int,mode: Long,mtime: Long) { throw IOException("RESTORE_REFUSED") }
    @androidx.annotation.RequiresApi(37)
    override fun onRestoreFile(input: FullRestoreDataInput) { throw IOException("RESTORE_REFUSED") }
}
