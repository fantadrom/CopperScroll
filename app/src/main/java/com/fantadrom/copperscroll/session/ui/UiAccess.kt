package com.fantadrom.copperscroll.session.ui

import com.fantadrom.copperscroll.storage.format.SettingsData
import com.fantadrom.copperscroll.storage.session.SessionCoordinator

/** UI authority only: no keys, content, IO or second session engine. Settings are an authenticated
 * worker result from the existing encrypted SettingsCodec, never preferences. Rebind on changes. */
class UiAccess private constructor(private val valid: () -> Boolean, val copyBlocked: Boolean,
                                  val screenshotsBlocked: Boolean) {
    @Volatile private var retired=false
    fun active() = !retired && valid()
    fun retire() { retired=true }
    fun mayCopy() = active() && !copyBlocked
    fun mayCapture() = active() && !screenshotsBlocked
    companion object {
        fun session(c: SessionCoordinator,w: SessionCoordinator.Window,cap: SessionCoordinator.Capability,
                    settings: SettingsData = SettingsData()): UiAccess =
            UiAccess({ c.currentCapability()===cap && c.ownsWindow(w) },settings.copyBlocked,settings.screenshotsBlocked)
        /** Explicit RAM demo boundary; conveys NO authentication, engine or storage admission. */
        internal fun demo() = UiAccess({true},true,true)
    }
}
