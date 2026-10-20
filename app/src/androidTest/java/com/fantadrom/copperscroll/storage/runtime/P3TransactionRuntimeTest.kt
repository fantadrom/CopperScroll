package com.fantadrom.copperscroll.storage.runtime

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.storage.access.CredentialManager
import com.fantadrom.copperscroll.storage.crypto.*
import com.fantadrom.copperscroll.storage.runtimechecks.P3Checks
import com.fantadrom.copperscroll.storage.transaction.TransactionCore
import com.fantadrom.copperscroll.storage.io.ResourceOwner
import com.fantadrom.copperscroll.storage.format.ContainerReader
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P3TransactionRuntimeTest {
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
        for(type in listOf(CredentialManager::class.java,ZoneKey::class.java,RecordCrypto::class.java,TransactionCore::class.java,ResourceOwner::class.java,ContainerReader::class.java,
            Argon2BytesGenerator::class.java,HKDFBytesGenerator::class.java,HMac::class.java))
            check(type===target.classLoader.loadClass(type.name)) { "Crypto backend does not resolve through target loader" }
    }
    private fun checks() = P3Checks()
    @Test fun createAddMutateReopen() = checks().createAddMutateReopen()
    @Test fun commitUnknownReconciliationAndRevision() = checks().commitUnknownReconciliationAndRevision()
    @Test fun recoveryIsolationAndNoPasswordFallback() = checks().recoveryIsolationAndNoPasswordFallback()
    @Test fun lazyVerificationAndUnsupportedSchema() = checks().lazyVerificationAndUnsupportedSchema()
    @Test fun ownerRevokeAndClosingSave() = checks().ownerRevokeAndClosingSave()
    @Test fun namespaceAndLocalStateDurability() = checks().namespaceAndLocalStateDurability()
    @Test fun catalogueSplitsAndLimits() = checks().catalogueSplitsAndLimits()
    @Test fun boundedStoreAndConflict() {
        checks().boundedStoreAndConflict()
        val path = instrumentation.targetContext.cacheDir.toPath().resolve("p3-"+java.util.UUID.randomUUID()+".synthetic")
        try {
            com.fantadrom.copperscroll.storage.io.FileChannelStore.open(path,true).use { store ->
                store.writeFullyAt(0,byteArrayOf(1,2,3)); store.force()
                check(store.readFullyAt(1,2).contentEquals(byteArrayOf(2,3)))
                check(store.truncate(2) && store.size == 2L)
            }
        } finally { java.nio.file.Files.deleteIfExists(path) }
    }
}
