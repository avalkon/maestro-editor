package com.digero.maestro.noteedit;

import com.digero.common.i18n.LocaleManager;
import com.digero.common.i18n.UIText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** B2.4.2 regression: independent X:-numbered parts of the SAME instrument. */
class AuthoredPartIsolationTest {
    @BeforeAll static void initialize() { LocaleManager.init(true); UIText.init(); }

    @Test void twoLutesRemainSeparateAndAStaleEditorKeepsMissingPartNotes() throws Exception {
        var a=new CompositionAbc.PartSpec(1,"Lute of Ages","Lute of Ages",120,"4/4","C");
        var b=new CompositionAbc.PartSpec(11,"Lute of Ages","Lute of Ages",120,"4/4","C");
        String baseline=CompositionAbc.forEditor("%abc-2.1\n",List.of(a));
        NoteDocument doc=AbcDocumentIO.readText(baseline);
        var stored=new NoteEditorDelta.Part(List.of(),List.of(
            new NoteEditorDelta.Value(60,90,7680,8640)));
        var persisted=Map.of(11,stored);
        assertEquals(persisted,NoteEditorDelta.capturePreservingMissing(doc,persisted),
            "An async baseline without X:11 must never discard its MSX notes");

        NoteEditorApp.EditorTab editor=new NoteEditorApp.EditorTab(NoteDocument.Kind.ABC,null,null);
        Path input=Files.createTempFile("maestro-two-lutes-",".abc");
        try {
            Files.writeString(input,baseline);
            editor.load(input.toFile());
            editor.ensureAbcPart(b,persisted); // New Part selected before the async ABC export completes
            assertEquals(11,editor.currentPartNumber());
            var merged=editor.getNoteDeltasPreserving(persisted);
            assertEquals(stored,merged.get(11));
            // After the new part arrives in generated ABC, rebase its saved notes.
            String updated=CompositionAbc.forEditor(baseline,List.of(a,b));
            editor.reloadGeneratedAbc(updated,merged);
            assertEquals(11,editor.currentPartNumber());
            assertEquals(stored,editor.getNoteDeltas().get(11));
            assertTrue(!editor.getNoteDeltas().containsKey(1),
                "Notes from X:11 must not appear in the first Lute track");
            editor.selectPartNumber(1);
            assertEquals(1,editor.currentPartNumber());
            editor.selectPartNumber(11);
            assertEquals(11,editor.currentPartNumber());
            editor.reloadGeneratedAbc(updated,merged);
            assertEquals(stored,editor.getNoteDeltas().get(11),
                "Refreshing an unchanged ABC baseline must preserve the notes");
        } finally { Files.deleteIfExists(input); }
    }

    @Test void duplicateXNumbersFailClosedInsteadOfMergingDifferentParts() {
        var a=new CompositionAbc.PartSpec(1,"Lute of Ages","Lute of Ages",120,"4/4","C");
        var b=new CompositionAbc.PartSpec(1,"Lute Two","Lute of Ages",120,"4/4","C");
        String duplicated="%abc-2.1\n"+CompositionAbc.header(a)+"z8 |]\n"+
            CompositionAbc.header(b)+"z8 |]\n";
        var error=assertThrows(java.io.IOException.class,()->AbcDocumentIO.readText(duplicated));
        assertTrue(error.getMessage().contains("Duplicate ABC part X:"));
    }
}
