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
    fun beginIdentitySample(mode: String, ordinal: Int) {
        check(mode in setOf("r","rw") && ordinal in 1..2) { "INVALID_SAMPLE_REQUEST" }
        stage=Stage.OPEN_IDENTITY
        values["identitySampleMode"]=mode; values["identitySampleOrdinal"]=ordinal.toString()
    }
    fun identityDescriptor(expectedAccess: Int, actualAccess: Int, regular: Boolean, size: Long) {
        values["identitySampleExpectedAccess"]=expectedAccess.toString(); values["identitySampleObservedAccess"]=actualAccess.toString()
        values["identitySampleRegular"]=regular.toString(); values["identitySampleSize"]=size.toString()
        checked(Stage.VERIFY_IDENTITY, expectedAccess==actualAccess && regular && size==262144L)
    }
    fun identity(kind: QualificationIdentity.Comparison, expected: QualificationIdentity, actual: QualificationIdentity) {
        stage=Stage.VERIFY_IDENTITY
        values.putAll(expected.compare(kind,actual)) // A mismatch is an observation, never an identity fallback.
    }
    fun restartIdentityNotRun() {
        for(kind in listOf(QualificationIdentity.Comparison.R_AFTER_RESTART,QualificationIdentity.Comparison.RW_AFTER_RESTART))
            for(part in listOf("deviceMatches","inodeMatches","matches")) values["${kind.field}.$part"]="NOT_RUN"
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
