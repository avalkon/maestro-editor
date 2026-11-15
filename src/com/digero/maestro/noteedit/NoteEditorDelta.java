package com.digero.maestro.noteedit;

import java.io.IOException;
import java.util.*;

/**
 * A reversible note-level difference from a generated ABC part. Deltas are
 * keyed by the stable ABC X:/Maestro part number, not list position. A fresh
 * conversion can supply new notes while manual additions/deletions are rebased.
 * No original MIDI events are modified by this class.
 */
public final class NoteEditorDelta {
    private NoteEditorDelta() {}
    public record Value(int pitch, int velocity, long start, long end) {
        public Value {
            if (pitch < 0 || pitch > 127 || velocity < 1 || velocity > 127 ||
                start < 0 || end <= start) throw new IllegalArgumentException("Invalid note delta");
        }
        public static Value of(EditableNote note) {
            return new Value(note.pitch(),note.velocity(),note.start(),note.end());
        }
        EditableNote note() {return EditableNote.create(pitch,velocity,0,start,end);}
    }
    public record Part(List<Value> removed, List<Value> added) {
        public Part { removed = List.copyOf(removed); added = List.copyOf(added); }
        public boolean empty() {return removed.isEmpty() && added.isEmpty();}
    }
    public record Applied(int changedParts, int unmatchedRemovals) {}

    /** Upgrade v1.5.4 MSX deltas (written-ABC pitch coordinates) to the
     * sounding-pitch coordinates used by the corrected piano roll. */
    public static Part shiftPitches(Part part, int semitones) {
        if (semitones == 0) return part;
        return new Part(part.removed().stream().map(v -> shift(v,semitones)).toList(),
                        part.added().stream().map(v -> shift(v,semitones)).toList());
    }
    private static Value shift(Value v, int semitones) {
        return new Value(v.pitch()+semitones,v.velocity(),v.start(),v.end());
    }

    public static Map<Integer,Part> capture(NoteDocument doc) {
        if(doc.kind != NoteDocument.Kind.ABC) throw new IllegalArgumentException("Not an ABC document");
        Map<Integer,Part> changes = new LinkedHashMap<>();
        for(NoteDocument.Layer layer:doc.layers) {
            Map<Value,Integer> old = count(layer.baselineNotes);
            Map<Value,Integer> now = count(layer.notes);
            List<Value> removed=new ArrayList<>(),added=new ArrayList<>();
            for(var e:old.entrySet()) for(int n=now.getOrDefault(e.getKey(),0);n<e.getValue();n++)removed.add(e.getKey());
            for(var e:now.entrySet()) for(int n=old.getOrDefault(e.getKey(),0);n<e.getValue();n++)added.add(e.getKey());
            Part part=new Part(removed,added);
            if(!part.empty())changes.put(layer.partNumber,part);
        }
        return Collections.unmodifiableMap(changes);
    }
    private static Map<Value,Integer> count(List<EditableNote> notes) {
        Map<Value,Integer> counts=new LinkedHashMap<>();
        for(EditableNote note:notes)counts.merge(Value.of(note),1,Integer::sum);
        return counts;
    }
    public static Applied apply(NoteDocument doc, Map<Integer,Part> changes) {
        if(changes==null || changes.isEmpty())return new Applied(0,0);
        int modified=0,unmatched=0;
        for(NoteDocument.Layer layer:doc.layers) {
            Part delta=changes.get(layer.partNumber);
            if(delta==null || delta.empty())continue;
            modified++;
            for(Value value:delta.removed()) {
                boolean found=false;
                for(int i=0;i<layer.notes.size();i++) {
                    if(Value.of(layer.notes.get(i)).equals(value)) {
                        layer.notes.remove(i);found=true;break;
                    }
                }
                if(!found)unmatched++;
            }
            for(Value note:delta.added())layer.notes.add(note.note());
            layer.notes.sort(Comparator.comparingLong(EditableNote::start).thenComparingInt(EditableNote::pitch));
            layer.edited=true;
        }
        return new Applied(modified,unmatched);
    }
    public static String mergeAbc(String generated,Map<Integer,Part> changes) throws IOException {
        if(changes==null||changes.isEmpty())return generated;
        NoteDocument doc=AbcDocumentIO.readText(generated);
        apply(doc,changes);
        return AbcDocumentIO.toText(doc);
    }
}
