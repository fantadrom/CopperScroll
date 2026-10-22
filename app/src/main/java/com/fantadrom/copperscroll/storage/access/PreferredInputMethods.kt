package com.fantadrom.copperscroll.storage.access

import com.fantadrom.copperscroll.storage.format.*

/** App-private noBackup preference port; production persistence/UI are P6. It has no file, crypto,
 * credential or maintenance dependency, and never converts or re-encodes S1. */
interface NoBackupInputPreferences {
    fun read(container: Bytes, zone: Bytes): Int?
    fun write(container: Bytes, zone: Bytes, method: Int)
}
object PreferredInputMethods {
    fun set(preferences: NoBackupInputPreferences, container: Bytes, zone: Bytes, method: Int) {
        container.id(16); zone.id(16); checkFormat(method in 1..4,Failure.ValidationError)
        preferences.write(container,zone,method)
    }
    fun preferred(preferences: NoBackupInputPreferences, container: Bytes, descriptor: Descriptor): Int =
        (preferences.read(container,descriptor.zoneId) ?: descriptor.inputMethod).also { checkFormat(it in 1..4) }
}
