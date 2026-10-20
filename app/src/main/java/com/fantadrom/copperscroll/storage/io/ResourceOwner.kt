package com.fantadrom.copperscroll.storage.io

import java.util.concurrent.TimeUnit

enum class OwnerPhase { Active, StopRequested, Quiescent, Released }
class WritePermit internal constructor(internal val owner: ResourceOwner, internal val epoch: Long,
                                       internal val checkpoint: Boolean = false) {
    internal var used = false
}

/** Access revocation and IO lifetime are independent. No monitor is held during syscalls. A timeout
 * only reports waiting; it NEVER closes a descriptor or releases its advisory/process lease.
 */
class ResourceOwner(val store: RandomAccessStore) : AutoCloseable {
    private val monitor = Object()
    private val resources = ArrayList<AutoCloseable>()
    private val lock: AutoCloseable
    private var epoch = 1L
    private var active: WritePermit? = null
    private var closing: WritePermit? = null
    private var stopping = false
    private var unresolved = false
    private var released = false
    private var releasing = false
    @Volatile var accessRevoked = false; private set
    init {
        synchronized(leases) { requireIo(leases.add(store.identity), IoCode.Busy) }
        try { lock = store.acquireExclusive() }
        catch (e: Throwable) { synchronized(leases) { leases.remove(store.identity) }; throw e }
    }
    val phase: OwnerPhase get() = synchronized(monitor) {
        when { released -> OwnerPhase.Released; releasing -> OwnerPhase.StopRequested; active == null -> OwnerPhase.Quiescent
            stopping -> OwnerPhase.StopRequested; else -> OwnerPhase.Active }
    }
    val reconciliationRequired get() = synchronized(monitor) { unresolved }
    fun accept(): WritePermit = acceptPermit(false)
    internal fun acceptCheckpoint(): WritePermit = acceptPermit(true)
    private fun acceptPermit(checkpoint: Boolean): WritePermit = synchronized(monitor) {
        requireIo(!released && !stopping && !accessRevoked, IoCode.AccessLost)
        requireIo(!unresolved, IoCode.Busy); WritePermit(this, epoch, checkpoint)
    }
    fun adopt(resource: AutoCloseable) = synchronized(monitor) {
        requireIo(!released && !releasing, IoCode.Closed); resources.add(resource)
    }
    /** Used by the serialized core after the resource's last IO use (or token eviction). */
    internal fun retire(resource: AutoCloseable) {
        val removed = synchronized(monitor) { if(releasing || released) false else resources.remove(resource) }
        if(removed) resource.close()
    }
    /** closingSave MUST have been accepted before this boundary. Late callbacks cannot mint it. */
    fun revoke(closingSave: WritePermit? = null) = synchronized(monitor) {
        if (closingSave != null) requireIo(closingSave.checkpoint && closingSave.owner === this && closingSave.epoch == epoch &&
            (active === closingSave || !closingSave.used), IoCode.AccessLost)
        closing = closingSave; accessRevoked = true; epoch++; stopping = true
        monitor.notifyAll()
    }
    fun check(permit: WritePermit) = synchronized(monitor) {
        requireIo(!released && active === permit && (!accessRevoked || closing === permit), IoCode.AccessLost)
    }
    /** Only the one preaccepted, not-yet-started checkpoint may enter after revoke.
     * A completed permit cannot publish a cached user result through this capability.
     */
    internal fun admitsClosingSave(permit: WritePermit): Boolean = synchronized(monitor) {
        accessRevoked && !released && !releasing && closing === permit && !permit.used
    }
    internal fun markUnknown() = synchronized(monitor) { unresolved = true }
    internal fun resolved() = synchronized(monitor) { unresolved = false }
    fun <T> execute(permit: WritePermit, block: () -> T): T {
        synchronized(monitor) {
            requireIo(permit.owner === this && !permit.used && !released &&
                ((!accessRevoked && permit.epoch == epoch) || closing === permit), IoCode.AccessLost)
            requireIo(active == null && !unresolved, IoCode.Busy)
            permit.used = true; active = permit
        }
        try { return block() }
        finally { synchronized(monitor) { active = null; monitor.notifyAll() } }
    }
    /** Internal read/force reconciliation is allowed after revoke. Its result never opens UI access. */
    fun <T> reconcile(block: () -> T): T {
        val permit = synchronized(monitor) {
            requireIo(!released && !releasing && active == null, IoCode.Busy)
            WritePermit(this, epoch).also { active = it }
        }
        try { return block() }
        finally { synchronized(monitor) { if (active === permit) active = null; monitor.notifyAll() } }
    }
    fun awaitQuiescent(timeoutMillis: Long): Boolean = synchronized(monitor) {
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtMost(60000))
        while (active != null) {
            val remaining = deadline - System.nanoTime(); if (remaining <= 0) return@synchronized false
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
        }
        true
    }
    /** Explicit release after quiescence AND resolution. A caller timeout is not resolution. */
    fun release(): Boolean {
        synchronized(monitor) {
            stopping = true
            if (active != null || unresolved) return false
            if (released) return true
            if (releasing) return false
            releasing = true; accessRevoked = true
        }
        var failed = false
        try {
            var i = resources.size - 1
            while(i >= 0) {
                try { resources[i].close() } catch(_: Throwable) { failed = true }
                i--
            }
            try { lock.close() } catch(_: Throwable) { failed = true }
            try { store.close() } catch(_: Throwable) { failed = true }
        } finally {
            synchronized(leases) { leases.remove(store.identity) }
            synchronized(monitor) { released = true; releasing = false; monitor.notifyAll() }
        }
        if(failed) throw StoreException(IoCode.Eio)
        return true
    }
    override fun close() { revoke(); release() }
    companion object { private val leases = HashSet<StoreIdentity>() }
}
