package com.fantadrom.copperscroll.p6aharness

import android.system.ErrnoException
import android.system.OsConstants
import com.fantadrom.copperscroll.storage.android.*
import com.fantadrom.copperscroll.storage.io.*
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch

/** Test-only syscall shim. Actual ContentResolver/PFD/Os remain the executed backend. */
internal object HarnessFaults : PositionalCalls {
    var shortLimit=Int.MAX_VALUE
    var eintr=0
    var zero=false
    var errno=0
    var syncFailure=false
    var localFault: Pair<String,String>?=null
    var blockingSync: Pair<CountDownLatch,CountDownLatch>?=null
    fun clear() { shortLimit=Int.MAX_VALUE; eintr=0; zero=false; errno=0; syncFailure=false; localFault=null; blockingSync=null }
    private fun amount(n: Int): Int {
        if(eintr>0) { eintr--; throw ErrnoException("synthetic",OsConstants.EINTR) }
        if(errno!=0) throw ErrnoException("synthetic",errno)
        return if(zero) 0 else minOf(n,shortLimit)
    }
    override fun read(fd: FileDescriptor,b: ByteArray,s: Int,n: Int,p: Long): Int {
        val a=amount(n); return if(a==0) 0 else SystemCalls.read(fd,b,s,a,p)
    }
    override fun write(fd: FileDescriptor,b: ByteArray,s: Int,n: Int,p: Long): Int {
        val a=amount(n); return if(a==0) 0 else SystemCalls.write(fd,b,s,a,p)
    }
    override fun sync(fd: FileDescriptor) {
        blockingSync?.let { it.first.countDown(); it.second.await() }
        if(syncFailure) throw ErrnoException("synthetic",OsConstants.EIO)
        SystemCalls.sync(fd)
    }
    fun localPoint(p: String,m: String) { if(localFault==(p to m)) throw StoreException(IoCode.Eio) }
}
