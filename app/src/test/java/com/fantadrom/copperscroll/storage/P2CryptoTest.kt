package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.P2Checks
import org.junit.Test

class P2CryptoTest {
    private fun checks() = P2Checks(checkNotNull(javaClass.classLoader!!.getResourceAsStream("p2/crypto.properties")))
    @Test fun primitiveKnownAnswers() = checks().primitiveKnownAnswers()
    @Test fun independentFormatFixture() = checks().independentFormatFixture()
    @Test fun independentEnvelopesAndExpectedZone() = checks().independentEnvelopesAndExpectedZone()
    @Test fun ambiguousCredentialAndUnchangedSecret() = checks().ambiguousCredentialAndUnchangedSecret()
    @Test fun kdfLimitsAndCancellation() = checks().kdfLimitsAndCancellation()
    @Test fun recordTamperingAndNoPlaintext() = checks().recordTamperingAndNoPlaintext()
    @Test fun freshRandomnessAndRngFailure() = checks().freshRandomnessAndRngFailure()
    @Test fun prepareNewKeyAndCleanup() = checks().prepareNewKeyAndCleanup()
}
