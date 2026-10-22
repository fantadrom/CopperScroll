package com.fantadrom.copperscroll.p6aharness

import java.util.Collections
import java.util.IdentityHashMap

/** Test-harness diagnostics only. Never serialize throwable messages, URIs or content hashes. */
internal class QualificationDiagnostics {
    enum class Stage { PREPARE, RECOVER, OPEN_ADAPTER, VERIFY_MODE, VERIFY_SIZE, READ_BYTES,
        VERIFY_HASH, CLOSE_ADAPTER, OPEN_IDENTITY, VERIFY_IDENTITY, CLOSE_IDENTITY,
        VERIFY_LOCAL_STATE, SAVE_VERIFIED, RECEIPT }
    private val values = linkedMapOf<String, String>()
    var stage = Stage.PREPARE
    private fun checked(stage: Stage, ok: Boolean) {
        this.stage = stage
        check(ok) { stage.name } // Fixed code only; original throw site is retained below.
        values["lastVerifiedCheck"] = stage.name
    }
    fun mode(writable: Boolean, grants: Int, durable: Boolean) {
        values["observedWritable"] = writable.toString()
        values["observedGrantCount"] = grants.toString()
        values["observedExistingWriteDurable"] = durable.toString()
        checked(Stage.VERIFY_MODE, !writable && grants == 0 && !durable)
    }
    fun size(actual: Long) {
        values["expectedSize"] = "262144"; values["observedSize"] = actual.toString()
        checked(Stage.VERIFY_SIZE, actual == 262144L)
    }
    fun hash(matches: Boolean) {
        values["contentHashMatches"] = matches.toString()
        checked(Stage.VERIFY_HASH, matches)
    }
    fun identity(expected: String?, actual: String) {
        val a = expected?.split(':'); val b = actual.split(':')
        values["identityDeviceMatches"] = (a?.getOrNull(0) == b.getOrNull(0)).toString()
        values["identityInodeMatches"] = (a?.getOrNull(1) == b.getOrNull(1)).toString()
        values["identityMatches"] = (expected == actual).toString()
        checked(Stage.VERIFY_IDENTITY, expected == actual)
    }
    fun snapshot(): Map<String, String> = values.toMap()
    fun failure(error: Throwable): Map<String, String> = values.toMutableMap().apply {
        put("failureCode", stage.name)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var count = 0
        fun safe(text: String) = if (text.length <= 180 && text.matches(Regex("[A-Za-z0-9_.$<>-]+"))) text else "REDACTED"
        fun frame(f: StackTraceElement) = "${safe(f.className)}.${safe(f.methodName)}(${safe(f.fileName ?: "Unknown")}:${f.lineNumber})"
        fun visit(t: Throwable, relation: String) {
            if (!seen.add(t) || count >= 4) { put("failureTruncated", "true"); return }
            val n = count++
            put("failureType$n", safe(t.javaClass.name))
            put("failureRelation$n", relation)
            put("failureOrigin$n", t.stackTrace.firstOrNull()?.let(::frame) ?: "UNAVAILABLE")
            // Bounded source frames only; Throwable.toString/message can contain private data.
            put("failureFrames$n", t.stackTrace.take(6).joinToString(";", transform = ::frame).take(2000))
            if (t.stackTrace.size > 6) put("failureTruncated", "true")
            t.cause?.let { visit(it, "CAUSE_OF_$n") }
            if (t.suppressed.size > 4) put("failureTruncated", "true")
            t.suppressed.take(4).forEach { visit(it, "SUPPRESSED_BY_$n") }
        }
        visit(error, "PRIMARY")
    }
}
