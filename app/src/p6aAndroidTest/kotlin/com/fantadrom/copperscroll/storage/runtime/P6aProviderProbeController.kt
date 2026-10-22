package com.fantadrom.copperscroll.storage.runtime

import android.content.Intent
import android.util.Base64
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.p6aharness.P6aProviderProbeActivity
import org.junit.Test
import java.io.File
import java.util.UUID

/** Separate from automatic runtime suite. A fresh explicit run waits for the system picker. */
class P6aProviderProbeController {
    @Test fun collectExplicitScratchProbe() {
        val i=InstrumentationRegistry.getInstrumentation(); val id=UUID.randomUUID().toString()
        val receipt=File(i.targetContext.noBackupFilesDir,"p6a-probe-$id-result.txt")
        check(!receipt.exists())
        i.targetContext.startActivity(Intent(i.targetContext,P6aProviderProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("runId",id))
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(10)
        while(!receipt.exists() && System.nanoTime()<deadline) Thread.sleep(250)
        check(receipt.isFile) { "Probe timeout; IO completion NOT confirmed" }
        val text=receipt.readText(); check(text.startsWith("runId=$id\n")); check(text.contains("productionApproved=false"))
        i.sendStatus(2,Bundle().apply { putString("p6aReceiptBase64",Base64.encodeToString(text.toByteArray(),Base64.NO_WRAP)) })
        val lines=text.lineSequence().toSet()
        check(listOf("probeOperations=OBSERVED","cleanup=RETAINED_NO_QUALIFIED_DELETE_CONTRACT","productionApproved=false",
            "existingWriteDurable=UNKNOWN","durableCreateIdentity=UNKNOWN","durableLocalState=UNKNOWN").all { it in lines }) { "Probe did not complete; see separate observations receipt" }
    }
}
