# Session-only note editor

## Implementation

The root Activity owns a `WorkspaceState` ViewModel. It retains open dialogs,
folder `TextFieldValue` (including selection), note data, cursor/selection and
scroll position across Activity recreation. Nothing is persisted to a
SavedStateHandle, files, preferences or logs. Closing a note discards its session.
New notes and folder drafts always start with white backgrounds.

`NoteDocument` is an immutable list of paragraphs plus ranges of `TextStyle`.
A paragraph's nullable `checked` field distinguishes ordinary text from unchecked
or checked todos. These flags are never inserted into the text. Offsets use
Android's UTF-16 convention; the user-visible counter counts Unicode code points,
including spaces and newlines.

`RichNoteEditText` uses the platform EditText, TextWatcher and managed spans. It
keeps native Android cursor movement, selection, clipboard and IME behavior.
Paragraph LeadingMarginSpans render stateful Android checkbox drawables in a
reserved gutter; hit testing toggles the corresponding document metadata. The
checkbox at the cursor also exposes a TalkBack action. Styled ranges render as
native bold/italic/underline/color spans and are remapped when text is edited.
Future formatting controls can operate on those ranges without replacing the
input component. No new dependencies were added.

The separate scroll rail supports dragging, track taps and accessibility scroll
actions. It never overlaps the text or checkbox gutter. Toolbars and the character
count sit outside the text view. The dialog uses 98% of the available width and
height (96.04% of area), after system/IME insets. The IME is configured not to use
fullscreen extract mode in landscape.

`NoteSaveHook` is the only future storage integration point. The session sends
the current immutable NoteData every seven seconds if dirty, on close, and on
Activity stop excluding configuration changes. The default hook does nothing;
there is no storage implementation or success notification.

## Verification

Run from the repository root with the environment's Android/JDK tools activated:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

Unit tests cover selection boundaries (including reversed selections), mixed
paragraph states, checkbox toggling, Enter/split/join, multiline replacements,
character counts, style remapping, default colors, draft isolation and hook
notifications. These tests do not prove Android keyboard, gesture or rotation
behavior. The device checklist below is intentionally separate.

## Device checklist (portrait and landscape)

- Open `+` → `Новая папка`. Reject an empty or whitespace-only name with an error
  and focus on the name. A nonempty name closes the dialog without creating an
  entry. Cancel, Back and an outside tap discard the draft.
- Select each of the 16 colors. Selection closes the picker; cancel preserves the
  old color. Reopening a new folder/note starts white. Rotate with a name and
  selection or the picker open and confirm the draft is retained.
- Open `+` → `Новая заметка`. Check the icon placeholder dialog, color picker,
  pin state, actions menu placeholders and close button. Outside taps must not
  dismiss the note. Back with a visible keyboard should first hide the keyboard.
- Type multiline text. Select, copy, cut and paste using native Android actions.
  Confirm spaces and newlines affect the count and toggling todos does not.
- Use `Чек-бокс` on a cursor line and on mixed selections. Test both first-line
  states and a selection ending exactly at the next line's start. Selection must
  remain logically unchanged. Toggle completed todos directly in the gutter.
- Press Enter in the middle/end of ordinary and completed todo paragraphs, and
  repeatedly in an empty todo. New todo paragraphs must be unchecked; the source
  state and moved suffix must be preserved.
- Create a long note; scroll by touch and drag the rail thumb. Confirm the rail
  disappears for short content and the footer remains visible.
- Rotate with text selected, a nonwhite note, checked todos, a pinned note and
  open child dialogs. Check content, selection, scroll and open windows.
- Repeat with the keyboard visible in landscape. Toolbars, editor and footer
  must remain accessible; Android must not replace the editor with extract UI.
- Close and reopen the editor: no prior note or sample file should appear.
