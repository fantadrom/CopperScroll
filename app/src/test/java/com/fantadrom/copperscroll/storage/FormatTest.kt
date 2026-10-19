package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.format.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

class FormatTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/p1/$name")!!.use { it.readBytes() }
    private fun hex(name: String) = resource("$name.hex").toString(Charsets.US_ASCII).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val zone = Bytes(ByteArray(16) { (it+1).toByte() })
    private val owner = Bytes(ByteArray(16) { (it+17).toByte() })
    private fun reject(failure: Failure? = null, block: () -> Unit) {
        try { block(); fail("accepted malformed input") } catch(e: FormatException) { if(failure != null) assertEquals(failure,e.failure) }
    }
    private fun rehash(b: ByteArray): ByteArray { MessageDigest.getInstance("SHA-256").digest(b.copyOfRange(0,65504)).copyInto(b,65504); return b }
    private fun source(bytes: ByteArray) = object: ReadOnlySource {
        override val size = bytes.size.toLong()
        override fun read(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(),offset.toInt()+length)
    }
    @Test fun independentGoldenCodecs() {
        val length = 196608L
        val d = DescriptorCodec.decode(hex("descriptor"),length)
        assertEquals("synthetic public hint",d.hint); assertEquals(7,d.sequence)
        assertArrayEquals(hex("descriptor"),DescriptorCodec.encode(d,length))
        assertArrayEquals(hex("descriptor-logical"),DescriptorCodec.logicalBytes(d))
        assertArrayEquals(hex("physical"),ReferenceCodec.encodePhysical(ReferenceCodec.decodePhysical(hex("physical"),length),length))
        assertArrayEquals(hex("logical"),ReferenceCodec.encodeLogical(ReferenceCodec.decodeLogical(hex("logical"))))
        val header=resource("superblock.bin")
        assertArrayEquals(header,SuperblockCodec.encode(SuperblockCodec.decode(header,length)))
        val record=resource("record.bin");val r=RecordCodec.decode(record)
        assertEquals(3,r.header.plaintextLength);assertEquals(1,r.header.kind)
        assertArrayEquals(record,RecordCodec.encode(r))
        assertArrayEquals(record.copyOfRange(0,76),RecordCodec.encodeHeader(r.header))
        for(n in listOf("catalogue-leaf","catalogue-branch")) {
            val b=resource("$n.bin");assertArrayEquals(b,CatalogueCodec.encode(CatalogueCodec.decode(b,length),length))
        }
        for(n in listOf("settings-default","settings-unknown")) {
            val b=hex(n);assertArrayEquals(b,SettingsCodec.encode(SettingsCodec.decode(b)))
        }
        val unknown=SettingsCodec.decode(hex("settings-unknown"));assertEquals(4294967295L,unknown.sortMode);assertEquals(1,unknown.displaySort)
        for(n in listOf("folder","object-unknown-icon","media-object")) {
            val b=hex(n);assertArrayEquals(b,ObjectCodec.encode(ObjectCodec.decode(b)))
        }
        val folder=ObjectCodec.decode(hex("folder"));assertTrue(folder.payload === FolderPayload);assertEquals(-1,folder.createdAt)
        val note=ObjectCodec.decode(hex("object-unknown-icon"));assertEquals("future_note",note.icon);assertEquals("note",note.displayIcon)
        val b=hex("note-rich");val body=NoteCodec.decode(b)
        assertEquals("A😀e\u0301\n todo \n\n",body.text);assertEquals(2L,body.styles[1].startUtf16)
        assertEquals(false,body.paragraphs[1].checked);assertEquals(true,body.paragraphs[2].checked)
        assertArrayEquals(b,NoteCodec.encode(body))
        assertEquals(body.utf16Length,(note.payload as NotePayload).clampedCursor(body))
        val root=RootCodec.decode(hex("root"),zone,7);assertEquals(ULong.MAX_VALUE,root.nextPinSequence)
        assertArrayEquals(hex("root"),RootCodec.encode(root,zone))
        for(n in listOf("index-objects","index-children","index-branch")) assertArrayEquals(hex(n),IndexCodec.encode(IndexCodec.decode(hex(n),zone),zone))
        for(n in listOf("chunk-leaf","chunk-branch")) assertArrayEquals(hex(n),ChunkListCodec.encode(ChunkListCodec.decode(hex(n),owner),owner))
    }
    @Test fun headersAndUnsupportedFallback() {
        val h=resource("superblock.bin");val absent=ByteArray(65536)
        assertEquals(0,SuperblockCodec.select(h,absent,196608).selectedSlot)
        assertEquals(0,SuperblockCodec.select(h,h,196608).selectedSlot)
        val newer=h.clone();ByteBuffer.wrap(newer).order(ByteOrder.LITTLE_ENDIAN).putLong(16,6);newer[10]=2;rehash(newer)
        reject(Failure.UnsupportedVersion) { SuperblockCodec.select(h,newer,196608) }
        val equal=h.clone();equal[76+20]=(equal[76+20].toInt() xor 1).toByte();rehash(equal)
        reject(Failure.CorruptHeader) { SuperblockCodec.select(h,equal,196608) }
        val bad=h.clone();bad[64]=3;rehash(bad)
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putLong(16,6);rehash(bad)
        reject { SuperblockCodec.select(h,bad,196608) }
        val padding=h.clone();padding[9000]=1;rehash(padding);reject { SuperblockCodec.decode(padding,196608) }
        reject { SuperblockCodec.decode(h,131072) }
    }
    @Test fun strictCborNegativesAndBounds() {
        // Null isn't [], booleans aren't integers; no default fields on read.
        val good=hex("settings-default")
        for(b in listOf(byteArrayOf(0x80.toByte()),good+0,byteArrayOf(0x9f.toByte(),0xff.toByte()),
            byteArrayOf(0xa0.toByte()),byteArrayOf(0xc0.toByte(),0x80.toByte()),byteArrayOf(0xf9.toByte(),0,0),
            byteArrayOf(0x98.toByte(),8)+good.drop(1),byteArrayOf(0x88.toByte(),0x18,1)+good.drop(2),
            byteArrayOf(0x7a,0x7f,0xff.toByte(),0xff.toByte(),0xff.toByte()),
            byteArrayOf(0x82.toByte(),0x61,0xc0.toByte(),0xf6.toByte()))) reject { SettingsCodec.decode(b) }
        reject { ReferenceCodec.decodePhysical(hex("physical"),131072) }
        reject { ReferenceCodec.encodePhysical(PhysicalRef(Long.MAX_VALUE-7,16384,Bytes(ByteArray(32))),Limits.FILE_MAX) }
        reject { ReferenceCodec.encodePhysical(PhysicalRef(131073,16384,Bytes(ByteArray(32))),196608) }
        for(n in 0 until good.size) reject { SettingsCodec.decode(good.copyOf(n)) }
        val p=resource("catalogue-leaf.bin");p[p.lastIndex]=1;reject { CatalogueCodec.decode(p,196608) }
        val r=resource("record.bin");r[72]=0xff.toByte();r[73]=0xff.toByte();r[74]=0xff.toByte();r[75]=0xff.toByte()
        reject(Failure.UnsupportedLimit) { RecordCodec.decode(r) }
        val malformed=NoteBody(listOf(NoteParagraph("\uD800")),emptyList());reject(Failure.ValidationError) { NoteCodec.encode(malformed) }
        reject { ObjectCodec.encode(ObjectData(owner,1,zone,"\u2000",icon="folder",createdAt=0,contentModifiedAt=0,metadataModifiedAt=0,payload=FolderPayload)) }
        reject { NoteCodec.encode(NoteBody(listOf(NoteParagraph("a")),listOf(NoteStyle(0,2)))) }
    }
    @Test fun publicGraphsAndBudgets() {
        val s=StructuralInspector.inspect(source(resource("synthetic.cscroll")))
        assertEquals(3L,s.cataloguePages);assertEquals(2L,s.records);assertEquals(1,s.zones)
        val two=StructuralInspector.inspect(source(resource("two-zones.cscroll")))
        assertEquals(2,two.zones);assertEquals(2L,two.records)
        assertFalse(s.toString().contains("hint"));assertTrue(s.toString().contains("cryptographicVerification=NONE"))
        reject { StructuralInspector.inspect(source(resource("cycle.cscroll"))) }
        reject { StructuralInspector.inspect(source(resource("overlap.cscroll"))) }
        reject(Failure.ResourceLimit) { StructuralInspector.inspect(source(resource("synthetic.cscroll")),InspectionLimits(maxMapBytes=1024)) }
        reject(Failure.ResourceLimit) { StructuralInspector.inspect(source(resource("synthetic.cscroll")),cancelled={true}) }
        reject(Failure.ResourceLimit) { StructuralInspector.inspect(source(resource("synthetic.cscroll")),InspectionLimits(maxReadBytes=65536)) }
        val corrupted=resource("synthetic.cscroll");corrupted[180224+80]=1;reject { StructuralInspector.inspect(source(corrupted)) }
    }
    @Test(timeout=20000) fun boundedMalformedRandomized() {
        val random=Random(0x43535031)
        val fixtures=listOf("settings-default","folder","note-rich","root","index-objects","chunk-leaf")
        repeat(10000) { i ->
            val base=hex(fixtures[i%fixtures.size]);val b=if(i%2==0) ByteArray(random.nextInt(0,2048)).also { random.nextBytes(it) } else base.clone().also {
                repeat(random.nextInt(1,5)) { _ -> val pos=random.nextInt(it.size);it[pos]=random.nextInt(256).toByte() }
            }
            // Valid mutations are allowed. Unchecked exceptions/OOM/hangs fail this test.
            try { when(i%6) {
                0 -> SettingsCodec.decode(b);1 -> ObjectCodec.decode(b);2 -> NoteCodec.decode(b)
                3 -> RootCodec.decode(b,zone);4 -> IndexCodec.decode(b,zone);else -> ChunkListCodec.decode(b,owner)
            } } catch(_: FormatException) { }
        }
        println("P1 malformed seed=0x43535031 iterations=10000 PASS")
    }
}
