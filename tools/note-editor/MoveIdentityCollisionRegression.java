package com.digero.maestro.noteedit;

import java.util.List;
import java.util.Map;

/** Run: javac -cp target/Maestro.jar -d /tmp/maestro-check tools/note-editor/MoveIdentityCollisionRegression.java
 *       java -cp /tmp/maestro-check:target/Maestro.jar com.digero.maestro.noteedit.MoveIdentityCollisionRegression
 * Regression for a repeated pitch being translated onto another note's
 * former position. Multiset differences silently cancelled these movements.
 */
public final class MoveIdentityCollisionRegression {
    private static void verify(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
    private static List<NoteEditorDelta.Value> values(NoteDocument.Layer layer) {
        return layer.notes.stream().map(NoteEditorDelta.Value::of).sorted(
            java.util.Comparator.comparingLong(NoteEditorDelta.Value::start)).toList();
    }
    public static void main(String[] args) throws Exception {
        String score="X:1\nT:Repeated Pitch\nM:4/4\nL:1/4\nQ:120\nK:C\nC C C C|\n";
        NoteDocument doc=AbcDocumentIO.readText(score);
        verify(doc.layer(0).notes.size()==4,"fixture");
        doc.edit(0,notes -> { for(int i=0;i<notes.size();i++)
            notes.set(i, notes.get(i).move(0,AbcDocumentIO.PPQ)); });
        Map<Integer,NoteEditorDelta.Part> deltas=NoteEditorDelta.capture(doc);
        NoteEditorDelta.Part part=deltas.get(1);
        verify(part!=null && part.removed().size()==4 && part.added().size()==4,
            "every repeating note must have its own removal/addition");
        NoteDocument generated=AbcDocumentIO.readText(score);
        verify(NoteEditorDelta.apply(generated,deltas).unmatchedRemovals()==0,"rebase removals");
        verify(values(generated.layer(0)).equals(values(doc.layer(0))),"rebase positions");
        generated.preserveHistoryFrom(doc);
        generated.undo();
        verify(NoteEditorDelta.capture(generated).isEmpty(),"undo across preview refresh");
        generated.redo();
        verify(NoteEditorDelta.capture(generated).equals(deltas),"redo across preview refresh");
        System.out.println("PASS: 4 overlapping positions moved, persisted, rebased and undo/redo preserved");
    }
}
