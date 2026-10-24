package com.fantadrom.copperscroll.session.ui

import java.text.BreakIterator

/** UTF-16 offsets into exactly the supplied immutable snapshot; no View or mutable iterator text. */
object WordSelection {
    data class Range(val start: Int,val end: Int)
    fun range(snapshot: String,position: Int): Range? {
        if(snapshot.isEmpty()) return null
        var anchor=position.coerceIn(0,snapshot.length)
        if(anchor==snapshot.length) anchor=Character.offsetByCodePoints(snapshot,anchor,-1)
        if(anchor>0 && Character.isLowSurrogate(snapshot[anchor]) && Character.isHighSurrogate(snapshot[anchor-1])) anchor--
        val words=BreakIterator.getWordInstance()
        words.setText(snapshot) // Explicit argument: inside BreakIterator.apply, `text` is its CharacterIterator!
        var end=words.following(anchor)
        if(end==BreakIterator.DONE) end=snapshot.length
        var start=words.preceding(end)
        if(start==BreakIterator.DONE) start=0
        if(snapshot.substring(start,end).all { it.isWhitespace() } && start>0) {
            end=start; start=words.preceding(end).let { if(it==BreakIterator.DONE) 0 else it }
        }
        if(!boundary(snapshot,start)) start--
        if(!boundary(snapshot,end)) end++
        check(start in 0 until end && end<=snapshot.length)
        return Range(start,end)
    }
    fun boundary(snapshot: String,offset: Int)=offset in 0..snapshot.length &&
        !(offset>0 && offset<snapshot.length && Character.isHighSurrogate(snapshot[offset-1]) && Character.isLowSurrogate(snapshot[offset]))
}
