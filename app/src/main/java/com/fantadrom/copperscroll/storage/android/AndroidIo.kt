package com.fantadrom.copperscroll.storage.android

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.fantadrom.copperscroll.storage.io.*
import java.io.FileDescriptor
import java.io.InterruptedIOException

/** Narrow syscall boundary. Only the test variant can replace calls, not an entire store/core. */
internal interface PositionalCalls {
    fun read(fd: FileDescriptor, b: ByteArray, s: Int, n: Int, p: Long): Int
    fun write(fd: FileDescriptor, b: ByteArray, s: Int, n: Int, p: Long): Int
    fun sync(fd: FileDescriptor)
}
internal object SystemCalls : PositionalCalls {
    override fun read(fd: FileDescriptor,b: ByteArray,s: Int,n: Int,p: Long) = Os.pread(fd,b,s,n,p)
    override fun write(fd: FileDescriptor,b: ByteArray,s: Int,n: Int,p: Long) = Os.pwrite(fd,b,s,n,p)
    override fun sync(fd: FileDescriptor) = Os.fsync(fd)
}
internal object AndroidIo {
    fun <T> call(block: ()->T): T = try { block() }
        catch(e: StoreException) { throw e }
        catch(e: ErrnoException) { throw StoreException(when(e.errno) {
            OsConstants.ENOSPC,OsConstants.EDQUOT -> IoCode.NoSpace
            OsConstants.EACCES,OsConstants.EPERM,OsConstants.ENOENT -> IoCode.AccessLost
            OsConstants.EAGAIN -> IoCode.Busy
            else -> IoCode.Eio
        }).also { it.addSuppressed(e) } }
        catch(e: SecurityException) { throw StoreException(IoCode.AccessLost).also { it.addSuppressed(e) } }
        catch(e: java.io.IOException) { throw StoreException(IoCode.Eio).also { it.addSuppressed(e) } }
    fun transfer(bytes: ByteArray, offset: Long, operation: (Int,Int,Long)->Int) {
        endAt(offset,bytes.size.toLong()); var done=0; var interrupted=0
        while(done<bytes.size) {
            val n=try { operation(done,bytes.size-done,offset+done) }
                catch(e: ErrnoException) { if(e.errno!=OsConstants.EINTR || ++interrupted>64) throw e; continue }
                catch(e: InterruptedIOException) {
                    if(e.bytesTransferred <= 0 || e.bytesTransferred > bytes.size-done) throw e
                    done+=e.bytesTransferred; continue
                }
            requireIo(n>0 && n<=bytes.size-done,IoCode.Eio); done+=n; interrupted=0
        }
    }
}
