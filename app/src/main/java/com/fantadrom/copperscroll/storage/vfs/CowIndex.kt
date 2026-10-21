package com.fantadrom.copperscroll.storage.vfs

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.transaction.ZoneWriteContext

/** The logical index updates only paths touched by this transaction. Removed records remain in
 * the append-only public catalogue. Root collapse/empty leaf never rewrites previous pages.
 */
internal class CowIndex(private val context: ZoneWriteContext, private val type: Int) {
    private fun page(ref: LogicalRef): IndexPage {
        ref.validate(2,context.zone,0); val b = context.read(ref)
        return try { IndexCodec.decode(b,context.zone).also { checkFormat(it.type == type) } } finally { b.fill(0) }
    }
    private fun append(level: Int, entries: List<IndexEntry>): IndexEntry {
        checkFormat(level in 0..7,Failure.UnsupportedLimit)
        val ref = context.record(2,context.zone,0,IndexCodec.encode(IndexPage(type,level,entries),context.zone))
        return IndexEntry(entries.lastOrNull()?.key ?: IndexKey(context.zone,if(type == 2) context.zone else null),ref)
    }
    fun put(root: LogicalRef, key: IndexKey, value: LogicalRef?): LogicalRef = change(root,key,value,false)
    fun remove(root: LogicalRef, key: IndexKey): LogicalRef = change(root,key,null,true)
    private fun change(root: LogicalRef, key: IndexKey, value: LogicalRef?, remove: Boolean): LogicalRef {
        fun edit(ref: LogicalRef, expected: Int?): List<IndexEntry> {
            val p = page(ref); checkFormat(expected == null || p.level == expected)
            val entries = p.entries.toMutableList()
            if(p.level == 0) {
                val i = entries.indexOfFirst { it.key.compareTo(key) == 0 }
                if(i >= 0) entries.removeAt(i) else if(remove) return listOf(IndexEntry(entries.lastOrNull()?.key ?: key,ref))
                if(!remove) entries.add(IndexEntry(key,value))
                entries.sortBy { it.key }
            } else {
                val i = entries.indexOfFirst { key <= it.key }.let { if(it < 0) entries.lastIndex else it }
                val child = edit(entries[i].value!!,p.level-1)
                entries.removeAt(i); entries.addAll(i,child)
            }
            if(entries.isEmpty()) return emptyList()
            return if(entries.size <= 64) listOf(append(p.level,entries)) else {
                val middle = entries.size/2
                listOf(append(p.level,entries.take(middle)),append(p.level,entries.drop(middle)))
            }
        }
        val level = page(root).level
        val result = edit(root,null)
        if(result.isEmpty()) return append(0,emptyList()).value!!
        var next = if(result.size == 1) result.single().value!! else append(level+1,result).value!!
        while(true) {
            val p = page(next)
            if(p.level == 0 || p.entries.size != 1) return next
            next = p.entries.single().value!!
        }
    }
}
