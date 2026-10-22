package com.fantadrom.copperscroll.p6aharness

/** Samples only: neither equality nor content is a qualified identity/locking contract. */
internal class QualificationIdentity(private val device: Long, private val inode: Long) {
    enum class Comparison(val field: String) {
        CREATED_RW_TO_R("identityCreatedRwToR"), CREATED_RW_TO_RW("identityCreatedRwToRw"),
        RW_TO_R("identityRwToR"), R_TO_R("identityRToR"), RW_TO_RW("identityRwToRw"),
        R_AFTER_RESTART("identityRAfterRestart"), RW_AFTER_RESTART("identityRwAfterRestart")
    }
    // Only the forced app-private checkpoint may contain this value.
    fun privateCheckpointValue() = "$device:$inode"
    override fun toString() = "PRIVATE_FSTAT_SAMPLE"
    fun compare(kind: Comparison, other: QualificationIdentity): Map<String,String> = mapOf(
        "${kind.field}.deviceMatches" to (device == other.device).toString(),
        "${kind.field}.inodeMatches" to (inode == other.inode).toString(),
        "${kind.field}.matches" to (device == other.device && inode == other.inode).toString()
    )
    companion object {
        fun fromCheckpoint(encoded: String?): QualificationIdentity {
            check(encoded != null && encoded.matches(Regex("-?[0-9]{1,19}:-?[0-9]{1,19}"))) { "INVALID_PRIVATE_SAMPLE" }
            val parts=encoded.split(':')
            return QualificationIdentity(parts[0].toLong(),parts[1].toLong()).also {
                check(it.privateCheckpointValue() == encoded) { "NONCANONICAL_PRIVATE_SAMPLE" }
            }
        }
    }
}
