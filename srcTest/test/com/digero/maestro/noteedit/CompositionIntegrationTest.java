package com.digero.maestro.noteedit;

import com.digero.common.abc.LotroInstrument;
import com.digero.common.abctomidi.AbcToMidi;
import com.digero.common.midi.KeySignature;
import com.digero.common.i18n.LocaleManager;
import com.digero.common.i18n.UIText;
import com.digero.maestro.abc.AbcPart;
import com.digero.maestro.abc.AbcSong;
import com.digero.maestro.midi.SequenceInfo;
import com.digero.maestro.abc.ExportFilenameTemplate;
import com.digero.maestro.util.FileResolver;
import com.digero.maestro.view.InstrNameSettings;
import com.digero.maestro.abc.PartAutoNumberer;
import com.digero.maestro.abc.PartNameTemplate;
import com.digero.maestro.view.MiscSettings;
import com.digero.maestro.view.SaveAndExportSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests of native compositions and imported-project empty-track semantics. */
class CompositionIntegrationTest {
    @TempDir Path temp;

    private final Preferences prefs = Preferences.userRoot().node("maestro-composition-tests/" + UUID.randomUUID());
    private final FileResolver noExternalSources = new FileResolver() {
        @Override public File locateFile(File file, String message) {
            throw new AssertionError("Composition unexpectedly requested an external file: " + file);
        }
        @Override public File resolveFile(File file, String message) {
            throw new AssertionError("Composition unexpectedly requested an external file: " + file);
        }
    };

    @BeforeAll static void initializeLocale() {
        LocaleManager.init(true);
        UIText.init();
    }

    private AbcSong load(File file) throws Exception {
        return new AbcSong(file,
            new PartAutoNumberer(prefs.node("numbers")),
            new PartNameTemplate(prefs.node("names")),
            new ExportFilenameTemplate(prefs.node("export")),
            new InstrNameSettings(prefs.node("instrument")),
            noExternalSources,
            new MiscSettings(prefs.node("misc"),false),
            new SaveAndExportSettings(prefs.node("save")));
    }

    private static long parts(String abc) {
        return abc.lines().filter(line -> line.stripLeading().startsWith("X:")).count();
    }

    // Timestamp changes every second; the arrangement must match exactly otherwise.
    private static String ignoringExportTimestamp(String abc) {
        return abc.replaceAll("(?m)^(%%export-timestamp)[ \t]+[^\r\n]*$", "$1 <generated>");
    }

    private static NoteEditorDelta.Part addition(int pitch, int start, int end) {
        return new NoteEditorDelta.Part(List.of(),
            List.of(new NoteEditorDelta.Value(pitch, 90, start, end)));
    }

    @Test void nativeCompositionPreviewsExportsAndReopensWithoutExternalSource() throws Exception {
        String scaffold = CompositionAbc.starter("Original Song", 120, "3/4", "G", "Lute of Ages", 8);
        File starter = temp.resolve("first-source.abc").toFile();
        Files.writeString(starter.toPath(), scaffold);
        AbcSong song = load(starter);
        if(song.getParts().isEmpty()) song.createNewPart();
        song.initializeComposition("Original Song", scaffold, 8);
        song.setTitle("Original Song");
        // The New ABC dialog applies the chosen key to the Maestro song model.
        song.setKeySignature(new KeySignature("G"));
        AbcPart lute = song.getParts().get(0);
        lute.setInstrument(LotroInstrument.LUTE_OF_AGES);
        AbcPart fiddle = song.createNewPart();
        fiddle.setInstrument(LotroInstrument.BASIC_FIDDLE);

        File abc = temp.resolve("output.abc").toFile();
        song.exportGeneratedAbc(abc,"Maestro");
        String generated = Files.readString(abc.toPath());
        assertEquals(0,parts(generated),"The normal exporter must omit empty tracks");
        NoteDocument baseline = AbcDocumentIO.readText(song.addMissingEditorParts(generated));
        assertEquals(2,baseline.layers.size(),"Even unpopulated new parts should be selectable in Piano Roll");
        assertTrue(baseline.layers.stream().allMatch(layer -> layer.notes.isEmpty()));
        // A completely blank new composition may export one playable rest-only starter.
        song.exportAbc(abc,"Maestro");
        assertEquals(1,parts(Files.readString(abc.toPath())));
        assertNotNull(AbcToMidi.convert(new AbcToMidi.Params(abc)));

        song.setNoteEditorDeltas(Map.of(
            lute.getPartNumber(), addition(48,0,960),
            fiddle.getPartNumber(), addition(60,960,1920)));
        SequenceInfo preview = SequenceInfo.fromAbcParts(song,true,false);
        assertEquals(2,preview.getLastTrackInfos().size(),"Each authored part should get its own preview graph track");
        assertTrue(preview.getLastTrackInfos().stream().allMatch(info -> info.getEvents().size()==1));
        assertTrue(preview.getSequence().getTracks().length >= 3,"Tempo plus both authored parts should exist");
        long expectedEnd = Math.round(preview.getSequence().getResolution() * 3.0 * 8);
        assertTrue(preview.getSequence().getTickLength() >= expectedEnd,
            "Preview must retain all eight requested measures, not stop after the empty starter");
        assertEquals(2, preview.getLastTrackInfos().size());
        // Production preview workers operate on a deep copy. Their new track
        // metadata must refer to the original parts so ArrangementView can
        // find the edited notes and Song Parts mute/solo can address them.
        AbcSong workerCopy = new AbcSong(song);
        SequenceInfo workerPreview = SequenceInfo.fromAbcParts(workerCopy,true,false);
        assertTrue(workerPreview.getLastTrackInfos().stream()
            .allMatch(info -> song.getParts().contains(info.part)));

        song.exportAbc(abc,"Maestro");
        String composed = Files.readString(abc.toPath());
        assertEquals(2,parts(composed));
        assertTrue(composed.contains("K: G"),"Composition key must be retained in rendered ABC");
        assertNotNull(AbcToMidi.convert(new AbcToMidi.Params(abc)));

        File saved = temp.resolve("saved.msx").toFile();
        TransformerFactory.newInstance().newTransformer()
            .transform(new DOMSource(song.saveToXml()), new StreamResult(saved));
        Files.delete(starter.toPath());
        AbcSong reopened = load(saved);
        assertTrue(reopened.isCompositionMode());
        assertEquals(8,reopened.getInitialCompositionMeasures());
        assertEquals(2,reopened.getParts().size());
        assertEquals(song.getNoteEditorDeltas(),reopened.getNoteEditorDeltas(),
            "All authored notes, pitches, velocities and durations must survive MSX reopening");
        SequenceInfo reopenedPreview=SequenceInfo.fromAbcParts(reopened,true,false);
        assertEquals(2,reopenedPreview.getLastTrackInfos().size(),
            "Reopened composition must rebuild both playable preview tracks");
        File reopenedAbc = temp.resolve("reopened.abc").toFile();
        reopened.exportAbc(reopenedAbc,"Maestro");
        assertEquals(2,parts(Files.readString(reopenedAbc.toPath())));
        assertNotNull(AbcToMidi.convert(new AbcToMidi.Params(reopenedAbc)));
    }

    @Test void importedProjectsStillOmitUneditedEmptyParts() throws Exception {
        String source=CompositionAbc.starter("Imported",120,"4/4","C","Lute of Ages",2)
            .replaceFirst("z8 \\|", "C8 |");
        File midiSource=temp.resolve("imported.abc").toFile();
        Files.writeString(midiSource.toPath(),source);
        AbcSong song=load(midiSource);
        if(song.getParts().isEmpty()) song.createNewPart();
        AbcPart empty=song.createNewPart();
        empty.setInstrument(LotroInstrument.BASIC_FIDDLE);
        assertFalse(song.isCompositionMode());
        File out=temp.resolve("export.abc").toFile();
        song.exportGeneratedAbc(out,"Maestro");
        String baseline=Files.readString(out.toPath());
        song.exportAbc(out,"Maestro");
        assertEquals(ignoringExportTimestamp(baseline),
            ignoringExportTimestamp(Files.readString(out.toPath())),
            "Normal project export should be identical apart from its generated timestamp");
        assertEquals(1,parts(baseline),"Empty additional part must be omitted");

        song.setNoteEditorDeltas(Map.of(empty.getPartNumber(),addition(60,960,1920)));
        SequenceInfo preview=SequenceInfo.fromAbcParts(song,true,false);
        assertEquals(2,preview.getLastTrackInfos().size(),
            "Authored part must appear alongside the original generated track");
        song.exportAbc(out,"Maestro");
        assertEquals(2,parts(Files.readString(out.toPath())));
        assertNotNull(AbcToMidi.convert(new AbcToMidi.Params(out)));
    }

    @Test void unplayableCompositionNotesRemainInMsxButNotAbcOrPreview() throws Exception {
        String scaffold=CompositionAbc.starter("Range",120,"4/4","C","Lute of Ages",8);
        File starter=temp.resolve("range-starter.abc").toFile();
        Files.writeString(starter.toPath(),scaffold);
        AbcSong song=load(starter);
        if(song.getParts().isEmpty())song.createNewPart();
        song.initializeComposition("Range",scaffold,8);
        AbcPart lute=song.getParts().getFirst();
        lute.setInstrument(LotroInstrument.LUTE_OF_AGES);
        AbcPart invalidOnly=song.createNewPart();
        invalidOnly.setInstrument(LotroInstrument.BASIC_FIDDLE);
        NoteEditorDelta.Value playable=new NoteEditorDelta.Value(48,90,960,1920);
        NoteEditorDelta.Value unplayable=new NoteEditorDelta.Value(4,90,2400,3360);
        song.setNoteEditorDeltas(Map.of(
            lute.getPartNumber(),new NoteEditorDelta.Part(List.of(),List.of(playable,unplayable)),
            invalidOnly.getPartNumber(),new NoteEditorDelta.Part(List.of(),
                List.of(new NoteEditorDelta.Value(20,90,4000,4960)))));

        File saved=temp.resolve("range.msx").toFile();
        TransformerFactory.newInstance().newTransformer()
            .transform(new DOMSource(song.saveToXml()),new StreamResult(saved));
        Files.delete(starter.toPath());
        AbcSong reopened=load(saved);
        assertEquals(song.getNoteEditorDeltas(),reopened.getNoteEditorDeltas(),
            "Unplayable notes must remain editable after save/reopen");

        File abc=temp.resolve("range-export.abc").toFile();
        reopened.exportAbc(abc,"Maestro");
        assertEquals(1,parts(Files.readString(abc.toPath())),
            "A part containing only unplayable additions must not be exported");
        NoteDocument rendered=AbcDocumentIO.read(abc);
        assertEquals(1,rendered.layers.getFirst().notes.size());
        assertEquals(48,rendered.layers.getFirst().notes.getFirst().pitch());
        assertNotNull(AbcToMidi.convert(new AbcToMidi.Params(abc)));
        SequenceInfo preview=SequenceInfo.fromAbcParts(reopened,true,false);
        assertEquals(1,preview.getLastTrackInfos().size());
        assertEquals(1,preview.getLastTrackInfos().getFirst().getEvents().size());
    }

    @Test void importedProjectDoesNotExportInvalidOnlyAdditionalPart() throws Exception {
        String source=CompositionAbc.starter("Imported",120,"4/4","C","Lute of Ages",2)
            .replaceFirst("z8 \\|", "C8 |");
        File input=temp.resolve("imported-range.abc").toFile();
        Files.writeString(input.toPath(),source);
        AbcSong song=load(input);
        AbcPart empty=song.createNewPart();
        empty.setInstrument(LotroInstrument.BASIC_FIDDLE);
        song.setNoteEditorDeltas(Map.of(empty.getPartNumber(),
            new NoteEditorDelta.Part(List.of(),List.of(
                new NoteEditorDelta.Value(20,90,960,1920)))));
        File out=temp.resolve("invalid-only-import.abc").toFile();
        song.exportAbc(out,"Maestro");
        assertEquals(1,parts(Files.readString(out.toPath())),
            "Normal imported-project empty-track omission must remain unchanged");
    }

}
