package com.fantadrom.copperscroll.storage
import com.fantadrom.copperscroll.storage.runtimechecks.P5aChecks
import org.junit.Test
class P5aCoreTest {
    @Test fun selectiveZonesAndIndependentReaders() = P5aChecks().selectiveZonesAndIndependentReaders()
    @Test fun authorizationCredentialsAndNoEffects() = P5aChecks().authorizationCredentialsAndNoEffects()
    @Test fun committedLengthCopyAndCoverage() = P5aChecks().committedLengthCopyAndCoverage()
    @Test fun restartAndLostAcknowledgement() = P5aChecks().restartAndLostAcknowledgement()
    @Test fun revokeSwitchAndExclusiveLease() = P5aChecks().revokeSwitchAndExclusiveLease()
    @Test fun faultCopyBuildAndJournal() = P5aChecks().faultCopyBuildAndJournal()
    @Test fun retentionSizesAndFailures() = P5aChecks().retentionSizesAndFailures()
    @Test fun largeMediaAndMainOnlyCompatibility() = P5aChecks().largeMediaAndMainOnlyCompatibility()
}
