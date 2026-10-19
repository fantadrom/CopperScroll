package com.fantadrom.copperscroll.storage.runtime

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.nstant.`in`.cbor.CborDecoder
import com.fantadrom.copperscroll.storage.access.CanonicalSecret
import com.fantadrom.copperscroll.storage.access.SecretEncoding
import com.fantadrom.copperscroll.storage.access.SecretEncodingException
import com.fantadrom.copperscroll.storage.format.Failure
import com.fantadrom.copperscroll.storage.format.FormatException
import com.fantadrom.copperscroll.storage.format.SettingsCodec
import com.fantadrom.copperscroll.storage.format.SettingsData
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.VersionInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Small synthetic runtime suite, not the full Unicode corpus. No plaintext/secret values in diagnostics. */
@RunWith(AndroidJUnit4::class)
class P1BackendRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun requireExpectedTargetApk() {
        val expected = InstrumentationRegistry.getArguments().getString("p1ExpectedVariant")
        assertTrue("Missing/unsupported target variant argument", expected == "debug" || expected == "p1Release")
        val target = instrumentation.targetContext
        val expectedPackage = "com.fantadrom.copperscroll" + if (expected == "p1Release") ".p1runtime" else ""
        assertEquals("Instrumentation targets wrong application", expectedPackage, target.packageName)
        assertEquals("Unexpected test package", "$expectedPackage.test", instrumentation.context.packageName)
        if (expected == "p1Release") {
            assertTrue("Release-derived target must not be debuggable", target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            val marker = target.assets.open("p1-runtime-target.txt").bufferedReader().use { it.readText().trim() }
            assertEquals("Wrong target assets", "p1Release", marker)
        }
        // Direct calls below must resolve to classes accessible through the target application loader.
        // The build-time DEX audit separately proves these classes are not duplicated in the test APK.
        for (type in listOf(SecretEncoding::class.java, CanonicalSecret::class.java, SettingsCodec::class.java,
            SettingsData::class.java, UCharacter::class.java, Normalizer2::class.java, CborDecoder::class.java)) {
            assertSame("Backend is not loaded from target", type, target.classLoader.loadClass(type.name))
        }
    }

    @Test fun targetApkLoadsPinnedUnicode16Data() {
        assertEquals("Wrong Unicode data version", VersionInfo.getInstance(16, 0, 0, 0), UCharacter.getUnicodeVersion())
        assertTrue("Unicode16 capability unavailable", SecretEncoding.verifyDataVersion())
        val resource = "com/ibm/icu/impl/data/icudata/nfc.nrm"
        val stream = instrumentation.targetContext.classLoader.getResourceAsStream(resource)
        assertNotNull("Target NFC resource missing", stream)
        stream?.close()
    }

    @Test fun unicode16CompositionAndEquivalentSecrets() {
        SecretEncoding.text("\uD804\uDF82\uD804\uDFC9").use { decomposed ->
            SecretEncoding.text("\uD804\uDF83").use { composed ->
                assertTrue("Unicode16 composition mismatch", composed.sameAs(decomposed))
                assertTrue("Unicode16 fixed bytes mismatch", composed.copyBytes().contentEquals(
                    byteArrayOf(0xf0.toByte(), 0x91.toByte(), 0x8e.toByte(), 0x83.toByte())))
            }
        }
        SecretEncoding.text("e\u0301").use { a ->
            SecretEncoding.text("\u00e9").use { b -> assertTrue("NFC equivalence mismatch", a.sameAs(b)) }
        }
    }

    @Test fun pinLeadingZerosAndRejectedRepertoire() {
        SecretEncoding.pin4("0012").use { pin ->
            assertTrue("PIN leading zeros lost", pin.copyBytes().contentEquals(byteArrayOf(48, 48, 49, 50)))
            SecretEncoding.text("0012").use { text -> assertTrue("Cross-method mismatch", pin.sameAs(text)) }
        }
        SecretEncoding.pin6("000012").use {
            assertTrue("PIN6 leading zeros lost", it.copyBytes().contentEquals(byteArrayOf(48, 48, 48, 48, 49, 50)))
        }
        rejected(SecretEncodingException.Code.InvalidSecret) { SecretEncoding.text("\u0378").close() }
        rejected(SecretEncodingException.Code.InvalidSecret) { SecretEncoding.text("\ufdd0").close() }
        rejected(SecretEncodingException.Code.InvalidSecret) { SecretEncoding.text("\u0000").close() }
    }

    @Test fun unsupportedBackendDoesNotFallBackToSystemNfc() {
        assertFalse("Older data accepted", SecretEncoding.supportsData(VersionInfo.getInstance(15, 0, 0, 0)))
        // Exercise the production failure path, without modifying/replacing the actual pinned backend.
        rejected(SecretEncodingException.Code.UnsupportedSecretEncoding) { SecretEncoding.textUsing("synthetic", null).close() }
    }

    @Test fun cborUnknownEnumsAndIndependentGolden() {
        val encoded = SettingsCodec.encode(SettingsData(sortMode = 0xffffffffL, viewMode = 99))
        val decoded = SettingsCodec.decode(encoded)
        assertEquals(0xffffffffL, decoded.sortMode)
        assertEquals(99L, decoded.viewMode)
        assertTrue("Unknown enum round-trip mismatch", SettingsCodec.encode(decoded).contentEquals(encoded))
        val golden = fixture("settings-default.hex")
        val settings = SettingsCodec.decode(golden)
        assertFalse(settings.rememberFolder)
        assertNull(settings.lastFolder)
        assertTrue(settings.screenshotsBlocked && settings.copyBlocked && settings.sortAscending)
        assertEquals(1L, settings.sortMode)
        assertEquals(1L, settings.viewMode)
        assertTrue("Independent golden bytes mismatch", SettingsCodec.encode(settings).contentEquals(golden))
    }

    @Test fun rejectsNoncanonicalGolden() {
        try {
            SettingsCodec.decode(fixture("noncanonical-settings.hex"))
            fail("Noncanonical CBOR accepted")
        } catch (e: FormatException) {
            assertEquals(Failure.CorruptData, e.failure)
        }
    }

    @Test fun all21SyntheticS1GoldenVectors() {
        val lines = instrumentation.context.assets.open("p1/s1.tsv").bufferedReader().use { it.readLines() }
        assertEquals("Wrong S1 vector count", 21, lines.size)
        for (line in lines) {
            val fields = line.split('\t')
            assertEquals("Malformed S1 fixture", 5, fields.size)
            val input = fields[2].chunked(4).map { it.toInt(16).toChar() }.joinToString("")
            fun encode(): CanonicalSecret = when (fields[1]) {
                "pin4" -> SecretEncoding.pin4(input)
                "pin6" -> SecretEncoding.pin6(input)
                "text" -> SecretEncoding.text(input)
                "pattern" -> SecretEncoding.pattern(fields[3].split(',').map { it.toInt() })
                else -> error("Unknown fixture method")
            }
            if (fields[4] == "InvalidSecret") {
                rejected(SecretEncodingException.Code.InvalidSecret) { encode().close() }
            } else {
                val expected = fields[4].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                encode().use { assertTrue("Synthetic S1 golden mismatch", it.copyBytes().contentEquals(expected)) }
            }
        }
    }

    private fun fixture(name: String): ByteArray {
        val hex = instrumentation.context.assets.open("p1/$name").bufferedReader().use { it.readText().trim() }
        assertTrue("Malformed test fixture", hex.length <= 1024 && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun rejected(code: SecretEncodingException.Code, block: () -> Unit) {
        try { block(); fail("Invalid S1 accepted") }
        catch (e: SecretEncodingException) { assertEquals(code, e.code) }
    }
}
