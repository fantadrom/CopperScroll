package com.fantadrom.copperscroll

import com.fantadrom.copperscroll.editor.NoteDocument
import org.junit.Assert.*
import org.junit.Test

class WorkspaceStateTest {
    @Test fun folderWhitespaceValidationIncludesUnicodeSpaces() {
        listOf("", " ", "\t\n", "\u00a0", "\u2003").forEach { assertTrue(it.isBlank()) }
        assertFalse(" Folder ".isBlank())
    }

    @Test fun paletteHasSixteenDistinctColorsAndStartsWhite() {
        assertEquals(16, ObjectColors.size)
        assertEquals(16, ObjectColors.toSet().size)
        assertEquals(-1, ObjectColors.first())
    }

    @Test fun cancelingDraftCannotChangeOriginalAndNewCreationStartsWhite() {
        val original = FolderData("Original", ObjectColors[3])
        val draft = FolderDraft(original, editing = true)
        draft.color = ObjectColors[7]
        assertEquals(ObjectColors[3], original.color)
        assertEquals(-1, FolderDraft().color)
        assertEquals(-1, NoteData().color)
        assertFalse(NoteData().pinned)
    }

    @Test fun hooksReceiveCurrentDocumentOnlyWhenPeriodicDirtyAndAlwaysAtBoundaries() {
        val events = mutableListOf<Pair<NoteData, SaveReason>>()
        val session = NoteSession(saveHook = NoteSaveHook { note, reason -> events += note to reason })
        session.checkpoint(SaveReason.PERIODIC)
        assertTrue(events.isEmpty())
        val changed = session.data.copy(document = NoteDocument().replace(0, 0, "latest"))
        session.update(changed)
        session.checkpoint(SaveReason.PERIODIC)
        session.checkpoint(SaveReason.PERIODIC)
        assertEquals(listOf(changed to SaveReason.PERIODIC), events)
        session.checkpoint(SaveReason.BACKGROUND)
        session.checkpoint(SaveReason.CLOSE)
        assertEquals(listOf(SaveReason.PERIODIC, SaveReason.BACKGROUND, SaveReason.CLOSE), events.map { it.second })
        assertTrue(events.all { it.first == changed })
    }
}
