import com.digero.maestro.abc.*;
import com.digero.maestro.midi.*;
import com.digero.maestro.util.*;
import com.digero.maestro.view.*;
import com.digero.maestro.noteedit.*;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.prefs.*;
public class LayeredCopyPreviewRegression {
 static void check(boolean good,String msg){if(!good)throw new AssertionError(msg);}
 static int[] preview(AbcSong song)throws Exception {
  SequenceInfo info=SequenceInfo.fromAbcParts(song,true,true);
  PreviewPartStats.updateExportedNoteCounts(info.getLastTrackInfos(),song.getEditedPreviewSourcePartNumbers());
  info.histogram.sumUp(song);
  int original=0,authored=0,displayed=0;
  for(var tr:info.getLastTrackInfos())if(tr.noteEvents!=null){
    if(tr instanceof AuthoredPreviewTrackInfo)authored+=tr.noteEvents.size();else original+=tr.noteEvents.size();}
  for(var part:song.getParts())if(part.getPartNumber()<=6)displayed+=part.numberOfExportedNotes;
  System.out.printf("original=%d authored=%d display=%d peak=%d%n",original,authored,displayed,info.histogram.maxAll());
  return new int[]{original,authored,displayed,info.histogram.maxAll()};
 }
 public static void main(String[]args)throws Exception {
  System.setProperty("java.awt.headless","true");
  com.digero.common.i18n.LocaleManager.init();com.digero.common.i18n.UIText.init();
  Preferences p=Preferences.userRoot().node("maestro-layered-tests-"+System.nanoTime());
  File source=new File(args[1]); FileResolver resolver=new FileResolver(){public File locateFile(File f,String s){return source;}public File resolveFile(File f,String s){return source;}};
  AbcSong song=new AbcSong(new File(args[0]),new PartAutoNumberer(p.node("number")),new PartNameTemplate(p.node("parts")),new ExportFilenameTemplate(p.node("export")),new InstrNameSettings(p.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(p.node("settings")));
  var replacements=new LinkedHashMap<Integer,NoteEditorDelta.Part>();
  for(var e:song.getNoteEditorDeltas().entrySet())replacements.put(e.getKey(),new NoteEditorDelta.Part(e.getValue().removed(),e.getValue().added(),true));
  song.setNoteEditorDeltas(replacements); song.setAuthoredTrackRouting(Map.of());
  check(song.isOriginalSourceOverridden(1),"New authored copy should start with imported original off");
  var authored=preview(song);
  check(authored[0]==0&&authored[1]==4589&&authored[2]==4589,"Authored-only preview damaged");
  for(int part:replacements.keySet())song.setOriginalSourceEnabled(part,true);
  check(!song.isOriginalSourceOverridden(1),"Imported original not enabled");
  var both=preview(song);
  check(both[0]==4589 && both[1]==4589 && both[2]==9178,"Both layers should count independently");
  var unrouted=new LinkedHashMap<Integer,Set<Integer>>();for(int part:replacements.keySet())unrouted.put(part,Set.of());
  song.setAuthoredTrackRouting(unrouted);
  var original=preview(song);
  check(original[0]==4589 && original[1]==0 && original[2]==4589,"Original only not restored");
  // Save the user's independent imported-source selections, then reload them.
  song.setAuthoredTrackRouting(Map.of());
  File saved=File.createTempFile("maestro-layered-copy-", ".msx");
  com.digero.maestro.util.XmlUtil.saveDocument(song.saveToXml(),saved);
  AbcSong restored=new AbcSong(saved,new PartAutoNumberer(p.node("number")),new PartNameTemplate(p.node("parts")),new ExportFilenameTemplate(p.node("export")),new InstrNameSettings(p.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(p.node("settings")));
  check(restored.isOriginalSourceEnabled(1),"Original selection lost on reload");
  var replay=preview(restored);
  check(Arrays.equals(both,replay),"Reload changed independent layering");
  restored.setOriginalSourceEnabled(1,false);
  check(restored.isOriginalSourceOverridden(1),"Cannot turn imported layer off independently");
  check(restored.getAuthoredTrackRouting().isEmpty(),"Imported checkbox incorrectly unrouted authored copy");
  // Prior full-copy MSX files had no importedSource element. Preserve their
  // initially displayed choice, then keep it independent of future routing.
  String older=Files.readString(saved.toPath()).replace(" perTrackImportedSelection=\"true\"", "").replaceAll("<importedSource[^>]+/>","");
  // Start with the default original-off state from the previous release.
  older=older.replaceAll("<importedSource[^>]+/>","");
  File legacy=File.createTempFile("maestro-legacy-fullcopy-", ".msx");
  Files.writeString(legacy.toPath(),older);
  AbcSong migrated=new AbcSong(legacy,new PartAutoNumberer(p.node("number")),
    new PartNameTemplate(p.node("parts")),new ExportFilenameTemplate(p.node("export")),
    new InstrNameSettings(p.node("names")),resolver,new MiscSettings(null,false),
    new SaveAndExportSettings(p.node("settings")));
  check(!migrated.isOriginalSourceEnabled(1),"Legacy MSX default not preserved");
  migrated.setAuthoredTrackRouting(unrouted);
  check(!migrated.isOriginalSourceEnabled(1),"Legacy MSX original still flips with authored route");
  legacy.delete();saved.delete();
  System.out.println("PASS independent original and authored preview, counts, sample polyphony, legacy MSX migration and persistence");
 }
}
