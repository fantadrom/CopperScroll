package com.fantadrom.copperscroll.session.ui

import org.junit.Test
import org.junit.Assert.*
import java.text.BreakIterator

class WordSelectionTest {
    private fun selected(s: String,p: Int)=WordSelection.range(s,p)!!.let { s.substring(it.start,it.end) }
    @Test fun legacyReceiverShadowDoesNotReadEditorText() {
        val editorText="a"
        val legacy=BreakIterator.getWordInstance().apply { setText(text.toString()) }
        val iterator=legacy.text
        val unrelated=buildString { var c=iterator.first(); while(c!=java.text.CharacterIterator.DONE) { append(c); c=iterator.next() } }
        assertNotEquals(editorText,unrelated)
        assertTrue("Legacy iterator can produce a range beyond the actual editor",legacy.following(0)>editorText.length)
        assertEquals("a",selected(editorText,0))
    }
    @Test fun wordsShorteningReplacementAndEnd() {
        assertEquals("alpha",selected("alpha beta",2)); assertEquals("beta",selected("alpha beta",10))
        assertEquals("x",selected("x",100)); assertEquals("new",selected("new text",1)); assertEquals("text",selected("new text",8))
    }
    @Test fun emptyAndWhitespace() {
        assertNull(WordSelection.range("",0)); assertEquals(" ",selected(" ",1))
        assertEquals("word",selected("word ",5))
    }
    @Test fun multilineAndCombiningTextAreNotNormalised() {
        val s="first\nsecond\nlast"
        assertEquals("second",selected(s,8)); assertEquals("last",selected(s,s.length))
        assertEquals("e\u0301clair",selected("e\u0301clair",2))
    }
    @Test fun everyOffsetOfEmojiAndSurrogatesHasValidUtf16Boundaries() {
        for(s in listOf("🔑","a 🔑","🔑 end","left 👩‍💻 right","a\n🔑\nlast","🇰🇬 end")) for(p in -1..s.length+1) {
            val r=WordSelection.range(s,p)!!
            assertTrue(r.start in 0 until r.end && r.end<=s.length)
            assertTrue(WordSelection.boundary(s,r.start)); assertTrue(WordSelection.boundary(s,r.end))
        }
        assertEquals("🔑",selected("end 🔑",6))
    }
}
