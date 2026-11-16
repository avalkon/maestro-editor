package com.digero.maestro.noteedit;

import com.digero.common.abc.LotroInstrument;
import com.digero.common.i18n.LocaleManager;
import com.digero.common.i18n.UIText;
import com.digero.maestro.abc.*;
import com.digero.maestro.midi.SequenceInfo;
import com.digero.maestro.util.FileResolver;
import com.digero.maestro.view.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for B2.4 preview-only SequenceInfo rendering and summed polyphony. */
class AuthoredPreviewCompatibilityTest {
    @TempDir Path temp;
    @BeforeAll static void initialize() {LocaleManager.init(true);UIText.init();}
    private AbcSong load(File file) throws Exception {
        Preferences prefs=Preferences.userRoot().node("authored-preview-"+UUID.randomUUID());
        FileResolver resolver=new FileResolver() {
            public File locateFile(File file,String message) {throw new AssertionError(file);}
            public File resolveFile(File file,String message) {throw new AssertionError(file);}
        };
        return new AbcSong(file,new PartAutoNumberer(prefs.node("numbers")),
            new PartNameTemplate(prefs.node("names")),new ExportFilenameTemplate(prefs.node("export")),
            new InstrNameSettings(prefs.node("instruments")),resolver,
            new MiscSettings(prefs.node("misc"),false),new SaveAndExportSettings(prefs.node("save")));
    }
    @Test void previewLanesHaveSourceTimingAndContributeToCombinedPolyphony() throws Exception {
        String source=CompositionAbc.starter("Test",120,"4/4","C","Lute of Ages",8);
        File starter=temp.resolve("starter.abc").toFile();Files.writeString(starter.toPath(),source);
        AbcSong song=load(starter);
        if(song.getParts().isEmpty())song.createNewPart();
        song.initializeComposition("Test",source,8);
        AbcPart lute=song.getParts().getFirst();
        AbcPart fiddle=song.createNewPart();fiddle.setInstrument(LotroInstrument.BASIC_FIDDLE);
        int a=lute.getPartNumber(),b=fiddle.getPartNumber();
        song.setNoteEditorDeltas(Map.of(
            a,new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(60,90,960,1920))),
            b,new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(64,90,960,1920)))));
        song.setAuthoredTrackRouting(Map.of(a,Set.of(a,b)));
        SequenceInfo preview=SequenceInfo.fromAbcParts(song,true,false);
        assertEquals(3,preview.getLastTrackInfos().size());
        assertTrue(preview.getLastTrackInfos().stream().allMatch(i->i instanceof AuthoredPreviewTrackInfo));
        assertNotNull(song.getSequenceInfo().getTrackList(),
            "Arrangement graphs must use the source SequenceInfo rather than preview-only metadata");
        preview.histogram.sumUp(song);
        assertEquals(3,preview.histogram.max(),
            "Overall polyphony includes authored notes in each destination, not just the original exporter histogram");
    }
}
