package com.digero.maestro.noteedit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;

/**
 * Conservative importer for a subset of Maestro-exported ABC 2.1.
 * Unedited tune sections are preserved byte-for-byte (after UTF-8 decoding).
 * Supported: K signatures, notes, chords, rests, lengths, accidentals, ties,
 * barlines, simple decorations. Repeats, tuplets, inline headers and voices
 * fail closed instead of silently changing the music.
 */
public final class AbcDocumentIO {
    private AbcDocumentIO() {}
    public static final int PPQ = 960;
    private static final Pattern PART_START = Pattern.compile("(?m)^X\\s*:");
    private static final Pattern FIELD = Pattern.compile("(?m)^([A-Za-z]):\\s*(.*)$");
    private static final int[] NATURAL = {0, 2, 4, 5, 7, 9, 11}; // C D E F G A B
    // LOTRO rejects any note/rest shorter than 60 ms. Ten microseconds of
    // headroom also covers the rounding-sensitive tempo values used by LOTRO.
    private static final long LOTRO_MIN_DURATION_MICROS = 60_010L;
    private static final double MICROS_PER_MINUTE = 60_000_000.0;

    public static NoteDocument read(File file) throws IOException {
        String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return readText(source,file);
    }
    public static NoteDocument readText(String source) throws IOException {
        return readText(source,new File("generated-arrangement.abc"));
    }
    private static NoteDocument readText(String source,File file) throws IOException {
        Matcher parts = PART_START.matcher(source);
        List<Integer> starts = new ArrayList<>();
        while (parts.find()) starts.add(parts.start());
        if (starts.isEmpty()) throw new IOException("No X: tune parts in ABC file.");
        NoteDocument doc = new NoteDocument(NoteDocument.Kind.ABC, PPQ, javax.sound.midi.Sequence.PPQ, file);
        doc.abcPreamble = source.substring(0, starts.getFirst());
        for (int i = 0; i < starts.size(); i++) {
            String text = source.substring(starts.get(i), i+1 < starts.size()? starts.get(i+1) : source.length());
            int split = -1;
            int pos = 0;
            while (pos < text.length()) {
                int end = text.indexOf('\n', pos);
                if (end < 0) end = text.length();
                String line = text.substring(pos, end).stripLeading();
                if (line.startsWith("K:")) { split = end < text.length() ? end + 1 : end; break; }
                pos = end + 1;
            }
            if (split < 0) throw new IOException("ABC part " + (i+1) + " has no K: header.");
            String header = text.substring(0, split);
            String body = text.substring(split);
            String title = field(header, "T", "Part " + (i+1));
            String key = field(header, "K", "C");
            String l = field(header, "L", "1/8");
            double base = baseTicks(l);
            int[] signature = keySignature(key);
            NoteDocument.Layer layer = new NoteDocument.Layer(title);
            layer.abcSource = text; layer.abcHeader = header;
            layer.instrumentName = directive(header, "made-for", title);
            layer.lotroProgram = LotroPatch.programFor(layer.instrumentName);
            layer.abcPitchShift = 12 * LotroPatch.octaveDeltaFor(layer.instrumentName);
            layer.abcTempo = tempo(field(header, "Q", "120"));
            layer.measureTicks = measureTicks(field(header, "M", "4/4"));
            try { layer.partNumber = Integer.parseInt(field(header,"X",String.valueOf(i+1))); }
            catch (NumberFormatException ex) { layer.partNumber = i+1; }
            if (i == 0) doc.measureTicks = layer.measureTicks;
            try { parseBody(body, base, signature, layer.abcPitchShift, layer.notes); }
            catch (IOException ex) { throw new IOException("ABC part " + (i+1) + " ("+title+"): " + ex.getMessage(), ex); }
            layer.baselineNotes.addAll(layer.notes);
            doc.layers.add(layer);
        }
        return doc;
    }
    private static String field(String header, String name, String fallback) {
        Matcher m = FIELD.matcher(header);
        while (m.find()) if (m.group(1).equals(name)) return m.group(2).trim();
        return fallback;
    }
    private static String directive(String header, String name, String fallback) {
        Pattern p = Pattern.compile("(?m)^%%" + Pattern.quote(name) + "\\s+(.+)$");
        Matcher m = p.matcher(header);
        return m.find() ? m.group(1).trim() : fallback;
    }

    /**
     * Organic Maestro exports deliberately use very large L: denominators.
     * These are valid ABC rational lengths, not MIDI tick values. Keep the
     * unit as a double and accumulate musical time BEFORE rounding individual
     * event endpoints to our 960-PPQ edit grid. That prevents cumulative drift
     * from rounding each tiny unit separately.
     */
    private static double baseTicks(String fraction) throws IOException {
        String[] s = fraction.strip().split("/", -1);
        try {
            if (s.length < 1 || s.length > 2) throw new NumberFormatException();
            long n = Long.parseLong(s[0].trim());
            long d = s.length == 2 ? Long.parseLong(s[1].trim()) : 1L;
            if (d <= 0 || n <= 0) throw new NumberFormatException();
            double result = 4.0 * PPQ * (n / (double) d);
            if (!Double.isFinite(result) || result <= 0)
                throw new NumberFormatException();
            return result;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid L: " + fraction, e);
        }
    }

    private static int tempo(String value) throws IOException {
        try {
            String bpm = value.strip();
            int equals = bpm.lastIndexOf('=');
            if (equals >= 0) bpm = bpm.substring(equals + 1).strip();
            int n = (int)Math.round(Double.parseDouble(bpm));
            if (n < 1 || n > 1000) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException ex) {
            // Tempo in an unusual ABC expression is harmless for editing notes;
            // preview defaults to 120 BPM rather than rejecting the entire part.
            return 120;
        }
    }

    private static int[] keySignature(String key) throws IOException {
        String k = key.trim().split("\\s+")[0];
        boolean minor = k.endsWith("m") || k.endsWith("min");
        String root = k.replaceAll("(?i)(minor|major|maj|min|m)$", "");
        if (root.length() < 1 || root.length() > 2) throw new IOException("Unsupported key signature: " + key);
        // Circle of fifths for major + minor relative majors.
        String[] majors = {"Cb","Gb","Db","Ab","Eb","Bb","F","C","G","D","A","E","B","F#","C#"};
        String[] minors = {"Ab","Eb","Bb","F","C","G","D","A","E","B","F#","C#","G#","D#","A#"};
        int fifths = 99;
        for (int i=0;i<majors.length;i++) if ((minor? minors[i]: majors[i]).equalsIgnoreCase(root)) fifths=i-7;
        if (fifths == 99) throw new IOException("Unsupported key signature: " + key);
        int[] alt = new int[7]; // C,D,E,F,G,A,B
        int[] sharpOrder={3,0,4,1,5,2,6}, flatOrder={6,2,5,1,4,0,3};
        for (int j=0;j<Math.abs(fifths);j++) alt[fifths>0?sharpOrder[j]:flatOrder[j]]=Integer.signum(fifths);
        return alt;
    }
    private static final class Scanner {
        final String s; int pos;
        Scanner(String s){this.s=s;}
        boolean has(){return pos<s.length();}
        char peek(){return s.charAt(pos);}
        char next(){return s.charAt(pos++);}
        void skipSpace(){while(has() && Character.isWhitespace(peek())) pos++;}
    }
    private record Tone(int pitch, double length, boolean tie) {}
    private static void parseBody(String source, double base, int[] key, int pitchShift, List<EditableNote> notes) throws IOException {
        StringBuilder music = new StringBuilder();
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("%") || trimmed.isEmpty()) continue;
            if (trimmed.matches("[A-Za-z]:.*")) throw new IOException("Inline ABC header not yet supported: " + trimmed);
            int comment = line.indexOf('%');
            music.append(comment>=0? line.substring(0,comment):line).append(' ');
        }
        Scanner s = new Scanner(music.toString());
        double time=0;
        int velocity=90;
        Map<Integer,Integer> barAcc=new HashMap<>();
        Map<Integer,Integer> tied=new HashMap<>();
        while(s.has()) {
            s.skipSpace(); if(!s.has()) break;
            char c=s.peek();
            if(c=='|' || c==':') {
                s.next();
                if(s.has() && (s.peek()==':' || (c==':' && s.peek()=='|')))
                    throw new IOException("Repeat barlines are not supported");
                if(s.has() && s.peek()=='|') s.next();
                if(s.has() && s.peek()==']') s.next();
                barAcc.clear(); continue;
            }
            if(c=='[' && s.pos+1<s.s.length() && s.s.charAt(s.pos+1)=='|') { s.pos+=2; barAcc.clear(); continue; }
            if(c==']') throw new IOException("Unexpected ']' at " + s.pos);
            if(c=='(' || c=='>' || c=='<' || c=='{' || c=='}' || c=='&' || c=='\\')
                throw new IOException("Tuplets, broken rhythms and multiple voices are not supported near position " + s.pos);
            if(c=='+' || c=='!' || c=='"') {
                char q=s.next(); int close=s.s.indexOf(q,s.pos);
                if(close<0) throw new IOException("Unclosed decoration");
                String value=s.s.substring(s.pos,close).toLowerCase(Locale.ROOT);
                int index=Arrays.asList("pppp","ppp","pp","p","mp","mf","f","ff","fff","ffff").indexOf(value);
                if(index>=0)velocity=new int[]{4,16,32,48,64,80,96,112,127,127}[index];
                s.pos=close+1; continue;
            }
            if(c=='~' || c=='.') { s.next(); continue; }
            if(c=='z' || c=='x' || c=='Z') {
                s.next(); time+=duration(s,base); tied.clear(); continue;
            }
            List<Tone> chord=new ArrayList<>(); double advance;
            if(c=='[') {
                s.next();
                while(s.has() && s.peek()!=']') {s.skipSpace(); if(!s.has())break; if(s.peek()==']')break;
                    chord.add(tone(s,base,key,barAcc,pitchShift));}
                if(!s.has() || s.next()!=']' || chord.isEmpty()) throw new IOException("Bad chord");
                // An explicit duration after ] scales the chord's note durations.
                double multiplier=duration(s,base);
                // duration() returns base by default; for explicit suffix, scale by multiplier/base.
                for(int i=0;i<chord.size();i++) {
                    Tone n=chord.get(i);
                    double dur=n.length() * (multiplier/base);
                    chord.set(i,new Tone(n.pitch(),dur,n.tie()));
                }
                advance=chord.stream().mapToDouble(Tone::length).max().orElse(base);
            } else {
                chord.add(tone(s,base,key,barAcc,pitchShift)); advance=chord.getFirst().length();
            }
            long onset = roundedTick(time);
            for(Tone n:chord) {
                long end = Math.max(onset + 1, roundedTick(time + n.length()));
                Integer previous=tied.remove(n.pitch());
                if(previous!=null && notes.get(previous).end()==onset) {
                    EditableNote old=notes.get(previous);
                    notes.set(previous,old.resize(end));
                    if(n.tie()) tied.put(n.pitch(), previous);
                } else {
                    notes.add(EditableNote.create(n.pitch(),velocity,0,onset,end));
                    if(n.tie())tied.put(n.pitch(),notes.size()-1);
                }
            }
            time+=advance;
            if (!Double.isFinite(time) || time > Long.MAX_VALUE / 4)
                throw new IOException("ABC timeline is too long");
            if(notes.size()>1_000_000)throw new IOException("Too many ABC notes");
        }
    }
    private static Tone tone(Scanner s,double base,int[] key,Map<Integer,Integer> barAcc,int pitchShift) throws IOException {
        int alteration=0; boolean explicit=false;
        while(s.has() && (s.peek()=='^' || s.peek()=='_' || s.peek()=='=')) {
            char a=s.next(); explicit=true;
            if(a=='=') alteration=0;
            else alteration+=a=='^'?1:-1;
        }
        if(!s.has()) throw new IOException("Missing ABC note pitch");
        char c=s.next();
        int index="CDEFGAB".indexOf(Character.toUpperCase(c));
        if(index<0) throw new IOException("Unsupported ABC symbol '"+c+"' at "+(s.pos-1));
        int octave=Character.isLowerCase(c)?4:3;
        while(s.has() && (s.peek()=='\'' || s.peek()==','))octave+=s.next()=='\''?1:-1;
        int natural=(octave+1)*12+NATURAL[index];
        if(explicit)barAcc.put(natural,alteration);
        else alteration=barAcc.getOrDefault(natural,key[index]);
        int pitch=natural+alteration+pitchShift;
        if(pitch<0 || pitch>127)throw new IOException("Instrument sounding pitch outside MIDI range");
        double length=duration(s,base);
        boolean tie=s.has() && s.peek()=='-';
        if(tie)s.next();
        return new Tone(pitch,length,tie);
    }
    private static double duration(Scanner s,double base) throws IOException {
        long n=0,d=1; boolean digits=false;
        while(s.has() && Character.isDigit(s.peek())) {digits=true; n=n*10+(s.next()-'0'); if(n>100000000)throw new IOException("Duration too large");}
        if(!digits)n=1;
        if(s.has() && s.peek()=='/') {
            s.next();
            if(s.has() && Character.isDigit(s.peek())) {
                d=0;
                while(s.has() && Character.isDigit(s.peek())) {d=d*10+(s.next()-'0'); if(d>100000000)throw new IOException("Duration denominator too large");}
            } else {
                d=2;
                while(s.has() && s.peek()=='/') {s.next();d*=2;}
            }
        }
        if(d==0 || n==0)throw new IOException("Invalid zero duration");
        return base*(n/(double)d);
    }

    private static long roundedTick(double t) throws IOException {
        if (!Double.isFinite(t) || t < 0 || t > Long.MAX_VALUE / 4)
            throw new IOException("Invalid ABC timing");
        return Math.round(t);
    }

    public static void write(NoteDocument doc,File out) throws IOException {
        Files.writeString(out.toPath(), toText(doc), StandardCharsets.UTF_8);
    }
    public static String toText(NoteDocument doc) throws IOException {
        if(doc.kind!=NoteDocument.Kind.ABC)throw new IllegalArgumentException("Not ABC");
        StringBuilder result=new StringBuilder(doc.abcPreamble);
        for(NoteDocument.Layer layer:doc.layers) {
            if(!layer.edited) { result.append(layer.abcSource); continue; }
            String header=layer.abcHeader;
            String meter = field(header, "M", "4/4");
            int bpm = layer.abcTempo;
            long abcUnitDenominator = organicUnitDenominator(bpm, meter);
            // The 960-PPQ editor grid rounds a 60 ms note to 85 ticks at
            // Q:89, which is only 59.691 ms if written using L:1/32.
            // Instead write durations in approximately one-microsecond units.
            if (Pattern.compile("(?m)^L:.*$").matcher(header).find())
                header=header.replaceFirst("(?m)^L:.*$","L: 1/" + abcUnitDenominator);
            else
                header=header.replaceFirst("(?m)^K:","L: 1/" + abcUnitDenominator + "\nK:");
            header=header.replaceFirst("(?m)^K:.*$","K: C");
            result.append(header).append(render(layer.notes, measureTicks(meter),layer.abcPitchShift,bpm));
        }
        return result.toString();
    }
    private static int meterDenominator(String meter) throws IOException {
        if (meter.equalsIgnoreCase("C")) return 4;
        if (meter.equalsIgnoreCase("C|")) return 2;
        try {
            String[] fields=meter.split("/");
            if (fields.length!=2) throw new NumberFormatException();
            int den=Integer.parseInt(fields[1].trim());
            if (den<1 || den>64) throw new NumberFormatException();
            return den;
        } catch(NumberFormatException ex) {
            throw new IOException("Unsupported ABC meter: " + meter, ex);
        }
    }
    /** Give one written ABC length unit approximately one microsecond. */
    private static long organicUnitDenominator(int bpm, String meter) throws IOException {
        if (bpm <= 0) throw new IOException("Invalid ABC tempo " + bpm);
        return Math.round(MICROS_PER_MINUTE * meterDenominator(meter) / bpm);
    }
    private static long microsAtTick(long tick, int bpm) {
        return Math.round(tick * MICROS_PER_MINUTE / (PPQ * (double) bpm));
    }
    private static long measureTicks(String meter) throws IOException {
        if (meter.equalsIgnoreCase("C")) return PPQ * 4L;
        if (meter.equalsIgnoreCase("C|")) return PPQ * 2L;
        try {
            String[] parts=meter.split("/");
            if(parts.length!=2) throw new NumberFormatException();
            long numerator=Long.parseLong(parts[0].trim()),denominator=Long.parseLong(parts[1].trim());
            if(numerator<1 || numerator>64 || denominator<1 || denominator>64)
                throw new NumberFormatException();
            long length=Math.round((4.0*PPQ*numerator)/denominator);
            if(length<1) throw new NumberFormatException();
            return length;
        } catch(NumberFormatException ex) { throw new IOException("Unsupported meter for edited ABC part: " + meter); }
    }
    private static String render(List<EditableNote> notes, long bar, int pitchShift, int bpm) throws IOException {
        TreeSet<Long> edges=new TreeSet<>(); edges.add(0L);
        long finish=0;
        for(EditableNote n:notes){edges.add(n.start());edges.add(n.end());finish=Math.max(finish,n.end());}
        if(finish==0)return "z" + Math.max(LOTRO_MIN_DURATION_MICROS, microsAtTick(bar,bpm)) + " |]\n\n";
        // Organic ABC to 960-PPQ rounding can leave note endpoints only
        // 1-2 ticks away from a bar boundary. Snap them TO the bar boundary,
        // otherwise the exporter emits an illegal sub-millisecond fragment
        // before/after the '|' and pads it to a whole 60 ms note.
        Map<Long,Long> toBar=new HashMap<>();
        TreeSet<Long> alignedEdges=new TreeSet<>();
        for(long t:edges) {
            long r=t%bar;
            long aligned = r<=2 ? t-r : (bar-r<=2 ? t+(bar-r) : t);
            toBar.put(t,aligned);
            alignedEdges.add(aligned);
        }
        for(long t=bar;t<finish;t+=bar)alignedEdges.add(t);
        // Coalesce only 1-2 tick rounding artifacts between other events,
        // never merge a real musical duration or erase a measure line.
        Map<Long,Long> snaps=new HashMap<>();
        long anchor=-1;
        for(long t:alignedEdges) {
            if (anchor>=0 && t-anchor<=2 && t%bar!=0) {
                snaps.put(t,anchor);
            } else {
                anchor=t;
                snaps.put(t,t);
            }
        }
        List<EditableNote> adjustedNotes=new ArrayList<>(notes.size());
        edges.clear(); edges.add(0L);
        for(EditableNote n:notes) {
            long start=snaps.get(toBar.get(n.start())), end=snaps.get(toBar.get(n.end()));
            if(end<=start) throw new IOException("Note shorter than the LOTRO minimum after timing normalization: "+n.pitch());
            EditableNote clean=new EditableNote(n.id(),n.pitch(),n.velocity(),n.channel(),start,end);
            adjustedNotes.add(clean);
            edges.add(start); edges.add(end);
        }
        for(long t=bar;t<finish;t+=bar)edges.add(t);
        List<Long> ticks=new ArrayList<>(edges);
        Map<Long,List<EditableNote>> noteStarts=new HashMap<>(), noteEnds=new HashMap<>();
        for(EditableNote note:adjustedNotes) {
            noteStarts.computeIfAbsent(note.start(), ignored->new ArrayList<>()).add(note);
            noteEnds.computeIfAbsent(note.end(), ignored->new ArrayList<>()).add(note);
        }
        Map<Integer,EditableNote> active=new TreeMap<>();
        StringBuilder out=new StringBuilder();
        int currentVelocity=90;
        long timingErrorMicros=0;
        for(int i=0;i<ticks.size()-1;i++) {
            long start=ticks.get(i),end=ticks.get(i+1);
            if(start>=finish)break;
            if(start>0 && start%bar==0)out.append("|\n");
            for(EditableNote n:noteEnds.getOrDefault(start,List.of())) active.remove(n.pitch(),n);
            for(EditableNote n:noteStarts.getOrDefault(start,List.of())) {
                if(active.containsKey(n.pitch()))
                    throw new IOException("Overlapping identical pitches cannot be represented in this ABC v1 exporter");
                active.put(n.pitch(),n);
            }
            List<EditableNote> sounding=new ArrayList<>(active.values());
            if(!sounding.isEmpty()) {
                // ABC has shared dynamics for a chord, not per-note MIDI velocities.
                // Preserve a best-fit dynamic when notes start; the editor's MIDI
                // path retains exact 1..127 velocities.
                int target=noteStarts.getOrDefault(start,List.of()).stream()
                    .mapToInt(EditableNote::velocity).max().orElse(currentVelocity);
                if(target!=currentVelocity){
                    String[] markings={"pppp","ppp","pp","p","mp","mf","f","ff","fff"};
                    int[] levels={4,16,32,48,64,80,96,112,127};
                    int best=0;for(int j=1;j<levels.length;j++)
                        if(Math.abs(target-levels[j])<Math.abs(target-levels[best]))best=j;
                    out.append('+').append(markings[best]).append('+');
                    currentVelocity=levels[best];
                }
            }
            long nominalMicros = microsAtTick(end,bpm) - microsAtTick(start,bpm);
            // A shorter segment cannot be played by LOTRO. Borrow the tiny
            // rounding difference from the next longer segment (usually a rest)
            // so that subsequent note onsets stay aligned to the editor grid.
            long writtenMicros = Math.max(LOTRO_MIN_DURATION_MICROS, nominalMicros - timingErrorMicros);
            timingErrorMicros += writtenMicros - nominalMicros;
            String length=Long.toString(writtenMicros);
            if(sounding.isEmpty())out.append('z').append(length);
            else if(sounding.size()==1) {
                EditableNote n=sounding.getFirst();
                out.append(pitch(n.pitch()-pitchShift)).append(length);
                if(n.end()>end)out.append('-');
            } else {
                // LOTRO does not accept a length after ']'. Each member of a
                // chord must carry its own duration, even when all are equal.
                out.append('[');
                for(EditableNote n:sounding) {
                    out.append(pitch(n.pitch()-pitchShift)).append(length);
                    if(n.end()>end)out.append('-');
                }
                out.append(']');
            }
            out.append(' ');
            if(out.length()>5000000)throw new IOException("ABC output too large");
        }
        out.append("|]\n\n");
        return out.toString();
    }
    private static String pitch(int p) throws IOException {
        if (p < 0 || p > 127) throw new IOException("Edited pitch outside LOTRO ABC range");
        int octave=p/12-1, semitone=p%12;
        String[] spelling={"C","^C","D","^D","E","F","^F","G","^G","A","^A","B"};
        String val=spelling[semitone];
        if(octave>=4)val=val.toLowerCase(Locale.ROOT);
        for(int i=0;i<Math.abs(octave-(octave>=4?4:3));i++)val+=octave>=4?"'":",";
        return val;
    }
}
