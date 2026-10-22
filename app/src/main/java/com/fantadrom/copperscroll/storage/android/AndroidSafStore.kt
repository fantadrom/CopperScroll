package com.fantadrom.copperscroll.storage.android

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.fantadrom.copperscroll.storage.io.*
import java.io.File
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

/** PFD -> one framework AutoClose stream -> its channel. No dup, borrowed stream or extra close.
 * Positional IO borrows the FD and never closes it. Only ResourceOwner closes the store chain.
 */
class AndroidSafStore private constructor(private val factory: SafStoreFactory,
    private val inspected: SafInspection, private val pfd: ParcelFileDescriptor,
    val writable: Boolean, override val identity: StoreIdentity) : RandomAccessStore {
    private val stream: Closeable = if(writable) ParcelFileDescriptor.AutoCloseOutputStream(pfd) else ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val channel: FileChannel = when(val s=stream) {
        is ParcelFileDescriptor.AutoCloseOutputStream -> s.channel
        is ParcelFileDescriptor.AutoCloseInputStream -> s.channel
        else -> error("Stream ownership")
    }
    internal val endpointStamp=inspected.environment to inspected.contractIdentity?.document
    private val fd get()=pfd.fileDescriptor
    private val calls=ProfileAdmission.calls(factory.context,inspected.uri)
    @Volatile private var closed=false
    override val guarantees=StoreGuarantees(writable && inspected.decision.permits(StorageGuarantee.ExistingWriteDurable),
        writable && inspected.decision.permits(StorageGuarantee.DurableCreateIdentity))
    private fun check(write: Boolean = false) {
        requireIo(!closed,IoCode.Closed); requireIo(!write || writable,IoCode.AccessLost)
        val actual=factory.inspect(inspected.uri,writable)
        requireIo(actual.environment==inspected.environment && actual.contractIdentity==inspected.contractIdentity,IoCode.AccessLost)
        if(writable) actual.decision.require(StorageGuarantee.ExistingWriteDurable)
        val stat=Os.fstat(fd)
        requireIo(OsConstants.S_ISREG(stat.st_mode) && identity(stat.st_dev,stat.st_ino)==identity,IoCode.AccessLost)
    }
    override val size: Long get()=AndroidIo.call { check(); Os.fstat(fd).st_size.also { endAt(it,0) } }
    override fun readFullyAt(offset: Long,length: Int): ByteArray = AndroidIo.call {
        requireIo(length in 0..(8*1024*1024)); endAt(offset,length.toLong()); check()
        requireIo(offset+length<=Os.fstat(fd).st_size,IoCode.Eio)
        val b=ByteArray(length)
        try { AndroidIo.transfer(b,offset) { s,n,p -> calls.read(fd,b,s,n,p) }; b }
        catch(e: Throwable) { b.fill(0); throw e }
    }
    override fun writeFullyAt(offset: Long,bytes: ByteArray) = AndroidIo.call {
        endAt(offset,bytes.size.toLong()); check(true)
        AndroidIo.transfer(bytes,offset) { s,n,p -> calls.write(fd,bytes,s,n,p) }
    }
    override fun force() = AndroidIo.call { check(true); requireIo(guarantees.existingWriteDurable,IoCode.UnsupportedDurability); calls.sync(fd) }
    override fun forceIdentity() = AndroidIo.call {
        check(); requireIo(guarantees.durableCreateIdentity,IoCode.UnsupportedDurability); check(true)
        // Profile-specific namespace publication. A repeated file fsync would not implement this.
        ProfileAdmission.forceIdentity(factory.context,inspected.uri)
        check(true)
    }
    override fun truncate(length: Long): Boolean = AndroidIo.call { endAt(length,0); check(true); Os.ftruncate(fd,length); true }
    override fun acquireExclusive(): AutoCloseable = AndroidIo.call {
        check()
        SafLifetimeLease.acquire(factory.context.noBackupFilesDir,identity,this)
    }
    internal fun documentLock(): FileLock = try { channel.tryLock(0,Long.MAX_VALUE,!writable) ?: throw StoreException(IoCode.Busy) }
        catch(_: OverlappingFileLockException) { throw StoreException(IoCode.Busy) }
    @Synchronized override fun close() {
        if(closed) return
        try { stream.close() } finally { closed=true; synchronized(handles) { handles.remove(identity) } }
    }
    companion object {
        private val handles=HashSet<StoreIdentity>()
        internal fun identity(device: Long,inode: Long)=StoreIdentity("android-fd:"+hash(Build.FINGERPRINT),"$device:$inode")
        internal fun hash(s: String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        internal fun open(factory: SafStoreFactory,i: SafInspection,writable: Boolean): AndroidSafStore = AndroidIo.call {
            synchronized(handles) {
                // The qualified contract supplies a pre-open alias identity. No duplicate descriptor
                // is opened/closed while this process holds a POSIX document lock.
                val known=i.contractIdentity
                val expected=if(known?.device!=null && known.inode!=null) identity(known.device,known.inode) else null
                requireIo(expected==null || expected !in handles,IoCode.Busy)
                requireIo(expected!=null || handles.isEmpty(),IoCode.Busy)
                val pfd=factory.context.contentResolver.openFileDescriptor(i.uri,if(writable) "rw" else "r") ?: throw StoreException(IoCode.AccessLost)
                var registered: StoreIdentity?=null
                try {
                    val stat=Os.fstat(pfd.fileDescriptor)
                    requireIo(OsConstants.S_ISREG(stat.st_mode),IoCode.UnsupportedDurability)
                    val access=Os.fcntlInt(pfd.fileDescriptor,OsConstants.F_GETFL,0) and OsConstants.O_ACCMODE
                    requireIo(access==if(writable) OsConstants.O_RDWR else OsConstants.O_RDONLY,IoCode.AccessLost)
                    Os.lseek(pfd.fileDescriptor,0,OsConstants.SEEK_CUR) // observation, not approval
                    val actual=identity(stat.st_dev,stat.st_ino)
                    requireIo(expected==null || expected==actual,IoCode.AccessLost)
                    // Unknown providers are read-only. A qualified writer requires pre-open identity.
                    requireIo(!writable || expected!=null,IoCode.UnsupportedDurability)
                    requireIo(handles.add(actual),IoCode.Busy); registered=actual
                    AndroidSafStore(factory,i,pfd,writable,actual)
                } catch(e: Throwable) {
                    try { pfd.close() } catch(clean: Throwable) { e.addSuppressed(clean) }
                    registered?.let { handles.remove(it) }; throw e
                }
            }
        }
    }
}

/** Stable APP-PRIVATE lock inode, separate from replaced document/state inode. Keeps cooperative
 * interprocess exclusion during reopen, including when a duplicate FD close drops POSIX file locks.
 * It does not protect against a noncooperating external writer; engine freshness checks still apply.
 */
internal class SafLifetimeLease private constructor(private val key: StoreIdentity,
    private val endpointStamp: Pair<StorageEnvironment,String?>,
    private val guardChannel: FileChannel, private val guardLock: FileLock, private var documentLock: FileLock?) : ReopenLease {
    private var closed=false
    override fun beforeReopen() { documentLock?.release(); documentLock=null }
    override fun afterReopen(store: RandomAccessStore) {
        requireIo(store is AndroidSafStore && store.identity==key && store.endpointStamp==endpointStamp,IoCode.AccessLost)
        documentLock=(store as AndroidSafStore).documentLock()
    }
    override fun close() {
        if(closed) return
        var failure: Throwable?=null
        fun attempt(b: ()->Unit) { try { b() } catch(e: Throwable) { if(failure==null) failure=e else failure!!.addSuppressed(e) } }
        attempt { documentLock?.release() }; attempt { guardLock.release() }; attempt { guardChannel.close() }
        synchronized(held) { held.remove(key) }; closed=true; failure?.let { throw it }
    }
    companion object {
        private val held=HashSet<StoreIdentity>()
        fun acquire(root: File,key: StoreIdentity,store: AndroidSafStore): SafLifetimeLease = synchronized(held) {
            requireIo(held.add(key),IoCode.Busy)
            var channel: FileChannel?=null; var lock: FileLock?=null
            try {
                val directory=File(root,"p6a-locks"); requireIo(directory.isDirectory || directory.mkdir(),IoCode.Eio)
                val file=File(directory,AndroidSafStore.hash(key.namespace+"\u0000"+key.document)+".lock")
                channel=java.io.RandomAccessFile(file,"rw").channel
                lock=try { channel.tryLock() ?: throw StoreException(IoCode.Busy) } catch(_: OverlappingFileLockException) { throw StoreException(IoCode.Busy) }
                SafLifetimeLease(key,store.endpointStamp,channel,lock,store.documentLock())
            } catch(e: Throwable) {
                try { lock?.release() } catch(c: Throwable) { e.addSuppressed(c) }
                try { channel?.close() } catch(c: Throwable) { e.addSuppressed(c) }
                held.remove(key); throw e
            }
        }
    }
}
