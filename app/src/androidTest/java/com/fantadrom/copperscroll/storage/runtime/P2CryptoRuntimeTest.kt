package com.fantadrom.copperscroll.storage.runtime

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.storage.access.CredentialManager
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.runtimechecks.P2Checks
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2CryptoRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun requireProductionTarget() {
        val variant=InstrumentationRegistry.getArguments().getString("p1ExpectedVariant")
        check(variant=="debug" || variant=="p1Release")
        val target=instrumentation.targetContext
        check(target.packageName=="com.fantadrom.copperscroll"+if(variant=="p1Release") ".p1runtime" else "")
        check(instrumentation.context.packageName==target.packageName+".test")
        if(variant=="p1Release") {
            check(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE==0)
            check(target.assets.open("p1-runtime-target.txt").bufferedReader().use { it.readText().trim() }=="p1Release")
        }
        for(type in listOf(CredentialManager::class.java,ZoneKey::class.java,RecordCrypto::class.java,
            Argon2BytesGenerator::class.java,HKDFBytesGenerator::class.java,HMac::class.java))
            check(type===target.classLoader.loadClass(type.name)) { "Crypto backend does not resolve through target loader" }
    }
    private fun checks() = P2Checks(instrumentation.context.assets.open("p2/crypto.properties"))
    @Test fun primitiveKnownAnswers() = checks().primitiveKnownAnswers()
    @Test fun independentFormatFixture() = checks().independentFormatFixture()
    @Test fun independentEnvelopesAndExpectedZone() = checks().independentEnvelopesAndExpectedZone()
    @Test fun ambiguousCredentialAndUnchangedSecret() = checks().ambiguousCredentialAndUnchangedSecret()
    @Test fun kdfLimitsAndCancellation() = checks().kdfLimitsAndCancellation()
    @Test fun recordTamperingAndNoPlaintext() = checks().recordTamperingAndNoPlaintext()
    @Test fun freshRandomnessAndRngFailure() = checks().freshRandomnessAndRngFailure()
    @Test fun prepareNewKeyAndCleanup() = checks().prepareNewKeyAndCleanup()
}
