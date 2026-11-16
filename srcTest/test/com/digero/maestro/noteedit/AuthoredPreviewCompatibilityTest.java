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
        // The play-button / immediate rebuild must take the same path as the
        // worker, rather than applying authored tracks after cache creation.
        SequenceInfo immediate=SequenceInfo.fromAbcParts(song.getAbcExporter(),song,true,false);
        immediate.histogram.sumUp(song);
        assertEquals(3,immediate.histogram.max(),
            "An immediate preview must include routed authored notes in its polyphony");
        assertEquals(3,immediate.getLastTrackInfos().size(),
            "Immediate preview must preserve authored track metadata");
    }
    @Test void mixedImportedAndAuthoredTracksRetainBothNoteCounts() throws Exception {
        String source=CompositionAbc.starter("Mixed",120,"4/4","C","Lute of Ages",4)
            .replaceFirst("z8 \\|", "C8 |");
        File starter=temp.resolve("mixed.abc").toFile();
        Files.writeString(starter.toPath(),source);
        AbcSong song=load(starter);
        if(song.getParts().isEmpty())song.createNewPart();
        AbcPart part=song.getParts().getFirst();
        part.setInstrument(LotroInstrument.LUTE_OF_AGES);
        song.setNoteEditorDeltas(Map.of(part.getPartNumber(),
            new NoteEditorDelta.Part(List.of(),List.of(
                new NoteEditorDelta.Value(60,90,0,960)))));
        SequenceInfo preview=SequenceInfo.fromAbcParts(song.getAbcExporter(),song,true,false);
        var tracks=preview.getLastTrackInfos();
        assertEquals(2,tracks.size(),"Generated and authored preview tracks must both exist");
        int generated=tracks.stream().filter(t->!(t instanceof AuthoredPreviewTrackInfo))
            .mapToInt(t->t.numberOfExportedNotes).sum();
        int authored=tracks.stream().filter(t->t instanceof AuthoredPreviewTrackInfo)
            .mapToInt(t->t.numberOfExportedNotes).sum();
        assertTrue(generated>0,"The imported baseline must still have notes");
        assertEquals(1,authored,"The one authored note must count in addition to the imported notes");
        assertEquals(generated+1,tracks.stream().mapToInt(t->t.numberOfExportedNotes).sum());
        // This is the same aggregator used by ProjectFrame.applyPreview:
        // track order must not let an authored lane replace the part total.
        part.numberOfExportedNotes=-1;
        PreviewPartStats.updateExportedNoteCounts(tracks);
        assertEquals(generated+authored,part.numberOfExportedNotes);
        preview.histogram.sumUp(song);
        assertTrue(preview.histogram.max()>=2,
            "Imported and authored notes overlapping at the start must contribute to the same peak");
    }

    @Test void addingAnAuthoredPluckDoesNotShortenImportedInstrumentSampleVoices() throws Exception {
        String source = CompositionAbc.starter("Plucked", 120, "4/4", "C", "Lute of Ages", 4)
            .replaceFirst("z8 \\|", "C C C C C C C C |");
        File file = temp.resolve("plucked.abc").toFile();
        Files.writeString(file.toPath(), source);
        AbcSong song = load(file);
        AbcPart part = song.getParts().getFirst();
        part.setInstrument(LotroInstrument.LUTE_OF_AGES);

        SequenceInfo unedited = SequenceInfo.fromAbcParts(song, true, false);
        unedited.histogram.sumUp(song);
        assertEquals(4, unedited.histogram.max(),
            "Plucked source notes continue sounding after written note-off");

        // An authored note just ten editor ticks long still rings for its sample's
        // full duration. It must not erase the exporter's original sample tails.
        song.setNoteEditorDeltas(Map.of(part.getPartNumber(),
            new NoteEditorDelta.Part(List.of(), List.of(
                new NoteEditorDelta.Value(64, 90, 480, 490)))));
        SequenceInfo background = SequenceInfo.fromAbcParts(song, true, false);
        background.histogram.sumUp(song);
        assertEquals(5, background.histogram.max(),
            "A single authored note must not lower the imported part's peak");
        assertEquals(5, background.histogram.peakForPart(part),
            "The displayed per-part peak must follow sample durations");

        SequenceInfo immediate = SequenceInfo.fromAbcParts(song.getAbcExporter(), song, true, false);
        immediate.histogram.sumUp(song);
        assertEquals(5, immediate.histogram.max(),
            "Immediate rebuild must use the same real sample durations");
    }

    @Test void shortAuthoredPlucksOverlapEvenAfterBothWrittenNoteOffs() throws Exception {
        String source = CompositionAbc.starter("Short plucks", 120, "4/4", "C", "Lute of Ages", 4);
        File file = temp.resolve("short-plucks.abc").toFile();
        Files.writeString(file.toPath(), source);
        AbcSong song = load(file);
        if (song.getParts().isEmpty()) song.createNewPart();
        AbcPart part = song.getParts().getFirst();
        part.setInstrument(LotroInstrument.LUTE_OF_AGES);
        song.setNoteEditorDeltas(Map.of(part.getPartNumber(),
            new NoteEditorDelta.Part(List.of(), List.of(
                new NoteEditorDelta.Value(60, 90, 960, 970),
                new NoteEditorDelta.Value(64, 90, 1080, 1090)))));

        SequenceInfo background = SequenceInfo.fromAbcParts(song, true, false);
        background.histogram.sumUp(song);
        assertEquals(2, background.histogram.max(),
            "Written pluck lengths do not limit audible sample polyphony");
        assertEquals(2, background.histogram.peakForPart(part));

        SequenceInfo immediate = SequenceInfo.fromAbcParts(song.getAbcExporter(), song, true, false);
        immediate.histogram.sumUp(song);
        assertEquals(2, immediate.histogram.max());
    }

    @Test void deletingImportedNotesRemovesTheirSampleVoicesWithoutAffectingOtherParts() throws Exception {
        String source = CompositionAbc.starter("Remove", 120, "4/4", "C", "Lute of Ages", 4)
            .replaceFirst("z8 \\|", "C8 |");
        File file = temp.resolve("remove.abc").toFile();
        Files.writeString(file.toPath(), source);
        AbcSong song = load(file);
        AbcPart part = song.getParts().getFirst();
        part.setInstrument(LotroInstrument.LUTE_OF_AGES);

        SequenceInfo original = SequenceInfo.fromAbcParts(song, true, false);
        original.histogram.sumUp(song);
        assertEquals(1, original.histogram.max());

        File generated = temp.resolve("remove-generated.abc").toFile();
        song.exportGeneratedAbc(generated, "Maestro");
        NoteDocument doc = AbcDocumentIO.readText(Files.readString(generated.toPath()));
        NoteEditorDelta.Value removed = NoteEditorDelta.Value.of(
            doc.layers.getFirst().baselineNotes.getFirst());
        song.setNoteEditorDeltas(Map.of(part.getPartNumber(),
            new NoteEditorDelta.Part(List.of(removed), List.of())));

        SequenceInfo preview = SequenceInfo.fromAbcParts(song, true, false);
        preview.histogram.sumUp(song);
        assertEquals(0, preview.histogram.max(),
            "Deleted generated notes should not remain in the sample-aware histogram");
    }
}
