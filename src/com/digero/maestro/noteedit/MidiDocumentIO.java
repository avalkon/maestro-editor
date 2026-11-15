package com.digero.maestro.noteedit;

import javax.sound.midi.*;
import java.io.*;
import java.util.*;

/** Round-trip MIDI notes while retaining non-note events (tempo, CC, programs, sysex). */
public final class MidiDocumentIO {
    private MidiDocumentIO() {}
    private record Pending(int pitch, int velocity, int channel, long start, MidiEvent event) {}

    public static NoteDocument read(File file) throws Exception {
        Sequence sequence = MidiSystem.getSequence(file);
        if (sequence.getDivisionType() != Sequence.PPQ)
            throw new IOException("SMPTE-timed MIDI is not editable in v1; MIDI file left unchanged.");
        NoteDocument doc = new NoteDocument(NoteDocument.Kind.MIDI, sequence.getResolution(),
                sequence.getDivisionType(), file);
        doc.originalMidi = sequence;
        long meterAt = Long.MAX_VALUE;
        for (Track track : sequence.getTracks()) for (int m = 0; m < track.size(); m++) {
            MidiEvent event = track.get(m);
            if (event.getTick() >= meterAt) continue;
            if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x58) {
                byte[] data = meta.getData();
                if (data.length >= 2 && (data[1] & 0xff) < 8) {
                    long ticks = Math.round(sequence.getResolution() * 4.0 *
                            (data[0] & 0xff) / (1 << (data[1] & 0xff)));
                    if (ticks >= 1) { doc.measureTicks = ticks; meterAt = event.getTick(); }
                }
            }
        }
        int index = 0;
        for (Track t : sequence.getTracks()) {
            NoteDocument.Layer layer = new NoteDocument.Layer("MIDI track " + (++index));
            Map<Integer, ArrayDeque<Pending>> active = new HashMap<>();
            for (int i = 0; i < t.size(); i++) {
                MidiEvent e = t.get(i);
                if (!(e.getMessage() instanceof ShortMessage m)) continue;
                int cmd = m.getCommand();
                if (cmd != ShortMessage.NOTE_ON && cmd != ShortMessage.NOTE_OFF) continue;
                int key = m.getChannel() * 128 + m.getData1();
                if (cmd == ShortMessage.NOTE_ON && m.getData2() > 0) {
                    active.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(
                            new Pending(m.getData1(), m.getData2(), m.getChannel(), e.getTick(), e));
                } else {
                    ArrayDeque<Pending> stack = active.get(key);
                    if (stack == null || stack.isEmpty()) { layer.unmatchedMidiEvents.add(e); continue; }
                    Pending on = stack.removeFirst();
                    if (e.getTick() > on.start()) {
                        layer.notes.add(EditableNote.create(on.pitch(), on.velocity(), on.channel(), on.start(), e.getTick()));
                    } else {
                        // Preserve zero-duration/malformed note pairs as untouched raw events.
                        layer.unmatchedMidiEvents.add(on.event());
                        layer.unmatchedMidiEvents.add(e);
                    }
                }
            }
            for (ArrayDeque<Pending> queue : active.values())
                for (Pending on : queue) layer.unmatchedMidiEvents.add(on.event());
            layer.notes.sort(Comparator.comparingLong(EditableNote::start));
            doc.layers.add(layer);
        }
        return doc;
    }
    public static void write(NoteDocument doc, File out) throws Exception {
        if (doc.kind != NoteDocument.Kind.MIDI) throw new IllegalArgumentException("Not MIDI");
        Sequence dst = toSequence(doc, -1);
        int[] types = MidiSystem.getMidiFileTypes(dst);
        int inputFormat = MidiSystem.getMidiFileFormat(doc.sourceFile).getType();
        int format = Arrays.stream(types).anyMatch(x -> x == inputFormat) ? inputFormat :
                Arrays.stream(types).anyMatch(x -> x == 1) ? 1 : (types.length > 0 ? types[0] : -1);
        if (format < 0 || MidiSystem.write(dst, format, out) <= 0) throw new IOException("No MIDI file writer");
    }
    /** Embed the complete edited MIDI in a project without creating a sidecar file. */
    public static byte[] toBytes(NoteDocument doc) throws Exception {
        Sequence sequence = toSequence(doc, -1);
        int[] types = MidiSystem.getMidiFileTypes(sequence);
        int format = Arrays.stream(types).anyMatch(x -> x == 1) ? 1 : (types.length == 0 ? -1 : types[0]);
        if (format < 0) throw new IOException("No MIDI writer available");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (MidiSystem.write(sequence, format, out) <= 0) throw new IOException("Could not serialize MIDI");
        return out.toByteArray();
    }
    /** Select -1 for all tracks; otherwise solo one track while retaining tempo maps. */
    public static Sequence toSequence(NoteDocument doc, int selectedTrack) throws Exception {
        if (doc.kind != NoteDocument.Kind.MIDI) throw new IllegalArgumentException("Not MIDI");
        Sequence src = doc.originalMidi;
        Sequence dst = new Sequence(src.getDivisionType(), src.getResolution());
        Track[] tracks = src.getTracks();
        for (int k = 0; k < tracks.length; k++) {
            Track target = dst.createTrack();
            for (int i = 0; i < tracks[k].size(); i++) {
                MidiEvent event = tracks[k].get(i);
                MidiMessage msg = event.getMessage();
                if (msg instanceof ShortMessage sm &&
                        (sm.getCommand() == ShortMessage.NOTE_ON || sm.getCommand() == ShortMessage.NOTE_OFF)) continue;
                if (msg instanceof MetaMessage mm && mm.getType() == 0x2f) continue;
                // Global tempo/time-signature meta events must stay visible to
                // a solo preview; track-local controller events must not.
                if (selectedTrack >= 0 && k != selectedTrack && !(msg instanceof MetaMessage)) continue;
                target.add(new MidiEvent((MidiMessage) msg.clone(), event.getTick()));
            }
            if (selectedTrack >= 0 && k != selectedTrack) continue;
            for (MidiEvent unmatched : doc.layers.get(k).unmatchedMidiEvents)
                target.add(new MidiEvent((MidiMessage) unmatched.getMessage().clone(), unmatched.getTick()));
            for (EditableNote n : doc.layers.get(k).notes) {
                target.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, n.channel(), n.pitch(), n.velocity()), n.start()));
                target.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, n.channel(), n.pitch(), 0), n.end()));
            }
        }
        return dst;
    }
}
