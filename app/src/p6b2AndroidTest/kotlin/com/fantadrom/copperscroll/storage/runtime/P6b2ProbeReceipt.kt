package com.fantadrom.copperscroll.storage.runtime

import android.os.*
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.copperscroll.p6b2harness.P6b2SessionActivity

internal fun receipt(checkpoint: String,extra: String="") {
    val d=android.os.Build.VERSION.SDK_INT
    val args=InstrumentationRegistry.getArguments()
    val body="runId=${args.getString("p6b2RunId")}\ndeviceRunId=${args.getString("p6b2DeviceRunId")}\ncheckpoint=$checkpoint\npid=${Process.myPid()}\nprocessSession=${P6b2SessionActivity.processSession}\napi=$d\nbuild=${android.os.Build.FINGERPRINT}\nproductionAdmission=REFUSED\n$extra"
    InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply {
        putString("p6b2ReceiptBase64",Base64.encodeToString(body.toByteArray(),Base64.NO_WRAP))
    })
}
