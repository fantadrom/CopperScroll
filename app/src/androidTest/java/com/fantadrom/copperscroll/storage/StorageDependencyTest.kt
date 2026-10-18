package com.fantadrom.copperscroll.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantadrom.copperscroll.storage.access.SecretEncoding
import com.fantadrom.copperscroll.storage.format.SettingsCodec
import com.fantadrom.copperscroll.storage.format.SettingsData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Must run on API31+; a JVM pass does not replace this gate. */
@RunWith(AndroidJUnit4::class)
class StorageDependencyTest {
    @Test fun pinnedUnicodeAndCborOnAndroid() {
        assertTrue(SecretEncoding.verifyDataVersion())
        SecretEncoding.text("\uD804\uDF82\uD804\uDFC9").use {
            assertArrayEquals(byteArrayOf(0xf0.toByte(),0x91.toByte(),0x8e.toByte(),0x83.toByte()),it.copyBytes())
        }
        val b=SettingsCodec.encode(SettingsData(sortMode=4294967295L,viewMode=99))
        assertEquals(4294967295L,SettingsCodec.decode(b).sortMode)
    }
}
