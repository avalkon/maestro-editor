package com.digero.maestro.noteedit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.midi.*;
import org.junit.jupiter.api.Test;

/** No audio device required: tests the note engine and preview sequence contents. */
public class NoteEditorIntegrationTest {
    @Test
    public void organicLargeDenominatorCanBeEditedAndReopened() throws Exception {
        Path source = Files.createTempFile("organic-abc", ".abc");
        String text = "% Global preamble preserved\n" +
                "X:1\nT:Organic part\nM:4/4\nQ: 140\nL:1/4000000\nK:C\n" +
                "z4000000 C500000 D500000 |]\n" +
                "X:2\nT:Untouched part\nM:4/4\nQ:140\nL:1/2181818\nK:C\n" +
                "E1000000 F2000000 |]\n";
        Files.writeString(source, text);
        NoteDocument doc = AbcDocumentIO.read(source.toFile());
        assertEquals(2, doc.layers.size());
        assertEquals(3840, doc.layer(0).notes.getFirst().start());
        assertEquals(140, doc.layer(0).abcTempo);
        assertEquals(2, doc.layer(1).notes.size());
        var n = doc.layer(0).notes.getFirst();
        doc.edit(0, notes -> notes.set(0, n.move(2, 0)));
        Path saved = Files.createTempFile("organic-edited", ".abc");
        AbcDocumentIO.write(doc, saved.toFile());
        String changed = Files.readString(saved);
        assertEquals(text.substring(text.indexOf("X:2")), changed.substring(changed.indexOf("X:2")));
        assertEquals(n.pitch() + 2, AbcDocumentIO.read(saved.toFile()).layer(0).notes.getFirst().pitch());
    }

    @Test
    public void playbackSequenceReflectsUnsavedMidiChangesAndPreservesTempo() throws Exception {
        Sequence src = new Sequence(Sequence.PPQ, 480);
        Track tempo = src.createTrack();
        tempo.add(new MidiEvent(new MetaMessage(0x51, new byte[]{0x07, (byte)0xA1, 0x20}, 3), 0));
        Track track = src.createTrack();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 20, 0), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 99), 0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0), 480));
        Path file = Files.createTempFile("midi-preview", ".mid");
        MidiSystem.write(src, 1, file.toFile());
        NoteDocument doc = MidiDocumentIO.read(file.toFile());
        EditableNote note = doc.layer(1).notes.getFirst();
        doc.edit(1, list -> list.set(0, note.move(4, 120)));
        Sequence preview = EditorPlayback.sequenceFor(doc, 1);
        assertEquals(480, preview.getResolution());
        boolean hasTempo = false, editedNote = false;
        for (Track t : preview.getTracks()) for (int i = 0; i < t.size(); i++) {
            var msg = t.get(i).getMessage();
            if (msg instanceof MetaMessage meta && meta.getType() == 0x51) hasTempo = true;
            if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON
                    && sm.getData1() == 64 && t.get(i).getTick() == 120) editedNote = true;
        }
        assertTrue(hasTempo);
        assertTrue(editedNote);
    }

    @Test
    public void abcPlaybackCanSoloAndPlayAllParts() throws Exception {
        Path file = Files.createTempFile("abc-preview", ".abc");
        Files.writeString(file,"X:1\nT:One\nL:1/4000000\nQ:100\nK:C\nC4000000 |]\n" +
                "X:2\nT:Two\nL:1/2181818\nQ:100\nK:C\nE2181818 |]\n");
        NoteDocument doc = AbcDocumentIO.read(file.toFile());
        assertEquals(2, EditorPlayback.sequenceFor(doc, 0).getTracks().length);
        assertEquals(3, EditorPlayback.sequenceFor(doc, -1).getTracks().length);
        assertEquals(3840, EditorPlayback.sequenceFor(doc, -1).getTickLength());
    }
}
