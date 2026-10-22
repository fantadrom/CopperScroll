package com.fantadrom.copperscroll.storage.runtime

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.p6aharness.P6aProviderProbeActivity
import com.fantadrom.copperscroll.p6aharness.RemoteHarness
import org.junit.Test
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Four explicit controller methods, NEVER part of the automatic 13-method correctness suite.
 * Each scenario requires its exact pair; reboot is exclusively the user's normal restart.
 */
class P6aQualificationController {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id get()=InstrumentationRegistry.getArguments().getString("qualificationRunId")!!.also {
        check(it.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
    }
    private fun pick() {
        val receipt=File(i.targetContext.noBackupFilesDir,"p6a-probe-$id-result.txt")
        check(!receipt.exists())
        i.targetContext.startActivity(Intent(i.targetContext,P6aProviderProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("runId",id).putExtra("qualificationPrimaryOnly",true))
        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10)
        while(!receipt.exists() && System.nanoTime()<deadline) Thread.sleep(250)
        check(receipt.isFile && receipt.length() in 1..16384)
        val lines=receipt.readText().lineSequence().toSet()
        check("runId=$id" in lines && "probeOperations=OBSERVED" in lines && "productionApproved=false" in lines && "interprocessLock=OBSERVED" in lines)
    }
    private fun send(b: Bundle,method: String) {
        val p=Properties(); p.setProperty("method",method)
        for(k in b.keySet()) p.setProperty(k,b.get(k).toString())
        val bytes=java.io.ByteArrayOutputStream().also { p.store(it,null) }.toByteArray(); check(bytes.size<16384)
        i.sendStatus(2,Bundle().apply { putString("p6a2ReceiptBase64",Base64.encodeToString(bytes,Base64.NO_WRAP)) })
    }
    private fun prepare(method: String,die: Boolean) {
        pick()
        RemoteHarness(i.targetContext).use { worker ->
            val result=worker.request("qualificationPrepare",name=id)
            result.putAll(worker.request("qualificationStatus",name=id))
            send(result,method); check(result.getString("status")=="PREPARED")
            if(die) worker.die() // Binder death observed; next method must also prove a fresh PID/session.
        }
    }
    private fun recover(method: String,reboot: Boolean) {
        RemoteHarness(i.targetContext).use { worker ->
            val result=worker.request(if(reboot) "qualificationReboot" else "qualificationProcess",name=id)
            result.putAll(worker.request("qualificationStatus",name=id))
            send(result,method); check(result.getString("status")=="RECOVERED")
        }
    }
    @Test fun prepareWorker()=prepare("prepareWorker",true)
    @Test fun recoverWorker()=recover("recoverWorker",false)
    @Test fun prepareReboot()=prepare("prepareReboot",false)
    @Test fun recoverReboot()=recover("recoverReboot",true)
}
