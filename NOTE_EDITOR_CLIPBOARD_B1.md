# Maestro-Editor 4.7.7-B1 — Piano-roll clipboard update

## New commands

| Command | Default shortcut | Behavior |
|---|---|---|
| Select All | Ctrl+A | Selects all notes in the selected part, or all parts when **Display all parts** is checked |
| Copy | Ctrl+C | Copies the selected notes with their pitch, velocity, MIDI channel, duration, part identity, and relative timing |
| Cut | Ctrl+X | Copies then deletes selected notes as one undoable action |
| Paste | Ctrl+V | Inserts notes starting at the current **playhead**, aligned to the current snap grid |

Commands are available in **Piano Roll Edit** and in **Tools → Options → Keyboard Shortcuts**. Their shortcuts can be remapped; the menu displays the configured shortcuts. The standalone editor uses Ctrl+A/C/X/V while its piano roll has focus.

### Paste rules

- Single-part selections paste into whichever MIDI track or final ABC Song Part is currently selected.
- In **Display all parts** mode, copying notes across multiple parts retains their respective ABC `X:` part numbers (or MIDI track indices). If the destination is missing a required part, nothing is pasted.
- Paste keeps the notes' relative start positions and durations. Timing scales to the destination's PPQ if necessary; original note spacing is not forcibly quantized.
- Every paste generates new note identifiers, selects the new notes, participates in Undo/Redo, and runs the existing live preview/export and `.msx` change notifications.
- Clipboard data uses a versioned plain-text format shared through the desktop clipboard. Unrelated clipboard text is ignored. If the OS clipboard is unavailable, a local fallback is used.
- These operations copy **notes only**, not source MIDI controllers, tempo events, or separate LOTRO part settings.

### Examples

1. Select a Song Part, press Ctrl+A then Ctrl+C.
2. Select the destination Song Part, scrub the playhead to the desired measure, and press Ctrl+V.
3. Use Undo (Ctrl+Z by default) if the paste is not where you intended.

For multi-part copying, tick **Display all parts**, select notes across the desired parts (or Ctrl+A), copy, seek the playhead and paste. The notes will remain assigned to their original parts.

## Build / apply

Apply the patch **from the directory containing `maestro2/`**, not from inside `maestro2/`:

```bash
git apply --check Maestro-Editor-4.7.7-B1-copy-paste.patch
git apply Maestro-Editor-4.7.7-B1-copy-paste.patch
cd maestro2
mvn clean test package
```

To test only the self-contained editor, without Maven:

```bash
bash tools/note-editor/test-editor-only.sh
java -jar target/MaestroNoteEditor.jar
```

The existing version remains `4.7.7-B1`; no EXE or `.msx` format changes are necessary. Rebuild `Maestro.jar` and place it in your existing portable app layout. **Do not replace the working Windows launchers with the earlier experimental binary-patched EXEs.**

## Validation performed

- Java 21 editor build and all editor regression tests, including clipboard tests
- Shortcut registry tests and Swing focus tests under a virtual display
- Modified `ProjectFrame.java` compiled against release JAR dependencies and source tree
- Patch application against the original `4.7.7-B1` source archive

A complete Maven build and on-device audible application test must still be run on a machine with Maven and the required dependencies.
