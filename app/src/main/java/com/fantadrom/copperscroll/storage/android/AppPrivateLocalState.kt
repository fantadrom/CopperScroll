package com.fantadrom.copperscroll.storage.android

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.fantadrom.copperscroll.storage.io.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

enum class LocalPresence { Fresh, Ready, Unresolved }
/** One noBackup record, one stable lock file, one init-intent marker. Never an empty-state fallback.
 * Files contain ONLY LocalStateCodec metadata, never credentials or decrypted engine objects.
 * Committed is available only under the separately qualified local contract (test build in P6a.1).
 */
class AppPrivateLocalState private constructor(private val context: Context, private val directory: File,
    private val channel: FileChannel, private val lock: FileLock, private val createdDirectory: Boolean) : LocalStateStore, AutoCloseable {
    private val state=File(directory,"state.bin")
    private val marker=File(directory,"initialized")
    private var closed=false
    private var unknown=false
    internal var lastFailure: Throwable?=null; private set
    private fun environment(): StorageEnvironment {
        val pkg=context.packageManager.getPackageInfo(context.packageName,0)
        val stat=Os.stat(directory.path)
        return StorageEnvironment("app-private",context.packageName,pkg.longVersionCode.toString(),"${pkg.versionName}:${pkg.lastUpdateTime}",
            android.os.Build.VERSION.SDK_INT,android.os.Build.FINGERPRINT,directory.canonicalPath+":"+stat.st_ino,
            stat.st_dev.toString(),"app-private-fd:"+stat.st_dev,"local-publication-protocol;physical-power-loss-NOT-qualified")
    }
    private val openedEnvironment=environment()
    val decision: GateDecision get()=AdmissionMatcher.decide(environment(),ProfileAdmission.localContract(context,openedEnvironment))
    override val durableLocalState get()=decision.permits(StorageGuarantee.DurableLocalState)
    override val reconciliationRequired get()=unknown || presence==LocalPresence.Unresolved
    val presence: LocalPresence get()=when {
        state.isFile && marker.isFile -> LocalPresence.Ready
        !state.exists() && !marker.exists() && createdDirectory -> LocalPresence.Fresh
        else -> LocalPresence.Unresolved
    }
    private fun check() { requireIo(!closed,IoCode.Closed) }
    @Synchronized override fun read(): LocalState {
        check()
        try {
            requireIo(presence==LocalPresence.Ready,IoCode.AccessLost)
            requireIo(marker.length()==3L && marker.readBytes().contentEquals(byteArrayOf(0x43,0x53,1)),IoCode.AccessLost)
            return decodeFile(state)
        } catch(e: Throwable) { unknown=true; throw e }
    }
    /** Explicit first-binding action. A durable marker precedes state creation so crash/loss cannot
     * turn an interrupted initialization into an automatically accepted first launch. */
    @Synchronized fun initialize(initial: LocalState): LocalWriteCode {
        check(); if(unknown) return LocalWriteCode.StateUnknown
        if(!durableLocalState || presence!=LocalPresence.Fresh) return LocalWriteCode.IoFailure
        try {
            point("InitialDirectory","Before"); syncDirectory(directory); syncDirectory(directory.parentFile!!)
            // Context may itself have just created noBackupFilesDir. Publish that owned name too.
            syncDirectory(context.noBackupFilesDir.parentFile!!); point("InitialDirectory","After")
            point("Marker","Before")
            RandomAccessFile(marker,"rw").use { f -> f.write(byteArrayOf(0x43,0x53,1)); Os.fsync(f.fd) }
            syncDirectory(directory); point("Marker","After")
        } catch(e: Throwable) { lastFailure=e; unknown=true; return LocalWriteCode.StateUnknown }
        return publish(initial,true)
    }
    @Synchronized override fun replace(state: LocalState): LocalWriteCode {
        check(); if(!durableLocalState) return LocalWriteCode.IoFailure
        if(reconciliationRequired || presence!=LocalPresence.Ready) return LocalWriteCode.StateUnknown
        return publish(state,false)
    }
    private fun publish(value: LocalState,initializing: Boolean): LocalWriteCode {
        val bytes=LocalStateCodec.encode(value)
        val temp=File(directory,"state-${UUID.randomUUID()}.tmp")
        var renameStarted=false; var failure: Throwable?=null
        var result=LocalWriteCode.IoFailure
        try {
            requireIo(temp.createNewFile(),IoCode.Eio)
            RandomAccessFile(temp,"rw").use { f ->
                point("TempWrite","Before")
                val split=bytes.size/2; f.write(bytes,0,split); point("TempWrite","Partial"); f.write(bytes,split,bytes.size-split)
                point("TempWrite","After"); point("TempForce","Before"); point("TempForce","Partial")
                Os.fsync(f.fd); point("TempForce","After")
            }
            // Even a failed rename syscall may have taken effect. Its whole phase is ambiguous.
            renameStarted=true; point("Rename","Before"); Os.rename(temp.path,state.path); point("Rename","Partial"); point("Rename","After")
            point("ParentForce","Before"); syncDirectory(directory); point("ParentForce","Partial"); point("ParentForce","After")
            point("Readback","Before"); val actual=LocalStateCodec.encode(decodeFile(state))
            try { requireIo(actual.contentEquals(bytes),IoCode.Eio) } finally { actual.fill(0) }
            point("Readback","Partial"); point("Readback","After")
            point("Ack","Before"); point("Ack","Partial"); point("Ack","After")
            unknown=false; result=LocalWriteCode.Committed
        } catch(e: Throwable) {
            failure=e; unknown=renameStarted || initializing
            result=if(unknown) LocalWriteCode.StateUnknown else LocalWriteCode.IoFailure
        } finally {
            bytes.fill(0)
            try { if(temp.exists()) requireIo(temp.delete(),IoCode.Eio) }
            catch(cleanup: Throwable) {
                if(failure!=null) failure.addSuppressed(cleanup) else { failure=cleanup; unknown=true; result=LocalWriteCode.StateUnknown }
            }
            lastFailure=failure
        }
        return result
    }
    @Synchronized override fun reconcile(): LocalState {
        check(); requireIo(durableLocalState,IoCode.UnsupportedDurability)
        // Reading a whole valid record is not an authority to choose a document. P5 recovery does
        // fresh credentials/grants/identities/whole verification before any switch or writer.
        val observed=read()
        RandomAccessFile(state,"rw").use { Os.fsync(it.fd) }; syncDirectory(directory)
        requireIo(LocalStateCodec.encode(observed).contentEquals(LocalStateCodec.encode(read())),IoCode.Eio)
        unknown=false; return observed
    }
    @Synchronized override fun close() {
        if(closed) return
        var failure: Throwable?=null
        try { lock.release() } catch(e: Throwable) { failure=e }
        try { channel.close() } catch(e: Throwable) { if(failure==null) failure=e else failure.addSuppressed(e) }
        finally { closed=true; synchronized(held) { held.remove(directory.path) } }
        failure?.let { throw it }
    }
    private fun point(p: String,m: String)=ProfileAdmission.localPoint(p,m)
    companion object {
        private val held=HashSet<String>()
        fun open(context: Context,name: String = "container-binding"): AppPrivateLocalState = AndroidIo.call {
            requireIo(name.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
            val base=context.applicationContext.noBackupFilesDir.canonicalFile
            val dir=File(base,name); requireIo(!java.nio.file.Files.isSymbolicLink(dir.toPath()),IoCode.AccessLost)
            synchronized(held) {
                requireIo(held.add(dir.path),IoCode.Busy)
                var ch: FileChannel?=null; var lock: FileLock?=null
                try {
                    val fresh=!dir.exists(); requireIo(dir.isDirectory || dir.mkdir(),IoCode.Eio)
                    ch=RandomAccessFile(File(dir,"state.lock"),"rw").channel
                    lock=try { ch.tryLock() ?: throw StoreException(IoCode.Busy) } catch(_: OverlappingFileLockException) { throw StoreException(IoCode.Busy) }
                    AppPrivateLocalState(context.applicationContext,dir,ch,lock,fresh)
                } catch(e: Throwable) {
                    try { lock?.release() } catch(c: Throwable) { e.addSuppressed(c) }
                    try { ch?.close() } catch(c: Throwable) { e.addSuppressed(c) }
                    held.remove(dir.path); throw e
                }
            }
        }
        internal fun decodeFile(file: File): LocalState = AndroidIo.call {
            RandomAccessFile(file,"r").use { f ->
                requireIo(f.length() in 45..LocalStateCodec.MAX_BYTES.toLong(),IoCode.Eio)
                val b=ByteArray(f.length().toInt()); try { f.readFully(b); LocalStateCodec.decode(b) } finally { b.fill(0) }
            }
        }
        internal fun syncDirectory(file: File) {
            val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,0)
            var primary: Throwable?=null
            try { requireIo(OsConstants.S_ISDIR(Os.fstat(fd).st_mode),IoCode.AccessLost); Os.fsync(fd) } catch(e: Throwable) { primary=e; throw e }
            finally { try { Os.close(fd) } catch(c: Throwable) { if(primary!=null) primary.addSuppressed(c) else throw c } }
        }
    }
}
