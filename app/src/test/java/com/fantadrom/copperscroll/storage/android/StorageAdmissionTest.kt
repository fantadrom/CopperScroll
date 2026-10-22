package com.fantadrom.copperscroll.storage.android

import com.fantadrom.copperscroll.storage.io.*
import org.junit.Assert.*
import org.junit.Test

class StorageAdmissionTest {
    private val env=StorageEnvironment("authority","package","17","build",35,"os","root","volume","backend","model")
    private val contract=QualifiedContract(env,"bounded-fixture",StorageGuarantee.entries.toSet(),true)
    @Test fun observationsNeverApproveUnknownProvider() {
        assertTrue(AdmissionMatcher.decide(env,null).granted.isEmpty())
        for(e in listOf(env.copy(root=null),env.copy(volume=null),env.copy(backend=null),env.copy(failureModel=null)))
            assertTrue(AdmissionMatcher.decide(e,contract).granted.isEmpty())
    }
    @Test fun exactProfileInvalidatedByEveryEnvironmentDimension() {
        assertEquals(3,AdmissionMatcher.decide(env,contract).granted.size)
        for(e in listOf(env.copy(authority="other"),env.copy(providerPackage="other"),env.copy(providerVersion="18"),
            env.copy(providerBuild="update"),env.copy(androidApi=37),env.copy(androidBuild="update"),env.copy(root="other"),
            env.copy(volume="other"),env.copy(backend="other"),env.copy(failureModel="other")))
            assertTrue(AdmissionMatcher.decide(e,contract).granted.isEmpty())
    }
    @Test fun allThreeGuaranteesIndependentAndCreateRequiresExclusiveContract() {
        for(g in StorageGuarantee.entries) {
            val gate=AdmissionMatcher.decide(env,QualifiedContract(env,"single",setOf(g),true))
            assertEquals(setOf(g),gate.granted)
        }
        val noCreate=AdmissionMatcher.decide(env,QualifiedContract(env,"no-exclusive",StorageGuarantee.entries.toSet(),false))
        assertFalse(noCreate.permits(StorageGuarantee.DurableCreateIdentity)); assertTrue(noCreate.permits(StorageGuarantee.ExistingWriteDurable))
    }
    @Test fun positionalTransferHandlesShortProgressAndAdvancesOffset() {
        val bytes=ByteArray(101); val positions=ArrayList<Long>()
        AndroidIo.transfer(bytes,13) { s,n,p -> assertEquals(13L+s,p); positions.add(p); minOf(7,n) }
        assertEquals(15,positions.size)
    }
    @Test fun positionalTransferRejectsZeroEofOversizedAndOverflow() {
        for(n in listOf(0,-1,12)) reject { AndroidIo.transfer(ByteArray(11),0) { _,_,_ -> n } }
        reject { AndroidIo.transfer(ByteArray(1),Long.MAX_VALUE) { _,_,_ -> 1 } }
    }
    private fun reject(block: ()->Unit) { try { block() } catch(_: StoreException) { return }; error("Expected bounded rejection") }
}
