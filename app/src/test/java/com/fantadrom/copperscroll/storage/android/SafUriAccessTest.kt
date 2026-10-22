package com.fantadrom.copperscroll.storage.android

import com.fantadrom.copperscroll.storage.io.*
import org.junit.Assert.*
import org.junit.Test

class SafUriAccessTest {
    private fun denied(block: ()->Unit) {
        try { block(); fail("Access must be refused") }
        catch(e: StoreException) { assertEquals(IoCode.AccessLost,e.code) }
    }
    @Test fun ownUidDoesNotNeedExplicitGrantOrSupplyDurability() {
        SafUriAccess.require(12345,12345,3) { error("Owner must not require an explicit grant") }
        val environment=StorageEnvironment("own","own","1","1",31,"os","root","volume","backend","model")
        assertTrue(AdmissionMatcher.decide(environment,null).granted.isEmpty())
    }
    @Test fun externalUidWithoutGrantIsDeniedForBothModes() {
        for(flags in listOf(1,3)) denied {
            SafUriAccess.require(12346,12345,flags) { required -> assertEquals(flags,required); false }
        }
    }
    @Test fun externalReadOnlyGrantCannotSatisfyReadWriteRequest() {
        val readOnly: (Int)->Boolean = { required -> required and 1==required }
        SafUriAccess.require(12346,12345,1,readOnly)
        denied { SafUriAccess.require(12346,12345,3,readOnly) }
        SafUriAccess.require(12346,12345,3) { it==3 }
    }
}
