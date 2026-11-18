import com.digero.maestro.noteedit.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Original and independently edited full-copy tracks may play at the same time. */
public class LayeredCopyRegression {
    static void check(boolean value,String message) {if(!value) throw new AssertionError(message);}
    static int count(NoteDocument doc) {int n=0;for(var layer:doc.layers)n+=layer.notes.size();return n;}
    static int simulate(String abc,Map<Integer,NoteEditorDelta.Part> copies,Set<Integer> routes,Set<Integer> mutedOriginals) throws Exception {
        NoteDocument doc=AbcDocumentIO.readText(abc);
        Map<Integer,Set<Integer>> map=new LinkedHashMap<>();
        for(int src:copies.keySet())map.put(src,routes.contains(src)?Set.of(src):Set.of());
        NoteEditorDelta.apply(doc,AuthoredTrackRouting.forExport(copies,map,copies.keySet(),mutedOriginals));
        return count(doc);
    }
    public static void main(String[]args)throws Exception {
        String abc=Files.readString(Path.of(args[0]));
        NoteDocument doc=AbcDocumentIO.readText(abc);
        int original=count(doc);
        check(original==4589,"Unexpected imported count "+original);
        var actions=new LinkedHashMap<Integer,Consumer<List<EditableNote>>>();
        for(int i=0;i<doc.layers.size();i++)actions.put(i,notes->{for(int k=0;k<notes.size();k++) {
            EditableNote old=notes.get(k);notes.set(k,new EditableNote(old.id(),old.pitch(),Math.max(1,old.velocity()/2),old.channel(),old.start()+5760,old.end()+5760));
        }});
        doc.editLayers(actions);
        Map<Integer,NoteEditorDelta.Part> copies=NoteEditorDelta.capturePreservingMissing(doc,Map.of());
        // Above deliberately swaps identities, but still establishes every note as a full copy.
        check(copies.values().stream().allMatch(NoteEditorDelta.Part::fullCopy),"Missing full-copy flag");
        check(copies.values().stream().mapToInt(x->x.added().size()).sum()==original,"Loss during move");
        Set<Integer> all=copies.keySet();
        check(simulate(abc,copies,all,all)==4589,"Authored only doubled or lost notes");
        check(simulate(abc,copies,all,Set.of())==9178,"Original+authored should both play");
        check(simulate(abc,copies,Set.of(),Set.of())==4589,"Original only should play");
        check(simulate(abc,copies,Set.of(),all)==0,"Both unchecked should be silent");
        System.out.println("PASS full-song original / authored / layered / neither: 4589 / 4589 / 9178 / 0");
    }
}
