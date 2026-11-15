package com.digero.maestro.noteedit;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * Versioned plain-text note clipboard. The text travels through the OS clipboard
 * (so separate Maestro windows can exchange notes) and uses an in-process
 * fallback when a system clipboard is unavailable, such as in headless tests.
 * No Java object deserialization or external data is executed.
 */
final class NoteClipboard {
    private static final String MAGIC = "MAESTRO_NOTES_V1";
    private static final int MAX_NOTES = 100_000;
    private static final int MAX_TEXT_LENGTH = 8_000_000;
    private static volatile String fallback;

    record Entry(int layer, int partNumber, int pitch, int velocity, int channel,
                 long startOffset, long length) {}
    record Contents(NoteDocument.Kind kind, int ppq, List<Entry> notes) {
        Contents { notes = List.copyOf(notes); }
    }

    private NoteClipboard() {}

    static String encode(Contents contents) {
        StringBuilder s = new StringBuilder(MAGIC).append('\t').append(contents.kind())
                .append('\t').append(contents.ppq()).append('\n');
        for (Entry n : contents.notes()) {
            s.append(n.layer()).append('\t').append(n.partNumber()).append('\t')
                    .append(n.pitch()).append('\t').append(n.velocity()).append('\t')
                    .append(n.channel()).append('\t').append(n.startOffset()).append('\t')
                    .append(n.length()).append('\n');
        }
        return s.toString();
    }

    static Contents decode(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH || !text.startsWith(MAGIC + "\t")) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length > MAX_NOTES + 2) return null;
        try {
            String[] header = lines[0].split("\t", -1);
            if (header.length != 3 || !header[0].equals(MAGIC)) return null;
            NoteDocument.Kind kind = NoteDocument.Kind.valueOf(header[1]);
            int ppq = Integer.parseInt(header[2]);
            if (ppq <= 0) return null;
            List<Entry> notes = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    if (i == lines.length - 1) break;
                    return null;
                }
                String[] fields = lines[i].split("\t", -1);
                if (fields.length != 7) return null;
                Entry note = new Entry(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                        Integer.parseInt(fields[4]), Long.parseLong(fields[5]), Long.parseLong(fields[6]));
                if (note.layer() < 0 || note.pitch() < 0 || note.pitch() > 127 || note.velocity() < 1 ||
                        note.velocity() > 127 || note.channel() < 0 || note.channel() > 15 ||
                        note.startOffset() < 0 || note.length() <= 0) return null;
                notes.add(note);
            }
            return notes.isEmpty() ? null : new Contents(kind, ppq, notes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void put(Contents contents) {
        String text = encode(contents);
        fallback = text;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (HeadlessException | IllegalStateException | SecurityException ignored) {
            // The fallback is used in unit tests or when the desktop clipboard is temporarily busy.
        }
    }

    static Contents get() {
        try {
            Object raw = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return decode(raw instanceof String ? (String)raw : null);
        } catch (HeadlessException | IllegalStateException | SecurityException | java.awt.datatransfer.UnsupportedFlavorException |
                 java.io.IOException ignored) {
            return decode(fallback);
        }
    }
}
