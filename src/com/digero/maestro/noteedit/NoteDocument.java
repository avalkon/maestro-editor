package com.digero.maestro.noteedit;

import javax.sound.midi.Sequence;
import java.io.File;
import java.util.*;

/** Editor-owned document. Upstream sequence/ABC models are never mutated. */
public final class NoteDocument {
    public enum Kind { MIDI, ABC }
    public static final class Layer {
        public final String name;
        public String instrumentName = "";
        public int lotroProgram = 0;
        /** MIDI-pitch offset from LOTRO's written ABC notes to the instrument's sounding register.
         * The piano roll stores sounding pitches; ABC export and LOTRO playback undo this offset.
         */
        public int abcPitchShift = 0;
        public long measureTicks;
        public int partNumber;
        public final List<EditableNote> baselineNotes = new ArrayList<>();
        public final List<EditableNote> notes = new ArrayList<>();
        /** Original source text, kept for untouched ABC parts. */
        String abcSource;
        String abcHeader;
        int abcTempo = 120;
        final List<javax.sound.midi.MidiEvent> unmatchedMidiEvents = new ArrayList<>();
        boolean edited;
        Layer(String name) { this.name = name; }
        public String toString() { return name + " (" + notes.size() + " notes)"; }
    }

    public final Kind kind;
    public final List<Layer> layers = new ArrayList<>();
    public final int ppq;
    public final float divisionType;
    public final File sourceFile;
    /** Default meter length, in document ticks; individual ABC layers may override it. */
    public long measureTicks;
    Sequence originalMidi;
    String abcPreamble = "";
    private final Deque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private boolean dirty;

    private record State(List<List<EditableNote>> notes, List<Boolean> edited) {}

    NoteDocument(Kind kind, int ppq, float divisionType, File sourceFile) {
        this.kind = kind; this.ppq = ppq; this.divisionType = divisionType; this.sourceFile = sourceFile;
        this.measureTicks = 4L * ppq;
    }
    public boolean isDirty() { return dirty; }
    public void markSaved() { dirty = false; }
    public Layer layer(int i) { return layers.get(i); }
    public long measureTicks(int layerIndex) {
        long value = layer(layerIndex).measureTicks;
        return value > 0 ? value : measureTicks;
    }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    private State snapshot() {
        List<List<EditableNote>> all = new ArrayList<>();
        List<Boolean> edits = new ArrayList<>();
        for (Layer layer : layers) {
            all.add(new ArrayList<>(layer.notes));
            edits.add(layer.edited);
        }
        return new State(all, edits);
    }
    private void restore(State state) {
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            layer.notes.clear();
            if (i < state.notes.size()) {
                layer.notes.addAll(state.notes.get(i));
                layer.edited = state.edited.get(i);
            } else {
                // This X: part was added after the saved undo snapshot.
                layer.edited = false;
            }
        }
        dirty = true;
    }
    public void edit(int layerIndex, java.util.function.Consumer<List<EditableNote>> change) {
        editLayers(Map.of(layerIndex, change));
    }
    /** One atomic undo step for edits spanning multiple visible parts. */
    public void editLayers(Map<Integer, java.util.function.Consumer<List<EditableNote>>> changes) {
        if (changes.isEmpty()) return;
        State before = snapshot();
        try {
            for (var entry : changes.entrySet()) {
                Layer layer = layer(entry.getKey());
                entry.getValue().accept(layer.notes);
                for (EditableNote n : layer.notes) Objects.requireNonNull(n);
            }
        } catch (RuntimeException e) {
            restore(before);
            throw e;
        }
        if (before.notes.equals(snapshot().notes)) return;
        undo.push(before);
        if (undo.size() > 200) undo.removeLast();
        redo.clear();
        for (int i : changes.keySet()) layer(i).edited = true;
        dirty = true;
    }
    public void undo() { if (!undo.isEmpty()) { redo.push(snapshot()); restore(undo.pop()); } }
    public void redo() { if (!redo.isEmpty()) { undo.push(snapshot()); restore(redo.pop()); } }
}
