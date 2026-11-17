package com.digero.maestro.noteedit;

import com.digero.common.abc.LotroInstrument;
import com.digero.common.i18n.LocaleManager;
import com.digero.common.i18n.UIText;
import com.digero.maestro.abc.*;
import com.digero.maestro.view.*;
import com.digero.maestro.util.FileResolver;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;

/** Regression coverage for composition identity, undo across refreshes and note-cell placement. */
public final class CompositionEditingRegressions {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static NoteDocument doc(NoteEditorApp.EditorTab tab) {
        try {
            var f = NoteEditorApp.EditorTab.class.getDeclaredField("doc");
            f.setAccessible(true);
            return (NoteDocument)f.get(tab);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static NoteDocument.Layer layer(NoteDocument doc, int x) {
        return doc.layers.stream().filter(p -> p.partNumber == x).findFirst().orElseThrow();
    }
    private static String abc(int... numbers) {
        StringBuilder source = new StringBuilder("%abc-2.1\n");
        for (int x:numbers) source.append(CompositionAbc.header(new CompositionAbc.PartSpec(
            x, "Lute " + x, "Lute of Ages", 120, "4/4", "C"))).append("z8 |]\n\n");
        return source.toString();
    }
    private static void undoAfterRegeneration() throws Exception {
        Path file = Files.createTempFile("composition-undo-", ".abc");
        try {
            Files.writeString(file, abc(1,11));
            NoteEditorApp.EditorTab editor = new NoteEditorApp.EditorTab(NoteDocument.Kind.ABC, null, null);
            editor.load(file.toFile());
            NoteDocument before=doc(editor);
            before.edit(0,list -> list.add(EditableNote.create(64,90,0,960,1920)));
            check(before.canUndo(),"Edit adds an undo step");
            Map<Integer,NoteEditorDelta.Part> changed=NoteEditorDelta.capture(before);
            check(changed.containsKey(1),"Edited source X:1 captured");

            // A generated arrangement reorders parts and updates a title. Old
            // undo stacks were tied to layer indices and were silently lost.
            String refreshed=abc(11,1).replace("T: Lute 1\n","T: A renamed Lute\n");
            editor.reloadGeneratedAbc(refreshed,changed);
            NoteDocument after=doc(editor);
            check(after != before,"Non-identical generated baseline triggers a new editor document");
            check(after.canUndo(),"Undo survives generation and part reorder");
            check(layer(after,1).notes.size()==1,"Edited note remains in X:1");
            check(layer(after,11).notes.isEmpty(),"X:11 remains independent");
            editor.undoFromShortcut();
            check(layer(doc(editor),1).notes.isEmpty(),"Undo removes the X:1 authored note");
            check(doc(editor).canRedo(),"Redo survives undo after regeneration");
            editor.redoFromShortcut();
            check(layer(doc(editor),1).notes.size()==1,"Redo restores authored note to X:1");
            check(layer(doc(editor),11).notes.isEmpty(),"Undo/redo must not edit X:11");
            // New X: parts also must not discard existing steps.
            editor.reloadGeneratedAbc(abc(11,1,21),NoteEditorDelta.capture(doc(editor)));
            check(doc(editor).canUndo(),"Adding an editor part does not erase undo history");
            editor.undoFromShortcut();
            check(layer(doc(editor),1).notes.isEmpty(),"Undo still targets X:1 after a new part");
        } finally { Files.deleteIfExists(file); }
    }
    private static void gridCellPlacement() throws Exception {
        NoteDocument document=AbcDocumentIO.readText(abc(1));
        PianoRollPanel roll=new PianoRollPanel();
        roll.setDocument(document,0);
        roll.setSnapDivisor(4); // 4/4 quarter-note cells, 85 pixels per quarter
        roll.setSize(1200,2800);
        int[] x={64,64+42,64+84,64+85,64+85+42,64+169,64+170};
        long[] expected={0,0,0,960,960,960,1920};
        for (int i=0; i<x.length; i++) {
            int y=1100+i*19;
            roll.dispatchEvent(new MouseEvent(roll,MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),0,x[i],y,2,false,MouseEvent.BUTTON1));
            long actual = document.layer(0).notes.getLast().start();
            check(actual==expected[i], "Cell x="+x[i]+" should start at "+expected[i]+", got "+actual);
        }
        check(document.layer(0).notes.size()==x.length,"Seven distinct note clicks");
    }
    private static void stableCompositionNumbers() throws Exception {
        Preferences prefs=Preferences.userRoot().node("comp-identity-regression-"+UUID.randomUUID());
        Path source=Files.createTempFile("comp-identity-", ".abc");
        try {
            String starter=CompositionAbc.starter("Identity",120,"4/4","C","Lute of Ages",4);
            Files.writeString(source,starter);
            PartAutoNumberer numberer=new PartAutoNumberer(prefs.node("numbers"));
            FileResolver resolver=new FileResolver() {
                public File locateFile(File file,String message) {throw new AssertionError(file);}
                public File resolveFile(File file,String message) {throw new AssertionError(file);}
            };
            AbcSong song=new AbcSong(source.toFile(),numberer,
                new PartNameTemplate(prefs.node("names")),
                new ExportFilenameTemplate(prefs.node("export")),
                new InstrNameSettings(prefs.node("instruments")),resolver,
                new MiscSettings(prefs.node("misc"),false),
                new SaveAndExportSettings(prefs.node("save")));
            if (song.getParts().isEmpty()) song.createNewPart();
            song.initializeComposition("Identity",starter,4);
            AbcPart first=song.getParts().getFirst();
            first.setInstrument(LotroInstrument.LUTE_OF_AGES);
            AbcPart second=song.createNewPart();
            second.setInstrument(LotroInstrument.LUTE_OF_AGES);
            int x1=first.getPartNumber(), x2=second.getPartNumber();
            check(x1 != x2,"Distinct authored part numbers");
            song.setNoteEditorDeltas(Map.of(
                x1,new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(60,90,0,960))),
                x2,new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(64,90,960,1920)))));
            song.setAuthoredTrackRouting(Map.of(x2,Set.of(x1,x2)));
            numberer.setInstrument(first,LotroInstrument.BASIC_HARP,song.getParts());
            check(first.getPartNumber()==x1,"Changed instrument keeps X: of first part");
            check(second.getPartNumber()==x2,"Other authored part does not take X:1");
            check(song.getNoteEditorDeltas().get(x1).added().getFirst().pitch()==60,
                "Notes follow X:1, not current instrument name");
            numberer.setInstrument(first,LotroInstrument.LUTE_OF_AGES,song.getParts());
            check(first.getPartNumber()==x1 && second.getPartNumber()==x2,
                "Switching back must not swap two Lute tracks");
            song.deletePart(first);
            check(second.getPartNumber()==x2,"Deleting a part must not shift surviving composition X:");
        } finally {
            Files.deleteIfExists(source);
            prefs.removeNode();
        }
    }
    public static void main(String[] args) throws Exception {
        LocaleManager.init(true); UIText.init();
        SwingUtilities.invokeAndWait(() -> {
            try {undoAfterRegeneration();gridCellPlacement();}
            catch (Exception ex) {throw new RuntimeException(ex);}
        });
        stableCompositionNumbers();
        System.out.println("PASS: undo/redo across generated refresh, reordered/new parts, note grid hitboxes, stable X: IDs");
    }
}
