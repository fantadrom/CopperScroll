package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.access.*
import com.ibm.icu.text.Normalizer2
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class SecretEncodingTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("accepted invalid secret") } catch(e: SecretEncodingException) { assertEquals(SecretEncodingException.Code.InvalidSecret,e.code) }
    }
    @Test fun fixedVectorsAndLimits() {
        assertTrue(SecretEncoding.verifyDataVersion())
        assertFalse(SecretEncoding.supportsData(com.ibm.icu.util.VersionInfo.getInstance(15,0,0,0)))
        try { SecretEncoding.textUsing("synthetic",null);fail("missing backend accepted") }
        catch(e:SecretEncodingException) { assertEquals(SecretEncodingException.Code.UnsupportedSecretEncoding,e.code) }
        javaClass.getResourceAsStream("/p1/s1.tsv")!!.bufferedReader().useLines { lines -> lines.forEach { line ->
            val f=line.split('\t');val input=f[2].chunked(4).map { it.toInt(16).toChar() }.joinToString("")
            fun encode(): CanonicalSecret = when(f[1]) {
                "pin4" -> SecretEncoding.pin4(input); "pin6" -> SecretEncoding.pin6(input)
                "text" -> SecretEncoding.text(input)
                else -> SecretEncoding.pattern(f[3].split(',').map { it.toInt() })
            }
            if(f[4]=="InvalidSecret") invalid { encode() } else encode().use { assertEquals(f[0],f[4],hex(it.copyBytes())) }
        } }
        SecretEncoding.pin4("0012").use { p -> SecretEncoding.text("0012").use { assertTrue(p.sameAs(it)) } }
        SecretEncoding.pattern(listOf(1,2,3,4)).use { p -> SecretEncoding.pin4("1234").use { assertTrue(p.sameAs(it)) } }
        SecretEncoding.pattern(listOf(1,3,7,1,9)).use { assertEquals("1235789",it.copyBytes().toString(Charsets.US_ASCII)) }
        SecretEncoding.text("😀".repeat(256)).use { assertEquals(1024,it.copyBytes().size) }
        invalid { SecretEncoding.text("a".repeat(257)) }
        invalid { SecretEncoding.text("a".repeat(1025)) }
        invalid { SecretEncoding.text("😀".repeat(1025)) }
        invalid { SecretEncoding.textUtf8(byteArrayOf(0xc0.toByte(),0x80.toByte())) }
        invalid { SecretEncoding.textUtf8(byteArrayOf(0xed.toByte(),0xa0.toByte(),0x80.toByte())) }
        invalid { SecretEncoding.text(String(Character.toChars(0x1FAEA))) } // Assigned after Unicode16.
        invalid { SecretEncoding.text("\uDC00") }; invalid { SecretEncoding.pattern(listOf(0,1,2,3,4)) }
        SecretEncoding.text("\u1100\u1161\u11A8".repeat(256)).use { assertEquals(768,it.copyBytes().size) }
        val closed=SecretEncoding.pin4("1234");assertEquals("CanonicalSecret(redacted)",closed.toString());closed.close()
        try { closed.copyBytes();fail("closed secret usable") } catch(_:IllegalStateException) { }
    }
    @Test(timeout=120000) fun fullOfficialUnicode16Conformance() {
        assertTrue(SecretEncoding.verifyDataVersion())
        val raw=GZIPInputStream(javaClass.getResourceAsStream("/p1/NormalizationTest-16.0.0.txt.gz")!!).use { it.readBytes() }
        assertEquals("d811971453e7075e1ad56fb1b301eece5aa80757b81f6156e74a1bfb3ae5ceb1",hex(MessageDigest.getInstance("SHA-256").digest(raw)))
        val normalizers=listOf(Normalizer2.getNFCInstance(),Normalizer2.getNFDInstance(),Normalizer2.getNFKCInstance(),Normalizer2.getNFKDInstance())
        val part1=HashSet<Int>();var part="";var rows=0;var assertions=0L
        for(line in raw.toString(Charsets.UTF_8).lineSequence()) {
            val clean=line.substringBefore('#').trim();if(clean.isEmpty())continue
            if(clean.startsWith('@')) { part=clean;continue }
            val c=clean.split(';').take(5).map { field -> field.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("") { String(Character.toChars(it.toInt(16))) } }
            if(part.startsWith("@Part1"))part1.add(c[0].codePointAt(0))
            val expected=listOf(listOf(c[1],c[1],c[1],c[3],c[3]),listOf(c[2],c[2],c[2],c[4],c[4]),List(5){c[3]},List(5){c[4]})
            for(n in normalizers.indices) for(i in c.indices) { assertEquals("Unicode row=$rows form=$n column=$i",expected[n][i],normalizers[n].normalize(c[i]));assertions++ }
            // S1 uses exactly the same pinned NFC instance, before its repertoire/length restrictions.
            assertEquals(c[1],SecretEncoding.normalizeUnicode16(c[0]));rows++
        }
        var sweep=0L
        for(cp in 0..0x10ffff) {
            if(cp in 0xd800..0xdfff || cp in part1)continue
            val s=String(Character.toChars(cp))
            for(n in normalizers) { assertEquals("Unicode identity cp=$cp",s,n.normalize(s));sweep++ }
        }
        assertEquals(19965,rows)
        println("ICU4J76.1 Unicode16 rows=$rows row_assertions=$assertions identity_assertions=$sweep PASS")
    }
}
