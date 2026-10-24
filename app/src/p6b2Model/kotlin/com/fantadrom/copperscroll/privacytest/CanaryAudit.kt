package com.fantadrom.copperscroll.privacytest

/** Synthetic canaries only. Never emits matched text, filenames, buffers or user data. */
object CanaryAudit {
    val markers=listOf("P6B2_MAIN_7f9d_Ж🔑","P6B2_DECOY_42ae_Ж🔑","P6B2_SERVICE_1b63_Ж🔑","P6B2_RELEASE_ASCII_93df")
    private val needles=markers.flatMap { marker ->
        listOf(marker.toByteArray(Charsets.UTF_8),marker.toByteArray(Charsets.UTF_16LE),marker.toByteArray(Charsets.UTF_16BE),
            marker.map { "\\u%04x".format(it.code) }.joinToString("").toByteArray(Charsets.US_ASCII),
            marker.map { if(it.code>127) "\\u%04x".format(it.code) else it.toString() }.joinToString("").toByteArray(Charsets.US_ASCII))
    }
    fun contains(bytes: ByteArray): Boolean = needles.any { n ->
        (0..bytes.size-n.size).any { start -> n.indices.all { bytes[start+it]==n[it] } }
    }
    fun scan(root: java.io.File): Int {
        if(!root.exists()) return 0
        val canonical=root.canonicalFile; var count=0
        canonical.walkTopDown().maxDepth(12).forEach { file ->
            check(file.canonicalFile.toPath().startsWith(canonical.toPath())) { "CANARY_SCOPE" }
            if(file.isFile) {
                check(++count<=4096 && file.length()<=16*1024*1024) { "CANARY_BUDGET" }
                check(!contains(file.readBytes())) { "CANARY_PERSISTED" }
            }
        }
        return count
    }
}
