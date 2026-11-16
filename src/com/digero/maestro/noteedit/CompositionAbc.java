package com.digero.maestro.noteedit;

import java.util.*;
import java.util.regex.*;

/**
 * ABC-only composition scaffolding. No dummy MIDI is created and no generated
 * part is changed. In particular, an ordinary imported project still omits
 * empty tracks. A missing part is represented in the editor by a rest-only
 * header; export adds it only when it contains authored notes.
 */
public final class CompositionAbc {
    private CompositionAbc() {}
    public record PartSpec(int number, String title, String instrument, int tempo, String meter, String key) {
        public PartSpec {
            if(number < 1 || number > 10000) throw new IllegalArgumentException("Part number out of range");
            Objects.requireNonNull(title); Objects.requireNonNull(instrument);
            Objects.requireNonNull(meter); Objects.requireNonNull(key);
            if(tempo < 20 || tempo > 400) throw new IllegalArgumentException("Tempo out of range");
        }
    }
    private static final Pattern PART = Pattern.compile("(?m)^X:\\s*(\\d+)\\s*$");

    public static String starter(String title,int tempo,String meter,String key,String instrument,int measures) {
        if(measures < 1 || measures > 2000) throw new IllegalArgumentException("Measures must be 1–2000");
        PartSpec part=new PartSpec(1,title,instrument,tempo,meter,key);
        StringBuilder abc=new StringBuilder("%abc-2.1\n");
        abc.append(header(part));
        // A rest is not a note: no notes will appear on the source track.
        // This keeps normal note generation disabled until the composer edits.
        for(int i=0;i<measures;i++)abc.append("z").append(measureRestUnits(meter)).append(" |\n");
        return abc.append("\n").toString();
    }
    private static int measureRestUnits(String meter) {
        String[] values=meter.split("/");
        if(values.length!=2)throw new IllegalArgumentException("Invalid meter");
        int top=Integer.parseInt(values[0]),bottom=Integer.parseInt(values[1]);
        if(top<=0 || bottom<=0 || bottom>8 || (bottom & (bottom-1))!=0)
            throw new IllegalArgumentException("Invalid meter");
        return top * 8 / bottom; // for e.g. 3/4 => 6 eighth notes
    }
    public static String header(PartSpec part) {
        return "X: " + part.number + "\n" +
            "T: " + safe(part.title) + "\n" +
            "Q: 1/4=" + part.tempo + "\n" +
            "M: " + part.meter + "\n" +
            "L: 1/8\n" +
            "%%made-for " + safe(part.instrument) + "\n" +
            "K: " + part.key + "\n";
    }
    private static String safe(String text) {return text.replace('\r',' ').replace('\n',' ').strip();}
    /**
     * Add rest-only editor baselines for otherwise omitted parts. This method is
     * NEVER used for normal generated ABC export.
     */
    public static String forEditor(String generated,Collection<PartSpec> parts) {
        Set<Integer> existing=new HashSet<>();
        Matcher m=PART.matcher(generated);
        while(m.find())existing.add(Integer.parseInt(m.group(1)));
        StringBuilder out=new StringBuilder(generated);
        for(PartSpec part:parts)if(existing.add(part.number)) {
            if(!out.isEmpty() && out.charAt(out.length()-1)!='\n')out.append('\n');
            out.append('\n').append(header(part)).append('z').append(measureRestUnits(part.meter)).append(" |]\n\n");
        }
        return out.toString();
    }
    /** Only add missing parts that are actually populated by the user. */
    public static String forExport(String generated,Collection<PartSpec> parts,
                                   Map<Integer,NoteEditorDelta.Part> edits,boolean newComposition) {
        List<PartSpec> occupied=new ArrayList<>();
        for(PartSpec part:parts) {
            NoteEditorDelta.Part change=edits.get(part.number);
            // An empty composition is allowed to export one empty part so the
            // resulting file is still a valid, importable ABC starter.
            // An authored part with only out-of-range notes must not create an
            // otherwise empty export track. Those notes remain in the MSX.
            int shift=12*LotroPatch.octaveDeltaFor(part.instrument);
            if(change!=null && change.added().stream().anyMatch(note ->
                LotroPatch.isPlayableSoundingPitch(part.instrument,note.pitch(),shift)))
                occupied.add(part);
        }
        if(newComposition && occupied.isEmpty() && !parts.isEmpty())occupied.add(parts.iterator().next());
        return forEditor(generated,occupied);
    }
}
