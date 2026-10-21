package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.P4Checks
import org.junit.Test

class P4CoreTest {
    @Test fun vfsBatchDepthTrash() = P4Checks().vfsBatchDepthTrash()
    @Test fun noteRichTextRevision() = P4Checks().noteRichTextRevision()
    @Test fun cardFieldRoundTripAndGolden() = P4Checks().cardFieldRoundTripAndGolden()
    @Test fun cardAtomicSaveAndDraft() = P4Checks().cardAtomicSaveAndDraft()
    @Test fun masksDatesAndConstraints() = P4Checks().masksDatesAndConstraints()
    @Test fun templatesAndGlobalMessagePolicy() = P4Checks().templatesAndGlobalMessagePolicy()
    @Test fun mediaStreamingAndCow() = P4Checks().mediaStreamingAndCow()
    @Test fun faultCommitUnknownAndRevoke() = P4Checks().faultCommitUnknownAndRevoke()
    @Test fun compatibilityRecoveryAndBudgets() = P4Checks().compatibilityRecoveryAndBudgets()
    @Test fun sortingSettingsAndZoneIsolation() = P4Checks().sortingSettingsAndZoneIsolation()
}
