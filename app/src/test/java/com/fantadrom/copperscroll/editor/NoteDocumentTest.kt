package com.fantadrom.copperscroll.editor

import org.junit.Assert.*
import org.junit.Test

class NoteDocumentTest {
    private fun doc(vararg lines: Paragraph) = NoteDocument(lines.toList())

    @Test fun emptyDocumentHasNoCharactersAndCanBecomeTodo() {
        val empty = NoteDocument()
        assertEquals(0, empty.characterCount)
        val todo = empty.toggleTodo(0, 0)
        assertEquals(false, todo.paragraphs.single().checked)
        assertEquals("", todo.text)
        assertEquals(0, todo.characterCount)
        assertEquals(empty, todo.toggleTodo(0, 0))
    }

    @Test fun cursorInSecondLineChangesOnlyThatLine() {
        val result = doc(Paragraph("one"), Paragraph("two"), Paragraph("three")).toggleTodo(5, 5)
        assertEquals(listOf(null, false, null), result.paragraphs.map { it.checked })
        assertEquals("one\ntwo\nthree", result.text)
    }

    @Test fun mixedSelectionStartingWithPlainTextPreservesCompletedTodo() {
        val original = doc(Paragraph("one"), Paragraph("two", true), Paragraph("three", false))
        val result = original.toggleTodo(1, 11)
        assertEquals(listOf(false, true, false), result.paragraphs.map { it.checked })
        assertEquals(original.text, result.text)
    }

    @Test fun mixedSelectionStartingWithTodoRemovesAllCheckboxes() {
        val original = doc(Paragraph("one", true), Paragraph("two"), Paragraph("three", false))
        val result = original.toggleTodo(2, original.text.length)
        assertTrue(result.paragraphs.all { it.checked == null })
        assertEquals(original.text, result.text)
    }

    @Test fun selectionEndingAtNextLineStartDoesNotIncludeIt() {
        val original = doc(Paragraph("one"), Paragraph("two", true))
        assertEquals(listOf(false, true), original.toggleTodo(0, 4).paragraphs.map { it.checked })
    }

    @Test fun reversedSelectionHasSameExclusiveEndBehavior() {
        val original = doc(Paragraph("one"), Paragraph("two"), Paragraph("three"))
        assertEquals(listOf(false, false, null), original.toggleTodo(8, 2).paragraphs.map { it.checked })
    }

    @Test fun selectionIncludingOneCharacterOfNextLineIncludesThatLine() {
        val original = doc(Paragraph("one"), Paragraph("two"))
        assertEquals(listOf(false, false), original.toggleTodo(2, 5).paragraphs.map { it.checked })
    }

    @Test fun toggleCheckedPreservesAllTextAndOtherTodos() {
        val original = doc(Paragraph("a", false), Paragraph("b", true), Paragraph("c"))
        val result = original.toggleChecked(0)
        assertEquals(listOf(true, true, null), result.paragraphs.map { it.checked })
        assertEquals(original.text, result.text)
        assertEquals(result, result.toggleChecked(2))
    }

    @Test fun enterSplitsCompletedTodoAndCreatesUncheckedTodo() {
        val original = doc(Paragraph("abcd", true), Paragraph("next", true))
        val result = original.replace(2, 2, "\n")
        assertEquals(listOf(Paragraph("ab", true), Paragraph("cd", false), Paragraph("next", true)), result.paragraphs)
    }

    @Test fun enterAtEndOfEmptyTodoAlwaysCreatesAnotherTodo() {
        val result = doc(Paragraph("", true)).replace(0, 0, "\n").replace(1, 1, "\n")
        assertEquals(listOf(Paragraph("", true), Paragraph("", false), Paragraph("", false)), result.paragraphs)
        assertEquals(2, result.characterCount)
    }

    @Test fun enterInOrdinaryParagraphCreatesOrdinaryParagraph() {
        val result = doc(Paragraph("abcd")).replace(2, 2, "\n")
        assertEquals(listOf(Paragraph("ab"), Paragraph("cd")), result.paragraphs)
    }

    @Test fun joiningLinesPreservesTheFirstParagraphMetadata() {
        val result = doc(Paragraph("a", true), Paragraph("b", false)).replace(1, 2, "")
        assertEquals(listOf(Paragraph("ab", true)), result.paragraphs)
    }

    @Test fun replacingSelectionAcrossParagraphsKeepsSuffixAndUnchangedFollowingParagraph() {
        val original = doc(Paragraph("first", true), Paragraph("second"), Paragraph("third", true))
        val result = original.replace(2, 9, "X\nY")
        assertEquals(listOf(Paragraph("fiX", true), Paragraph("Yond", false), Paragraph("third", true)), result.paragraphs)
    }

    @Test fun multilinePasteCreatesTodosOnlyInsideTodoParagraph() {
        val todo = doc(Paragraph("x", false)).replace(1, 1, "\na\nb")
        assertEquals(listOf(false, false, false), todo.paragraphs.map { it.checked })
        val plain = NoteDocument().replace(0, 0, "a\nb")
        assertTrue(plain.paragraphs.all { it.checked == null })
    }

    @Test fun counterIncludesSpacesAndNewlinesButNotMetadata() {
        val original = doc(Paragraph("a b", true), Paragraph("🙂", false), Paragraph(""))
        assertEquals(6, original.characterCount)
        assertEquals(original.characterCount, original.toggleTodo(0, original.text.length).characterCount)
    }

    @Test fun textReplacementMatchesPlainTextForEveryRange() {
        val original = doc(Paragraph("one", true), Paragraph(""), Paragraph("two", false))
        for (start in 0..original.text.length) for (end in start..original.text.length) {
            for (insert in listOf("", "x", "\n", "a\n\nb")) {
                assertEquals(original.text.replaceRange(start, end, insert), original.replace(start, end, insert).text)
            }
        }
    }

    @Test fun formattingMovesWithTextAndSurvivesTodoChanges() {
        val original = NoteDocument(listOf(Paragraph("abcd")), listOf(StyledRange(1, 3, TextStyle(bold = true))))
        val shifted = original.replace(0, 0, "XX")
        assertEquals(StyledRange(3, 5, TextStyle(bold = true)), shifted.styles.single())
        assertEquals(shifted.styles, shifted.toggleTodo(0, 0).styles)
    }

    @Test fun formattingCoversInsertedTextInsideRangeAndIsRemovedWithItsText() {
        val original = NoteDocument(listOf(Paragraph("abcd")), listOf(StyledRange(0, 4, TextStyle(italic = true))))
        assertEquals(5, original.replace(2, 2, "X").styles.single().end)
        assertTrue(original.replace(0, 4, "").styles.isEmpty())
    }
}
