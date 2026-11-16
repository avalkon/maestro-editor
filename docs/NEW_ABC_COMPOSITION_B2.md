# Maestro-Editor 4.7.7-B2 — New ABC Composition and Edited-Note Preview

This document describes the Beta 2 additions to Maestro-Editor, built on the existing Maestro codebase. The original MIDI-to-ABC workflow, user documentation, authorship notes, resources and launchers remain in place.

## Starting a song from scratch

1. Open **File → New ABC Composition…**.
2. Enter a title, optional composer, BPM, time signature, musical key, initial number of measures (1–2000), and a starting LOTRO instrument.
3. Maestro opens a new project in its **Piano Roll** view. The selected instrument part is visible even though it has no notes yet.
4. Add notes with the normal piano-roll tools. The existing snapping, marquee selection, velocity, Ctrl+A/C/X/V, undo/redo, instrument audition and keyboard shortcuts still apply.
5. Use **New Part** / the **Song Parts** list to add and select more instrument parts. Each newly added empty part can be selected and composed in the piano roll. Enable **Display all parts** for a multicolor overview.
6. Switch to **ABC Preview** to see and play the current final parts; authored notes from different parts appear on separate part tracks.
7. Save with **File → Save** to create an `.msx` project, and use the normal **ABC Export** action to produce a LOTRO `.abc` file. Reopening the saved `.msx` restores the song and its note edits without relying on the transient starter ABC file.

The New ABC flow creates a **rest-only ABC scaffold** to initialize Maestro's existing timeline and metadata. It **does not generate a dummy MIDI file**. The scaffold's content is stored in the `.msx` project; the temporary starter file is deleted. The original composition can be reopened without an external ABC source file.

## Composition and imported projects: different empty-part behavior

- **Normal imported projects:** Maestro's original exporter still omits empty or disabled tracks. Simply adding an empty Song Part does **not** make it appear in the exported ABC. This remains true even if you enable Display all parts in the piano roll.
- **Authored notes in imported projects:** If you draw notes into a part that otherwise has no generated notes, a distinct preview track is created **only while that part has authored notes**. The authored part is also included in exported ABC. Notes edited in a part that already has a generated track replace/update that part's existing preview track and graph rather than duplicating its sound.
- **New ABC compositions:** Empty instrument parts remain selectable and saved in the `.msx`. The preview only creates note tracks once a part contains authored notes. If you export a completely blank new composition, it exports one empty part as a valid ABC starter; no other empty parts are added.

**Do not confuse the temporary rest-only editor baselines with generated ABC tracks:** The baselines exist to make an empty part selectable and do not change Maestro's normal export behavior.

## Synchronizing preview tracks

Changes in Piano Roll are represented by the existing per-part note-delta system. The ABC preview overlay builds the **final note set** for each affected part and updates both:

1. The MIDI playback track used by Maestro's ABC Preview.
2. The `AbcExporter.ExportTrackInfo.noteEvents` list used to draw the part's notes in the ABC Preview graphical track.

When a part previously had **no** generated track, but has authored notes, the overlay adds one preview track for that part with its assigned LOTRO instrument program. Empty parts are never created as audible tracks merely because they are listed in the Song Parts box.

The editor remains **source MIDI independent** in ABC composition mode; ordinary Original MIDI editing and existing MIDI-to-ABC conversion are unchanged.

## Musical timing, pitch and key

- Piano Roll uses the existing 960-PPQ edit grid; snapping values refer to **fractions of a measure**, based on the selected meter.
- The initial measure count sets a usable empty timeline width before the song contains any notes.
- The selected New ABC key is stored separately in composition metadata to accommodate an upstream Maestro build configuration that disables the legacy key field. It is reproduced in created ABC parts and retained through `.msx` save/reopen.
- The editor retains the corrected LOTRO-specific instrument octave interpretation and the strict ~60 ms minimum duration safeguards introduced in previous betas.
- For instrument or range issues, consult the existing range audit and the Beta 1 User Manual.

## Saving and backward compatibility

New projects use optional `composition` information inside the `.msx`, alongside the already-established optional `noteEditor` entries. Existing `.msx` files do not need conversion. Existing imported-project exports that have no editor changes still follow the exact upstream export path.

The existing author/contributor records, manuals, licenses and bundled audio assets from the uploaded source have not been replaced. The application version is `4.7.7-B2`; original source history continues to document earlier releases as before.

## Known Beta 2 limitations and validation

- Heavily regenerated imported arrangements can still create **note-delta matching conflicts**; check the result after changing many source tracks or quantization settings.
- An empty new composition has no actual notes to play until notes are authored.
- The legacy blank-file scaffold is visible only as empty source timing, not as a MIDI composition.
- Preview-channel assignments may reuse a channel when many parts use different instruments; test exceptionally large multi-instrument preview arrangements.
- Importing complex unsupported ABC notation into the standalone editor remains subject to its conservative parser restrictions.
- Test exported files in ABC Player and make project backups while using this beta.

## Building

With **Java 21** and **Maven** installed, from the extracted `maestro-editor` project directory:

```bash
mvn clean test package
```

The expected JARs are `target/Maestro.jar`, `target/AbcPlayer.jar`, and `target/AbcTools.jar`. Reuse your existing working Windows launchers and bundled runtime with freshly built JARs; this update does **not** include replacement `.exe` files. Windows launchers must be rebuilt with the Windows `jpackage` tool if you want new Windows executable metadata.

For editor-only tests (no Maven):

```bash
bash tools/note-editor/test-editor-only.sh
```

The source also includes the JUnit 5 `CompositionIntegrationTest`, which checks blank composition playback preview, one track per authored part, key persistence, self-contained `.msx` reload, and the continued omission of empty tracks in imported projects.
