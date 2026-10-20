package com.fantadrom.copperscroll.storage.io

import java.util.concurrent.atomic.AtomicBoolean

enum class IoAction { Read, Write, Force, Identity, Truncate }
enum class FaultMoment { Before, Partial, After }
class IoEvent(val index: Int, val action: IoAction, val offset: Long, val length: Int)
class IoFault(val event: Int, val moment: FaultMoment, val prefix: Int = 0,
              val code: IoCode = IoCode.Eio)

/** Deterministic bounded synthetic medium: completed forces persist bytes; unforced writes may
 * disappear, persist in full, or persist as a prefix/ranges. Previously forced untouched ranges
 * survive. This is a model assumption, not a claim about physical storage.
 */
class FaultMedium(initial: ByteArray = ByteArray(0), val limit: Int = 8 * 1024 * 1024) {
    internal var volatile = initial.clone()
    internal var durable = initial.clone()
    internal val locked = AtomicBoolean()
    fun bytes() = volatile.clone()
    fun durableBytes() = durable.clone()
    fun crash(retainUnforced: List<IntRange> = emptyList()) {
        var result = durable.clone()
        for (range in retainUnforced) {
            require(range.first >= 0 && range.last < volatile.size)
            if (result.size <= range.last) result = result.copyOf(range.last + 1)
            volatile.copyInto(result, range.first, range.first, range.last + 1)
        }
        volatile = result; durable = result.clone()
    }
    fun corrupt(offset: Int, value: Byte) { volatile[offset] = value; durable = volatile.clone() }
}
class FaultStore(val medium: FaultMedium = FaultMedium(),
                 override val identity: StoreIdentity = StoreIdentity("fault", java.util.UUID.randomUUID().toString()),
                 override val guarantees: StoreGuarantees = StoreGuarantees(true, true),
                 private val publishIdentity: () -> Unit = {}) : RandomAccessStore {
    var fault: IoFault? = null
    var observer: (IoEvent) -> Unit = {}
    val events = ArrayList<IoEvent>()
    var closeCount = 0; private set
    private var closed = false
    fun resetTrace() { events.clear(); fault = null }
    override val size: Long get() { live(); return medium.volatile.size.toLong() }
    private fun live() { requireIo(!closed, IoCode.Closed) }
    private fun event(action: IoAction, offset: Long, length: Int): IoEvent {
        live(); val event = IoEvent(events.size, action, offset, length); events.add(event); observer(event)
        if (fault?.event == event.index && fault?.moment == FaultMoment.Before) throw StoreException(fault!!.code)
        return event
    }
    private fun partial(event: IoEvent): Int? = fault?.takeIf {
        it.event == event.index && it.moment == FaultMoment.Partial
    }?.prefix?.coerceIn(0, event.length)
    private fun after(event: IoEvent) {
        if (fault?.event == event.index && fault?.moment != FaultMoment.Before) throw StoreException(fault!!.code)
    }
    override fun readFullyAt(offset: Long, length: Int): ByteArray {
        val end = endAt(offset, length.toLong()); requireIo(end <= size, IoCode.Eio)
        val e = event(IoAction.Read, offset, length); after(e)
        return medium.volatile.copyOfRange(offset.toInt(), end.toInt())
    }
    override fun writeFullyAt(offset: Long, bytes: ByteArray) {
        val end = endAt(offset, bytes.size.toLong()); requireIo(end <= medium.limit, IoCode.NoSpace)
        val e = event(IoAction.Write, offset, bytes.size); val n = partial(e) ?: bytes.size
        val actualEnd = offset.toInt() + n
        if (actualEnd > medium.volatile.size) medium.volatile = medium.volatile.copyOf(actualEnd)
        bytes.copyInto(medium.volatile, offset.toInt(), 0, n); after(e)
    }
    override fun force() {
        val e = event(IoAction.Force, 0, medium.volatile.size)
        val prefix = partial(e)
        if (prefix == null) medium.durable = medium.volatile.clone()
        else {
            if (prefix > medium.durable.size) medium.durable = medium.durable.copyOf(prefix)
            medium.volatile.copyInto(medium.durable, 0, 0, prefix)
        }
        after(e)
    }
    override fun forceIdentity() {
        requireIo(guarantees.durableCreateIdentity, IoCode.UnsupportedDurability)
        val e = event(IoAction.Identity, 0, 0); publishIdentity(); after(e)
    }
    override fun truncate(length: Long): Boolean {
        endAt(length, 0); requireIo(length <= size)
        val e = event(IoAction.Truncate, length, 0); medium.volatile = medium.volatile.copyOf(length.toInt()); after(e); return true
    }
    override fun acquireExclusive(): AutoCloseable {
        live(); requireIo(medium.locked.compareAndSet(false, true), IoCode.Busy)
        return AutoCloseable { medium.locked.set(false) }
    }
    override fun close() { if (!closed) { closeCount++; closed = true } }
}

/** Namespace is independent of file force. Rename/document binding is volatile until namespace sync. */
class FaultNamespace(private val durableCreateSupported: Boolean = true) {
    private var visible = HashMap<String, Pair<StoreIdentity, FaultMedium>>()
    private var durable = HashMap<String, Pair<StoreIdentity, FaultMedium>>()
    fun create(name: String): FaultStore {
        requireIo(durableCreateSupported, IoCode.UnsupportedDurability)
        requireIo(name !in visible)
        val identity = StoreIdentity("fault-namespace", java.util.UUID.randomUUID().toString())
        val medium = FaultMedium(); visible[name] = identity to medium
        return FaultStore(medium, identity, StoreGuarantees(true, true)) { sync() }
    }
    fun sync() { durable = HashMap(visible) }
    fun crash() { visible.values.forEach { it.second.crash() }; visible = HashMap(durable) }
    fun open(name: String): FaultStore {
        val (identity, medium) = visible[name] ?: throw StoreException(IoCode.AccessLost)
        return FaultStore(medium, identity, StoreGuarantees(true, durableCreateSupported)) { sync() }
    }
    fun exists(name: String) = name in visible
}
