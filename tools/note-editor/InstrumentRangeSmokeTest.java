package com.digero.maestro.noteedit;

import java.util.List;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Track;

/** All 23 official v4.7.6 LOTRO instrument octave shifts and ABC round trips. */
public final class InstrumentRangeSmokeTest {
    private record Instrument(String name, int octaves) {}
    private static final List<Instrument> INSTRUMENTS = List.of(
        new Instrument("Basic Harp", 0),
        new Instrument("Misty Mountain Harp", 0),
        new Instrument("Basic Lute", 0),
        new Instrument("Lute of Ages", 0),
        new Instrument("Basic Theorbo", -1),
        new Instrument("Traveller's Trusty Fiddle", 1),
        new Instrument("Jaunty Hand-knells", 3),
        new Instrument("Bardic Fiddle", 1),
        new Instrument("Basic Fiddle", 1),
        new Instrument("Lonely Mountain Fiddle", 1),
        new Instrument("Sprightly Fiddle", 1),
        new Instrument("Student's Fiddle", 1),
        new Instrument("Basic Bagpipe", 1),
        new Instrument("Basic Bassoon", 0),
        new Instrument("Brusque Bassoon", 0),
        new Instrument("Lonely Mountain Bassoon", 0),
        new Instrument("Basic Clarinet", 1),
        new Instrument("Basic Flute", 2),
        new Instrument("Basic Horn", 0),
        new Instrument("Basic Pibgorn", 2),
        new Instrument("Basic Cowbell", 0),
        new Instrument("Moor Cowbell", 0),
        new Instrument("Basic Drum", 0)
    );
    private static void require(boolean good, String message) {
        if (!good) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        for (Instrument instrument : INSTRUMENTS) {
            require(LotroPatch.octaveDeltaFor(instrument.name()) == instrument.octaves(),
                    "Incorrect offset for " + instrument.name());
            int shift=12*instrument.octaves();
            // Maestro Note.MIN_PLAYABLE=C2 (MIDI 36) and MAX_PLAYABLE=C5 (72),
            // not the ABC written C3 (48). Percussion/FX have extra pitch rules.
            require(36+shift>=0 && 72+shift<=127, "Playable range outside MIDI for "+instrument.name());
            require(LotroPatch.isPlayableSoundingPitch(instrument.name(),36+shift,shift),
                    instrument.name()+" minimum range boundary");
            require(LotroPatch.isPlayableSoundingPitch(instrument.name(),72+shift,shift),
                    instrument.name()+" maximum range boundary");
            require(!LotroPatch.isPlayableSoundingPitch(instrument.name(),35+shift,shift),
                    instrument.name()+" below minimum marked invalid");
            require(!LotroPatch.isPlayableSoundingPitch(instrument.name(),73+shift,shift),
                    instrument.name()+" above maximum marked invalid");
            if (instrument.name().equals("Student's Fiddle")) {
                require(LotroPatch.isPlayableSoundingPitch(instrument.name(),38+shift,shift), "Student FX D2");
                require(!LotroPatch.isPlayableSoundingPitch(instrument.name(),40+shift,shift), "Student FX/chromatic gap E2");
                require(LotroPatch.isPlayableSoundingPitch(instrument.name(),43+shift,shift), "Student chromatic G2");
            }
            String text="X:1\nT:Test\n%%made-for "+instrument.name()+
                        "\nM:4/4\nQ:120\nL:1/8\nK:C\nC,2 C2 c2 c'2 |]\n";
            NoteDocument doc=AbcDocumentIO.readText(text);
            require(doc.layers.size()==1, instrument.name()+" part count");
            NoteDocument.Layer layer=doc.layer(0);
            require(layer.abcPitchShift==shift, instrument.name()+" stored offset");
            int[] written={36,48,60,72};
            require(layer.notes.size()==written.length, instrument.name()+" note count");
            for(int i=0;i<written.length;i++) {
                require(layer.notes.get(i).pitch()==written[i]+shift,
                        instrument.name()+" pitch "+i+" should be "+(written[i]+shift));
            }
            // Playback into LOTRO patches must use the written pitch, not a
            // second octave translation (the soundfont itself supplies it).
            Sequence playback=EditorPlayback.sequenceFor(doc,0);
            Track midi=playback.getTracks()[1];
            int noteOnCount=0;
            for(int j=0;j<midi.size();j++) {
                MidiEvent event=midi.get(j);
                if(event.getMessage() instanceof ShortMessage sm &&
                   sm.getCommand()==ShortMessage.NOTE_ON && sm.getData2()>0) {
                    require(sm.getData1()==written[noteOnCount],
                            instrument.name()+" LOTRO playback pitch "+noteOnCount);
                    noteOnCount++;
                }
            }
            require(noteOnCount==written.length,instrument.name()+" playback note count");
            EditableNote first=layer.notes.getFirst();
            doc.edit(0,notes->notes.set(0,first.move(1,0)));
            NoteDocument reopened=AbcDocumentIO.readText(AbcDocumentIO.toText(doc));
            require(reopened.layer(0).notes.getFirst().pitch()==37+shift,
                    instrument.name()+" edit/export/reopen pitch");
            require(reopened.layer(0).notes.size()==written.length,
                    instrument.name()+" round-trip note count");
        }
        // The keyboard is broader than a normal instrument: Student's Fiddle
        // has a special FX band C2-D2 (36-38), with chromatic notes G2-C5 (43-72).
        // Drum/cowbells can also map notes to sounds rather than pitched tones.
        System.out.println("PASS: 23 instrument shifts, playable range envelope, LOTRO playback MIDI, ABC export/reopen");
    }
}
