package com.digero.maestro.noteedit;

import javax.sound.midi.*;
import java.nio.file.*;
import java.util.*;

/** Editor-only functional tests for meter-aware snap and live delta rebasing. */
public final class LiveOverlaySmokeTest {
    private static void check(boolean ok,String message){ if(!ok)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        snapMeters(); rebaseChangedPart(); midiMeter();
        if(args.length>0)organicLargeRatio(Path.of(args[0]));
        System.out.println("PASS: meter-aware snap (5/8), 1/1 and 1/2, rebase with added source notes, live ABC merge, MIDI meter");
    }
    private static void snapMeters() throws Exception {
        String abc="X:1\nT:Test\nM:5/8\nQ:120\nL:1/8\nK:C\nC D E F G |]\n";
        NoteDocument doc=AbcDocumentIO.readText(abc);
        check(doc.measureTicks==2400,"5/8 measure is 2400 ticks at 960 PPQ");
        check(doc.measureTicks(0)==2400,"Per-part measure available");
        PianoRollPanel roll=new PianoRollPanel();roll.setDocument(doc,0);
        roll.setSnapDivisor(3);check(roll.getSnapDivisor()==3,"Third grid available");
        roll.setSnapDivisor(12);check(roll.getSnapDivisor()==12,"Twelfth grid available");
        roll.setSnapDivisor(1);check(roll.getSnapDivisor()==1,"Whole-bar grid available");
        roll.setSnapDivisor(2);check(roll.getSnapDivisor()==2,"Half-bar grid available");
    }
    private static void rebaseChangedPart() throws Exception {
        String header="%abc-2.1\nT:Track selection\n";
        String first="X:1\nT:Lute\nM:4/4\nL:1/8\nQ:120\nK:C\nC2 D2 |]\n\n";
        String second="X:2\nT:Harp\nM:4/4\nL:1/8\nQ:120\nK:C\nE2 F2 |]\n";
        NoteDocument original=AbcDocumentIO.readText(header+first+second);
        EditableNote existing=original.layer(0).notes.getFirst();
        original.edit(0,list->list.set(0,existing.move(2,0)));
        Map<Integer,NoteEditorDelta.Part> delta=NoteEditorDelta.capture(original);
        check(delta.size()==1 && delta.containsKey(1),"One edited part by its X: number");
        check(delta.get(1).added().size()==1 && delta.get(1).removed().size()==1,"Pitch edit makes signed deltas");
        // A new source track is ticked in Song Parts; Maestro now generates an additional G note.
        String regenerated=header+first.replace("C2 D2 |]","C2 D2 G2 |]")+second;
        NoteDocument updated=AbcDocumentIO.readText(regenerated);
        NoteEditorDelta.Applied result=NoteEditorDelta.apply(updated,delta);
        check(result.changedParts()==1&&result.unmatchedRemovals()==0,"Rebase matched existing note");
        check(updated.layer(0).notes.size()==3,"New source-track note survives rebase");
        check(updated.layer(0).notes.stream().anyMatch(n->n.pitch()==50 && n.start()==0),"Manual pitch edit survives rebase");
        check(updated.layer(0).notes.stream().noneMatch(n->n.pitch()==48 && n.start()==0),"Edited-out source note removed");
        check(updated.layer(0).notes.stream().anyMatch(n->n.pitch()==55),"New G note added");
        String merged=NoteEditorDelta.mergeAbc(regenerated,delta);
        check(merged.endsWith(second),"Unedited second part remains byte-identical");
        NoteDocument reloaded=AbcDocumentIO.readText(merged);
        check(reloaded.layer(0).notes.size()==3,"Edited ABC exports the combined notes");
        check(reloaded.layer(0).notes.stream().anyMatch(n->n.pitch()==50),"ABC export has changed pitch");
    }
    private static void midiMeter() throws Exception {
        Sequence seq=new Sequence(Sequence.PPQ,480);
        Track t=seq.createTrack();
        t.add(new MidiEvent(new MetaMessage(0x58,new byte[]{5,3,24,8},4),0));
        Path f=Files.createTempFile("maestro-meter-", ".mid");
        MidiSystem.write(seq,0,f.toFile());
        NoteDocument d=MidiDocumentIO.read(f.toFile());
        check(d.measureTicks==1200,"MIDI 5/8 has 1200 ticks at 480 PPQ");
        Files.deleteIfExists(f);
    }
    private static void organicLargeRatio(Path path) throws Exception {
        NoteDocument doc=AbcDocumentIO.read(path.toFile());
        check(doc.layers.size()>=1,"Organic fixture loads");
        EditableNote n=doc.layer(0).notes.getFirst();
        doc.edit(0, notes->notes.set(0,n.move(1,0)));
        Map<Integer,NoteEditorDelta.Part> delta=NoteEditorDelta.capture(doc);
        String merged=NoteEditorDelta.mergeAbc(Files.readString(path),delta);
        NoteDocument reloaded=AbcDocumentIO.readText(merged);
        check(reloaded.layer(0).notes.stream().anyMatch(z->z.pitch()==n.pitch()+1 && z.start()==n.start()),
                "Organic edited note survives merge");
        check(reloaded.layers.size()==doc.layers.size(),"All organic tune parts preserved");
    }
}
