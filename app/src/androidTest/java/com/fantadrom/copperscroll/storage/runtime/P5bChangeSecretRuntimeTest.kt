package com.fantadrom.copperscroll.storage.runtime

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.format.ContainerReader
import com.fantadrom.copperscroll.storage.transaction.TransactionCore
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.runtimechecks.P5bChecks
import com.fantadrom.copperscroll.storage.maintenance.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P5bChangeSecretRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun requireProductionTarget() {
        val variant=InstrumentationRegistry.getArguments().getString("p1ExpectedVariant")
        check(variant == "debug" || variant == "p1Release")
        val target=instrumentation.targetContext
        check(target.packageName == "com.fantadrom.copperscroll"+if(variant == "p1Release") ".p1runtime" else "")
        check(instrumentation.context.packageName == target.packageName+".test")
        if(variant == "p1Release") {
            check(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            check(target.assets.open("p1-runtime-target.txt").bufferedReader().use { it.readText().trim() } == "p1Release")
        }
        for(type in listOf(ZoneRepository::class.java,TransactionCore::class.java,ContainerReader::class.java,
            SnapshotCopier::class.java,MaintenanceCoordinator::class.java,MaintenanceRecovery::class.java))
            check(type === target.classLoader.loadClass(type.name)) { "P5b backend must resolve from target loader" }
    }
    private fun checks() = P5bChecks()
    @Test fun mainOnlyAndCompatibility() { checks().mainOnlyAndCompatibility() }
    @Test fun mainRekeyPreservesDecoy() { checks().mainRekeyPreservesDecoy() }
    @Test fun decoyRekeyPreservesMain() { checks().decoyRekeyPreservesMain() }
    @Test fun credentialsAndNoArtifacts() { checks().credentialsAndNoArtifacts() }
    @Test fun oldKeyCannotReadRekeyOrFutureTransactions() { checks().oldKeyCannotReadRekeyOrFutureTransactions() }
    @Test fun revokeSwitchAndLease() { checks().revokeSwitchAndLease() }
    @Test fun restartWithDistinctCredentialContexts() { checks().restartWithDistinctCredentialContexts() }
    @Test fun preferredInputAndRepeatedChange() { checks().preferredInputAndRepeatedChange() }
}
