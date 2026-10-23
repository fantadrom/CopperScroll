package com.fantadrom.copperscroll.storage.transaction

import com.fantadrom.copperscroll.storage.format.*
import java.util.concurrent.atomic.AtomicBoolean

/** Prepared on a worker before revoke. Only storage implementations can build this bounded,
 * immutable save payload. No UI callback, key, Activity or post-revoke payload is accepted. */
abstract class FinalCheckpoint internal constructor(internal val core: TransactionCore) : AutoCloseable {
    private val offered=AtomicBoolean()
    private val reserved=AtomicBoolean()
    internal fun offer() { com.fantadrom.copperscroll.storage.io.requireIo(offered.compareAndSet(false,true),com.fantadrom.copperscroll.storage.io.IoCode.AccessLost) }
    internal fun reserve() { com.fantadrom.copperscroll.storage.io.requireIo(reserved.compareAndSet(false,true),com.fantadrom.copperscroll.storage.io.IoCode.AccessLost) }
    internal abstract fun build(snapshot: ReadSnapshot, view: ZoneView, revision: Long): ZoneMutation
    override fun toString()="FinalCheckpoint(redacted)"
}
class ClosingSave internal constructor(internal val core: TransactionCore, internal val checkpoint: FinalCheckpoint,
    internal val permit: com.fantadrom.copperscroll.storage.io.WritePermit) {
    internal val consumed=AtomicBoolean()
    override fun toString()="ClosingSave(redacted)"
}
