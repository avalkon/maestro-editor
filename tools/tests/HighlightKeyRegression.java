import com.digero.maestro.abc.*;
import com.digero.maestro.noteedit.*;
import com.digero.maestro.util.*;
import com.digero.maestro.view.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.*;

/** Headless regression for display-only key selection. */
public class HighlightKeyRegression {
    static final Preferences prefs=Preferences.userRoot().node("maestro-highlight-key-test-"+System.nanoTime());
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static AbcSong load(File file,File source)throws Exception{
        FileResolver resolver=new FileResolver(){public File locateFile(File f,String s){return source;}public File resolveFile(File f,String s){return source;}};
        return new AbcSong(file,new PartAutoNumberer(prefs.node("number")),new PartNameTemplate(prefs.node("parts")),new ExportFilenameTemplate(prefs.node("export")),new InstrNameSettings(prefs.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(prefs.node("settings")));
    }
    // The exporter writes wall-clock time into the ABC header on every export.
    // Ignore just that field so crossing a second boundary does not fail this test.
    static String exportContent(File file)throws IOException{
        String timestamp=com.digero.common.abc.AbcField.EXPORT_TIMESTAMP.toString();
        return Files.readString(file.toPath()).replaceAll("(?m)^"+java.util.regex.Pattern.quote(timestamp)+"[^\\r\\n]*",timestamp+"<time>");
    }
    public static void main(String[]args)throws Exception{
        System.setProperty("java.awt.headless","true");com.digero.common.i18n.LocaleManager.init();com.digero.common.i18n.UIText.init();
        check(HighlightKey.options().size()==25,"key count");
        check(Arrays.equals(HighlightKey.scale("D major"),new int[]{2,4,6,7,9,11,1}),"D major");
        check(HighlightKey.normalize("invalid").equals("Auto"),"invalid key");
        var roll=new PianoRollPanel();
        var doc=AbcDocumentIO.readText("X:1\nM:4/4\nL:1/8\nK:C\nCDEF|");
        roll.setDocument(doc,0);check(roll.getHighlightKey().equals("Auto"),"default");
        roll.setHighlightKey("F# minor");roll.setDocument(doc,0);
        check(roll.getEffectiveHighlightKey().equals("F# minor"),"detection overwrote manual choice");
        roll.setShowAllParts(true);check(roll.getEffectiveHighlightKey().equals("F# minor"),"scope overwrote manual choice");
        roll.setHighlightKey("Auto");check(roll.getEffectiveHighlightKey().equals(roll.getDetectedKey()),"auto not restored");
        Path dir=Files.createTempDirectory("maestro-highlight-key-");
        try {
            File source=dir.resolve("source.abc").toFile(), project=dir.resolve("project.msx").toFile(), output=dir.resolve("output.abc").toFile();
            String blank=CompositionAbc.starter("Key test",120,"4/4","C","Lute of Ages",4);Files.writeString(source.toPath(),blank);
            AbcSong song=load(source,source);song.initializeComposition("Key test",blank,4);song.setTempoBPM(120);
            var part=song.getParts().isEmpty()?song.createNewPart():song.getParts().getFirst();int x=part.getPartNumber();
            check(song.getHighlightKey().equals("Auto"),"new song default");
            var original=Map.of(x,new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(60,96,0,960)),true));
            song.setNoteEditorDeltas(original);song.exportAbc(output,"Maestro");String before=exportContent(output);
            song.setHighlightKey("Eb major");song.exportAbc(output,"Maestro");
            check(before.equals(exportContent(output)),"highlight setting changed ABC export");
            check(song.getNoteEditorDeltas().equals(original),"notes changed");
            XmlUtil.saveDocument(song.saveToXml(),project);AbcSong restored=load(project,source);
            check(restored.getHighlightKey().equals("Eb major"),"manual key not persisted");
            check(restored.getNoteEditorDeltas().equals(original),"raw notes not persisted");
            restored.setHighlightKey("Auto");XmlUtil.saveDocument(restored.saveToXml(),project);
            check(load(project,source).getHighlightKey().equals("Auto"),"Auto not persisted");
            System.out.println("PASS: 24 manual keys, Auto, detection independence, MSX round-trip, unchanged ABC and notes");
        } finally {try(var files=Files.list(dir)){for(Path p:files.toList())Files.deleteIfExists(p);}Files.deleteIfExists(dir);prefs.removeNode();}
        System.exit(0);
    }
}
