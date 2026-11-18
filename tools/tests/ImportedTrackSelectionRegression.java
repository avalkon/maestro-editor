package com.digero.maestro.view;

import com.digero.maestro.abc.*;
import com.digero.maestro.midi.*;
import com.digero.maestro.util.*;
import com.digero.maestro.noteedit.*;
import com.digero.common.midi.*;
import com.digero.common.view.ColorTable;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.prefs.*;
import javax.sound.midi.*;
import javax.swing.*;

/** Exercise the actual JCheckBox action listeners, not a simulated group toggle. */
public class ImportedTrackSelectionRegression {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class SilentSequencer extends NoteFilterSequencerWrapper {
        SilentSequencer() throws MidiUnavailableException {super();}
        @Override public Receiver createReceiver() {
            return new Receiver() {public void send(MidiMessage m,long t){} public void close(){}};
        }
    }
    static Object field(Class<?> type,Object instance,String name) throws Exception {
        Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(instance);
    }
    static JCheckBox box(TrackPanel panel) throws Exception {
        return (JCheckBox)field(TrackPanel.class,panel,"enableTrackCheckBox");
    }
    static ColorTable color(TrackPanel panel) throws Exception {
        return (ColorTable)field(NoteGraph.class,field(TrackPanel.class,panel,"noteGraph"),"noteColor");
    }
    static void assertAssignments(AbcPart part,List<Integer> tracks,TrackPanel[] panels,boolean... expected) throws Exception {
        for(int i=0;i<expected.length;i++) {
            check(part.isTrackEnabled(tracks.get(i))==expected[i],"Wrong source flag at "+i);
            check(box(panels[i]).isSelected()==expected[i],"Wrong checkbox at "+i);
        }
        check(part.getEnabledTrackCount()==java.util.stream.IntStream.range(0,expected.length).filter(i->expected[i]).count(),"Wrong assignment count");
    }
    static AbcSong load(File f,FileResolver resolver,Preferences p)throws Exception {
        return new AbcSong(f,new PartAutoNumberer(p.node("number")),new PartNameTemplate(p.node("parts")),new ExportFilenameTemplate(p.node("export")),new InstrNameSettings(p.node("names")),resolver,new MiscSettings(null,false),new SaveAndExportSettings(p.node("settings")));
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("java.awt.headless","true");
        com.digero.common.i18n.LocaleManager.init();com.digero.common.i18n.UIText.init();
        Preferences p=Preferences.userRoot().node("maestro-selection-test-"+System.nanoTime());
        File source=new File(args[1]);FileResolver resolver=new FileResolver(){public File locateFile(File f,String s){return source;} public File resolveFile(File f,String s){return source;}};
        AbcSong song=load(new File(args[0]),resolver,p);
        var replacements=new LinkedHashMap<Integer,NoteEditorDelta.Part>();
        for(var e:song.getNoteEditorDeltas().entrySet()) replacements.put(e.getKey(),new NoteEditorDelta.Part(e.getValue().removed(),e.getValue().added(),true));
        song.setNoteEditorDeltas(replacements);
        AbcPart part=song.getParts().stream().filter(x->x.getPartNumber()==1).findFirst().orElseThrow();
        List<Integer> tracks=new ArrayList<>();
        for(int i=0;i<part.getTrackCount();i++)if(song.getSequenceInfo().getTrackInfo(i).hasEvents())tracks.add(i);
        int original=tracks.stream().filter(part::isTrackConfigured).findFirst().orElseThrow();
        tracks.remove(Integer.valueOf(original)); tracks.addFirst(original); tracks=List.copyOf(tracks.subList(0,3));
        final List<Integer> chosen=tracks;
        SwingUtilities.invokeAndWait(()->{
            SilentSequencer midi=null,preview=null;
            TrackPanel[] panels=new TrackPanel[3];
            try {
                midi=new SilentSequencer(); preview=new SilentSequencer();
                midi.setSequence(song.getSequenceInfo().getSequence());
                Sequence previewMidi=new Sequence(Sequence.PPQ,480);previewMidi.createTrack();previewMidi.createTrack();preview.setSequence(previewMidi);
                part.setPreviewSequenceTrackNumber(1);
                for(int i=0;i<3;i++) {
                    panels[i]=new TrackPanel(song.getSequenceInfo().getTrackInfo(chosen.get(i)),midi,part,preview,new ControlLayout(48,new JPanel()));
                    panels[i].setAbcPreviewMode(true);
                }
                assertAssignments(part,chosen,panels,false,false,false);
                check(color(panels[0])==ColorTable.NOTE_OFF,"Unassigned original not grey");
                for(TrackPanel panel:panels)box(panel).doClick(0);
                assertAssignments(part,chosen,panels,true,true,true);
                check(color(panels[0])==ColorTable.NOTE_ENABLED,"Assigned original not colored");
                box(panels[0]).doClick(0);
                assertAssignments(part,chosen,panels,false,true,true);
                check(color(panels[0])==ColorTable.NOTE_OFF,"Deselected original not grey");
                song.setAuthoredTrackRouting(Map.of(1,Set.of(1)));
                for(TrackPanel panel:panels)panel.refreshImportedRoutingAppearance();
                box(panels[1]).doClick(0);
                assertAssignments(part,chosen,panels,false,false,true);
                box(panels[0]).doClick(0);
                assertAssignments(part,chosen,panels,true,false,true);
                box(panels[2]).doClick(0);
                assertAssignments(part,chosen,panels,true,false,false);
                box(panels[0]).doClick(0);
                assertAssignments(part,chosen,panels,false,false,false);
                AbcPart elsewhere=song.getParts().stream().filter(x->x!=part).findFirst().orElseThrow();
                elsewhere.setTrackEnabled(chosen.get(0),true); elsewhere.setPreviewSequenceTrackNumber(1);
                panels[0].refreshImportedRoutingAppearance();
                check(color(panels[0])==ColorTable.NOTE_DISABLED,"Elsewhere-assigned source should stay colored");
                preview.setTrackMute(1,true);panels[0].refreshImportedRoutingAppearance();
                check(color(panels[0])==ColorTable.NOTE_OFF,"Muted source should be grey");
                preview.setTrackMute(1,false);
                elsewhere.setTrackEnabled(chosen.get(0),false);panels[0].refreshImportedRoutingAppearance();
                check(color(panels[0])==ColorTable.NOTE_OFF,"Completely unassigned source should be grey");
                // Keep exactly two of the three rows active and retain a disabled row's settings.
                box(panels[0]).doClick(0);box(panels[2]).doClick(0);
                part.setTrackVolumeAdjust(chosen.get(1),-12);
                assertAssignments(part,chosen,panels,true,false,true);
            }catch(Exception ex){throw new RuntimeException(ex);}finally{
                for(TrackPanel panel:panels)if(panel!=null)panel.discard();
                if(preview!=null)preview.discard();if(midi!=null)midi.discard();
            }
        });
        File saved=File.createTempFile("maestro-independent-source-", ".msx");
        XmlUtil.saveDocument(song.saveToXml(),saved); AbcSong reloaded=load(saved,resolver,p);
        AbcPart r=reloaded.getParts().stream().filter(x->x.getPartNumber()==1).findFirst().orElseThrow();
        check(r.isTrackEnabled(chosen.get(0))&&!r.isTrackEnabled(chosen.get(1))&&r.isTrackEnabled(chosen.get(2)),"Checkboxes lost on reload");
        check(r.getTrackVolumeAdjust(chosen.get(1))==-12,"Disabled track settings lost on reload");
        check(reloaded.getAuthoredTrackRouting().get(1).equals(Set.of(1)),"Authored route changed");
        SequenceInfo a=SequenceInfo.fromAbcParts(reloaded,true,true);
        int authored=a.getLastTrackInfos().stream().filter(t->t instanceof AuthoredPreviewTrackInfo&&t.part.getPartNumber()==1).mapToInt(t->t.noteEvents.size()).sum();
        int imported=a.getLastTrackInfos().stream().filter(t->!(t instanceof AuthoredPreviewTrackInfo)&&t.part.getPartNumber()==1).mapToInt(t->t.noteEvents.size()).sum();
        check(authored>0&&imported>0,"Both layers must be audible");
        r.setTrackEnabled(chosen.get(0),false);r.setTrackEnabled(chosen.get(2),false);
        SequenceInfo b=SequenceInfo.fromAbcParts(reloaded,true,true);
        int remaining=b.getLastTrackInfos().stream().filter(t->!(t instanceof AuthoredPreviewTrackInfo)&&t.part.getPartNumber()==1).mapToInt(t->t.noteEvents.size()).sum();
        check(remaining==0,"Disabled originals still play");
        check(b.getLastTrackInfos().stream().filter(t->t instanceof AuthoredPreviewTrackInfo&&t.part.getPartNumber()==1).mapToInt(t->t.noteEvents.size()).sum()==authored,"Original toggling changed authored notes");
        saved.delete();System.out.println("PASS real checkbox listeners, per-track persistence, grey/other-part/muted colors, and independent preview: authored="+authored+", imported="+imported);
    }
}
