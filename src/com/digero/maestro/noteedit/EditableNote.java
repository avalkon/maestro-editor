package com.digero.maestro.noteedit;

import java.util.UUID;

/** Immutable, editor-owned note. Times are [start, end) ticks. */
public record EditableNote(UUID id, int pitch, int velocity, int channel, long start, long end) {
    public EditableNote {
        if (id == null || pitch < 0 || pitch > 127 || velocity < 1 || velocity > 127 ||
                channel < 0 || channel > 15 || start < 0 || end <= start) {
            throw new IllegalArgumentException("Invalid note");
        }
    }
    public static EditableNote create(int pitch, int velocity, int channel, long start, long end) {
        return new EditableNote(UUID.randomUUID(), pitch, velocity, channel, start, end);
    }
    public EditableNote move(int semitones, long ticks) {
        long s = Math.max(0, start + ticks);
        return new EditableNote(id, Math.max(0, Math.min(127, pitch + semitones)), velocity,
                channel, s, s + end - start);
    }
    public EditableNote resize(long newEnd) {
        return new EditableNote(id, pitch, velocity, channel, start, Math.max(start + 1, newEnd));
    }
}
