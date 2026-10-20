package com.fantadrom.copperscroll.storage.format

import com.fantadrom.copperscroll.storage.io.RandomAccessStore

/** Persistent public catalogue. Inserts append only the changed path and split pages; obsolete
 * record entries are retained. No free-list reuse and no scan for record magic.
 */
class CowCatalogue(private val store: RandomAccessStore, private val zone: Bytes) {
    private fun page(ref: PhysicalRef): CataloguePage {
        ref.validate(store.size, Limits.PAGE.toLong())
        val b = store.readFullyAt(ref.offset, Limits.PAGE); checkFormat(sha(b) == ref.hash)
        return CatalogueCodec.decode(b, store.size).also { checkFormat(it.zoneId == zone) }
    }
    private fun append(level: Int, entries: List<CatalogueEntry>): CatalogueEntry {
        checkFormat(entries.isNotEmpty() && level in 0..7, Failure.UnsupportedLimit)
        val bytes = CatalogueCodec.encode(CataloguePage(zone,level,entries),store.size)
        val offset = store.appendAligned(bytes)
        return CatalogueEntry(entries.last().key, child = PhysicalRef(offset,bytes.size.toLong(),sha(bytes)))
    }
    fun insert(root: PhysicalRef?, key: Bytes, record: PhysicalRef): PhysicalRef {
        key.id(32); record.validate(store.size)
        if(root == null) return append(0,listOf(CatalogueEntry(key,record))).child!!
        fun add(ref: PhysicalRef, expected: Int?): List<CatalogueEntry> {
            val p = page(ref); checkFormat(expected == null || p.level == expected)
            val entries = p.entries.toMutableList()
            if(p.level == 0) {
                checkFormat(entries.none { it.key == key })
                entries.add(CatalogueEntry(key,record)); entries.sortBy { it.key }
            } else {
                val i = entries.indexOfFirst { key <= it.key }.let { if(it < 0) entries.lastIndex else it }
                val children = add(entries[i].child!!,p.level-1)
                entries.removeAt(i); entries.addAll(i,children)
            }
            return if(entries.size <= 128) listOf(append(p.level,entries)) else {
                val middle = entries.size/2
                listOf(append(p.level,entries.take(middle)),append(p.level,entries.drop(middle)))
            }
        }
        val level = page(root).level
        val result = add(root,null)
        return if(result.size == 1) result.single().child!! else append(level+1,result).child!!
    }
    fun build(records: List<Pair<Bytes,PhysicalRef>>): PhysicalRef {
        checkFormat(records.isNotEmpty())
        val sorted = records.sortedBy { it.first }; ordered(sorted.map { it.first })
        var pages = sorted.chunked(128).map { chunk -> append(0,chunk.map { CatalogueEntry(it.first,it.second) }) }
        var level = 0
        while(pages.size > 1) { level++; pages = pages.chunked(128).map { append(level,it) } }
        return pages.single().child!!
    }
}
