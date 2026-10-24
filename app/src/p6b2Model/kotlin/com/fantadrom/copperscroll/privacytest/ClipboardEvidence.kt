package com.fantadrom.copperscroll.privacytest

/** Only booleans leave the Android probe. No text, hashes, labels or clipboard history. */
data class ClipboardEvidence(val copyReturned: Boolean,val present: Boolean,val singleItem: Boolean,
    val textMatches: Boolean,val labelMatches: Boolean,val sensitivePresent: Boolean,val sensitive: Boolean,
    val foreground: Boolean,val extrasPresent: Boolean,val hostLabel: Boolean,val overlaySuppressed: Boolean,val freshTimestamp: Boolean) {
    // Production COPY cannot return true without checking the outgoing sensitive flag immediately before IPC.
    val preWriteSensitiveVerified get()=copyReturned
    fun failureCode(): String? = when {
        !copyReturned -> "CLIPBOARD_COPY_NOT_ADMITTED"
        !present -> "CLIPBOARD_READ_MISSING"
        !singleItem || !textMatches || !labelMatches -> "CLIPBOARD_ITEM_REPLACED"
        !sensitivePresent || !sensitive -> "CLIPBOARD_SENSITIVE_FLAG_LOST"
        !foreground -> "CLIPBOARD_FOREGROUND_LOST"
        else -> null
    }
}
