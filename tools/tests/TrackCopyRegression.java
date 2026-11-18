import com.digero.maestro.noteedit.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
public class TrackCopyRegression {
  static void require(boolean cond, String message) {if(!cond)throw new AssertionError(message);}
  static int count(NoteDocument doc) {int n=0;for(var l:doc.layers)n+=l.notes.size();return n;}
  public static void main(String[] args) throws Exception {
    String abc=Files.readString(Path.of(args[0]));
    NoteDocument before=AbcDocumentIO.readText(abc);
    int total=count(before);require(total==4589,"unexpected imported original note count "+total);
    Map<Integer,Integer> perPart=new LinkedHashMap<>();
    for(var l:before.layers)perPart.put(l.partNumber,l.notes.size());
    long delta=5760;
    var editActions=new LinkedHashMap<Integer,Consumer<List<EditableNote>>>();
    for(int i=0;i<before.layers.size();i++)editActions.put(i, notes->{for(int n=0;n<notes.size();n++)notes.set(n,notes.get(n).move(0,delta));});
    before.editLayers(editActions);
    Map<Integer,NoteEditorDelta.Part> copies=NoteEditorDelta.capturePreservingMissing(before, Map.of());
    require(copies.size()==6,"expected six authored copies "+copies.size());
    int importedRemoved=0,authored=0;
    for(var e:copies.entrySet()) {
      require(e.getValue().fullCopy(),"not a full copy at X:"+e.getKey());
      require(e.getValue().removed().size()==perPart.get(e.getKey()),"missing imported baseline");
      require(e.getValue().added().size()==perPart.get(e.getKey()),"missing authored notes");
      importedRemoved+=e.getValue().removed().size();authored+=e.getValue().added().size();
    }
    require(importedRemoved==total && authored==total,"lost notes on complete move");
    // New default: authored selected, and the imported source is replaced.
    var routed=AuthoredTrackRouting.forExport(copies,Map.of(),copies.keySet());
    NoteDocument preview=AbcDocumentIO.readText(abc);
    NoteEditorDelta.apply(preview,routed);
    require(count(preview)==total,"default route doubled or removed notes");
    for(var layer:preview.layers)for(var note:layer.notes)
       require(note.start()>=delta,"old imported note still playing in replacement");
    // Uncheck the authored route of a single part: it must restore every one
    // of the ORIGINAL notes, without mutating its saved authored copy.
    int first=before.layers.get(0).partNumber;
    var routes=new LinkedHashMap<Integer,Set<Integer>>();routes.put(first,Set.of());
    var restoredDeltas=AuthoredTrackRouting.forExport(copies,routes,copies.keySet());
    NoteDocument restored=AbcDocumentIO.readText(abc);
    NoteEditorDelta.apply(restored,restoredDeltas);
    require(count(restored)==total,"restoring original changed overall note count");
    var original=AbcDocumentIO.readText(abc).layers.get(0);
    var after=restored.layers.get(0);
    require(after.notes.size()==original.notes.size(),"original missing notes on return");
    for(int i=0;i<original.notes.size();i++)require(
      NoteEditorDelta.Value.of(original.notes.get(i)).equals(NoteEditorDelta.Value.of(after.notes.get(i))),
      "original note changed on return");
    require(copies.get(first).added().size()==perPart.get(first),"saved full copy disappeared");
    // Subsequent edits, and undo to the original pitches, keep the independent
    // full-copy identity so it remains available for later re-selection.
    before.undo();
    var afterUndo=NoteEditorDelta.capturePreservingMissing(before,copies);
    require(afterUndo.get(first).fullCopy(),"undo disposed of retained authored copy");
    before.redo();
    var afterRedo=NoteEditorDelta.capturePreservingMissing(before,afterUndo);
    require(afterRedo.get(first).fullCopy() && afterRedo.get(first).added().size()==perPart.get(first),
      "redo corrupted authored full copy");
    System.out.printf("PASS imported=%d, copied=%d, replaced=%d, original re-enabled at X:%d, undo/redo preserved; parts=%d%n",
      total,authored,count(preview),first,before.layers.size());
  }
}
