import com.digero.maestro.abc.*;
import com.digero.maestro.midi.*;
import com.digero.maestro.noteedit.*;
import com.digero.maestro.util.*;
import com.digero.maestro.view.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.*;

/** Headless end-to-end regression for authored sections, route isolation and project persistence. */
public class AuthoredSectionsRegression {
    static final Preferences prefs=Preferences.userRoot().node("maestro-authored-sections-regression-"+System.nanoTime());
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static AbcSong load(File file,File source)throws Exception{
        FileResolver resolver=new FileResolver(){public File locateFile(File f,String s){return source;}public File resolveFile(File f,String s){return source;}};
        return new AbcSong(file,new PartAutoNumberer(prefs.node("number")),new PartNameTemplate(prefs.node("parts")),new ExportFilenameTemplate(prefs.node("export")),new InstrNameSettings(prefs.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(prefs.node("settings")));
    }
    static List<Integer> velocities(AbcSong song,int source,int target)throws Exception{
        var info=SequenceInfo.fromAbcParts(song,true,true);
        for(var tr:info.getLastTrackInfos())if(tr instanceof AuthoredPreviewTrackInfo a && a.sourcePartNumber==source && tr.part.getPartNumber()==target)
            return tr.noteEvents.stream().map(e->e.getVelocity()).toList();
        return List.of();
    }
    public static void main(String[]args)throws Exception{
        System.setProperty("java.awt.headless","true");com.digero.common.i18n.LocaleManager.init();com.digero.common.i18n.UIText.init();
        check(HighlightKey.options().size()==25,"key count");
        check(Arrays.equals(HighlightKey.scale("D major"),new int[]{2,4,6,7,9,11,1}),"D major");
        check(HighlightKey.normalize("invalid").equals("Auto"),"invalid key");
        var roll=new PianoRollPanel();roll.setHighlightKey("F# minor");roll.setDocument(AbcDocumentIO.readText("X:1\nM:4/4\nL:1/8\nK:C\nCDEF|"),0);
        check(roll.getEffectiveHighlightKey().equals("F# minor"),"detection overwrote manual choice");roll.setShowAllParts(true);check(roll.getEffectiveHighlightKey().equals("F# minor"),"scope overwrote manual choice");roll.setHighlightKey("Auto");check(roll.getEffectiveHighlightKey().equals(roll.getDetectedKey()),"auto not restored");
        File source=File.createTempFile("authored-sections-", ".abc"), project=File.createTempFile("authored-sections-", ".msx"), output=File.createTempFile("authored-sections-output-", ".abc");
        try{
            String blank=CompositionAbc.starter("Sections",120,"4/4","C","Lute of Ages",4);Files.writeString(source.toPath(),blank);
            AbcSong song=load(source,source);song.initializeComposition("Sections",blank,4);song.setTempoBPM(120);
            var a=song.getParts().isEmpty()?song.createNewPart():song.getParts().getFirst();var b=song.createNewPart();b.setInstrument(a.getInstrument());
            int x=a.getPartNumber(), y=b.getPartNumber();check(x!=y,"duplicate part");
            var notes=List.of(new NoteEditorDelta.Value(60,96,0,960),new NoteEditorDelta.Value(62,96,1920,2880),new NoteEditorDelta.Value(64,96,3840,4800),new NoteEditorDelta.Value(65,96,5760,6720),new NoteEditorDelta.Value(67,96,7680,8640));
            var original=Map.of(x,new NoteEditorDelta.Part(List.of(),notes,true));song.setNoteEditorDeltas(original);song.setAuthoredTrackRouting(Map.of(x,Set.of(x,y)));
            var fade=new AuthoredSections.Settings(List.of(new AuthoredSections.Effect(0,2,0,0,100,false,false,0,127,0)),null);
            song.setAuthoredSections(x,x,fade);song.setHighlightKey("Eb major");
            var expected=List.of(96,72,48,24,96);
            check(velocities(song,x,x).equals(expected),"fade preview "+velocities(song,x,x));
            check(velocities(song,x,y).equals(List.of(96,96,96,96,96)),"fade leaked to other destination");
            song.exportAbc(output,"Maestro");var exported=AbcDocumentIO.read(output);var faded=exported.layers.stream().filter(l->l.partNumber==x).findFirst().orElseThrow().notes.stream().map(EditableNote::velocity).toList();
            check(faded.size()==5&&faded.get(0)>faded.get(1)&&faded.get(1)>faded.get(2)&&faded.get(2)>faded.get(3)&&faded.get(4)>faded.get(3),"ABC dynamics "+faded);
            check(song.getNoteEditorDeltas().equals(original),"effects rewrote original notes");
            XmlUtil.saveDocument(song.saveToXml(),project);AbcSong restored=load(project,source);
            check(restored.getHighlightKey().equals("Eb major"),"key not persisted");check(restored.getAuthoredSections(x,x).equals(fade),"sections not persisted");check(restored.getNoteEditorDeltas().equals(original),"raw notes not persisted");
            check(velocities(restored,x,x).equals(expected),"restored fade");restored.setAuthoredSections(x,x,AuthoredSections.EMPTY);check(velocities(restored,x,x).equals(List.of(96,96,96,96,96)),"clearing sections did not restore original");
            System.out.println("PASS manual key, auto reset, routed preview fade, ABC dynamics, MSX round-trip and non-destructive reset");
        }finally{source.delete();project.delete();output.delete();prefs.removeNode();}
        System.exit(0);
    }
}
