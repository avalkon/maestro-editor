package com.digero.maestro.noteedit;

import java.io.File;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.sound.midi.Sequence;
import javax.swing.KeyStroke;

/** Headless tests for clipboard serialization and piano-roll editing semantics. */
public final class CopyPasteSmokeTest {
    private static void check(boolean actual, String what) {
        if (!actual) throw new AssertionError(what);
    }
    private static NoteDocument example(NoteDocument.Kind kind, int ppq) {
        NoteDocument doc=new NoteDocument(kind,ppq,Sequence.PPQ,new File("clipboard.abc"));
        NoteDocument.Layer lute=new NoteDocument.Layer("Lute");
        lute.partNumber=1;
        lute.notes.add(EditableNote.create(48,83,2,ppq,2L*ppq));
        lute.notes.add(EditableNote.create(52,66,2,2L*ppq,3L*ppq));
        NoteDocument.Layer fiddle=new NoteDocument.Layer("Fiddle");
        fiddle.partNumber=2;
        fiddle.notes.add(EditableNote.create(69,97,3,2L*ppq,4L*ppq));
        doc.layers.add(lute);doc.layers.add(fiddle);
        return doc;
    }
    public static void main(String[]args) {
        testSinglePart();
        testAllParts();
        testClipboardFormat();
        testKeyboardBindings();
        System.out.println("PASS: Ctrl+A/C/X/V, multi-part clipboard, cross-PPQ paste, undo/redo, safety");
    }
    private static void testSinglePart() {
        NoteDocument doc=example(NoteDocument.Kind.ABC,960);
        PianoRollPanel roll=new PianoRollPanel();roll.setDocument(doc,0);
        roll.selectAllVisibleNotes();
        check(roll.selectionSize()==2,"Ctrl+A selected current part");
        check(roll.copySelection(),"Copy succeeded");
        roll.setDocument(doc,1);
        roll.setPlayheadTick(3840);
        check(roll.pasteClipboard(),"Pasted into other selected part");
        check(doc.layer(0).notes.size()==2,"Source part remains unchanged");
        check(doc.layer(1).notes.size()==3,"Target receives both notes");
        EditableNote first=doc.layer(1).notes.get(1);
        check(first.start()==3840 && first.end()==4800,"Paste aligns earliest note to playhead");
        check(first.pitch()==48 && first.velocity()==83 && first.channel()==2,"Pitch, velocity and channel are kept");
        check(roll.selectionSize()==2,"New notes are selected");
        doc.undo();
        check(doc.layer(1).notes.size()==1,"Atomic undo paste");
        doc.redo();
        check(doc.layer(1).notes.size()==3,"Redo paste");
        roll.cutSelection();
        check(doc.layer(1).notes.size()==1,"Cut deletes only selected pasted notes");
        doc.undo();
        check(doc.layer(1).notes.size()==3,"Cut is undoable");
        roll.setDocument(doc,0);
        check(roll.pasteClipboard(),"Cut clipboard can be pasted again");
        check(doc.layer(0).notes.size()==4,"Pasted cut notes into first part");
    }
    private static void testAllParts() {
        NoteDocument doc=example(NoteDocument.Kind.ABC,960);
        PianoRollPanel roll=new PianoRollPanel();roll.setDocument(doc,0);
        roll.setShowAllParts(true);
        roll.selectAllVisibleNotes();
        check(roll.selectionSize()==3,"All-parts mode selects all parts");
        roll.copySelection();
        roll.setPlayheadTick(5*960);
        check(roll.pasteClipboard(),"Multi-part paste");
        check(doc.layer(0).notes.size()==4 && doc.layer(1).notes.size()==2,"Each part retains its notes");
        check(doc.layer(1).notes.get(1).start()==6*960,"Multi-part relative offsets preserved");
        doc.undo();
        check(doc.layer(0).notes.size()==2 && doc.layer(1).notes.size()==1,"One undo for multi-part paste");

        NoteDocument other=example(NoteDocument.Kind.ABC,480);
        PianoRollPanel different=new PianoRollPanel();different.setDocument(other,0);
        different.setPlayheadTick(960);
        check(different.pasteClipboard(),"Paste across documents with different PPQ");
        check(other.layer(0).notes.size()==4 && other.layer(1).notes.size()==2,"Part-number mapping works");
        check(other.layer(0).notes.get(2).start()==960,"Paste at new timebase and playhead");
        check(other.layer(0).notes.get(2).end()==1440,"Duration rescaled to new PPQ");
        check(other.layer(1).notes.get(1).start()==1440,"Other part offset rescaled");

        NoteDocument one=example(NoteDocument.Kind.ABC,960);
        one.layers.remove(1);
        PianoRollPanel missing=new PianoRollPanel();missing.setDocument(one,0);
        check(!missing.pasteClipboard(),"Multi-part paste rejects missing part without partial changes");
        check(one.layer(0).notes.size()==2,"No partial notes were pasted");
    }
    private static void testClipboardFormat() {
        NoteClipboard.Contents clip = new NoteClipboard.Contents(NoteDocument.Kind.MIDI,480,
            List.of(new NoteClipboard.Entry(0,0,60,100,9,0,240)));
        check(NoteClipboard.decode(NoteClipboard.encode(clip)).equals(clip),"Clipboard encoding round-trip");
        check(NoteClipboard.decode("untrusted text") == null,"Unrelated clipboard text is not pasted");
        check(NoteClipboard.decode("MAESTRO_NOTES_V1\tABC\t960\n0\t1\t999\t90\t1\t0\t30\n")==null,
            "Out-of-range pitch rejected");
    }
    private static void testKeyboardBindings() {
        PianoRollPanel roll=new PianoRollPanel();
        for(int k : new int[]{KeyEvent.VK_A,KeyEvent.VK_C,KeyEvent.VK_X,KeyEvent.VK_V})
            check(roll.getInputMap(PianoRollPanel.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(k,InputEvent.CTRL_DOWN_MASK))!=null,
                "Standalone Ctrl key binding exists: "+k);
        roll.setSpaceShortcutEnabled(false);
        for(int k : new int[]{KeyEvent.VK_A,KeyEvent.VK_C,KeyEvent.VK_X,KeyEvent.VK_V})
            check(roll.getInputMap(PianoRollPanel.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(k,InputEvent.CTRL_DOWN_MASK))==null,
                "Embedded editor defers shortcuts to remappable manager: "+k);
    }
}
