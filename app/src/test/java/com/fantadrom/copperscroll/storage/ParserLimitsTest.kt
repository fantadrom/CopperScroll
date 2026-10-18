package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.format.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ParserLimitsTest {
    private val zone=Bytes(ByteArray(16){(it+1).toByte()})
    private val owner=Bytes(ByteArray(16){(it+17).toByte()})
    private fun resource(name:String)=javaClass.getResourceAsStream("/p1/$name")!!.use{it.readBytes()}
    private fun unhex(s:String)=s.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    private fun reject(code:Failure?=null,block:()->Unit) {
        try{block();fail("accepted malformed input")}catch(e:FormatException){if(code!=null)assertEquals(code,e.failure)}
    }
    @Test fun frozenNegativeFixturesAndReconciliation() {
        resource("negative.tsv").toString(Charsets.UTF_8).lineSequence().filter{it.isNotEmpty()}.forEach {
            val f=it.split('\t');val b=unhex(f[1]);reject(Failure.valueOf(f[2])) { when(f[0]) {
                "settings"->SettingsCodec.decode(b);"object"->ObjectCodec.decode(b);else->ReferenceCodec.decodePhysical(b,196608)
            } }
        }
        resource("reconciliation.tsv").toString(Charsets.UTF_8).lineSequence().filter{it.isNotEmpty()}.forEach {
            val f=it.split('\t');val b=unhex(f[3])
            if(f[1]=="folder") {
                if(f[2]=="False")reject{ObjectCodec.decode(b)}else assertArrayEquals(b,ObjectCodec.encode(ObjectCodec.decode(b)))
            } else if(f[1]=="settings")assertArrayEquals(b,SettingsCodec.encode(SettingsCodec.decode(b)))
            else {
                val icon=StrictCbor.decode(b) as String
                val value=ObjectData(owner,2,zone,"",icon=icon,createdAt=0,contentModifiedAt=0,metadataModifiedAt=0,
                    payload=NotePayload(1,listOf(LogicalRef(Bytes(ByteArray(32){1}),Bytes(ByteArray(32)),4,owner,0)),0))
                assertEquals(icon,ObjectCodec.decode(ObjectCodec.encode(value)).icon)
            }
        }
    }
    @Test fun cborFramingBoundaries() {
        val integers=listOf(0uL,23uL,24uL,255uL,256uL,65535uL,65536uL,0xffffffffuL,0x100000000uL,ULong.MAX_VALUE)
        for(n in integers)assertEquals(n,StrictCbor.decode(StrictCbor.encode(n)))
        for(n in listOf(Long.MIN_VALUE,-4294967297L,-65537L,-257L,-25L,-24L,-1L))assertEquals(n,StrictCbor.decode(StrictCbor.encode(n)))
        for(b in listOf("1817","1900ff","1a0000ffff","1b00000000ffffffff","3817","590000","7800","f817","a0","c000","f90000","fa00000000","fb0000000000000000","f7","ff","61c0","62eda0","63eda080","64f4908080","62c080","63e08080","62c2ff","5bffffffffffffffff","9bffffffffffffffff"))reject{StrictCbor.decode(unhex(b))}
        val nested=ByteArray(18){0x81.toByte()}+byteArrayOf(0)
        reject{StrictCbor.decode(nested)}
        reject(Failure.UnsupportedLimit){StrictCbor.decode(unhex("9a000186a1"))}
        reject(Failure.UnsupportedLimit){StrictCbor.encode("x".repeat(16385))}
    }
    @Test fun localSchemaGraphConstraints() {
        val ref=LogicalRef(Bytes(ByteArray(32){1}),Bytes(ByteArray(32)),2,zone,0)
        reject{IndexCodec.encode(IndexPage(1,0,listOf(IndexEntry(IndexKey(owner),ref))),zone)}
        val objectRef=LogicalRef(ref.recordId,ref.hash,3,owner,0)
        reject{IndexCodec.encode(IndexPage(1,0,listOf(IndexEntry(IndexKey(owner),objectRef),IndexEntry(IndexKey(owner),objectRef))),zone)}
        reject{IndexCodec.encode(IndexPage(1,8,emptyList()),zone)}
        reject{IndexCodec.encode(IndexPage(1,1,emptyList()),zone)}
        reject{ChunkListCodec.encode(ChunkListPage(1,emptyList()),owner)}
        reject{CatalogueCodec.encode(CataloguePage(zone,1,emptyList()),196608)}
        reject{IndexCodec.encode(IndexPage(1,0,List(65){IndexEntry(IndexKey(owner),objectRef)}),zone)}
        reject{CatalogueCodec.encode(CataloguePage(zone,256,emptyList()),196608)}
        reject{CatalogueCodec.encode(CataloguePage(zone,0,List(129){CatalogueEntry(ref.recordId,PhysicalRef(131072,92,ref.hash))}),196608)}
        reject{RecordCodec.encodeHeader(RecordHeader(257,zone,ref.recordId,Bytes(ByteArray(12)),0))}
        reject{RootCodec.encode(RootData(1,owner,owner,ref,ref,LogicalRef(ref.recordId,ref.hash,3,zone,0),1uL,emptyList()),zone)}
        reject{SettingsCodec.encode(SettingsData(lastFolder=owner))}
        reject{ChunkListCodec.encode(ChunkListPage(0,listOf(ChunkListEntry(1,LogicalRef(ref.recordId,ref.hash,5,owner,0)))),owner)}
        val media=MediaPayload("x",1048577,2,objectRef)
        val namedMedia=ObjectData(owner,3,zone,"x".repeat(1100),icon="image",createdAt=0,contentModifiedAt=0,metadataModifiedAt=0,payload=media)
        assertEquals(namedMedia.name,ObjectCodec.decode(ObjectCodec.encode(namedMedia)).name)
        ChunkListCodec.validateMediaLengths(media,listOf(1048576,1))
        reject{ChunkListCodec.validateMediaLengths(media,listOf(1,1048576))}
        val body=NoteCodec.encode(NoteBody(listOf(NoteParagraph("😀")),listOf(NoteStyle(1,2))))
        val nr=LogicalRef(ref.recordId,ref.hash,4,owner,0)
        val payload=NotePayload(body.size.toLong(),listOf(nr),0)
        assertEquals("😀",NoteCodec.decodeChunks(payload,owner,listOf(body)).text)
        reject{NoteCodec.decodeChunks(NotePayload(body.size+1L,listOf(nr),0),owner,listOf(body))}
    }
    @Test(timeout=20000) fun randomizedValidModelsAndPublicMutation() {
        val random=Random(0x53315031)
        repeat(1000) {
            val s=SettingsData(sortMode=random.nextLong(0,0x100000000L),viewMode=random.nextLong(0,0x100000000L),sortAscending=random.nextBoolean())
            val b=SettingsCodec.encode(s);val decoded=SettingsCodec.decode(b)
            assertEquals(s.sortMode,decoded.sortMode);assertEquals(s.viewMode,decoded.viewMode);assertArrayEquals(b,SettingsCodec.encode(decoded))
            val n=NoteBody(List(random.nextInt(1,10)){NoteParagraph(listOf("", "😀", "e\u0301", " x ")[random.nextInt(4)],listOf(null,false,true)[random.nextInt(3)])},emptyList())
            assertEquals(n.text,NoteCodec.decode(NoteCodec.encode(n)).text)
        }
        val page=resource("catalogue-leaf.bin");val record=resource("record.bin")
        repeat(2000) { i ->
            val b=(if(i%2==0) page else record).clone()
            repeat(random.nextInt(1,5)){b[random.nextInt(b.size)]=random.nextInt(256).toByte()}
            try{if(i%2==0)CatalogueCodec.decode(b,196608)else RecordCodec.decode(b)}catch(_:FormatException){}
        }
        println("P1 valid/mutation seed=0x53315031 valid=2000 mutated=2000 PASS")
    }
    @Test(timeout=30000) fun maximumNoteCountsRemainBounded() {
        val body=NoteBody(List(100000){NoteParagraph(if(it==0) "a" else "")},List(100000){NoteStyle(0,1)})
        val b=NoteCodec.encode(body);assertTrue(b.size<Limits.NOTE)
        val parsed=NoteCodec.decode(b);assertEquals(100000,parsed.paragraphs.size);assertEquals(100000,parsed.styles.size)
        reject(Failure.UnsupportedLimit){NoteCodec.encode(NoteBody(List(100001){NoteParagraph("")},emptyList()))}
    }
}
