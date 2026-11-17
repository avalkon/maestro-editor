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

    // ABC history is keyed by stable X: part identity, not the layer's current
    // position. Preview regeneration and adding parts may reorder editor layers.
    // MIDI uses negative keys so its source track indices remain distinct.
    private record State(Map<Integer,List<EditableNote>> notes, Map<Integer,Boolean> edited) {}
    private int historyKey(int index) {
        return kind == Kind.ABC && layer(index).partNumber > 0
                ? layer(index).partNumber : -index - 1;
    }
    /** Reuse history after a non-destructive generated ABC refresh. The caller
     * verifies that the old part identities, baseline, and edited notes survive.
     * New preview layers need not have existed when an older state was saved. */
    void preserveHistoryFrom(NoteDocument previous) {
        if (kind != previous.kind) throw new IllegalArgumentException("Document kind changed");
        undo.addAll(previous.undo);
        redo.addAll(previous.redo);
    }

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
        Map<Integer,List<EditableNote>> all = new LinkedHashMap<>();
        Map<Integer,Boolean> edits = new LinkedHashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            int key = historyKey(i);
            if (all.put(key, new ArrayList<>(layer.notes)) != null)
                throw new IllegalStateException("Duplicate part identity: " + key);
            edits.put(key, layer.edited);
        }
        return new State(all, edits);
    }
    private void restore(State state) {
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            int key = historyKey(i);
            layer.notes.clear();
            List<EditableNote> previous = state.notes.get(key);
            if (previous != null) layer.notes.addAll(previous);
            // A newly added part has no notes in an older undo snapshot.
            layer.edited = state.edited.getOrDefault(key, false);
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
