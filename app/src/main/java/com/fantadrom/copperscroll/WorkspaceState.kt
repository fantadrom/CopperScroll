package com.fantadrom.copperscroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fantadrom.copperscroll.editor.NoteDocument

val ObjectColors = listOf(
    0xFFFFFFFF, 0xFFFFF1F1, 0xFFFFE4EC, 0xFFFFE9DD,
    0xFFFFF2D6, 0xFFFFFAD1, 0xFFF2F7D5, 0xFFE4F3DA,
    0xFFDDF4E8, 0xFFDFF4F2, 0xFFE1F1FA, 0xFFE4EBFA,
    0xFFEDE6FA, 0xFFF4E5F6, 0xFFEEE9E4, 0xFFEAECEF
).map { it.toInt() }

data class FolderData(val name: String = "", val color: Int = ObjectColors.first())
data class NoteData(
    val document: NoteDocument = NoteDocument(),
    val color: Int = ObjectColors.first(),
    val pinned: Boolean = false
)

enum class SaveReason { PERIODIC, CLOSE, BACKGROUND }

/** Future persistence integration. This implementation deliberately performs no IO. */
fun interface NoteSaveHook {
    fun onSaveRequested(note: NoteData, reason: SaveReason)
}

class FolderDraft(val initial: FolderData = FolderData(), val editing: Boolean = false) {
    var input by mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial.name))
    val name: String get() = input.text
    var color by mutableStateOf(initial.color)
    var error by mutableStateOf(false)
    var choosingColor by mutableStateOf(false)
}

class NoteSession(initial: NoteData = NoteData(), private val saveHook: NoteSaveHook) {
    var data by mutableStateOf(initial)
        private set
    var choosingColor by mutableStateOf(false)
    var choosingIcon by mutableStateOf(false)
    var actionsOpen by mutableStateOf(false)
    var selectionStart = 0
    var selectionEnd = 0
    var scrollY = 0
    var editor: com.fantadrom.copperscroll.editor.RichNoteEditText? = null
    private var dirty = false

    fun update(next: NoteData) {
        if (next != data) {
            data = next
            dirty = true
        }
    }

    fun checkpoint(reason: SaveReason) {
        if (dirty || reason != SaveReason.PERIODIC) {
            saveHook.onSaveRequested(data, reason)
            dirty = false
        }
    }
}

/** Survives Activity recreation only. No SavedStateHandle, files, preferences or logging. */
class WorkspaceState : ViewModel() {
    var launched by mutableStateOf(false)
    var addMenuOpen by mutableStateOf(false)
    var folder by mutableStateOf<FolderDraft?>(null)
    var note by mutableStateOf<NoteSession?>(null)
    var saveHook: NoteSaveHook = NoteSaveHook { _, _ -> }

    fun openFolder(initial: FolderData = FolderData(), editing: Boolean = false) {
        folder = FolderDraft(initial, editing)
        addMenuOpen = false
    }

    fun openNote(initial: NoteData = NoteData()) {
        note = NoteSession(initial, saveHook)
        addMenuOpen = false
    }

    fun closeNote() {
        note?.checkpoint(SaveReason.CLOSE)
        note = null
    }
}
