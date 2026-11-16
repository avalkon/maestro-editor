package com.digero.maestro.view;

import com.digero.common.abc.LotroInstrument;
import com.digero.common.i18n.LocaleManager;
import com.digero.common.i18n.UIText;
import com.digero.common.midi.SequencerWrapper;
import com.digero.maestro.abc.*;
import com.digero.maestro.midi.SequenceInfo;
import com.digero.maestro.noteedit.*;
import com.digero.maestro.util.FileResolver;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.swing.SwingUtilities;

/** Desktop-independent integration regression: synthetic preview track paints
 * without calling the unsupported preview-only SequenceInfo.getTrackCount(). */
public final class PreviewLaneRegression {
    private static void require(boolean good, String desc) {
        if (!good) throw new AssertionError(desc);
    }
    private static final class QuietSequencer extends SequencerWrapper {
        QuietSequencer() throws MidiUnavailableException {super();}
        @Override public Receiver createReceiver() {return new Receiver() {
            @Override public void send(MidiMessage message,long stamp) {}
            @Override public void close() {}
        };}
    }
    public static void main(String[] args) throws Exception {
        LocaleManager.init(true); UIText.init();
        var base = Files.createTempDirectory("maestro-b25-lane-");
        String abc=CompositionAbc.starter("Test",120,"4/4","C","Lute of Ages",8);
        File starter=base.resolve("starter.abc").toFile(); Files.writeString(starter.toPath(),abc);
        Preferences prefs=Preferences.userRoot().node("maestro-b25-"+UUID.randomUUID());
        FileResolver noExternal=new FileResolver() {
            public File locateFile(File f,String msg){throw new AssertionError(f);}
            public File resolveFile(File f,String msg){throw new AssertionError(f);}
        };
        AbcSong song=new AbcSong(starter,new PartAutoNumberer(prefs.node("n")),
            new PartNameTemplate(prefs.node("pn")),new ExportFilenameTemplate(prefs.node("ef")),
            new InstrNameSettings(prefs.node("in")),noExternal,
            new MiscSettings(prefs.node("m"),false),new SaveAndExportSettings(prefs.node("s")));
        if(song.getParts().isEmpty())song.createNewPart();
        song.initializeComposition("Test",abc,8);
        AbcPart a=song.getParts().getFirst();
        AbcPart b=song.createNewPart();b.setInstrument(LotroInstrument.BASIC_FIDDLE);
        song.setNoteEditorDeltas(Map.of(
            a.getPartNumber(),new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(60,90,15360,16320))),
            b.getPartNumber(),new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(64,90,15360,16320)))));
        song.setAuthoredTrackRouting(Map.of(a.getPartNumber(),Set.of(a.getPartNumber(),b.getPartNumber())));
        SequenceInfo preview=SequenceInfo.fromAbcParts(song,true,false);
        require(preview.getLastTrackInfos().size()==3,"three routed preview lanes");
        System.out.println("PREVIEW tickLen="+preview.getSequence().getTickLength()+" ppq="+preview.getSequence().getResolution());
        preview.histogram.sumUp(song);
        require(preview.histogram.max()==3,"combined simultaneous notes counted: " + preview.histogram.max());
        var info=preview.getLastTrackInfos().stream()
            .filter(x->x instanceof AuthoredPreviewTrackInfo).findFirst().orElseThrow();
        QuietSequencer sq=new QuietSequencer();
        try {
            sq.setSequence(preview.getSequence());
            System.out.println("WRAPPER length="+sq.getLength()+" ticks="+sq.getTickLength());
            SwingUtilities.invokeAndWait(() -> {
                AuthoredTrackPanel panel=new AuthoredTrackPanel(a.getPartNumber(),a,a,
                    song.getNoteEditorDeltas().get(a.getPartNumber()).added(),
                    AuthoredTrackRouting.targets(a.getPartNumber(),song.getAuthoredTrackRouting()),
                    sq,true,(destination,enabled)->{});
                try {
                    panel.setAbcPreviewMode(true);
                    panel.setSize(245,48);
                    var graph=panel.getNoteGraph();
                    graph.setSize(600,48);
                    var previewNote=((NoteGraph)graph).getEvents().getFirst();
                    require(previewNote.getStartMicros()==sq.tickToMicros(previewNote.getStartTick()),
                        "authored graph must use preview sequencer timing, not source exporter timing");
                    require(previewNote.getStartMicros()>7_000_000L,
                        "a note in measure 5 must not be compressed onto the left edge");
                    require(!graph.getBackground().equals(java.awt.Color.WHITE),
                        "authored lane must use Maestro dark graph background");
                    require(panel.isRoutedToSelectedPart(), "originating part must be routed");
                    require(((NoteGraph)graph).isOctaveLinesVisible(),
                        "authored lane must show octave guide lines like an imported track");
                    BufferedImage img=new BufferedImage(600,48,BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g=img.createGraphics();
                    try { graph.paint(g); } finally { g.dispose(); panel.discard(); }
                } catch (Throwable error) {throw new AssertionError("Preview lane paint failed",error);}
            });
        } finally {sq.discard();prefs.removeNode();}
        System.out.println("PASS: three routed tracks, combined polyphony=3, preview lane paints safely");
    }
}
