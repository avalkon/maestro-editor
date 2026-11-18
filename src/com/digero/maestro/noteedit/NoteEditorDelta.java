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
    /** A full copy is an independent, editable alternative to the imported
     * part. Its additions are the ENTIRE authored track, not just changes.
     * The imported baseline stays untouched and can be selected again.
     * Older MSX note deltas retain their original partial-edit semantics. */
    public record Part(List<Value> removed, List<Value> added, boolean fullCopy) {
        public Part { removed = List.copyOf(removed); added = List.copyOf(added); }
        public Part(List<Value> removed, List<Value> added) { this(removed, added, false); }
        public boolean empty() {return !fullCopy && removed.isEmpty() && added.isEmpty();}
    }
    public record Applied(int changedParts, int unmatchedRemovals) {}

    /** Upgrade v1.5.4 MSX deltas (written-ABC pitch coordinates) to the
     * sounding-pitch coordinates used by the corrected piano roll. */
    public static Part shiftPitches(Part part, int semitones) {
        if (semitones == 0) return part;
        return new Part(part.removed().stream().map(v -> shift(v,semitones)).toList(),
                        part.added().stream().map(v -> shift(v,semitones)).toList(),part.fullCopy());
    }
    private static Value shift(Value v, int semitones) {
        return new Value(v.pitch()+semitones,v.velocity(),v.start(),v.end());
    }

    public static Map<Integer,Part> capture(NoteDocument doc) {
        if(doc.kind != NoteDocument.Kind.ABC) throw new IllegalArgumentException("Not an ABC document");
        Map<Integer,Part> changes = new LinkedHashMap<>();
        for(NoteDocument.Layer layer:doc.layers) {
            // Match each note to ITS OWN baseline identity. A value/multiset diff
            // loses movements in repeating melodies: moving A onto B's old
            // position cancels A's addition with B's removal, even when B
            // itself moved. The MSX then plays some originals and some moved
            // notes together. UUIDs survive move/resize and undo/redo, and
            // regenerated layers receive a fresh, self-consistent baseline.
            Map<UUID,EditableNote> original = byId(layer.baselineNotes);
            Map<UUID,EditableNote> current = byId(layer.notes);
            List<Value> removed=new ArrayList<>(),added=new ArrayList<>();
            for(EditableNote note:layer.baselineNotes) {
                EditableNote after=current.get(note.id());
                if(after==null || !Value.of(note).equals(Value.of(after)))
                    removed.add(Value.of(note));
            }
            for(EditableNote note:layer.notes) {
                EditableNote before=original.get(note.id());
                if(before==null || !Value.of(before).equals(Value.of(note)))
                    added.add(Value.of(note));
            }
            Part part=new Part(removed,added);
            if(!part.empty())changes.put(layer.partNumber,part);
        }
        return Collections.unmodifiableMap(changes);
    }
    /** Preserve persisted changes for parts missing from an asynchronous editor
     * baseline. Only layers actually present may replace/remove their deltas.
     * This prevents a mode switch or delayed new-part refresh from wiping notes
     * belonging to another X: part (including another part of the same instrument). */
    public static Map<Integer,Part> capturePreservingMissing(NoteDocument doc,
            Map<Integer,Part> previous) {
        Map<Integer,Part> updated=new LinkedHashMap<>(previous);
        Map<Integer,Part> current=capture(doc);
        Set<Integer> seen=new HashSet<>();
        for(NoteDocument.Layer layer:doc.layers) {
            if(!seen.add(layer.partNumber))
                throw new IllegalStateException("Duplicate ABC part X:"+layer.partNumber);
            Part delta=current.get(layer.partNumber);
            Part prior=previous.get(layer.partNumber);
            // First genuine edit to an imported part promotes its entire
            // generated track into a separately selectable authored copy.
            // Keep the copy after undoing the first edit, unless the user
            // explicitly switches back to (or deletes) the authored route.
            boolean copy = !layer.baselineNotes.isEmpty() && delta != null;
            if (prior != null && prior.fullCopy()) copy = true;
            if (copy) {
                List<Value> baseline=layer.baselineNotes.stream().map(Value::of).toList();
                List<Value> authored=layer.notes.stream().map(Value::of).toList();
                updated.put(layer.partNumber,new Part(baseline,authored,true));
            } else if(delta==null) updated.remove(layer.partNumber);
            else updated.put(layer.partNumber,delta);
        }
        return Collections.unmodifiableMap(updated);
    }

    private static Map<UUID,EditableNote> byId(List<EditableNote> notes) {
        Map<UUID,EditableNote> ids=new LinkedHashMap<>();
        for(EditableNote note:notes)
            if(ids.put(note.id(),note)!=null)
                throw new IllegalStateException("Duplicate piano-roll note identity: " + note.id());
        return ids;
    }
    public static Applied apply(NoteDocument doc, Map<Integer,Part> changes) {
        if(changes==null || changes.isEmpty())return new Applied(0,0);
        int modified=0,unmatched=0;
        for(NoteDocument.Layer layer:doc.layers) {
            Part delta=changes.get(layer.partNumber);
            if(delta==null || delta.empty())continue;
            modified++;
            if (delta.fullCopy()) {
                // Never try to match old velocities or repeated pitches in a
                // replacement: the whole generated lane is being replaced.
                layer.notes.clear();
                for (Value value : delta.added()) layer.notes.add(value.note());
                layer.notes.sort(Comparator.comparingLong(EditableNote::start)
                    .thenComparingInt(EditableNote::pitch));
                layer.edited=true;
                continue;
            }
            for(Value value:delta.removed()) {
                int match = -1;
                // Prefer exact matching, including velocity. Import/export may
                // normalize dynamics after a part-level volume adjustment,
                // though, so velocity cannot be used as the ONLY identity of
                // a removed original note. Musical identity here is pitch and
                // both endpoints; never delete an unrelated note by pitch alone.
                for(int i=0;i<layer.notes.size();i++) {
                    if(Value.of(layer.notes.get(i)).equals(value)) {
                        match = i;
                        break;
                    }
                }
                if(match < 0) {
                    int closestVelocity = Integer.MAX_VALUE;
                    for(int i=0;i<layer.notes.size();i++) {
                        EditableNote candidate = layer.notes.get(i);
                        if(candidate.pitch() != value.pitch() ||
                           candidate.start() != value.start() ||
                           candidate.end() != value.end()) continue;
                        int difference = Math.abs(candidate.velocity() - value.velocity());
                        if(difference < closestVelocity) {
                            closestVelocity = difference;
                            match = i;
                        }
                    }
                }
                if(match >= 0) layer.notes.remove(match);
                else unmatched++;
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
