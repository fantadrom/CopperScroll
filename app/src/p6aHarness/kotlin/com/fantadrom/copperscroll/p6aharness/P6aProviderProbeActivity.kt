package com.fantadrom.copperscroll.p6aharness

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.widget.*
import com.fantadrom.copperscroll.storage.android.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit test-only scratch probe. Never selects, opens or enumerates a personal container. */
class P6aProviderProbeActivity : Activity() {
    private val picker=SafPickerPort()
    private var token: SafPickerPort.Token?=null
    private lateinit var status: TextView
    private var runId=""
    private var launched=false
    private val screenOff=object: BroadcastReceiver() { override fun onReceive(c: Context,i: Intent) { picker.invalidate(); token=null } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        runId=intent.getStringExtra("runId") ?: ""
        require(runId.matches(Regex("[a-f0-9-]{36}")))
        if(Build.VERSION.SDK_INT>=33) registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF))
        status=TextView(this).apply { text="P6a Provider Probe\nВыберите отдельную пустую тестовую папку. Будут созданы только собственные scratch-файлы с synthetic данными. Это наблюдения, не допуск хранилища." }
        val button=Button(this).apply { text="Выбрать тестовую папку"; isEnabled=savedInstanceState==null; setOnClickListener {
            if(launched) return@setOnClickListener
            launched=true; isEnabled=false
            token=picker.beginLocked(SafOperation.Create)
            @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),31)
        } }
        setContentView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24); addView(status); addView(button) })
        if(savedInstanceState!=null) finishReceipt("picker=STALE_CONFIGURATION;rerun-controller\n")
    }
    @Deprecated("Platform result port for isolated diagnostic harness")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=31) return
        val current=token ?: return; token=null
        val root=try { picker.accept(current,resultCode,data,contentResolver) } catch(_: Exception) { finishReceipt("grant=ERROR\n"); return }
        if(root==null) { finishReceipt("picker=CANCELLED\n"); return }
        if(intent.getBooleanExtra("qualificationPrimaryOnly",false) &&
            (root.authority!="com.android.externalstorage.documents" ||
                !DocumentsContract.getTreeDocumentId(root).startsWith("primary:"))) {
            finishReceipt("probeOperations=UNSUPPORTED\nscope=PRIMARY_REQUIRED_BEFORE_CREATE\n"); return
        }
        status.text="Проверка выполняется вне UI. Не используйте эту папку для личных файлов."
        Thread({ probe(root) },"p6a-scratch-io").start()
    }
    private fun probe(root: Uri) {
        val r=StringBuilder()
        var scratch: Uri?=null; var createdIdentity: Pair<Long,Long>?=null
        fun line(k: String,v: Any?) {
            // Provider metadata is untrusted data, never additional receipt fields/status lines.
            val safe=(v?.toString() ?: "unknown").take(2048).map { c ->
                if(c.code<32 || c.code in listOf(0x7f,0x85,0x2028,0x2029)) "\\u%04x".format(c.code) else c.toString()
            }.joinToString("")
            r.append(k).append('=').append(safe).append('\n')
        }
        try {
            val authority=root.authority!!; val info=packageManager.resolveContentProvider(authority,0)!!
            val pkg=packageManager.getPackageInfo(info.packageName,0)
            line("authority",authority); line("providerPackage",info.packageName); line("providerVersion",pkg.longVersionCode)
            line("providerBuild","${pkg.versionName}:${pkg.lastUpdateTime}"); line("androidApi",Build.VERSION.SDK_INT); line("androidBuild",Build.FINGERPRINT)
            line("device","${Build.MANUFACTURER}:${Build.MODEL}")
            // URI/root are stored privately for later exact matching. Public receipt has only hashes.
            File(noBackupFilesDir,"p6a-probe-$runId-root.txt").writeText(root.toString())
            line("rootHash",AndroidSafStore.hash(root.toString())); line("volume","unknown"); line("backend","unknown")
            line("fdMode","rw;no-truncate"); line("identityStrategy","observed-fstat-dev-inode;NOT-qualified")
            val parent=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root))
            scratch=DocumentsContract.createDocument(contentResolver,parent,"application/octet-stream","cscroll-probe-$runId.bin") ?: error("Null create; outcome unknown")
            line("create","OBSERVED")
            val uri=scratch
            val bytes=ByteArray(65537) { (it%251).toByte() }; val expected=MessageDigest.getInstance("SHA-256").digest(bytes)
            val first=contentResolver.openFileDescriptor(uri,"rw") ?: error("Null FD")
            ParcelFileDescriptor.AutoCloseOutputStream(first).use { stream ->
                val fd=first.fileDescriptor; val stat=Os.fstat(fd); check(OsConstants.S_ISREG(stat.st_mode)); check(stat.st_size==0L)
                createdIdentity=stat.st_dev to stat.st_ino
                AndroidIo.transfer(bytes,11) { s,n,p -> SystemCalls.write(fd,bytes,s,n,p) }
                line("positionalWrite","OBSERVED"); SystemCalls.sync(fd); line("fileFsync","OBSERVED")
                val lock=stream.channel.tryLock() ?: error("Lock unavailable")
                try { RemoteHarness(this).use { remote -> check(remote.request("probeLock",uri).getString("status")=="Busy") }; line("interprocessLock","OBSERVED") }
                finally { lock.release() }
            }
            val reopened=contentResolver.openFileDescriptor(uri,"rw") ?: error("Reopen failed")
            ParcelFileDescriptor.AutoCloseOutputStream(reopened).use {
                val fd=reopened.fileDescriptor; val stat=Os.fstat(fd)
                check(createdIdentity==(stat.st_dev to stat.st_ino)); check(stat.st_size==65548L)
                val actual=ByteArray(bytes.size)
                try { AndroidIo.transfer(actual,11) { s,n,p -> SystemCalls.read(fd,actual,s,n,p) }; check(MessageDigest.getInstance("SHA-256").digest(actual).contentEquals(expected)) }
                finally { actual.fill(0); bytes.fill(0) }
                line("noTruncateReopenLengthHash","OBSERVED")
            }
            val flags=contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use { c -> if(c.moveToFirst()) c.getInt(0).toString() else "unknown" }
            line("documentFlags",flags); line("probeOperations","OBSERVED")
        } catch(e: Exception) { line("probeOperations","ERROR"); line("errorType",e.javaClass.simpleName) }
        finally {
            // Unknown providers have no qualified compare-and-delete identity contract. A stat
            // check followed by deleteDocument is a race; retain our scratch rather than delete
            // an externally replaced document. No name-based search/delete is attempted.
            if(scratch!=null) {
                File(noBackupFilesDir,"p6a-probe-$runId-scratch.txt").writeText(scratch.toString())
                line("scratchHash",AndroidSafStore.hash(scratch.toString()))
                line("cleanup","RETAINED_NO_QUALIFIED_DELETE_CONTRACT")
            } else line("cleanup","no-known-owned-identity;no-delete")
        }
        finishReceipt(r.toString())
    }
    private fun finishReceipt(observations: String) = synchronized(receiptMonitor) {
        if(File(noBackupFilesDir,"p6a-probe-$runId-result.txt").exists()) return@synchronized
        val text="runId=$runId\n"+observations+"existingWriteDurable=UNKNOWN\ndurableCreateIdentity=UNKNOWN\ndurableLocalState=UNKNOWN\nproductionApproved=false\npowerInterruption=NOT_TESTED\nprocessRestart=NOT_TESTED\n"
        val temp=File(noBackupFilesDir,"p6a-probe-$runId-result.tmp")
        java.io.RandomAccessFile(temp,"rw").use { f -> f.write(text.toByteArray()); Os.fsync(f.fd) }
        Os.rename(temp.path,File(noBackupFilesDir,"p6a-probe-$runId-result.txt").path)
        AppPrivateLocalState.syncDirectory(noBackupFilesDir)
        runOnUiThread { status.text="Наблюдения сохранены. productionApproved=false. Можно вернуться в Terminal.\n$observations" }
    }
    companion object { private val receiptMonitor=Any() }
    override fun onDestroy() { picker.invalidate(); unregisterReceiver(screenOff); super.onDestroy() }
}
