package com.digero.maestro.noteedit;

import com.digero.common.abc.LotroInstrument;
import com.digero.common.abctomidi.AbcToMidi;
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
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import static org.junit.jupiter.api.Assertions.*;

/** Routing and serialization tests, independent of physical audio hardware. */
class AuthoredTrackRoutingTest {
    @TempDir Path temp;
    private final Preferences prefs=Preferences.userRoot().node("maestro-route-test-"+UUID.randomUUID());
    private final FileResolver noExternal=new FileResolver() {
        @Override public File locateFile(File file,String message) {throw new AssertionError(file);}
        @Override public File resolveFile(File file,String message) {throw new AssertionError(file);}
    };
    @BeforeAll static void setUp() {LocaleManager.init(true);UIText.init();}
    private AbcSong load(File source) throws Exception {
        return new AbcSong(source,new PartAutoNumberer(prefs.node("numbers")),
            new PartNameTemplate(prefs.node("names")),
            new ExportFilenameTemplate(prefs.node("export")),
            new InstrNameSettings(prefs.node("instruments")),noExternal,
            new MiscSettings(prefs.node("misc"),false),
            new SaveAndExportSettings(prefs.node("save")));
    }
    private static NoteEditorDelta.Part notes(int pitch) {
        return new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(pitch,90,960,1920)));
    }
    private static long partCount(String abc) {
        return abc.lines().filter(s->s.startsWith("X:")).count();
    }
    @Test void compositionRoutesSeparatePreviewTracksAndPersists() throws Exception {
        String scaffold=CompositionAbc.starter("Routing",120,"4/4","C","Lute of Ages",8);
        File starter=temp.resolve("starter.abc").toFile();
        Files.writeString(starter.toPath(),scaffold);
        AbcSong song=load(starter);
        if(song.getParts().isEmpty())song.createNewPart();
        song.initializeComposition("Routing",scaffold,8);
        AbcPart lute=song.getParts().getFirst();lute.setInstrument(LotroInstrument.LUTE_OF_AGES);
        AbcPart fiddle=song.createNewPart();fiddle.setInstrument(LotroInstrument.BASIC_FIDDLE);
        int a=lute.getPartNumber(),b=fiddle.getPartNumber();
        song.setNoteEditorDeltas(Map.of(a,notes(60),b,notes(64)));
        assertEquals(2,SequenceInfo.fromAbcParts(song,true,false).getLastTrackInfos().size());
        song.setAuthoredTrackRouting(Map.of(a,Set.of(a,b)));
        var preview=SequenceInfo.fromAbcParts(song,true,false);
        assertEquals(3,preview.getLastTrackInfos().size());
        assertTrue(preview.getLastTrackInfos().stream().allMatch(i->i instanceof AuthoredPreviewTrackInfo));
        assertEquals(2,preview.getLastTrackInfos().stream().filter(i->i.part.getPartNumber()==b).count());
        assertTrue(preview.getLastTrackInfos().stream().allMatch(i->i.getEvents().size()==1));
        assertEquals(1,preview.getLastTrackInfos().stream().filter(i->i.part.getPartNumber()==b)
            .map(i->i.channel).distinct().count(),
            "Two authored lanes in one part must share a program channel");
        File abc=temp.resolve("export.abc").toFile();
        song.exportAbc(abc,"Maestro");
        var document=AbcDocumentIO.read(abc);
        assertEquals(2,document.layers.size());
        assertEquals(2,document.layers.stream().filter(l->l.partNumber==b).findFirst().orElseThrow().notes.size());
        assertNotNull(AbcToMidi.convert(new AbcToMidi.Params(abc)));
        File msx=temp.resolve("saved.msx").toFile();
        TransformerFactory.newInstance().newTransformer()
            .transform(new DOMSource(song.saveToXml()),new StreamResult(msx));
        Files.delete(starter.toPath());
        AbcSong reloaded=load(msx);
        assertEquals(song.getAuthoredTrackRouting(),reloaded.getAuthoredTrackRouting());
        assertEquals(song.getNoteEditorDeltas(),reloaded.getNoteEditorDeltas());
        assertEquals(3,SequenceInfo.fromAbcParts(reloaded,true,false).getLastTrackInfos().size());
        reloaded.setAuthoredTrackRouting(Map.of(a,Set.of(b)));
        assertEquals(2,SequenceInfo.fromAbcParts(reloaded,true,false).getLastTrackInfos().size());
        reloaded.exportAbc(abc,"Maestro");
        assertEquals(1,partCount(Files.readString(abc.toPath())));
        reloaded.setAuthoredTrackRouting(Map.of(a,Set.of()));
        assertEquals(1,SequenceInfo.fromAbcParts(reloaded,true,false).getLastTrackInfos().size());
        assertEquals(1,reloaded.getNoteEditorDeltas().get(a).added().size());
    }
    @Test void twoIdenticallyNamedLutesKeepDistinctRoutesAfterMsxReopen() throws Exception {
        String scaffold=CompositionAbc.starter("Same Instrument",120,"4/4","C","Lute of Ages",8);
        File starter=temp.resolve("same-instrument.abc").toFile();
        Files.writeString(starter.toPath(),scaffold);
        AbcSong song=load(starter);
        if(song.getParts().isEmpty())song.createNewPart();
        song.initializeComposition("Same Instrument",scaffold,8);
        AbcPart a=song.getParts().getFirst();
        AbcPart b=song.createNewPart();
        a.setInstrument(LotroInstrument.LUTE_OF_AGES);
        b.setInstrument(LotroInstrument.LUTE_OF_AGES);
        int x=a.getPartNumber(),y=b.getPartNumber();
        assertTrue(x!=y,"same instrument must not imply same X: part identity");
        var first=notes(60);
        var second=new NoteEditorDelta.Part(List.of(),List.of(
            new NoteEditorDelta.Value(64,110,7680,8640)));
        song.setNoteEditorDeltas(Map.of(x,first,y,second));
        var original=SequenceInfo.fromAbcParts(song,true,false).getLastTrackInfos();
        assertEquals(2,original.size());
        for(var track:original) {
            var authored=(AuthoredPreviewTrackInfo)track;
            assertEquals(authored.sourcePartNumber,authored.part.getPartNumber());
        }
        song.setAuthoredTrackRouting(Map.of(x,Set.of(x,y)));
        var routes=SequenceInfo.fromAbcParts(song,true,false).getLastTrackInfos();
        assertEquals(3,routes.size());
        assertEquals(2,routes.stream().filter(t->t.part.getPartNumber()==y).count());
        assertEquals(1,routes.stream().filter(t->t.part.getPartNumber()==x).count());

        File msx=temp.resolve("two-lutes.msx").toFile();
        TransformerFactory.newInstance().newTransformer()
            .transform(new DOMSource(song.saveToXml()),new StreamResult(msx));
        Files.delete(starter.toPath());
        AbcSong reopened=load(msx);
        assertEquals(song.getNoteEditorDeltas(),reopened.getNoteEditorDeltas());
        assertEquals(song.getAuthoredTrackRouting(),reopened.getAuthoredTrackRouting());
        assertEquals(3,SequenceInfo.fromAbcParts(reopened,true,false).getLastTrackInfos().size());
    }

    @Test void importedSourceTracksRemainSeparateAndUneditedEmptyPartsOmitted() throws Exception {
        String source=CompositionAbc.starter("Imported",120,"4/4","C","Lute of Ages",2)
            .replaceFirst("z8 \\|","C8 |");
        File input=temp.resolve("imported.abc").toFile();
        Files.writeString(input.toPath(),source);
        AbcSong song=load(input);
        AbcPart part=song.getParts().getFirst();
        AbcPart empty=song.createNewPart();empty.setInstrument(LotroInstrument.BASIC_FIDDLE);
        File out=temp.resolve("out.abc").toFile();
        song.exportAbc(out,"Maestro");
        assertEquals(1,partCount(Files.readString(out.toPath())));
        song.setNoteEditorDeltas(Map.of(part.getPartNumber(),notes(60)));
        var preview=SequenceInfo.fromAbcParts(song,true,false);
        assertEquals(2,preview.getLastTrackInfos().size());
        assertEquals(1,preview.getLastTrackInfos().stream()
            .filter(t->t instanceof AuthoredPreviewTrackInfo).count());
        song.setAuthoredTrackRouting(Map.of(part.getPartNumber(),Set.of(empty.getPartNumber())));
        song.exportAbc(out,"Maestro");
        assertEquals(2,partCount(Files.readString(out.toPath())));
        song.setAuthoredTrackRouting(Map.of(part.getPartNumber(),Set.of()));
        song.exportAbc(out,"Maestro");
        assertEquals(1,partCount(Files.readString(out.toPath())));
    }
}
