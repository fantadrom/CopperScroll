package com.fantadrom.copperscroll.storage.android

import com.fantadrom.copperscroll.storage.io.*
import org.junit.Test
import org.junit.Assert.*

/** The ResourceOwner transition used by SAF must hold its stable guard across the closed-FD gap. */
class ContinuousLeaseTest {
    private class Guard : ReopenLease {
        var held=true; var before=0; var after=0; var closes=0; var failAttach=false
        override fun beforeReopen() { assertTrue(held); before++ }
        override fun afterReopen(store: RandomAccessStore) { assertTrue(held); after++; if(failAttach) throw StoreException(IoCode.AccessLost) }
        override fun close() { assertTrue(held); held=false; closes++ }
    }
    private class Store(val guard: Guard,val same: StoreIdentity=StoreIdentity("test","document")) : RandomAccessStore {
        val backend=FaultStore(); var closes=0
        override val identity=same; override val guarantees=backend.guarantees; override val size get()=backend.size
        override fun readFullyAt(offset: Long,length: Int)=backend.readFullyAt(offset,length)
        override fun writeFullyAt(offset: Long,bytes: ByteArray)=backend.writeFullyAt(offset,bytes)
        override fun force()=backend.force()
        override fun acquireExclusive(): AutoCloseable=guard
        override fun close() { closes++; backend.close() }
    }
    @Test fun noLeaseWindowAndOneCloseAcrossReopen() {
        val guard=Guard(); val first=Store(guard); val next=Store(guard); val owner=ResourceOwner(first)
        val permit=owner.accept(); owner.execute(permit) { owner.reopen(permit) { assertTrue(guard.held); assertEquals(1,first.closes); next } }
        assertSame(next,owner.store); assertEquals(1,guard.before); assertEquals(1,guard.after)
        owner.close(); assertEquals(1,guard.closes); assertEquals(1,next.closes); assertEquals(1,first.closes)
    }
    @Test fun attachFailureClosesNewFdButRetainsGuardUntilOwnerRelease() {
        val guard=Guard().apply { failAttach=true }; val first=Store(guard); val next=Store(guard); val owner=ResourceOwner(first)
        val permit=owner.accept()
        try { owner.execute(permit) { owner.reopen(permit) { next } }; fail() } catch(_: StoreException) { }
        assertTrue(guard.held); assertEquals(1,next.closes); owner.close(); assertEquals(1,guard.closes); assertEquals(1,first.closes)
    }
    @Test fun failedOpenStillRetainsStableGuard() {
        val guard=Guard(); val first=Store(guard); val owner=ResourceOwner(first); val p=owner.accept()
        try { owner.execute(p) { owner.reopen(p) { throw StoreException(IoCode.AccessLost) } }; fail() } catch(_: StoreException) { }
        assertTrue(guard.held); owner.close(); assertEquals(1,guard.closes); assertEquals(1,first.closes)
    }
}
