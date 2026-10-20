package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.runtimechecks.P3Fixtures as F
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import com.fantadrom.copperscroll.storage.crypto.*

class P3RetryWindowTest {
    @Test fun incompleteCreateAndCorruptReadbackCanCloseWithoutFalseAck() {
        val baseline = FaultStore(); val baseCore = TransactionCore(ResourceOwner(baseline))
        check(baseCore.submit(baseCore.issueCreate(F.prepared(1))).code == WriteCode.Committed)
        val point = baseline.events.single { it.action == IoAction.Write && it.offset == 0L && it.length == 65536 }.index
        baseCore.close()
        val store = FaultStore(); val core = TransactionCore(ResourceOwner(store)); val token = core.issueCreate(F.prepared(1))
        store.fault = IoFault(point,FaultMoment.Partial,1)
        check(core.submit(token).code == WriteCode.CommitUnknown); store.fault = null
        check(core.reconcile().code == WriteCode.NotApplied && core.owner.accessRevoked)
        core.close(); check(store.closeCount == 1)
        val (corrupt,c) = F.core(F.image(),hook={ throw StoreException(IoCode.Eio) })
        val t = c.issueMutation(F.mutation(c)); check(c.submit(t).code == WriteCode.CommitUnknown)
        val snapshot = ContainerReader(corrupt).snapshot(); val root = snapshot.catalogues[0][snapshot.header.zones[0].root.recordId]!!
        val index = root.offset.toInt()+80
        corrupt.medium.corrupt(index,(corrupt.medium.bytes()[index].toInt() xor 1).toByte())
        check(c.reconcile().code == WriteCode.CorruptData && c.owner.accessRevoked)
        c.close(); check(corrupt.closeCount == 1)
    }
    @Test fun revokeCancelsKdfAtQuiescenceAndClosingSaveIsNarrow() {
        val store = FaultStore(); val owner = ResourceOwner(store)
        val normal = owner.accept(); var denied = false
        try { owner.revoke(normal) } catch(e: StoreException) { denied = e.code == IoCode.AccessLost }
        check(denied && !owner.accessRevoked)
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        var failure: CryptoFailure? = null; var calls = 0
        val cancel = CryptoCancellation { owner.accessRevoked }
        val argon = ArgonDerivation { _,_ -> calls++; entered.countDown(); finish.await(); ByteArray(32) }
        val thread = Thread {
            try {
                KdfWorker.run(cancel) { submit ->
                    submit.derive(byteArrayOf(1),F.prepared(1).use { it.envelope },argon).fill(0)
                    submit.derive(byteArrayOf(1),F.prepared(1).use { it.envelope },argon).fill(0)
                    AutoCloseable {}
                }
            } catch(e: CryptoException) { failure=e.failure }
        }.apply { isDaemon=true }
        thread.start(); check(entered.await(10,TimeUnit.SECONDS)); owner.revoke()
        var busy = false
        try { KdfWorker.run(CryptoCancellation()) { AutoCloseable {} } } catch(e: CryptoException) { busy=e.failure == CryptoFailure.Busy }
        check(busy && calls == 1 && thread.isAlive)
        finish.countDown(); thread.join(10000); check(!thread.isAlive && calls == 1 && failure == CryptoFailure.Cancelled)
        owner.close()
    }
    @Test fun recent256AndLiveTokenEviction() {
        val store = FaultStore(FaultMedium(F.image(),64*1024*1024)); val core = TransactionCore(ResourceOwner(store)); core.attach(F.key(1))
        val opIds = ArrayList<com.fantadrom.copperscroll.storage.format.Bytes>(); var first: OperationToken? = null; var last: OperationToken? = null
        for(i in 0..256) {
            val request = SettingsMutation(i+1L,i.toLong(),i+1L,SettingsData(sortMode=2+(i%2).toLong()))
            val token = core.issueMutation(request); opIds.add(token.opId); if(first == null) first = token; last = token
            check(core.submit(token).code == WriteCode.Committed)
        }
        check(core.submit(first!!).code == WriteCode.UnknownOperation && core.appliedHint(first.opId) == null)
        check(core.submit(last!!).code == WriteCode.Committed)
        F.key(1).use { key ->
            val root = ContainerReader(store).openZone(core.currentSnapshot(),key).root!!
            check(root.sequence == 258L && root.recentOperations.map { it.first } == opIds.takeLast(256))
        }
        val stale = core.issueMutation(SettingsMutation(1,257,258,SettingsData()))
        check(core.submit(stale).code == WriteCode.Conflict)
        core.close()
    }
    @Test fun staleAddAndReleaseWaitsForActualClose() {
        val (_,core) = F.core(F.image()); val add = F.preparedAdd(core)
        check(core.submit(core.issueMutation(F.mutation(core))).code == WriteCode.Committed)
        check(core.submit(add).code == WriteCode.Conflict && core.currentSnapshot().header.zones.size == 1)
        core.close()
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val store = FaultStore()
        val owner = ResourceOwner(store); var closed = 0
        owner.adopt(AutoCloseable { closed++; throw StoreException(IoCode.Eio) })
        owner.adopt(AutoCloseable { entered.countDown(); finish.await() })
        var outcome: Throwable? = null
        val thread = Thread { try { owner.release() } catch(e: Throwable) { outcome=e } }.apply { isDaemon=true }
        thread.start(); check(entered.await(10,TimeUnit.SECONDS))
        check(owner.phase == OwnerPhase.StopRequested && !owner.release() && store.closeCount == 0)
        var busy = false
        try { ResourceOwner(FaultStore(store.medium,store.identity)) } catch(e: StoreException) { busy=e.code == IoCode.Busy }
        check(busy); finish.countDown(); thread.join(10000)
        check(!thread.isAlive && closed == 1 && outcome is StoreException && store.closeCount == 1 && owner.phase == OwnerPhase.Released)
    }
    @Test fun preparedKeyEvictionClosesAndDetachesOwnership() {
        val (_,core) = F.core(F.image())
        val first = F.preparedAdd(core)
        val field = first.request.javaClass.getDeclaredField("prepared").apply { isAccessible=true }
        val prepared = field.get(first.request) as com.fantadrom.copperscroll.storage.crypto.PreparedZone
        repeat(256) { F.preparedAdd(core) }
        var closed = false
        try { prepared.key.ensureOpen() } catch(e: com.fantadrom.copperscroll.storage.crypto.CryptoException) {
            closed = e.failure == com.fantadrom.copperscroll.storage.crypto.CryptoFailure.Closed
        }
        check(closed && core.submit(first).code == WriteCode.UnknownOperation)
        val resources = ResourceOwner::class.java.getDeclaredField("resources").apply { isAccessible=true }
        check((resources.get(core.owner) as List<*>).size == 257) // one active key + bounded 256 prepared requests
        core.close()
    }
}
