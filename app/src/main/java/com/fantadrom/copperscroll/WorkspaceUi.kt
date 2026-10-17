package com.fantadrom.copperscroll

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fantadrom.copperscroll.editor.NoteTextArea
import kotlinx.coroutines.delay

@Composable
fun WorkspaceScreen(state: WorkspaceState) {
    LaunchedEffect(state) {
        if (!state.launched) {
            androidx.compose.runtime.withFrameNanos { }
            delay(500)
            state.launched = true
        }
    }
    Column(Modifier.fillMaxSize().background(colorResource(R.color.screen_background)).safeDrawingPadding()) {
        if (!state.launched) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.startup_loading), color = Color.Black)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), Modifier.weight(1f), color = Color.Black)
                Box {
                    ToolIcon(R.drawable.ic_add, stringResource(R.string.create_object), { state.addMenuOpen = true })
                    DropdownMenu(state.addMenuOpen, { state.addMenuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_folder)) }, onClick = { state.openFolder() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_note)) }, onClick = { state.openNote() })
                    }
                }
            }
            // The session has no file system or demonstration objects.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.startup_done), color = Color.Black)
            }
        }
    }
    state.folder?.let { draft -> FolderDialog(draft, { state.folder = null }, { state.folder = null }) }
    state.note?.let { session -> NoteEditorDialog(session, state::closeNote) }
}

@Composable
private fun ToolIcon(resource: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(resource), contentDescription = description, tint = Color.Black, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ColorButton(color: Int, description: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(26.dp).background(Color(color)).border(1.dp, Color.DarkGray))
    }
}

@Composable
fun ColorPicker(selectedColor: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_color)) },
        text = {
            Column {
                ObjectColors.chunked(4).forEachIndexed { row, colors ->
                    Row {
                        colors.forEachIndexed { column, color ->
                            val index = row * 4 + column
                            val description = stringResource(R.string.color_number, index + 1)
                            Box(Modifier.size(48.dp).clickable(role = Role.Button) { onSelect(color) }
                                .semantics { contentDescription = description; selected = color == selectedColor }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(32.dp).background(Color(color)).border(
                                    if (color == selectedColor) 3.dp else 1.dp,
                                    if (color == selectedColor) Color.Black else Color.Gray
                                ))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun FolderDialog(draft: FolderDraft, onDismiss: () -> Unit, onConfirm: (FolderData) -> Unit) {
    val focus = remember(draft) { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (draft.editing) R.string.edit_folder else R.string.new_folder)) },
        text = {
            Row(verticalAlignment = Alignment.Top) {
                ColorButton(draft.color, stringResource(R.string.folder_color)) { draft.choosingColor = true }
                OutlinedTextField(
                    value = draft.input,
                    onValueChange = { draft.input = it; draft.error = false },
                    label = { Text(stringResource(R.string.folder_name)) },
                    singleLine = true,
                    isError = draft.error,
                    supportingText = if (draft.error) ({ Text(stringResource(R.string.folder_name_required)) }) else null,
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (draft.name.isBlank()) {
                    draft.error = true
                    focus.requestFocus()
                } else onConfirm(FolderData(draft.name, draft.color))
            }) { Text(stringResource(if (draft.editing) R.string.save else R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
    if (draft.choosingColor) ColorPicker(draft.color, {
        draft.color = it
        draft.choosingColor = false
    }, { draft.choosingColor = false })
}

@Composable
fun NoteEditorDialog(session: NoteSession, onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(session) {
        while (true) {
            delay(7_000)
            session.checkpoint(SaveReason.PERIODIC)
        }
    }
    Dialog(
        onDismissRequest = {
            val editor = session.editor
            if (editor?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) {
                context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(editor.windowToken, 0)
            } else onClose()
        },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // The outside box receives safe-area and IME insets before allocating 98% in each dimension.
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize(.98f), shape = RoundedCornerShape(8.dp), color = Color.White, contentColor = Color.Black) {
                Column {
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolIcon(R.drawable.ic_note, stringResource(R.string.note_icon)) { session.choosingIcon = true }
                        ColorButton(session.data.color, stringResource(R.string.note_color)) { session.choosingColor = true }
                        IconToggleButton(checked = session.data.pinned, onCheckedChange = { session.update(session.data.copy(pinned = it)) }) {
                            Icon(painterResource(R.drawable.ic_pin), stringResource(R.string.pin_note),
                                tint = if (session.data.pinned) Color(0xFF245DA8) else Color.DarkGray,
                                modifier = Modifier.size(24.dp).background(if (session.data.pinned) Color(0xFFDCEAFF) else Color.Transparent))
                        }
                        Box {
                            ToolIcon(R.drawable.ic_more, stringResource(R.string.note_actions)) { session.actionsOpen = true }
                            DropdownMenu(session.actionsOpen, { session.actionsOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.move_note)) }, onClick = { session.actionsOpen = false })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete_note)) }, onClick = { session.actionsOpen = false })
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        ToolIcon(R.drawable.ic_close, stringResource(R.string.close_editor)) {
                            session.editor?.let { editor ->
                                (context.getSystemService(InputMethodManager::class.java)).hideSoftInputFromWindow(editor.windowToken, 0)
                            }
                            onClose()
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        TextButton(onClick = { session.editor?.toggleSelectedTodos() }) {
                            Text(stringResource(R.string.checkbox))
                        }
                    }
                    AndroidView(
                        factory = { NoteTextArea(it) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onRelease = { area -> if (session.editor === area.editor) session.editor = null },
                        update = { area -> area.bind(session); session.editor = area.editor }
                    )
                    Text(stringResource(R.string.character_count, session.data.document.characterCount),
                        color = Color.Black, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (session.choosingColor) ColorPicker(session.data.color, {
        session.update(session.data.copy(color = it))
        session.choosingColor = false
    }, { session.choosingColor = false })
    if (session.choosingIcon) {
        Dialog(onDismissRequest = { session.choosingIcon = false }) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                .clickable { session.choosingIcon = false }) {
                Text(stringResource(R.string.choose_note_icon), modifier = Modifier.padding(24.dp))
            }
        }
    }
}
