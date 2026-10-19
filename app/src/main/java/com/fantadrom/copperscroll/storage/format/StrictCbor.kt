package com.fantadrom.copperscroll.storage.format

import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.CborEncoder
import co.nstant.`in`.cbor.model.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Bounded preflight runs before the library allocates its object tree. Only the v1 subset is admitted. */
internal object StrictCbor {
    private const val MAX_NODES = 1100010
    private const val ALLOCATION_BUDGET = 128L * 1024 * 1024
    fun decode(bytes: ByteArray, maxBytes: Int = Limits.PAGE): Any? {
        checkFormat(bytes.size <= maxBytes, Failure.UnsupportedLimit)
        val scan = Scan(bytes)
        scan.item(0)
        checkFormat(scan.pos == bytes.size)
        return try { from(CborDecoder.decode(bytes).single()) }
        catch (e: FormatException) { throw e }
        catch (_: Exception) { throw FormatException(Failure.CorruptData) }
    }
    fun encode(value: Any?, maxBytes: Int = Limits.PAGE): ByteArray {
        measure(value, maxBytes)
        val out = ByteArrayOutputStream()
        try { CborEncoder(out).encode(to(value)) }
        catch (e: FormatException) { throw e }
        catch (_: Exception) { throw FormatException(Failure.ValidationError) }
        val bytes = out.toByteArray()
        decode(bytes, maxBytes)
        return bytes
    }
    // Bound trusted-model encoding too, before cloning byte strings/building the library tree.
    private fun measure(root: Any?, maxBytes: Int) {
        var bytes = 0L; var nodes = 0
        fun visit(x: Any?, depth: Int) {
            checkFormat(depth <= 16 && ++nodes <= MAX_NODES, Failure.ResourceLimit)
            bytes += when(x) {
                null, is Boolean -> 1
                is Int -> integerSize(if(x >= 0) x.toULong() else (-1L-x).toULong())
                is Long -> integerSize(if(x >= 0) x.toULong() else (-1L-x).toULong())
                is ULong -> integerSize(x)
                is Bytes -> x.size.toLong() + integerSize(x.size.toULong())
                is String -> {
                    checkFormat(x.length <= maxBytes, Failure.UnsupportedLimit)
                    val n = utf8(x).size
                    n.toLong() + integerSize(n.toULong())
                }
                is List<*> -> { checkFormat(x.size <= 100000, Failure.UnsupportedLimit); integerSize(x.size.toULong()) }
                else -> throw FormatException(Failure.ValidationError)
            }
            checkFormat(bytes <= maxBytes, Failure.UnsupportedLimit)
            if(x is List<*>) x.forEach { visit(it, depth+1) }
        }
        visit(root,0)
    }
    private fun integerSize(n: ULong): Long = when {
        n < 24uL -> 1; n <= 255uL -> 2; n <= 65535uL -> 3; n <= 0xffffffffuL -> 5; else -> 9
    }
    private fun to(x: Any?): DataItem = when (x) {
        null -> SimpleValue.NULL
        is Boolean -> if (x) SimpleValue.TRUE else SimpleValue.FALSE
        is Bytes -> ByteString(x.copy())
        is String -> { utf8(x); UnicodeString(x) }
        is Int -> to(x.toLong())
        is Long -> if (x >= 0) UnsignedInteger(x) else NegativeInteger(x)
        is ULong -> UnsignedInteger(BigInteger(x.toString()))
        is List<*> -> co.nstant.`in`.cbor.model.Array().apply { x.forEach { add(StrictCbor.to(it)) } }
        else -> throw FormatException(Failure.ValidationError)
    }
    private fun from(x: DataItem): Any? = when (x) {
        is UnsignedInteger -> x.value.toString().toULong()
        is NegativeInteger -> { checkFormat(x.value >= BigInteger.valueOf(Long.MIN_VALUE)); x.value.toLong() }
        is ByteString -> Bytes(x.bytes)
        is UnicodeString -> x.string
        is co.nstant.`in`.cbor.model.Array -> x.dataItems.map { from(it) }
        SimpleValue.NULL -> null
        SimpleValue.TRUE -> true
        SimpleValue.FALSE -> false
        else -> throw FormatException(Failure.CorruptData)
    }
    private class Scan(val b: ByteArray) {
        var pos = 0
        var nodes = 0
        var allocation = 0L
        fun take(): Int { checkFormat(pos < b.size); return b[pos++].toInt() and 255 }
        fun item(depth: Int) {
            checkFormat(depth <= 16)
            checkFormat(++nodes <= MAX_NODES, Failure.ResourceLimit)
            allocation += 96
            checkFormat(allocation <= ALLOCATION_BUDGET, Failure.ResourceLimit)
            val head = take(); val major = head ushr 5; val ai = head and 31
            if (major == 7) { checkFormat(ai in 20..22); return }
            checkFormat(major in 0..4 && ai <= 27)
            val count = when {
                ai < 24 -> ai.toULong()
                else -> {
                    val width = 1 shl (ai - 24)
                    var n = 0uL
                    repeat(width) { n = (n shl 8) or take().toULong() }
                    val minimum = when (width) { 1 -> 24uL; 2 -> 256uL; 4 -> 65536uL; else -> 4294967296uL }
                    checkFormat(n >= minimum); n
                }
            }
            when (major) {
                0 -> Unit
                1 -> checkFormat(count <= Long.MAX_VALUE.toULong())
                2, 3 -> {
                    checkFormat(count <= (b.size - pos).toULong())
                    val n = count.toInt()
                    allocation += n.toLong() * if (major == 3) 6 else 3
                    checkFormat(allocation <= ALLOCATION_BUDGET, Failure.ResourceLimit)
                    if (major == 3) decodeUtf8(b, pos, n)
                    pos += n
                }
                4 -> {
                    checkFormat(count <= 100000uL, Failure.UnsupportedLimit)
                    checkFormat(count <= (b.size - pos).toULong())
                    repeat(count.toInt()) { item(depth + 1) }
                }
            }
        }
    }
}
internal fun Any?.arr(n: Int? = null): List<Any?> {
    checkFormat(this is List<*> && (n == null || size == n))
    @Suppress("UNCHECKED_CAST") return this as List<Any?>
}
internal fun Any?.uint(max: ULong = ULong.MAX_VALUE): ULong {
    checkFormat(this is ULong && this <= max); return this as ULong
}
internal fun Any?.number(max: Long = Long.MAX_VALUE): Long = uint(max.toULong()).toLong()
internal fun Any?.signed(): Long = if (this is Long) this else number()
internal fun Any?.bin(n: Int): Bytes { checkFormat(this is Bytes); return (this as Bytes).sized(n) }
internal fun Any?.ident(n: Int = 16): Bytes = bin(n).id(n)
internal fun Any?.text(max: Int): String { checkFormat(this is String && utf8(this).size <= max); return this as String }
internal fun Any?.bool(): Boolean { checkFormat(this is Boolean); return this as Boolean }
internal fun Any?.version(): Unit { checkFormat(this == 1uL, Failure.UnsupportedVersion) }
internal fun ordered(keys: List<Bytes>) { checkFormat(keys.zipWithNext().all { (a,b) -> a < b }) }
