package com.digero.maestro.noteedit;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks ABC timing and LOTRO's 60 ms minimum using only the Java standard library. */
public final class LotroTimingSmokeTest {
    private static void require(boolean ok, String error) {
        if (!ok) throw new AssertionError(error);
    }

    public static void main(String[] args) throws Exception {
        String source = "X: 1\nT: Duration test\n%%made-for Lute of Ages\nM: 3/4\nQ: 89\n"
                + "L: 1/32\nK: C\nB,17/24 z79/24 [^F17/24B17/24] z79/24 |]\n";
        NoteDocument doc = AbcDocumentIO.readText(source);
        // Mark the part modified to exercise the actual export path.
        doc.edit(0, notes -> notes.set(0, notes.getFirst().move(1, 0)));
        String output = AbcDocumentIO.toText(doc);
        require(output.contains("L: 1/2696629"), "High-resolution organic ABC unit");
        require(!Pattern.compile("\\][0-9/]").matcher(output).find(), "No chord-duration suffix");
        Matcher m = Pattern.compile("[A-Ga-gz](\\d+)").matcher(output.substring(output.indexOf("K: C")));
        int count = 0;
        while (m.find()) {
            long micros = Long.parseLong(m.group(1));
            require(micros >= 60010L, "LOTRO duration less than 60.01 ms: " + micros);
            count++;
        }
        require(count > 2, "Duration test should contain notes and rests");
        NoteDocument roundTrip = AbcDocumentIO.readText(output);
        require(roundTrip.layer(0).notes.size() == doc.layer(0).notes.size(), "Round-trip note count");

        // Timing boundaries a single tick before a measure must snap to the barline;
        // they must not produce a 0.7 ms extra tied note or a 60 ms extension.
        NoteDocument barline = AbcDocumentIO.readText(source);
        barline.edit(0, notes -> {
            notes.clear();
            notes.add(EditableNote.create(48, 90, 0, 2879, 2965)); // 3/4 bar at tick 2880
        });
        NoteDocument snapped = AbcDocumentIO.readText(AbcDocumentIO.toText(barline));
        EditableNote edited = snapped.layer(0).notes.getFirst();
        require(Math.abs(edited.start() - 2879) <= 1, "Barline-start snap introduced drift");
        require(Math.abs(edited.end() - 2965) <= 1, "Barline-end snap introduced drift");

        if (args.length > 0) {
            // Optional full regression using a supplied ABC fixture. No fixtures are bundled.
            NoteDocument fixture = AbcDocumentIO.read(new File(args[0]));
            List<EditableNote> before = fixture.layer(0).notes.stream()
                    .sorted(Comparator.comparingLong(EditableNote::start)
                            .thenComparingInt(EditableNote::pitch)
                            .thenComparingLong(EditableNote::end)).toList();
            fixture.layer(0).edited = true;
            String fixed = AbcDocumentIO.toText(fixture);
            NoteDocument check = AbcDocumentIO.readText(fixed);
            List<EditableNote> after = check.layer(0).notes.stream()
                    .sorted(Comparator.comparingLong(EditableNote::start)
                            .thenComparingInt(EditableNote::pitch)
                            .thenComparingLong(EditableNote::end)).toList();
            require(before.size() == after.size(), "Fixture note count differs");
            for (int i=0; i<before.size(); i++) {
                EditableNote a=before.get(i), b=after.get(i);
                require(a.pitch()==b.pitch(), "Fixture changed a pitch at note " + i);
                require(Math.abs(a.start()-b.start())<=1 && Math.abs(a.end()-b.end())<=1,
                        "Fixture introduced more than one editor tick of drift at note " + i);
            }
        }
        System.out.println("PASS: microsecond ABC export, 60 ms LOTRO minimum, chords, barline rounding and note timing");
    }
}
