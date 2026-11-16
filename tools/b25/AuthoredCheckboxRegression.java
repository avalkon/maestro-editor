package com.digero.maestro.view;

import com.digero.common.abc.LotroInstrument;
import com.digero.common.i18n.LocaleManager;
import com.digero.common.i18n.UIText;
import com.digero.common.midi.SequencerWrapper;
import com.digero.maestro.abc.*;
import com.digero.maestro.midi.SequenceInfo;
import com.digero.maestro.noteedit.*;
import com.digero.maestro.util.FileResolver;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.swing.SwingUtilities;
import javax.swing.JPanel;

/** B2.4.3: all authored tracks appear for every selected Song Part; the
 * checkbox changes that selected part's routing only. */
public final class AuthoredCheckboxRegression {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static final class QuietSequencer extends SequencerWrapper {
        QuietSequencer() throws MidiUnavailableException { super(); }
        @Override public Receiver createReceiver() { return new Receiver() {
            @Override public void send(MidiMessage m, long t) {}
            @Override public void close() {}
        }; }
    }
    public static void main(String[] args) throws Exception {
        LocaleManager.init(true); UIText.init();
        Path temp = Files.createTempDirectory("authored-checkbox-regression-");
        File file = temp.resolve("starter.abc").toFile();
        String abc = CompositionAbc.starter("Checkbox",120,"4/4","C","Lute of Ages",8);
        Files.writeString(file.toPath(), abc);
        Preferences prefs=Preferences.userRoot().node("authored-checkbox-"+UUID.randomUUID());
        FileResolver noExternal = new FileResolver() {
            public File locateFile(File f,String m) {throw new AssertionError(f);}
            public File resolveFile(File f,String m) {throw new AssertionError(f);}
        };
        try {
            AbcSong song=new AbcSong(file,new PartAutoNumberer(prefs.node("numbers")),
                new PartNameTemplate(prefs.node("names")),new ExportFilenameTemplate(prefs.node("export")),
                new InstrNameSettings(prefs.node("instruments")),noExternal,
                new MiscSettings(prefs.node("misc"),false),new SaveAndExportSettings(prefs.node("save")));
            if(song.getParts().isEmpty())song.createNewPart();
            song.initializeComposition("Checkbox",abc,8);
            AbcPart a=song.getParts().getFirst();
            AbcPart b=song.createNewPart();
            // Intentionally identical instrument and name: X: must remain unique.
            b.setInstrument(LotroInstrument.LUTE_OF_AGES);
            b.setTitle(a.getTitle());
            int xa=a.getPartNumber(),xb=b.getPartNumber();
            require(xa!=xb,"two Song Parts must have independent X: numbers");
            song.setNoteEditorDeltas(Map.of(
                xa,new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(60,80,15360,16320))),
                xb,new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(64,90,19200,20160)))));
            // X:b is completely unrouted, so no MIDI preview track is created.
            song.setAuthoredTrackRouting(Map.of(xb,Set.of()));
            SequenceInfo preview=SequenceInfo.fromAbcParts(song,true,false);
            long routed=preview.getLastTrackInfos().stream()
                .filter(i->i instanceof AuthoredPreviewTrackInfo).count();
            require(routed==1,"unrouted source must not create a playback track");
            QuietSequencer sq=new QuietSequencer();
            try {
                sq.setSequence(preview.getSequence());
                SwingUtilities.invokeAndWait(() -> {
                    // Both rows are present with X:a selected, including the unrouted one.
                    AuthoredTrackPanel sourceA=new AuthoredTrackPanel(xa,a,a,
                        song.getNoteEditorDeltas().get(xa).added(),
                        AuthoredTrackRouting.targets(xa,song.getAuthoredTrackRouting()),
                        sq,false,(destination,enabled)->{});
                    AuthoredTrackPanel sourceB=new AuthoredTrackPanel(xb,b,a,
                        song.getNoteEditorDeltas().get(xb).added(),
                        AuthoredTrackRouting.targets(xb,song.getAuthoredTrackRouting()),
                        sq,false,(destination,enabled)-> {
                            var routes = new HashMap<Integer,Set<Integer>>(song.getAuthoredTrackRouting());
                            var targets = new LinkedHashSet<Integer>(AuthoredTrackRouting.targets(xb,routes));
                            if(enabled)targets.add(destination);else targets.remove(destination);
                            routes.put(xb,targets);
                            song.setAuthoredTrackRouting(routes);
                        });
                    try {
                        require(sourceA.isRoutedToSelectedPart(),"source A checked in A");
                        require(!sourceB.isRoutedToSelectedPart(),"source B unchecked in A");
                        require(!sourceB.isAssignedAnywhere(),"white bar absent for unassigned B");
                        require(sourceB.getAuthoredNoteGraph().isOctaveLinesVisible(),
                            "octave guides visible for authored notes");
                        require(sourceB.getAuthoredNoteGraph().getEvents().size()==1,
                            "unrouted source B still has a visible note event");
                        long actual=sourceB.getAuthoredNoteGraph().getEvents().getFirst().getStartTick();
                        long expected=Math.round(19200.0*preview.getSequence().getResolution()/AbcDocumentIO.PPQ);
                        require(actual==expected,"authored notes must use preview PPQ and retain their measure");
                        sourceB.getRoutingCheckbox().doClick();
                        require(song.getAuthoredTrackRouting().get(xb).equals(Set.of(xa)),
                            "checking source B while part A selected routes B to A only");
                        require(AuthoredTrackRouting.targets(xa,song.getAuthoredTrackRouting()).equals(Set.of(xa)),
                            "source A route not modified by source B checkbox");
                        sourceB.getRoutingCheckbox().doClick();
                        require(song.getAuthoredTrackRouting().get(xb).isEmpty(),
                            "unchecking again restores explicit unassigned state");
                        JPanel graph=sourceB.getNoteGraph();
                        graph.setSize(600,48);
                        BufferedImage bitmap=new BufferedImage(600,48,BufferedImage.TYPE_INT_ARGB);
                        Graphics2D g=bitmap.createGraphics();
                        try {graph.paint(g);}finally{g.dispose();}
                    } finally { sourceA.discard(); sourceB.discard(); }
                });
            } finally { sq.discard(); }
        } finally { prefs.removeNode(); }
        System.out.println("PASS: both authored source rows visible in every part; checkbox routing, octave lines, and timing");
    }
}
