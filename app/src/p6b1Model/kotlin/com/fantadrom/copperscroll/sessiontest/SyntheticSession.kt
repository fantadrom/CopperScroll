package com.fantadrom.copperscroll.sessiontest

import com.fantadrom.copperscroll.storage.access.*
import com.fantadrom.copperscroll.storage.crypto.KdfParameters
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.maintenance.fingerprint
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.session.*

/** Opt-in target/JVM only. Explicit synthetic credentials, real envelope/login/binding/graph path.
 * No role or key injection, files, production allowlist or durability claim. */
class SyntheticSession {
    companion object {
        const val MAIN="P6b1 synthetic main"
        const val DECOY="P6b1 synthetic decoy"
        private val image: ByteArray by lazy {
            val store=FaultStore(); val core=TransactionCore(ResourceOwner(store))
            val container=Bytes(java.util.UUID.randomUUID().let { java.nio.ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array() })
            val zone=Bytes(ByteArray(16) { if(it==15) 1 else 0 })
            SecretEncoding.text(MAIN).use { s ->
                val prepared=CredentialManager().create(container,zone,1,3,s,KdfParameters(16384,2))
                check(core.submit(core.issueCreate(prepared,containerMinor=2)).code==WriteCode.Committed)
                SecretEncoding.text(DECOY).use { d -> check(core.submit(core.issueAdd(s,d,d,3,KdfParameters(16384,2))).code==WriteCode.Committed) }
            }
            store.medium.durableBytes().also { core.close() }
        }
    }
    val medium=FaultMedium(image,16*1024*1024)
    val identity=StoreIdentity("p6b1-synthetic",java.util.UUID.randomUUID().toString())
    val document: SessionDocument
    init { val store=FaultStore(medium,identity); val h=ContainerReader(store).snapshot().header; document=SessionDocument(identity,h.containerId,h.instanceId); store.close() }
    var lastStore: FaultStore?=null; private set
    fun owner(): ResourceOwner {
        val store=FaultStore(medium,identity)
        try { return ResourceOwner(store).also { lastStore=store } }
        catch(e: Throwable) { try { store.close() } catch(clean: Throwable) { e.addSuppressed(clean) }; throw e }
    }
    fun local(store: RandomAccessStore)=FaultLocalStateStore(LocalState(fingerprint(store,"synthetic:p6b1"),null))
    fun login(c: SessionCoordinator,decoy: Boolean=false): AuthenticatedSession {
        val attempt=c.beginUnlock(); check(c.submitUnlock(attempt))
        var owned: ResourceOwner?=null
        try {
            val owner=owner(); owned=owner
            check(c.claimUnlock(attempt,owner))
            val result=SecretEncoding.text(if(decoy) DECOY else MAIN).use { SessionAuthentication.authenticate(owner,local(owner.store),document,it) }
            check(c.completeUnlock(attempt,result)); return result
        } catch(e: Throwable) {
            c.rejectUnlock(attempt); owned?.let { it.revoke(); try { it.release() } catch(clean: Throwable) { e.addSuppressed(clean) } }; throw e
        } finally { owned?.let { c.unlockFinished(it) }; c.unlockWorkFinished(attempt) }
    }
}
