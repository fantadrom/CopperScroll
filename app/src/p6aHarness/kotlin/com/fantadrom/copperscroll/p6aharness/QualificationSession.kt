package com.fantadrom.copperscroll.p6aharness

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import com.fantadrom.copperscroll.storage.android.*
import com.fantadrom.copperscroll.storage.format.Bytes
import com.fantadrom.copperscroll.storage.io.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Observation-only, bounded scratch protocol. No imported evidence can issue a production gate.
 * All URIs/identity/local-binding bytes stay in noBackup; public output contains hashes only.
 * Only the helper process calls this object, under its sequential worker + stable local-state lock.
 */
internal object QualificationSession {
    private val session=UUID.randomUUID().toString()
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray)=bytes.joinToString("") { "%02x".format(it) }
    private fun boot(c: Context)=Settings.Global.getInt(c.contentResolver,Settings.Global.BOOT_COUNT,-1).also { check(it>=0) { "BOOT_COUNT_UNAVAILABLE" } }
    private fun dir(c: Context,id: String): File {
        check(id.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        val d=File(c.noBackupFilesDir,"p6a2-$id")
        if(!d.exists()) {
            check(c.noBackupFilesDir.listFiles()!!.count { it.name.matches(Regex("p6a2-[a-f0-9-]{36}")) }<32) { "Scratch session limit; retain metadata for diagnosis" }
            check(d.mkdir()); AppPrivateLocalState.syncDirectory(c.noBackupFilesDir)
        }
        return d
    }
    private fun read(file: File): Properties {
        check(file.isFile && file.length() in 1..16384)
        return Properties().apply { file.inputStream().use { load(it) }; check(size<32) }
    }
    private fun save(file: File,p: Properties) {
        val temp=File(file.parentFile,file.name+".tmp")
        RandomAccessFile(temp,"rw").use { f -> f.setLength(0); val b=java.io.ByteArrayOutputStream().also { p.store(it,null) }.toByteArray(); check(b.size<=16384); f.write(b); Os.fsync(f.fd) }
        Os.rename(temp.path,file.path); AppPrivateLocalState.syncDirectory(file.parentFile!!)
    }
    private fun environment(c: Context,root: Uri): String {
        val pi=c.packageManager.resolveContentProvider(root.authority!!,0)!!
        val pkg=c.packageManager.getPackageInfo(pi.packageName,0)
        return listOf(root.authority,pi.packageName,pkg.longVersionCode,"${pkg.versionName}:${pkg.lastUpdateTime}",Build.VERSION.SDK_INT,Build.FINGERPRINT).joinToString("\u0000")
    }
    private fun grant(c: Context,root: Uri) {
        val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        check(c.checkUriPermission(root,Process.myPid(),Process.myUid(),flags)==PackageManager.PERMISSION_GRANTED)
        check(c.contentResolver.persistedUriPermissions.any { it.uri==root && it.isReadPermission && it.isWritePermission })
        check(SafStoreFactory(c).gate(root).granted.isEmpty()) // Real endpoint remains unqualified.
        val unexpected=try { SafStoreFactory(c).create(root) }
            catch(e: StoreException) { check(e.code==IoCode.UnsupportedDurability); null }
        if(unexpected!=null) {
            unexpected.second.use { } // Close failure cannot convert unexpected creation into PASS.
            error("PRODUCTION_CREATE_UNEXPECTEDLY_ALLOWED")
        }
    }
    fun phase(c: Context,id: String): Bundle = Bundle().apply {
        val f=File(dir(c,id),"checkpoint.properties")
        putString("checkpointPhase",if(f.isFile) read(f).getProperty("phase") else "MISSING")
    }
    fun prepare(c: Context,id: String): Bundle {
        val directory=dir(c,id); val checkpoint=File(directory,"checkpoint.properties")
        check(!checkpoint.exists()) // No replay or implicit reset.
        val rootFile=File(c.noBackupFilesDir,"p6a-probe-$id-root.txt")
        check(rootFile.length() in 1..4096)
        val root=Uri.parse(rootFile.readText())
        check(root.authority=="com.android.externalstorage.documents")
        check(DocumentsContract.getTreeDocumentId(root).startsWith("primary:"))
        grant(c,root)
        // Explicit probe exception: a NEW synthetic scratch only. This does not bypass the
        // production factory's create gate for any real container or workflow.
        val p=Properties().apply {
            setProperty("runId",id); setProperty("phase","BEFORE_CREATE"); setProperty("root",root.toString())
            setProperty("environment",environment(c,root)); setProperty("pid",Process.myPid().toString())
            setProperty("session",session); setProperty("bootCount",boot(c).toString())
        }
        save(checkpoint,p)
        val parent=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root))
        val uri=DocumentsContract.createDocument(c.contentResolver,parent,"application/octet-stream","cscroll-qualification-$id.bin") ?: error("Unknown create outcome; retain")
        p.setProperty("uri",uri.toString()); p.setProperty("phase","URI_RETURNED"); save(checkpoint,p)
        val bytes=ByteArray(262144) { (it%251).toByte() }
        try {
            val fd=c.contentResolver.openFileDescriptor(uri,"rw") ?: error("No FD")
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use {
                val stat=Os.fstat(fd.fileDescriptor); check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size==0L)
                check(Os.fcntlInt(fd.fileDescriptor,OsConstants.F_GETFL,0) and OsConstants.O_ACCMODE==OsConstants.O_RDWR)
                p.setProperty("identity","${stat.st_dev}:${stat.st_ino}")
                AndroidIo.transfer(bytes,0) { s,n,pos -> SystemCalls.write(fd.fileDescriptor,bytes,s,n,pos) }
                p.setProperty("phase","BYTES_WRITTEN"); save(checkpoint,p)
                SystemCalls.sync(fd.fileDescriptor)
                p.setProperty("phase","FILE_FORCED"); save(checkpoint,p)
            }
            val digest=Bytes(hash(bytes)); val identifier=Bytes(hash(id.toByteArray()).copyOf(16))
            val binding=DocumentFingerprint(uri.toString(),identifier,identifier,1,bytes.size.toLong(),digest,digest)
            AppPrivateLocalState.open(c,"p6a2-local-$id").use { local ->
                check(local.initialize(LocalState(binding,null))==LocalWriteCode.Committed)
                val journal=StateJournal(identifier,"qualification",listOf(identifier),binding,null,null,JournalStage.Prepared,binding)
                check(local.replace(LocalState(binding,journal))==LocalWriteCode.Committed)
                p.setProperty("localHash",hex(hash(LocalStateCodec.encode(local.read()))))
            }
            p.setProperty("sha256",hex(hash(bytes))); p.setProperty("phase","PREPARED"); save(checkpoint,p)
            verifyBytes(c,uri,p)
            return receipt(c,root,p,"PREPARED")
        } finally { bytes.fill(0) }
    }
    private fun verifyBytes(c: Context,uri: Uri,p: Properties) {
        val opened=SafStoreFactory(c).openOriginal(uri,true)
        opened.store.use { store ->
            check(!opened.writable && opened.decision.granted.isEmpty() && !store.guarantees.existingWriteDurable)
            check(store.size==262144L)
            val bytes=store.readFullyAt(0,262144)
            try { check(hex(hash(bytes))==p.getProperty("sha256")) } finally { bytes.fill(0) }
        }
        // Observed fstat only, after closing the adapter: no duplicate close under its lease.
        c.contentResolver.openFileDescriptor(uri,"r")!!.use { fd ->
            val stat=Os.fstat(fd.fileDescriptor); check("${stat.st_dev}:${stat.st_ino}"==p.getProperty("identity"))
        }
    }
    fun recover(c: Context,id: String,reboot: Boolean): Bundle {
        val checkpoint=File(dir(c,id),"checkpoint.properties"); val p=read(checkpoint)
        check(p.getProperty("runId")==id && p.getProperty("phase")=="PREPARED")
        if(reboot) check(boot(c)>p.getProperty("bootCount").toInt()) { "REBOOT_NOT_OBSERVED" }
        else check(boot(c)==p.getProperty("bootCount").toInt() && Process.myPid()!=p.getProperty("pid").toInt())
        check(session!=p.getProperty("session")) { "FRESH_WORKER_NOT_OBSERVED" }
        val root=Uri.parse(p.getProperty("root")); check(environment(c,root)==p.getProperty("environment")) { "ENVIRONMENT_CHANGED" }; grant(c,root)
        verifyBytes(c,Uri.parse(p.getProperty("uri")),p)
        AppPrivateLocalState.open(c,"p6a2-local-$id").use { local ->
            check(local.presence==LocalPresence.Ready)
            check(hex(hash(LocalStateCodec.encode(local.read())))==p.getProperty("localHash"))
            check(local.read().journal!!.stage==JournalStage.Prepared)
        }
        p.setProperty("phase","RECOVERED"); save(checkpoint,p)
        return receipt(c,root,p,"RECOVERED")
    }
    private fun receipt(c: Context,root: Uri,p: Properties,phase: String)=Bundle().apply {
        val pi=c.packageManager.resolveContentProvider(root.authority!!,0)!!
        val pkg=c.packageManager.getPackageInfo(pi.packageName,0)
        putString("status",phase); putString("runId",p.getProperty("runId")); putInt("pid",Process.myPid())
        putString("session",session); putInt("bootCount",boot(c))
        putString("rootHash",AndroidSafStore.hash(root.toString())); putString("environmentHash",AndroidSafStore.hash(p.getProperty("environment")))
        putString("authority",root.authority); putString("providerPackage",pi.packageName)
        putString("providerVersion",pkg.longVersionCode.toString()); putString("providerBuildHash",AndroidSafStore.hash("${pkg.versionName}:${pkg.lastUpdateTime}"))
        putString("androidBuildHash",AndroidSafStore.hash(Build.FINGERPRINT)); putString("androidApi",Build.VERSION.SDK_INT.toString())
        putString("checkpoints","BEFORE_CREATE,URI_RETURNED,BYTES_WRITTEN,FILE_FORCED,PREPARED"+if(phase=="RECOVERED") ",RECOVERED" else "")
        putString("lock","INTERPROCESS_PROBE_OBSERVED")
        putString("productionCreate","REFUSED_BEFORE_CREATE")
        putString("namespacePublication","UNSUPPORTED_PUBLIC_SAF")
        putString("recovery","LOCAL_RECORD_HASH_AND_SCRATCH_BYTES")
        putString("grants","PERSISTED_RW_OBSERVED"); putString("identity","FSTAT_EQUAL_OBSERVED_NOT_QUALIFIED")
        putString("cleanup","RETAINED_NO_QUALIFIED_DELETE_CONTRACT")
    }
}
