package com.fantadrom.copperscroll.storage.android

import com.fantadrom.copperscroll.storage.io.*

/** Access only. External providers require ALL requested URI-grant bits, even if exported. */
internal object SafUriAccess {
    fun require(providerUid: Int, callerUid: Int, flags: Int, explicitGrant: (Int)->Boolean) {
        requireIo(providerUid==callerUid || explicitGrant(flags),IoCode.AccessLost)
    }
}

/** Observations are not durability evidence. Unknown fields never match an approved profile. */
@ConsistentCopyVisibility
data class StorageEnvironment internal constructor(
    val authority: String, val providerPackage: String, val providerVersion: String,
    val providerBuild: String, val androidApi: Int, val androidBuild: String,
    val root: String?, val volume: String?, val backend: String?, val failureModel: String?
)
enum class StorageGuarantee { ExistingWriteDurable, DurableCreateIdentity, DurableLocalState }
enum class EvidenceStatus { Unknown, Observed, ContractAndModel }
class GateDecision internal constructor(val granted: Set<StorageGuarantee>, val reasons: List<String>,
    val environment: StorageEnvironment?, internal val evidenceId: String? = null) {
    fun permits(guarantee: StorageGuarantee) = guarantee in granted
    internal fun require(vararg guarantees: StorageGuarantee) {
        requireIo(guarantees.all(::permits),IoCode.UnsupportedDurability)
    }
    override fun toString() = "GateDecision(granted=$granted,reasons=$reasons)"
}
/** This constructor is module-internal. The compiled registry, not a caller boolean, issues evidence. */
internal class QualifiedContract(val environment: StorageEnvironment, val id: String,
    val guarantees: Set<StorageGuarantee>, val exclusiveCreate: Boolean)
internal object AdmissionMatcher {
    fun decide(actual: StorageEnvironment, contract: QualifiedContract?): GateDecision {
        val complete = listOf(actual.root,actual.volume,actual.backend,actual.failureModel).all { !it.isNullOrBlank() }
        if(!complete) return GateDecision(emptySet(),listOf("BackendIdentityUnknown"),actual)
        if(contract == null) return GateDecision(emptySet(),listOf("ProfileNotQualified"),actual)
        if(contract.environment != actual) return GateDecision(emptySet(),listOf("ProfileEnvironmentChanged"),actual)
        val granted = contract.guarantees.toMutableSet()
        if(!contract.exclusiveCreate) granted.remove(StorageGuarantee.DurableCreateIdentity)
        return GateDecision(granted.toSet(),emptyList(),actual,contract.id)
    }
}
