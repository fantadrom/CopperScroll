package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.runtimechecks.P5bChecks
import org.junit.Test

class P5bCoreTest {
    @Test fun mainOnlyAndCompatibility() { P5bChecks().mainOnlyAndCompatibility() }
    @Test fun mainRekeyPreservesDecoy() { P5bChecks().mainRekeyPreservesDecoy() }
    @Test fun decoyRekeyPreservesMain() { P5bChecks().decoyRekeyPreservesMain() }
    @Test fun credentialsAndNoArtifacts() { P5bChecks().credentialsAndNoArtifacts() }
    @Test fun oldKeyCannotReadRekeyOrFutureTransactions() { P5bChecks().oldKeyCannotReadRekeyOrFutureTransactions() }
    @Test fun revokeSwitchAndLease() { P5bChecks().revokeSwitchAndLease() }
    @Test fun restartWithDistinctCredentialContexts() { P5bChecks().restartWithDistinctCredentialContexts() }
    @Test fun preferredInputAndRepeatedChange() { P5bChecks().preferredInputAndRepeatedChange() }
}
