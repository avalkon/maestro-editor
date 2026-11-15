package com.digero.maestro.noteedit;

import javax.sound.midi.*;
import java.nio.file.*;
import java.util.*;

/** Standard-library self-test, executable without the upstream dependency graph. */
public final class EditorSmokeTest {
    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        midiRoundTrip(); abcRoundTrip(); lotroChordDurations(); rejectsUnknown();
        System.out.println("PASS: MIDI metadata/notes, undo/redo, ABC part isolation/ties, ABC safety checks");
    }
    private static void midiRoundTrip() throws Exception {
        Path dir=Files.createTempDirectory("maestro-midi-smoke");
        Path input=dir.resolve("input.mid"),output=dir.resolve("edited.mid");
        Sequence seq=new Sequence(Sequence.PPQ,480);
        Track meta=seq.createTrack();
        meta.add(new MidiEvent(new MetaMessage(0x51,new byte[]{0x07,(byte)0xA1,0x20},3),0));
        Track tr=seq.createTrack();
        tr.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE,0,24,0),0));
        tr.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE,0,7,90),0));
        tr.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,0,60,100),0));
        tr.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,0,60,0),480));
        MidiSystem.write(seq,1,input.toFile());
        NoteDocument doc=MidiDocumentIO.read(input.toFile());
        require(doc.layers.size()==2,"MIDI track count");
        require(doc.layer(1).notes.size()==1,"Parsed MIDI note");
        EditableNote original=doc.layer(1).notes.getFirst();
        doc.edit(1,list->list.set(0,original.move(2,240)));
        require(doc.layer(1).notes.getFirst().pitch()==62,"Move note");
        doc.undo();require(doc.layer(1).notes.getFirst().pitch()==60,"Undo");
        doc.redo();require(doc.layer(1).notes.getFirst().pitch()==62,"Redo");
        MidiDocumentIO.write(doc,output.toFile());
        NoteDocument loaded=MidiDocumentIO.read(output.toFile());
        require(loaded.layer(1).notes.getFirst().pitch()==62,"MIDI pitch saved");
        require(loaded.layer(1).notes.getFirst().start()==240,"MIDI tick saved");
        boolean tempo=false,program=false,controller=false;
        for(Track t:MidiSystem.getSequence(output.toFile()).getTracks())for(int i=0;i<t.size();i++) {
            MidiMessage m=t.get(i).getMessage();
            if(m instanceof MetaMessage mm && mm.getType()==0x51)tempo=true;
            if(m instanceof ShortMessage sm && sm.getCommand()==ShortMessage.PROGRAM_CHANGE)program=true;
            if(m instanceof ShortMessage sm && sm.getCommand()==ShortMessage.CONTROL_CHANGE)controller=true;
        }
        require(tempo && program && controller,"Non-note MIDI events preserved");
    }
    private static void abcRoundTrip() throws Exception {
        Path dir=Files.createTempDirectory("maestro-abc-smoke");
        Path input=dir.resolve("input.abc"),output=dir.resolve("edited.abc");
        String source="%abc-2.1\nT: Song metadata\nI:decoration +\n\n"+
                "X: 1\nT: Harp\nM: 3/4\nQ: 120\nL: 1/8\nK: C\n+mf+ C2 [EG]2 ^F2- | ^F2 z2 |]\n\n"+
                "X: 2\nT: Lute\nM: 4/4\nQ: 120\nL: 1/8\nK: C\nG2 A2 B2 c2 |]\n";
        Files.writeString(input,source);
        NoteDocument doc=AbcDocumentIO.read(input.toFile());
        require(doc.layers.size()==2,"ABC part count");
        require(doc.layer(0).notes.size()==4,"ABC notes including tied note");
        require(doc.layer(1).notes.size()==4,"ABC second part notes");
        EditableNote first=doc.layer(0).notes.getFirst();
        doc.edit(0,list->list.set(0,first.move(2,0)));
        AbcDocumentIO.write(doc,output.toFile());
        String written=Files.readString(output);
        require(written.contains("K: C"),"K:C maintained");
        require(written.contains("M: 3/4"),"Meter maintained");
        require(written.endsWith(source.substring(source.indexOf("X: 2"))),"Unedited ABC part unchanged");
        NoteDocument again=AbcDocumentIO.read(output.toFile());
        require(again.layer(0).notes.getFirst().pitch()==50,"ABC pitch saved");
        require(again.layer(1).notes.size()==4,"Untouched ABC part intact");
    }
    private static void lotroOctaveAndDynamics() throws Exception {
        Path source=Files.createTempFile("maestro-abc-octaves", ".abc");
        Files.writeString(source,"X:1\nT:Octaves\nL:1/8\nM:4/4\nK:C\nC2 c2 +pp+ D2 |]\n");
        NoteDocument doc=AbcDocumentIO.read(source.toFile());
        require(doc.layer(0).notes.get(0).pitch()==48,"LOTRO C is MIDI 48, not 60");
        require(doc.layer(0).notes.get(1).pitch()==60,"LOTRO c is MIDI 60, not 72");
        require(doc.layer(0).notes.get(2).velocity()==32,"ABC dynamic parsed");
        EditableNote n=doc.layer(0).notes.get(0);
        doc.edit(0,notes->notes.set(0,new EditableNote(n.id(),n.pitch(),112,n.channel(),n.start(),n.end())));
        Path output=Files.createTempFile("maestro-abc-octaves-out", ".abc");
        AbcDocumentIO.write(doc,output.toFile());
        NoteDocument again=AbcDocumentIO.read(output.toFile());
        require(again.layer(0).notes.get(0).pitch()==48,"LOTRO pitch survives ABC edit");
        require(again.layer(0).notes.get(0).velocity()==112,"ABC dynamic survives export");
        require(again.layer(0).notes.get(1).pitch()==60,"Higher octave survives ABC edit");
    }
    private static void lotroChordDurations() throws Exception {
        String source="X:1\nT:Chord\n%%made-for Lute of Ages\nM:4/4\nL:1/8\nK:C\n[^FB]17/24 z17/24 |]\n";
        NoteDocument doc=AbcDocumentIO.readText(source);
        require(doc.layer(0).notes.size()==2,"External ABC chord with suffix loads for repair");
        EditableNote note=doc.layer(0).notes.getFirst();
        doc.edit(0,notes->notes.set(0,note.move(1,0)));
        String written=AbcDocumentIO.toText(doc);
        require(!java.util.regex.Pattern.compile("\\][0-9/]").matcher(written).find(),
                "LOTRO export must never put a duration after a chord close bracket");
        NoteDocument reloaded=AbcDocumentIO.readText(written);
        require(reloaded.layer(0).notes.size()==2,"Chord event count survives LOTRO-safe export");
        require(reloaded.layer(0).notes.getFirst().pitch()==note.pitch()+1,
                "Edited pitch survives chord export/reimport");
        require(reloaded.layer(0).notes.getFirst().end()==note.end(),
                "Chord duration survives export/reimport");
    }
    private static void rejectsUnknown() throws Exception {
        Path file=Files.createTempFile("unsafe",".abc");
        Files.writeString(file,"X:1\nT:Triplet\nM:4/4\nL:1/8\nK:C\n(3CDE |]\n");
        try{AbcDocumentIO.read(file.toFile());throw new AssertionError("Tuplet was silently accepted");}
        catch(java.io.IOException expected){require(expected.getMessage().contains("Tuplet"),"Helpful tuplets error");}
    }
}
