import com.digero.maestro.abc.*;
import com.digero.maestro.midi.*;
import com.digero.maestro.view.*;
import com.digero.maestro.util.*;
import com.digero.maestro.noteedit.*;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.prefs.*;
public class LayeredCopyExportRegression {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static int count(String abc)throws Exception {NoteDocument doc=AbcDocumentIO.readText(abc);int total=0;for(var layer:doc.layers)total+=layer.notes.size();return total;}
 public static void main(String[]args)throws Exception {
  System.setProperty("java.awt.headless","true");
  com.digero.common.i18n.LocaleManager.init();com.digero.common.i18n.UIText.init();
  Preferences p=Preferences.userRoot().node("maestro-layered-export-"+System.nanoTime());
  File source=new File(args[1]);FileResolver resolver=new FileResolver(){public File locateFile(File f,String s){return source;}public File resolveFile(File f,String s){return source;}};
  AbcSong song=new AbcSong(new File(args[0]),new PartAutoNumberer(p.node("number")),new PartNameTemplate(p.node("parts")),new ExportFilenameTemplate(p.node("export")),new InstrNameSettings(p.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(p.node("settings")));
  Map<Integer,NoteEditorDelta.Part> copies=new LinkedHashMap<>();for(var e:song.getNoteEditorDeltas().entrySet())copies.put(e.getKey(),new NoteEditorDelta.Part(e.getValue().removed(),e.getValue().added(),true));
  song.setNoteEditorDeltas(copies);
  var tmp=Files.createTempFile("layered-export-", ".abc");
  for(int part:copies.keySet())song.setOriginalSourceEnabled(part,false);
  song.exportAbc(tmp.toFile(),"Maestro"); int authored=count(Files.readString(tmp));
  for(int part:copies.keySet())song.setOriginalSourceEnabled(part,true);
  song.exportAbc(tmp.toFile(),"Maestro");int both=count(Files.readString(tmp));
  System.out.println("ABC parsed authored="+authored+" both="+both);
  check(both>authored,"Selected imported original not included in ABC export");
  Files.delete(tmp);
  System.out.println("PASS ABC exported both layers");
 }
}
