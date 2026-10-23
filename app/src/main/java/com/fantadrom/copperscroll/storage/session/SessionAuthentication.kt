package com.fantadrom.copperscroll.storage.session

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.CryptoCancellation
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.TransactionCore

class SessionDocument(val store: StoreIdentity,val containerId: Bytes,val instanceId: Bytes) {
    init { require(containerId.size==16 && instanceId.size==16) }
    internal fun matches(other: SessionDocument)=store==other.store && containerId==other.containerId && instanceId==other.instanceId
    override fun toString()="SessionDocument(redacted)"
}
enum class UserZone { Main, Decoy }
/** The only producer is explicit user login + authenticated core attachment + binding checks.
 * A maintenance CurrentCredential is deliberately not accepted by this API. */
class AuthenticatedSession private constructor(val core: TransactionCore,val document: SessionDocument,
    val zoneId: Bytes,val zone: UserZone) {
    internal fun live()=!core.owner.accessRevoked && core.owner.phase!=OwnerPhase.Released && !core.owner.reconciliationRequired
    override fun toString()="AuthenticatedSession(redacted)"
    companion object {
    /** Worker only. Caller owns S1; success transfers owner/key to the session, failure leaves owner
     * for the worker's quiescent cleanup. No passwords or key copies are retained for re-entry. */
    internal fun authenticate(owner: ResourceOwner,local: LocalStateStore,document: SessionDocument,secret: CanonicalSecret): AuthenticatedSession {
        requireIo(owner.store.identity==document.store,IoCode.AccessLost)
        val permit=owner.accept()
        val match=owner.execute(permit) {
            val s=ContainerReader(owner.store).snapshot(); owner.check(permit)
            requireIo(s.header.containerId==document.containerId && s.header.instanceId==document.instanceId,IoCode.AccessLost)
            CredentialManager().login(s.header.containerId,s.header.zones,secret,CryptoCancellation { owner.accessRevoked })
        }
        try {
            val core=TransactionCore.openBound(owner,local,match.key)
            val s=core.currentSnapshot()
            requireIo(s.header.containerId==document.containerId && s.header.instanceId==document.instanceId,IoCode.AccessLost)
            return AuthenticatedSession(core,document,match.key.zoneId,if(match.key.role==1) UserZone.Main else UserZone.Decoy)
        } catch(e: Throwable) { match.close(); throw e }
    }
}
}
object SessionAuthentication {
    fun authenticate(owner: ResourceOwner,local: LocalStateStore,document: SessionDocument,secret: CanonicalSecret): AuthenticatedSession =
        AuthenticatedSession.authenticate(owner,local,document,secret)
}
