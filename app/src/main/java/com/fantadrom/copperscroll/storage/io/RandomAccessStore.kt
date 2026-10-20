package com.fantadrom.copperscroll.storage.io

import com.fantadrom.copperscroll.storage.format.Limits
import com.fantadrom.copperscroll.storage.format.ReadOnlySource
import java.io.IOException

enum class IoCode { Eio, NoSpace, AccessLost, Closed, UnsupportedDurability, Busy, InvalidRange }
class StoreException(val code: IoCode) : IOException(code.name)
fun requireIo(ok: Boolean, code: IoCode = IoCode.InvalidRange) { if (!ok) throw StoreException(code) }
fun endAt(offset: Long, length: Long): Long {
    requireIo(offset >= 0 && length >= 0 && offset <= Limits.FILE_MAX && length <= Limits.FILE_MAX - offset)
    return offset + length
}
fun aligned(offset: Long): Long { endAt(offset, 7); return (offset + 7) and -8L }

/** Identity is a namespace/document identity, NOT the portable containerId or its display name. */
class StoreIdentity(val namespace: String, val document: String) {
    init { require(namespace.length in 1..4096 && document.length in 1..4096) }
    override fun equals(other: Any?) = other is StoreIdentity && namespace == other.namespace && document == other.document
    override fun hashCode() = 31 * namespace.hashCode() + document.hashCode()
    override fun toString() = "StoreIdentity(redacted)"
}
class StoreGuarantees(val existingWriteDurable: Boolean, val durableCreateIdentity: Boolean)

/** Caller must serialize append/write/force. Full IO either completes or throws; partial side effects
 * are possible on error. force() orders all prior writes in the DECLARED storage model only.
 * A data force does not publish a namespace identity. Only the ResourceOwner closes this handle.
 */
interface RandomAccessStore : ReadOnlySource, AutoCloseable {
    val identity: StoreIdentity
    val guarantees: StoreGuarantees
    fun readFullyAt(offset: Long, length: Int): ByteArray
    override fun read(offset: Long, length: Int) = readFullyAt(offset, length)
    fun writeFullyAt(offset: Long, bytes: ByteArray)
    fun appendAligned(bytes: ByteArray): Long {
        val old = size; val start = aligned(old); endAt(start, bytes.size.toLong())
        if (start > old) writeFullyAt(old, ByteArray((start - old).toInt()))
        writeFullyAt(start, bytes); return start
    }
    fun force()
    fun forceIdentity() { throw StoreException(IoCode.UnsupportedDurability) }
    fun truncate(length: Long): Boolean = false
    fun acquireExclusive(): AutoCloseable
}
