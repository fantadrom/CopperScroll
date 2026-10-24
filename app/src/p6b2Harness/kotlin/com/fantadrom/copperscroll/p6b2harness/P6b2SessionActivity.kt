package com.fantadrom.copperscroll.p6b2harness

import android.os.Bundle
import android.widget.*
import com.fantadrom.copperscroll.p6b1harness.P6b1SessionActivity

/** Test-only nonexported UI. Cross-process controller communicates through signed probe IPC,
 * never exported launch extras/automatic login on ordinary release. */
class P6b2SessionActivity: P6b1SessionActivity() {
    companion object { val processSession=java.util.UUID.randomUUID().toString() }
    val observations=java.util.concurrent.ConcurrentHashMap<String,String>()
    var finishedPrivacy=false
    override fun onCreate(state: Bundle?) {
        super.onCreate(null)
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        panel.addView(Button(this).apply { text="Explicit synthetic login"; setOnClickListener { if(model.coordinator.snapshot().unlockReady) model.login(false) } })
        for(step in listOf("active_ime","clipboard_preview","accessibility","recents_screenshot","autofill_capture")) {
            panel.addView(TextView(this).apply { text="Observation: $step (synthetic only)" })
            for(result in listOf("OBSERVED_OK","FAIL","NOT_RUN","NOT_CONFIRMED")) panel.addView(Button(this).apply {
                text="$step: $result"; setOnClickListener { observations[step]=result }
            })
        }
        panel.addView(Button(this).apply { text="Finish privacy observations"; setOnClickListener { finishedPrivacy=true } })
        panel.addView(Button(this).apply {
            text="Explicit synthetic sensitive copy"
            setOnClickListener {
                val c=model.coordinator; val cap=c.currentCapability()
                val epoch=c.snapshot().epoch
                val destination=java.lang.ref.WeakReference(this@P6b2SessionActivity)
                try { if(cap!=null) model.worker.command(c.admit(cap,com.fantadrom.copperscroll.storage.session.SessionCoordinator.Command.Save)) {
                    val session=cap.session
                    val repo=com.fantadrom.copperscroll.storage.vfs.ZoneRepository(session.core)
                    val settings=com.fantadrom.copperscroll.storage.format.SettingsData(copyBlocked=false)
                    check(session.core.submit(repo.issueSettings(repo.stamp(),settings)).code==com.fantadrom.copperscroll.storage.transaction.WriteCode.Committed)
                    destination.get()?.runOnUiThread {
                        destination.get()?.let { live ->
                            if(c.currentCapability()===cap && live.presentation.applySettings(epoch,settings)) {
                                live.editor.setSelection(0,live.editor.length())
                                live.editor.onTextContextMenuItem(android.R.id.copy)
                            }
                        }
                    }
                } } catch(_: com.fantadrom.copperscroll.storage.io.StoreException) { model.event("PRIVACY_COPY_BUSY") }
            }
        })
        // Probe controls are only shown when explicitly requested by its instrumentation.
        privacyPanel=panel
    }
    lateinit var privacyPanel: LinearLayout; private set
}
