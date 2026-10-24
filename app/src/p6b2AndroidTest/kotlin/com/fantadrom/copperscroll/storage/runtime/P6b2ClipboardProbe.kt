package com.fantadrom.copperscroll.storage.runtime

import android.content.ClipboardManager
import android.util.Log
import com.fantadrom.copperscroll.privacytest.ClipboardEvidence
import com.fantadrom.copperscroll.session.android.ProtectedEditText

/** Exactly one write and one read, no retry/re-copy that could hide a replaced item or lost flag. */
internal fun P6b2Driver.sensitiveCopy(editor: ProtectedEditText,expected: String) {
    val started=System.currentTimeMillis()
    val copied=editor.onTextContextMenuItem(android.R.id.copy) // Calls the real production pre-write check.
    val clip=if(copied) context.getSystemService(ClipboardManager::class.java).primaryClip else null
    val description=clip?.description; val extras=description?.extras
    val evidence=ClipboardEvidence(copied,clip!=null,clip?.itemCount==1,
        clip?.let { it.itemCount==1 && it.getItemAt(0).text?.toString()==expected && it.getItemAt(0).uri==null && it.getItemAt(0).intent==null }==true,
        description?.label?.toString()=="Copper Scroll",
        extras?.containsKey("android.content.extra.IS_SENSITIVE")==true,
        extras?.getBoolean("android.content.extra.IS_SENSITIVE")==true,
        editor.hasWindowFocus() && host().model.coordinator.snapshot().presented,
        extras!=null,description?.label?.toString()=="host clipboard",
        extras?.getBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY")==true,
        description?.timestamp?.let { it in started..System.currentTimeMillis() }==true)
    Log.i("P6B2_SAFE_CLIPBOARD",evidence.toString()+"; preWriteSensitiveVerified=${evidence.preWriteSensitiveVerified}; code=${evidence.failureCode() ?: "PASS"}")
    check(evidence.failureCode()==null) { "${evidence.failureCode()}; $evidence; preWriteSensitiveVerified=${evidence.preWriteSensitiveVerified}" }
}
internal fun uniqueSyntheticCopy()="synthetic copy "+java.util.UUID.randomUUID().toString()
