package com.digero.maestro.noteedit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Run with: java -cp <patched classes>:<Maestro.jar> com.digero.maestro.noteedit.AbcAccidentalRegression [msx abc output] */
public final class AbcAccidentalRegression {
    private static void check(boolean b, String message) {if(!b) throw new AssertionError(message);}

    /** The public export and importer must agree on pitches across repeated accidentals,
     * different octaves, chords, and resets at measure boundaries. */
    private static void synthetic() throws Exception {
        String header="%abc-2.1\nX: 1\nT: Accidental Test\nQ: 1/4=100\nM: 4/4\nL: 1/8\n%%made-for Basic Bagpipe\nK: Gm\n";
        NoteDocument doc=AbcDocumentIO.readText(header+"z8 |]\n");
        NoteDocument.Layer layer=doc.layers.getFirst();
        // Write in the octave used by the Basic Bagpipe: the note names in
        // this sequence are relative to its published sounding-pitch shift.
        int s=layer.abcPitchShift;
        int[] written={58,57,58,57,69,70,69,58,57,57,58,57,58,57,57};
        // Gm key has Bb, so B natural (59) requires =B even without a prior accidental.
        // The last notes cross the measure boundary to verify state reset.
        long[] starts={0,240,480,720,960,960,1200,1440,1680,1920,2160,2400,2880,3360,3840};
        for(int i=0;i<written.length;i++)
            layer.notes.add(EditableNote.create(written[i]+s,80,0,starts[i],starts[i]+240));
        layer.notes.add(EditableNote.create(59+s,80,0,4200,4440));
        layer.edited=true;
        String abc=AbcDocumentIO.toText(doc);
        check(abc.contains("^A") && abc.contains("=A"),"Sharp -> natural never writes =A");
        check(abc.contains("=B"),"The key signature's B flat must be naturalized");
        NoteDocument parsed=AbcDocumentIO.readText(abc);
        List<EditableNote> original=new ArrayList<>(layer.notes);
        List<EditableNote> actual=parsed.layers.getFirst().notes;
        check(actual.size()==original.size(),"Synthetic note count "+original.size()+" != "+actual.size());
        Comparator<EditableNote> order=Comparator.comparingLong(EditableNote::start)
            .thenComparingInt(EditableNote::pitch);
        original.sort(order);
        actual=new ArrayList<>(actual);actual.sort(order);
        for(int i=0;i<original.size();i++){
            EditableNote a=original.get(i),b=actual.get(i);
            check(a.pitch()==b.pitch() && Math.abs(a.start()-b.start())<=1 && Math.abs(a.end()-b.end())<=1,
                "Synthetic mismatch: "+a+" vs "+b);
        }
        System.out.println("PASS: synthetic sharp/natural in same bar, chords, octaves, G minor signature and barline round trip");
    }

    /** Reconstitute ALL authored notes directly from the user's MSX, rather
     * than using pitches decoded from their already-damaged ABC export. */
    private static void project(Path msx,Path abc,Path corrected) throws Exception {
        var dbf=DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities",false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        Document xml=dbf.newDocumentBuilder().parse(msx.toFile());
        Element root=xml.getDocumentElement();
        Map<Integer,List<EditableNote>> additions=new HashMap<>();
        NodeList parts=root.getElementsByTagName("partDelta");
        for(int i=0;i<parts.getLength();i++){
            Element part=(Element)parts.item(i);
            int id=Integer.parseInt(part.getAttribute("number"));
            List<EditableNote> notes=new ArrayList<>();
            NodeList children=part.getElementsByTagName("add");
            for(int j=0;j<children.getLength();j++){
                Element add=(Element)children.item(j);
                notes.add(EditableNote.create(Integer.parseInt(add.getAttribute("pitch")),
                    Integer.parseInt(add.getAttribute("velocity")),0,
                    Long.parseLong(add.getAttribute("start")),Long.parseLong(add.getAttribute("end"))));
            }
            additions.put(id,notes);
        }
        Map<Integer,Set<Integer>> routes=new HashMap<>();
        for(int i=0;i<root.getElementsByTagName("authoredTrack").getLength();i++){
            Element r=(Element)root.getElementsByTagName("authoredTrack").item(i);
            Set<Integer> targets=new HashSet<>();
            for(String dest:r.getAttribute("destinations").split(","))
                if(!dest.isBlank())targets.add(Integer.parseInt(dest.strip()));
            routes.put(Integer.parseInt(r.getAttribute("source")),targets);
        }
        NoteDocument original=AbcDocumentIO.readText(Files.readString(abc,StandardCharsets.UTF_8));
        Map<Integer,List<EditableNote>> routed=new HashMap<>();
        for(var entry:additions.entrySet())
            for(int target:AuthoredTrackRouting.targets(entry.getKey(),routes))
                routed.computeIfAbsent(target,k->new ArrayList<>()).addAll(entry.getValue());
        int input=0;
        for(NoteDocument.Layer l:original.layers){
            l.notes.clear();
            l.notes.addAll(routed.getOrDefault(l.partNumber,List.of()));
            input+=l.notes.size();
            l.edited=true;
        }
        String encoded=AbcDocumentIO.toText(original);
        Files.writeString(corrected,encoded,StandardCharsets.UTF_8);
        NoteDocument reimport=AbcDocumentIO.readText(encoded);
        int exact=0,near=0,different=0,missing=0;
        int output=0;
        int pitchHistogramDisagreements=0;
        long maximalSamePitchOnsetShift=0;
        for(int i=0;i<original.layers.size();i++){
            NoteDocument.Layer before=original.layers.get(i),after=reimport.layers.get(i);
            output+=after.notes.size();
            for(int pitch=0;pitch<128;pitch++) {
                final int p=pitch;
                List<Long> fromMsx=before.notes.stream().filter(n->n.pitch()==p)
                    .map(EditableNote::start).sorted().toList();
                List<Long> exported=after.notes.stream().filter(n->n.pitch()==p)
                    .map(EditableNote::start).sorted().toList();
                pitchHistogramDisagreements+=Math.abs(fromMsx.size()-exported.size());
                for(int j=0;j<Math.min(fromMsx.size(),exported.size());j++)
                    maximalSamePitchOnsetShift=Math.max(maximalSamePitchOnsetShift,
                        Math.abs(fromMsx.get(j)-exported.get(j)));
            }
            // Pair exact/near onset notes pitch-by-pitch; the previously documented
            // same-pitch retrigger normalizer can remove simultaneous unisons.
            Map<Long,List<EditableNote>> indexed=new TreeMap<>();
            for(EditableNote n:after.notes) indexed.computeIfAbsent(n.start(),k->new ArrayList<>()).add(n);
            for(EditableNote n:before.notes){
                EditableNote match=null;
                for(long dt=-2;dt<=2 && match==null;dt++){
                    List<EditableNote> candidates=indexed.get(n.start()+dt);
                    if(candidates==null)continue;
                    for(EditableNote c:candidates)if(c.pitch()==n.pitch()) {match=c;break;}
                    if(match!=null){candidates.remove(match);break;}
                }
                if(match!=null){
                    if(match.start()==n.start() && match.end()==n.end())exact++;
                    else near++;
                } else {
                    // Some same-pitch reattacks may be suppressed when simultaneous.
                    boolean any=false;
                    for(long dt=-2;dt<=2;dt++){
                        List<EditableNote> candidates=indexed.get(n.start()+dt);
                        if(candidates!=null && !candidates.isEmpty())any=true;
                    }
                    if(any)different++;else missing++;
                }
            }
        }
        System.out.printf("PROJECT: MSX notes=%d reimported=%d same-pitch events=%d near-onset=%d at-different-timing=%d pitch-histogram discrepancies=%d max within-pitch onset shift=%d ticks%n",
            input,output,exact,near,missing,pitchHistogramDisagreements,maximalSamePitchOnsetShift);
        check(input==output,"Exported note count changed");
        check(pitchHistogramDisagreements==0,
            "Reimported pitch counts differ from saved MSX for "+pitchHistogramDisagreements+" events");
        check(different==0,"Exported note pitches differ from MSX at "+different+" same-onset events");
        System.out.println("PASS: full MSX re-export and ABC reimport preserve authored pitches and note count");
    }
    public static void main(String[] args) throws Exception {
        synthetic();
        if(args.length==3)project(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]));
    }
}
