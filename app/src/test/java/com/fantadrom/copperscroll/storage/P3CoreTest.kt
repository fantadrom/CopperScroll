package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.P3Checks
import org.junit.Test

class P3CoreTest {
    @Test fun createAddMutateReopen() = P3Checks().createAddMutateReopen()
    @Test fun commitUnknownReconciliationAndRevision() = P3Checks().commitUnknownReconciliationAndRevision()
    @Test fun recoveryIsolationAndNoPasswordFallback() = P3Checks().recoveryIsolationAndNoPasswordFallback()
    @Test fun lazyVerificationAndUnsupportedSchema() = P3Checks().lazyVerificationAndUnsupportedSchema()
    @Test fun ownerRevokeAndClosingSave() = P3Checks().ownerRevokeAndClosingSave()
    @Test fun namespaceAndLocalStateDurability() = P3Checks().namespaceAndLocalStateDurability()
    @Test fun catalogueSplitsAndLimits() = P3Checks().catalogueSplitsAndLimits()
    @Test fun boundedStoreAndConflict() = P3Checks().boundedStoreAndConflict()
}
