import com.digero.maestro.abc.*;
import com.digero.maestro.midi.*;
import com.digero.maestro.noteedit.*;
import com.digero.maestro.util.*;
import com.digero.maestro.view.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.*;

/** Run with recovered MSX and original ABC as arguments. Also builds a native composition. */
public class AuthoredTuneTimingRegression {
    static final Preferences prefs = Preferences.userRoot().node("maestro-tune-regression-"+System.nanoTime());
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static AbcSong load(File file,File source)throws Exception {
        FileResolver resolver=new FileResolver(){public File locateFile(File f,String s){return source;}public File resolveFile(File f,String s){return source;}};
        return new AbcSong(file,new PartAutoNumberer(prefs.node("number")),new PartNameTemplate(prefs.node("parts")),new ExportFilenameTemplate(prefs.node("export")),new InstrNameSettings(prefs.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(prefs.node("settings")));
    }
    static void apply(AbcSong song,TuneLine... lines){
        NavigableMap<Float,TuneLine> old=new TreeMap<>();
        if(song.tuneBars!=null)for(var entry:song.tuneBars.entrySet())old.put(entry.getKey(),new TuneLine(entry.getValue()));
        song.tuneBars=new TreeMap<>(); for(TuneLine line:lines)song.tuneBars.put(line.startBar,line);
        song.tuneEdited(old);
    }
    static TuneLine tune(float start,float end,int tempo,int accel,int pitch,int fade){
        TuneLine t=new TuneLine();t.startBar=start;t.endBar=end;t.tempo=tempo;t.accelerando=accel;t.seminoteStep=pitch;t.fade=fade;return t;
    }
    static int count(AbcSong s){return s.getNoteEditorDeltas().values().stream().mapToInt(p->p.added().size()).sum();}
    static double seconds(long tick,int bpm){return tick*60.0/(960*bpm);}
    static int previewCount(AbcSong song)throws Exception {
        var info=SequenceInfo.fromAbcParts(song,true,true);
        int total=0; var cache=info.getDataCache();long origin=song.getAbcExporter().getExportStartTick();
        double tolerance=0.025; // enough for note grid and MIDI tick rounding, not a tempo error
        for(var tr:info.getLastTrackInfos())if(tr instanceof AuthoredPreviewTrackInfo authored){
            var notes=song.getNoteEditorDeltas().get(authored.sourcePartNumber).added();
            var events=tr.noteEvents;
            check(events.size()==notes.size(),"preview lost authored notes X:"+authored.sourcePartNumber+" "+events.size()+" != "+notes.size());
            var starts=notes.stream().map(NoteEditorDelta.Value::start).sorted().toList();
            var ends=notes.stream().map(NoteEditorDelta.Value::end).sorted().toList();
            var actualStarts=events.stream().mapToLong(e->cache.tickToMicros(e.getStartTick())-cache.tickToMicros(origin)).sorted().toArray();
            var actualEnds=events.stream().mapToLong(e->cache.tickToMicros(e.getEndTick())-cache.tickToMicros(origin)).sorted().toArray();
            for(int j=0;j<events.size();j++){
                check(Math.abs(actualStarts[j]/1e6-seconds(starts.get(j),song.getTempoBPM()))<tolerance,"preview start doesn't match written note X:"+authored.sourcePartNumber+" "+actualStarts[j]+" vs "+starts.get(j));
                check(Math.abs(actualEnds[j]/1e6-seconds(ends.get(j),song.getTempoBPM()))<tolerance,"preview end doesn't match written note");
            }
            total+=events.size();
        }
        return total;
    }
    static void verifyRoundTrip(Map<Integer,NoteEditorDelta.Part> original,AbcSong song,long tolerance){
        for(var e:original.entrySet()){
            var now=song.getNoteEditorDeltas().get(e.getKey()).added();var old=e.getValue().added();check(now.size()==old.size(),"lost notes");
            for(int i=0;i<old.size();i++){
                var a=old.get(i);var b=now.get(i);check(a.pitch()==b.pitch(),"pitch changed");
                check(Math.abs(a.start()-b.start())<=tolerance && Math.abs(a.end()-b.end())<=tolerance,"round-trip timing drift: "+a+" vs "+b);
            }
        }
    }
    public static void main(String[]args)throws Exception {
        System.setProperty("java.awt.headless","true");com.digero.common.i18n.LocaleManager.init();com.digero.common.i18n.UIText.init();
        File source=new File(args[1]);AbcSong song=load(new File(args[0]),source);
        // The original recovered MSX uses the legacy removal/addition representation.
        // Tempo edits must not resurrect any of its removed imported notes.
        AbcSong legacy=load(new File(args[0]),source);
        apply(legacy,tune(4,16,20,12,0,0));
        check(legacy.getNoteEditorDeltas().values().stream().allMatch(NoteEditorDelta.Part::fullCopy),"legacy moves not promoted");
        check(previewCount(legacy)==4589,"legacy authored count after tempo change");
        check(SequenceInfo.fromAbcParts(legacy,true,true).getLastTrackInfos().stream().filter(t->!(t instanceof AuthoredPreviewTrackInfo)).mapToInt(t->t.noteEvents.size()).sum()==0,"legacy originals reappeared");
        System.out.println("PASS legacy full-move MSX promotion and tempo change");
        Map<Integer,NoteEditorDelta.Part> copies=new LinkedHashMap<>();for(var e:song.getNoteEditorDeltas().entrySet())copies.put(e.getKey(),new NoteEditorDelta.Part(List.of(),e.getValue().added(),true));
        song.setNoteEditorDeltas(copies);song.setAuthoredTrackRouting(Map.of());
        var original=song.getNoteEditorDeltas();check(count(song)==4589,"fixture count");check(previewCount(song)==4589,"initial preview");
        apply(song,tune(4,16,20,12,0,0),tune(21,25,-10,-8,0,0));
        var warped=song.getNoteEditorDeltas();check(!warped.equals(original),"tempo did not move notes");check(count(song)==4589,"tempo deleted notes");check(previewCount(song)==4589,"tempo preview count");
        System.out.println("PASS 4589-note song tempo + accelerando/ritardando and preview timing");
        apply(song,tune(4,16,20,12,0,0),tune(21,25,-10,-8,0,0));check(song.getNoteEditorDeltas().equals(warped),"re-applying same settings moves notes again");
        var saved=File.createTempFile("maestro-tune-", ".msx");XmlUtil.saveDocument(song.saveToXml(),saved);AbcSong restored=load(saved,source);check(restored.getNoteEditorDeltas().equals(warped),"save reload changed authored timing");check(previewCount(restored)==4589,"reloaded preview");saved.delete();
        apply(song);verifyRoundTrip(original,song,2);check(previewCount(song)==4589,"reset preview");
        System.out.println("PASS apply twice, save/reload, reset tune timing (<=2 editor ticks)");
        // A copy with zero offset must remain aligned to the live imported
        // performance when both layers undergo a section tempo change.
        AbcSong mixed=load(source,source);
        File generated=File.createTempFile("maestro-tune-baseline-", ".abc");
        mixed.exportGeneratedAbc(generated,"Maestro");
        Map<Integer,NoteEditorDelta.Part> straightCopies=new LinkedHashMap<>();
        for(var layer:AbcDocumentIO.read(generated).layers)
            straightCopies.put(layer.partNumber,new NoteEditorDelta.Part(List.of(),layer.notes.stream().map(NoteEditorDelta.Value::of).toList(),true));
        mixed.setNoteEditorDeltas(straightCopies);
        for(int n:straightCopies.keySet())mixed.setOriginalSourceEnabled(n,true);
        apply(mixed,tune(4,16,20,12,0,0),tune(21,25,-10,-8,0,0));
        check(previewCount(mixed)==4589,"mixed authored count");
        mixed.exportGeneratedAbc(generated,"Maestro");
        // Organic conversion may snap or merge short notes, so compare a
        // representative population of equal-pitch onsets rather than require
        // matching note counts after exporter-specific quantization.
        int aligned=0, examined=0;
        for(var layer:AbcDocumentIO.read(generated).layers){
            var authored=mixed.getNoteEditorDeltas().get(layer.partNumber).added();
            Map<Integer,List<Long>> onsets=new HashMap<>();
            for(var v:authored) onsets.computeIfAbsent(v.pitch(),k->new ArrayList<>()).add(v.start());
            for(var n:layer.notes){examined++;if(onsets.getOrDefault(n.pitch(),List.of()).stream().anyMatch(t->Math.abs(t-n.start())<=3))aligned++;}
        }
        System.out.println("Mixed imported/authored regenerated onset matches (within 3 editor ticks): "+aligned+"/"+examined);
        check(aligned>examined*0.95,"Imported and authored timing diverged");
        generated.delete();
        System.out.println("PASS identical imported/authored layers remain aligned through tempo effects");
        String blank=CompositionAbc.starter("Tune timing test",120,"4/4","C","Lute of Ages",1);File f=File.createTempFile("maestro-blank-", ".abc");Files.writeString(f.toPath(),blank);AbcSong nativeSong=load(f,f);nativeSong.initializeComposition("Tune timing test",blank,1);nativeSong.setTempoBPM(120);int part=(nativeSong.getParts().isEmpty()?nativeSong.createNewPart():nativeSong.getParts().getFirst()).getPartNumber();
        var nativeNotes=List.of(new NoteEditorDelta.Value(60,100,0,960),new NoteEditorDelta.Value(62,100,3360,4320),new NoteEditorDelta.Value(64,100,4800,5760),new NoteEditorDelta.Value(65,100,8640,9600),new NoteEditorDelta.Value(67,100,12480,13440));
        nativeSong.setNoteEditorDeltas(Map.of(part,new NoteEditorDelta.Part(List.of(),nativeNotes,true)));
        check(previewCount(nativeSong)==5,"native preview baseline");
        apply(nativeSong,tune(1,2,120,0,0,0),tune(2,3,0,20,0,0));
        var fast=nativeSong.getNoteEditorDeltas().get(part).added();
        System.out.println("Native warped notes: "+fast);
        check(fast.get(0).equals(nativeNotes.get(0)),"note before edit moved");
        check(Math.abs(fast.get(1).end()-4080)<=1,"crossing note endpoint incorrect");
        check(Math.abs(fast.get(2).start()-4320)<=1 && Math.abs(fast.get(2).end()-4800)<=1,"tempo doubling should halve in-section time and duration");
        check(fast.get(3).end()<nativeNotes.get(3).end(),"accelerando ignored beyond scaffold");check(previewCount(nativeSong)==5,"native tempo preview");
        File out=File.createTempFile("maestro-tune-output-", ".abc");nativeSong.exportAbc(out,"Maestro");var exported=AbcDocumentIO.read(out).layers.getFirst().notes;check(exported.size()==5,"native ABC export lost notes");for(int i=0;i<5;i++){check(Math.abs(exported.get(i).start()-fast.get(i).start())<=2,"export time mismatch");}
        apply(nativeSong,tune(1,2,120,0,2,30),tune(2,3,0,20,0,0));var effects=nativeSong.getNoteEditorDeltas().get(part).added();check(effects.get(2).pitch()==66 && effects.get(2).velocity()<100,"pitch/fade not applied using source beat");check(effects.get(2).start()==fast.get(2).start(),"pitch/fade-only changed timing");
        apply(nativeSong);verifyRoundTrip(Map.of(part,new NoteEditorDelta.Part(List.of(), nativeNotes,true)),nativeSong,2);check(previewCount(nativeSong)==5,"native reset preview");f.delete();out.delete();
        System.out.println("PASS native composition beyond scaffold, fixed tempo, crossing notes, ramps, mixed effects and exported ABC");
        System.exit(0);
    }
}
