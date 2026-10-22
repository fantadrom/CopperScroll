package com.fantadrom.copperscroll.p6aharness

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.Os
import com.fantadrom.copperscroll.storage.android.AppPrivateLocalState
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Same target UID, separate process. Not a power-interruption simulator or a real-provider proof. */
class ControlledDocumentsProvider : DocumentsProvider() {
    private val base get()=File(context!!.noBackupFilesDir,"p6a-provider")
    private val restartFaults by lazy { ProviderRestartFaults(File(base,"restart-faults")) }
    private val modes=HashMap<String,String>()
    private val published=HashSet<String>()
    private var backend="p6a-controlled-v1"
    private var namespaceSync=true
    private var creates=0
    private fun caller() { if(Binder.getCallingUid()!=Process.myUid()) throw SecurityException("Harness UID only") }
    private fun canonical(id: String)=id.removePrefix("alias:")
    private fun root(id: String)=canonical(id).substringBefore(':')
    private fun file(id: String): File {
        val c=canonical(id); require(c.matches(Regex("[abc](:[a-f0-9-]{36})?")))
        val r=File(base,root(c))
        return if(':' in c) File(r,c.substringAfter(':')) else r
    }
    override fun onCreate(): Boolean { base.mkdirs(); for(r in listOf("a","b","c")) file(r).mkdir(); return true }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID)).apply {
        // Hide controlled roots from the user's real-provider picker.
    }
    override fun queryDocument(documentId: String,projection: Array<out String>?): Cursor {
        caller(); val columns=projection ?: arrayOf("document_id","display_name","mime_type","flags","_size")
        val f=file(documentId); if(!f.exists() || modes[canonical(documentId)]=="missing" || restartFaults.state(canonical(documentId))=="unavailable") throw FileNotFoundException()
        val mode=modes[canonical(documentId)]
        if(mode=="denied") throw SecurityException("Synthetic grant denial")
        val flags=if(f.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else
            (if(mode=="readonly") 0 else DocumentsContract.Document.FLAG_SUPPORTS_WRITE) or
            if(mode=="virtual") DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT else 0
        return MatrixCursor(columns).also { c -> c.addRow(columns.map { col -> when(col) {
            "document_id" -> documentId; "display_name" -> "p6a-synthetic"; "mime_type" -> if(f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
            "flags" -> flags; "_size" -> if(mode=="unknownLength") null else if(mode=="staleSize") 1L else f.length(); else -> null
        } }) }
    }
    override fun queryChildDocuments(parentDocumentId: String,projection: Array<out String>?,sortOrder: String?): Cursor {
        caller(); val columns=projection ?: arrayOf("document_id"); val c=MatrixCursor(columns)
        file(parentDocumentId).listFiles()?.forEach { f -> queryDocument("${root(parentDocumentId)}:${f.name}",columns).use { row ->
            if(row.moveToFirst()) c.addRow(columns.indices.map { i -> when(row.getType(i)) { Cursor.FIELD_TYPE_INTEGER -> row.getLong(i); Cursor.FIELD_TYPE_STRING -> row.getString(i); else -> null } })
        } }; return c
    }
    override fun isChildDocument(parentDocumentId: String,documentId: String): Boolean { caller(); return root(parentDocumentId)==root(documentId) && canonical(parentDocumentId)!=canonical(documentId) }
    override fun createDocument(parentDocumentId: String,mimeType: String,displayName: String): String {
        caller(); creates++
        if(modes["create"]=="before") throw FileNotFoundException()
        val id="${root(parentDocumentId)}:${UUID.randomUUID()}"; check(file(id).createNewFile())
        if(modes["create"]=="after") throw IllegalStateException("Create outcome unknown")
        return id // exclusive, never replacing an existing ID; display name is intentionally changed
    }
    override fun deleteDocument(documentId: String) { caller(); check(file(documentId).isFile && file(documentId).delete()); published.remove(canonical(documentId)) }
    override fun openDocument(documentId: String,mode: String,signal: CancellationSignal?): ParcelFileDescriptor {
        caller(); val id=canonical(documentId); val f=file(id)
        when(modes[id]) {
            "denied" -> throw SecurityException("Synthetic denial")
            "missing" -> throw FileNotFoundException()
            "pipe","virtual" -> { val pipe=ParcelFileDescriptor.createPipe(); pipe[1].close(); return pipe[0] }
        }
        if(!f.isFile) throw FileNotFoundException()
        if(mode!="r" && mode!="rw") throw IllegalArgumentException("No truncation mode")
        // Commit the consumed/unavailable state before death. Framework retry starts a new
        // provider process; an in-memory switch cannot describe persistent unavailability.
        when(restartFaults.beforeOpen(id)) {
            ProviderRestartFaults.Action.Die -> { Process.killProcess(Process.myPid()); throw FileNotFoundException() }
            ProviderRestartFaults.Action.Unavailable -> throw FileNotFoundException()
            ProviderRestartFaults.Action.Allow -> Unit
        }
        if(mode=="rw" && modes[id]=="readonly") throw SecurityException("Read only")
        return ParcelFileDescriptor.open(f,if(mode=="rw" && modes[id]!="readonlyFd") ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun call(method: String,arg: String?,extras: Bundle?): Bundle? {
        if(!method.startsWith("p6a.")) return super.call(method,arg,extras)
        caller()
        val out=Bundle()
        when(method) {
            "p6a.identity" -> {
                val uri=Uri.parse(arg!!); val id=if(DocumentsContract.isDocumentUri(context!!,uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
                val f=file(id); val stat=Os.stat(f.path)
                out.putString("root",root(id)); out.putString("volume","private-test-volume"); out.putString("backend",backend)
                out.putString("document",canonical(id)); out.putLong("device",stat.st_dev); out.putLong("inode",stat.st_ino)
            }
            "p6a.dataForce" -> {
                val id=canonical(DocumentsContract.getDocumentId(Uri.parse(arg!!)))
                val snapshots=File(base,"forced-data").also { it.mkdirs() }
                file(id).copyTo(File(snapshots,id),overwrite=true)
            }
            "p6a.dataLoss" -> {
                for(r in listOf("a","b","c")) file(r).listFiles()?.forEach { f ->
                    val saved=File(File(base,"forced-data"),"$r:${f.name}")
                    if(saved.exists()) saved.inputStream().use { input -> java.io.FileOutputStream(f,false).use { output -> input.copyTo(output,65536) } } else java.io.RandomAccessFile(f,"rw").use { it.setLength(0) }
                }
            }
            "p6a.forceIdentity" -> {
                if(!namespaceSync) throw IllegalStateException("Namespace contract unavailable")
                val id=DocumentsContract.getDocumentId(Uri.parse(arg!!)); val f=file(id)
                AppPrivateLocalState.syncDirectory(f.parentFile!!); AppPrivateLocalState.syncDirectory(base)
                published.add(canonical(id)); out.putBoolean("published",true)
            }
            "p6a.reset" -> { base.deleteRecursively(); base.mkdirs(); modes.clear(); published.clear(); backend="p6a-controlled-v1"; creates=0; namespaceSync=true; for(r in listOf("a","b","c")) file(r).mkdir() }
            "p6a.mode" -> {
                val id=canonical(arg!!)
                val mode=extras!!.getString("mode")!!
                if(id=="create") { require(mode in setOf("before","after","normal")); modes[id]=mode }
                else { file(id); when(mode) {
                    "deathOnce" -> { restartFaults.arm(id,false); modes.remove(id) }
                    "deathUnavailable" -> { restartFaults.arm(id,true); modes.remove(id) }
                    "death" -> error("Use an explicit restart scenario")
                    else -> { restartFaults.clear(id); modes[id]=mode }
                } }
            }
            "p6a.restartFault" -> out.putString("state",restartFaults.state(canonical(arg!!)))
            "p6a.backend" -> backend=arg!!
            "p6a.namespace" -> namespaceSync=arg=="on"
            "p6a.namespaceLoss" -> for(r in listOf("a","b","c")) file(r).listFiles()?.forEach { f -> if("$r:${f.name}" !in published) f.delete() }
            "p6a.replace" -> { val f=file(arg!!); val tmp=File(f.parentFile,"replacement"); tmp.writeBytes(byteArrayOf(1)); Os.rename(tmp.path,f.path) }
            "p6a.count" -> { out.putInt("creates",creates); out.putInt("documents",listOf("a","b","c").sumOf { file(it).listFiles()?.size ?: 0 }) }
            "p6a.pid" -> out.putInt("pid",Process.myPid())
            else -> throw IllegalArgumentException("Unknown harness command")
        }
        return out
    }
}
