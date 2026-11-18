import com.digero.maestro.abc.*;
import com.digero.maestro.midi.*;
import com.digero.maestro.util.*;
import com.digero.maestro.view.*;
import com.digero.maestro.noteedit.*;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.prefs.*;
public class TrackCopyPreviewRegression {
 static void check(boolean good,String msg){if(!good) throw new AssertionError(msg);}
 static int[] preview(AbcSong song)throws Exception {
  var info=SequenceInfo.fromAbcParts(song,true,true);
  PreviewPartStats.updateExportedNoteCounts(info.getLastTrackInfos(),song.getNoteEditorDeltas().keySet());
  info.histogram.sumUp(song);
  int original=0,authored=0,displayed=0;
  for(var tr:info.getLastTrackInfos())if(tr.noteEvents!=null){
   if(tr instanceof AuthoredPreviewTrackInfo)authored+=tr.noteEvents.size();else original+=tr.noteEvents.size();}
  for(var part:song.getParts())if(part.getPartNumber()<=6)displayed+=part.numberOfExportedNotes;
  System.out.printf("original=%d authored=%d display=%d peak=%d%n",original,authored,displayed,info.histogram.maxAll());
  return new int[]{original,authored,displayed};
 }
 public static void main(String[]args)throws Exception {
  System.setProperty("java.awt.headless","true");
  com.digero.common.i18n.LocaleManager.init(); com.digero.common.i18n.UIText.init();
  Preferences p=Preferences.userRoot().node("maestro-fullcopy-tests-"+System.nanoTime());
  File source=new File(args[1]);FileResolver resolver=new FileResolver(){public File locateFile(File f,String s){return source;} public File resolveFile(File f,String s){return source;}};
  AbcSong song=new AbcSong(new File(args[0]),new PartAutoNumberer(p.node("number")),
   new PartNameTemplate(p.node("parts")),new ExportFilenameTemplate(p.node("export")),
   new InstrNameSettings(p.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(p.node("settings")));
  var replacements=new LinkedHashMap<Integer,NoteEditorDelta.Part>();
  for(var e:song.getNoteEditorDeltas().entrySet())replacements.put(e.getKey(),
    new NoteEditorDelta.Part(e.getValue().removed(),e.getValue().added(),true));
  song.setNoteEditorDeltas(replacements);song.setAuthoredTrackRouting(Map.of());
  check(song.isOriginalSourceOverridden(1),"source not overridden with selected authored copy");
  int[] copied=preview(song);
  check(copied[0]==0 && copied[1]==4589 && copied[2]==4589,"cloned full song has duplicate or lost notes");
  // Change one part back to its normal imported voice and ensure the rest of
  // the song is still the authored copy.
  song.setAuthoredTrackRouting(Map.of(2,Set.of()));
  song.setOriginalSourceEnabled(2,true);
  check(!song.isOriginalSourceOverridden(2),"imported checkbox not reenabled");
  int[] original=preview(song);
  check(original[0]>0 && original[1]>0 && original[0]+original[1]==4589 && original[2]==4589,
   "original selection doesn't preserve note count");
  song.setAuthoredTrackRouting(Map.of());
  song.setOriginalSourceEnabled(2,false);
  int[] again=preview(song);
  check(Arrays.equals(copied,again),"reselecting authored copy was destructive");
  // The MSX must remember independent copies and the route state.
  File saved=File.createTempFile("maestro-full-copy-", ".msx");
  com.digero.maestro.util.XmlUtil.saveDocument(song.saveToXml(),saved);
  AbcSong reloaded=new AbcSong(saved,new PartAutoNumberer(p.node("number")),
    new PartNameTemplate(p.node("parts")),new ExportFilenameTemplate(p.node("export")),
    new InstrNameSettings(p.node("names")),resolver,new MiscSettings(null,false),
    new SaveAndExportSettings(p.node("settings")));
  check(reloaded.getNoteEditorDeltas().values().stream().allMatch(NoteEditorDelta.Part::fullCopy),
    "full-copy flag disappeared during save/reload");
  check(reloaded.isOriginalSourceOverridden(2),"authored routing lost during reload");
  check(Arrays.equals(copied,preview(reloaded)),"reloaded MSX changed playback");
  reloaded.setAuthoredTrackRouting(Map.of(2,Set.of()));
  reloaded.setOriginalSourceEnabled(2,true);
  check(Arrays.equals(original,preview(reloaded)),"reloaded MSX cannot select imported source");
  saved.delete();
  System.out.println("PASS independent source/clone preview, save/reload and reselect without duplication");
 }
}
