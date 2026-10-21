package com.fantadrom.copperscroll.storage.runtime

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.format.ContainerReader
import com.fantadrom.copperscroll.storage.transaction.TransactionCore
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.runtimechecks.P4Checks
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P4VfsCardRuntimeTest {
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
            CardCodec::class.java,DigitMask::class.java,CardDraftSession::class.java,NoteStorageAdapter::class.java))
            check(type === target.classLoader.loadClass(type.name)) { "P4 backend must resolve from target loader" }
    }
    private fun checks() = P4Checks { name -> instrumentation.context.assets.open("p4/$name").bufferedReader().use { it.readText() } }
    @Test fun vfsBatchDepthTrash() = checks().vfsBatchDepthTrash()
    @Test fun noteRichTextRevision() = checks().noteRichTextRevision()
    @Test fun cardFieldRoundTripAndGolden() = checks().cardFieldRoundTripAndGolden()
    @Test fun cardAtomicSaveAndDraft() = checks().cardAtomicSaveAndDraft()
    @Test fun masksDatesAndConstraints() = checks().masksDatesAndConstraints()
    @Test fun templatesAndGlobalMessagePolicy() = checks().templatesAndGlobalMessagePolicy()
    @Test fun mediaStreamingAndCow() = checks().mediaStreamingAndCow()
    @Test fun faultCommitUnknownAndRevoke() = checks().faultCommitUnknownAndRevoke()
    @Test fun compatibilityRecoveryAndBudgets() = checks().compatibilityRecoveryAndBudgets()
    @Test fun sortingSettingsAndZoneIsolation() = checks().sortingSettingsAndZoneIsolation()
}
