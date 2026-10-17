package com.fantadrom.copperscroll.editor

/** Session-only rich text. Todo metadata is never part of the editable text. */
data class Paragraph(val text: String = "", val checked: Boolean? = null)

data class TextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val foreground: Int? = null
)

data class StyledRange(val start: Int, val end: Int, val style: TextStyle)

data class NoteDocument(
    val paragraphs: List<Paragraph> = listOf(Paragraph()),
    val styles: List<StyledRange> = emptyList()
) {
    init {
        require(paragraphs.isNotEmpty())
        require(paragraphs.none { '\n' in it.text })
        require(styles.all { it.start >= 0 && it.end > it.start && it.end <= text.length })
    }

    val text: String get() = paragraphs.joinToString("\n") { it.text }
    val characterCount: Int get() = text.codePointCount(0, text.length)

    fun paragraphAt(offset: Int): Int {
        val position = offset.coerceIn(0, text.length)
        var start = 0
        paragraphs.forEachIndexed { index, paragraph ->
            if (position <= start + paragraph.text.length) return index
            start += paragraph.text.length + 1
        }
        return paragraphs.lastIndex
    }

    fun paragraphStart(index: Int): Int = paragraphs.take(index).sumOf { it.text.length + 1 }

    fun toggleTodo(anchor: Int, focus: Int): NoteDocument {
        val start = minOf(anchor, focus).coerceIn(0, text.length)
        val end = maxOf(anchor, focus).coerceIn(start, text.length)
        val first = paragraphAt(start)
        // An exclusive selection end at the next paragraph's start does not include it.
        val last = paragraphAt(if (end > start) end - 1 else end)
        val remove = paragraphs[first].checked != null
        return copy(paragraphs = paragraphs.mapIndexed { index, paragraph ->
            if (index !in first..last) paragraph
            else paragraph.copy(checked = if (remove) null else paragraph.checked ?: false)
        })
    }

    fun toggleChecked(index: Int): NoteDocument = copy(paragraphs = paragraphs.mapIndexed { i, p ->
        if (i == index && p.checked != null) p.copy(checked = !p.checked) else p
    })

    /** Applies an IME edit, paste, cut or selection replacement to text and metadata together. */
    fun replace(start: Int, end: Int, inserted: String): NoteDocument {
        require(start in 0..end && end <= text.length)
        val first = paragraphAt(start)
        val last = paragraphAt(end)
        val prefix = paragraphs[first].text.take(start - paragraphStart(first))
        val suffix = paragraphs[last].text.drop(end - paragraphStart(last))
        val pieces = (prefix + inserted + suffix).split('\n')
        val origin = paragraphs[first].checked
        val changed = pieces.mapIndexed { index, content ->
            // Splitting a completed todo keeps its state only on the original paragraph.
            Paragraph(content, if (index == 0) origin else origin?.let { false })
        }
        val delta = inserted.length - (end - start)
        fun mapStart(position: Int): Int = when {
            position < start -> position
            position >= end -> position + delta
            else -> start
        }
        fun mapEnd(position: Int): Int = when {
            position <= start -> position
            position >= end -> position + delta
            else -> start + inserted.length
        }
        val nextStyles = styles.mapNotNull { range ->
            val from = mapStart(range.start)
            val to = mapEnd(range.end)
            if (from < to) range.copy(start = from, end = to) else null
        }
        return NoteDocument(paragraphs.take(first) + changed + paragraphs.drop(last + 1), nextStyles)
    }
}
