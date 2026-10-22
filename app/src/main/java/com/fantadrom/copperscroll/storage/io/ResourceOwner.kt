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
class ResourceOwner(store: RandomAccessStore) : AutoCloseable {
    var store: RandomAccessStore = store; private set
    private val identity = store.identity
    private val monitor = Object()
    private val resources = ArrayList<AutoCloseable>()
    private var lock: AutoCloseable?
    private var storeCloseAttempted = false
    private fun closeLock() { val previous = lock; lock = null; previous?.close() }
    private fun closeStore() { if(!storeCloseAttempted) { storeCloseAttempted = true; store.close() } }
    private var epoch = 1L
    private var active: WritePermit? = null
    private var closing: WritePermit? = null
    private var stopping = false
    private var unresolved = false
    private var maintenanceHeld = false
    private var externalRevoke = false
    internal val revokeRequested get() = synchronized(monitor) { externalRevoke }
    private var switchAccepted = false
    private var revokedAfterSwitch = false
    internal val switchUnreported get() = synchronized(monitor) { switchAccepted && revokedAfterSwitch }
    private var released = false
    private var releasing = false
    @Volatile var accessRevoked = false; private set
    init {
        synchronized(leases) { requireIo(leases.add(store.identity), IoCode.Busy) }
        try { lock = store.acquireExclusive() }
        catch (e: Throwable) { synchronized(leases) { leases.remove(identity) }; throw e }
    }
    val phase: OwnerPhase get() = synchronized(monitor) {
        when { released -> OwnerPhase.Released; releasing || maintenanceHeld && active == null -> OwnerPhase.StopRequested; active == null -> OwnerPhase.Quiescent
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
    internal fun ownedDocument(identity: StoreIdentity): ResourceOwner? = synchronized(monitor) {
        resources.filterIsInstance<ResourceOwner>().singleOrNull { it.store.identity == identity && it.phase != OwnerPhase.Released }
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
        externalRevoke = true
        if(switchAccepted) revokedAfterSwitch = true
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
            requireIo(!released && !releasing && active == null && !maintenanceHeld, IoCode.Busy)
            WritePermit(this, epoch).also { active = it }
        }
        try { return block() }
        finally { synchronized(monitor) { if (active === permit) active = null; monitor.notifyAll() } }
    }
    /** Hold the lease through error publication and cleanup, even after the core body returns. */
    internal fun beginMaintenance(permit: WritePermit) = synchronized(monitor) {
        requireIo(active === permit && !maintenanceHeld && !accessRevoked,IoCode.Busy)
        maintenanceHeld = true
    }
    internal fun stopMaintenanceAdmission() = synchronized(monitor) {
        closing = null; stopping = true
        if(!accessRevoked) { accessRevoked = true; epoch++ }
        monitor.notifyAll()
    }
    internal fun finishMaintenanceIo() = synchronized(monitor) {
        if(maintenanceHeld) { requireIo(active == null,IoCode.Busy); maintenanceHeld = false; monitor.notifyAll() }
    }
    /** The same short boundary as revoke. No IO and no caller-supplied role flag. */
    internal fun acceptSwitch(permit: WritePermit): Boolean = synchronized(monitor) {
        if(released || accessRevoked || active !== permit || unresolved) false
        else { switchAccepted = true; accessRevoked = true; stopping = true; epoch++; true }
    }
    /** Reopen our own document without releasing the process lease or opening a duplicate FD.
     * Only an active operation can do this; a failed reopen must not turn into a verified result. */
    internal fun reopen(permit: WritePermit, factory: () -> RandomAccessStore) {
        check(permit)
        val continuous = lock as? ReopenLease
        if(continuous != null) continuous.beforeReopen() else closeLock()
        closeStore()
        val next = factory()
        try {
            requireIo(next.identity == identity,IoCode.AccessLost)
            if(continuous != null) continuous.afterReopen(next) else lock = next.acquireExclusive()
            store = next; storeCloseAttempted = false
        } catch(e: Throwable) { try { next.close() } catch(clean: Throwable) { e.addSuppressed(clean) }; throw e }
        check(permit)
    }
    fun awaitQuiescent(timeoutMillis: Long): Boolean = synchronized(monitor) {
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtMost(60000))
        while (active != null || maintenanceHeld) {
            val remaining = deadline - System.nanoTime(); if (remaining <= 0) return@synchronized false
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
        }
        true
    }
    /** Explicit release after quiescence AND resolution. A caller timeout is not resolution. */
    fun release(): Boolean {
        synchronized(monitor) {
            stopping = true
            if (active != null || maintenanceHeld || unresolved) return false
            if (released) return true
            if (releasing) return false
            releasing = true; accessRevoked = true
        }
        var failure: Throwable? = null
        fun failed(e: Throwable) { if(failure == null) failure = e else failure!!.addSuppressed(e) }
        try {
            var i = resources.size - 1
            while(i >= 0) {
                try { resources[i].close() } catch(e: Throwable) { failed(e) }
                i--
            }
            try { closeLock() } catch(e: Throwable) { failed(e) }
            try { closeStore() } catch(e: Throwable) { failed(e) }
        } finally {
            synchronized(leases) { leases.remove(identity) }
            synchronized(monitor) { released = true; releasing = false; monitor.notifyAll() }
        }
        failure?.let { throw it }
        return true
    }
    override fun close() { revoke(); release() }
    companion object { private val leases = HashSet<StoreIdentity>() }
}
