package com.fantadrom.copperscroll.storage.transaction

import com.fantadrom.copperscroll.storage.format.*

/** Internal extension point: P4 supplies immutable intent, P3 alone owns publication/ACK. */
internal interface ZoneMutation : AutoCloseable {
    val expectedZoneSequence: Long
    val expectedRevision: Long
    val revision: Long
    val resultObjectId: Bytes?
    val checkpoint: Boolean
    val retainedBytes: Long get() = 0
    fun binding(): ByteArray
    fun apply(context: ZoneWriteContext): ZoneEdit
    override fun close() {}
}
internal class ZoneEdit(val root: RootData, val noOp: Boolean = false, val overflow: List<Bytes> = emptyList(), val noObject: Boolean = false)
internal class MutationRefusal(val code: WriteCode) : Exception(code.name)
internal class ZoneWriteContext(val snapshot: ReadSnapshot, val view: ZoneView,
    private val readBlock: (LogicalRef) -> ByteArray,
    private val appendBlock: (Int,Bytes,Long,ByteArray) -> LogicalRef,
    private val accessCheck: () -> Unit) {
    val zone get() = view.descriptor.zoneId
    fun check() = accessCheck()
    fun read(ref: LogicalRef): ByteArray { check(); return readBlock(ref) }
    fun record(kind: Int, owner: Bytes, ordinal: Long, bytes: ByteArray): LogicalRef {
        check(); return appendBlock(kind,owner,ordinal,bytes)
    }
}
