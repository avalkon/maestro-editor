package com.digero.maestro.noteedit;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.MouseEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import javax.swing.*;

/** Regression for the presentation-only all-parts audibility filter. */
public class PianoRollAudibleRegression {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static <T> T field(Object object,String name,Class<T> type)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(object));
    }
    static void layers(PianoRollPanel roll,Integer... expected)throws Exception{
        Method m=PianoRollPanel.class.getDeclaredMethod("visibleLayers");m.setAccessible(true);
        check(m.invoke(roll).equals(List.of(expected)),"visible layers should be "+List.of(expected)+", got "+m.invoke(roll));
    }
    static Point point(PianoRollPanel roll,EditableNote n)throws Exception{
        Method x=PianoRollPanel.class.getDeclaredMethod("x",long.class),y=PianoRollPanel.class.getDeclaredMethod("y",int.class);
        x.setAccessible(true);y.setAccessible(true);
        return new Point((Integer)x.invoke(roll,(n.start()+n.end())/2),(Integer)y.invoke(roll,n.pitch())+4);
    }
    static String tooltip(PianoRollPanel roll,Point p){
        return roll.getToolTipText(new MouseEvent(roll,MouseEvent.MOUSE_MOVED,0,0,p.x,p.y,0,false));
    }
    static int pixel(PianoRollPanel roll,Point p){
        roll.setSize(roll.getPreferredSize());
        BufferedImage image=new BufferedImage(roll.getWidth(),roll.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();roll.paint(g);g.dispose();return image.getRGB(p.x,p.y);
    }
    static void run(Path file)throws Exception{
        var editor=new NoteEditorApp.EditorTab(NoteDocument.Kind.ABC,null,(kind,bytes)->{});
        editor.useEmbeddedMode();editor.useSongPartsSelection();editor.load(file.toFile());
        var roll=field(editor,"roll",PianoRollPanel.class);
        roll.setFont(new Font(Font.DIALOG,Font.PLAIN,12));
        var all=field(editor,"allParts",JCheckBox.class);
        var only=field(editor,"onlyAudible",JCheckBox.class);
        var key=field(editor,"highlightKey",JComboBox.class);
        var doc=field(editor,"doc",NoteDocument.class);
        check(!all.isSelected()&&!only.isSelected()&&only.isEnabled(),"default controls");
        JToolBar toolbar=(JToolBar)only.getParent();toolbar.setSize(1400,32);toolbar.doLayout();
        check(only.getX()>all.getX()&&only.getX()<key.getX(),"filter position");
        check(key.getX()+key.getWidth()>=toolbar.getWidth()-12,"right-aligned key");
        editor.selectPartNumber(20);editor.setHighlightKey("G minor");
        var normal=Map.of(10,new boolean[]{false,false},20,new boolean[]{true,false},30,new boolean[]{false,false});
        editor.setPartMuteSoloStates(normal);
        layers(roll,1);only.doClick(0);layers(roll,1);roll.selectAllVisibleNotes();check(roll.selectionSize()==1,"muted single part remains editable");
        all.doClick(0);layers(roll,0,2);roll.selectAllVisibleNotes();check(roll.selectionSize()==2,"filter selects audible notes only");
        Point p=point(roll,doc.layer(1).notes.getFirst());
        int hiddenPixel=pixel(roll,p);check(!tooltip(roll,p).contains("Pitch "),"hidden note cannot be hit");
        check(!roll.deleteAt(p),"hidden right-click cannot delete");
        only.doClick(0);layers(roll,0,1,2);check(pixel(roll,p)!=hiddenPixel&&tooltip(roll,p).contains("Pitch "),"muted note painted and hittable with filter off");
        roll.selectAllVisibleNotes();check(roll.selectionSize()==3,"all notes selected");
        only.doClick(0);layers(roll,0,2);check(roll.selectionSize()==2,"hidden selection pruned");
        var preferred=roll.getPreferredSize();String detected=roll.getDetectedKey();boolean playScope=editor.playsAllParts();
        editor.setPartMuteSoloStates(Map.of(10,new boolean[]{false,true},20,new boolean[]{false,false},30,new boolean[]{false,true}));
        layers(roll,0,2);
        editor.setPartMuteSoloStates(Map.of(10,new boolean[]{true,false},20,new boolean[]{false,true},30,new boolean[]{false,false}));
        layers(roll,1);check(roll.selectionSize()==0,"live solo clears newly hidden selection");
        editor.setPartMuteSoloStates(Map.of(10,new boolean[]{true,false},20,new boolean[]{true,true},30,new boolean[]{false,false}));
        layers(roll,1); // Maestro's preview gives solo priority over mute.
        editor.setPartMuteSoloStates(Map.of(10,new boolean[]{true,false},20,new boolean[]{true,false},30,new boolean[]{true,false}));
        layers(roll);roll.selectAllVisibleNotes();check(roll.selectionSize()==0,"all hidden");
        all.doClick(0);layers(roll,1);check(only.isSelected(),"filter stays checked in single-part mode");
        all.doClick(0);layers(roll);
        editor.setPartMuteSoloStates(Map.of(10,new boolean[]{false,false},20,new boolean[]{false,false},30,new boolean[]{false,false}));
        layers(roll,0,1,2);
        editor.setPartMuteSoloStates(Map.of(10,new boolean[]{false,true},20,new boolean[]{false,false},30,new boolean[]{false,false}));
        layers(roll,0);
        editor.setPartMuteSoloStates(Map.of(20,new boolean[]{false,false},30,new boolean[]{false,false}));
        layers(roll,0,1,2); // no stale solo after replacing snapshot
        check(preferred.equals(roll.getPreferredSize()),"timeline/zoom unchanged");
        check(detected.equals(roll.getDetectedKey())&&roll.getEffectiveHighlightKey().equals("G minor"),"key unchanged");
        check(!doc.isDirty()&&!doc.canUndo()&&doc.layers.stream().allMatch(l->l.notes.size()==1),"view does not edit document");
        check(playScope==editor.playsAllParts(),"view does not change playback scope");
        editor.reloadGeneratedAbc(Files.readString(file).replace("T:Third","T:Renamed"),Map.of());
        check(only.isSelected()&&roll.isShowingOnlyAudible(),"filter survives refresh");layers(roll,0,1,2);
        var midi=new NoteEditorApp.EditorTab(NoteDocument.Kind.MIDI,null,(kind,bytes)->{});
        check(!field(midi,"onlyAudible",JCheckBox.class).isEnabled(),"raw MIDI has no mapped Song Parts state");
        editor.suspendEmbeddedView();midi.suspendEmbeddedView();
        System.out.println("PASS: checkbox layout/defaults, four view combinations, single/multiple solo, mute, all hidden, selection/hit/render safety, stale state replacement, unchanged notes/zoom/key/play scope, refresh, raw MIDI disabled");
    }
    public static void main(String[] args)throws Exception{
        Path file=Files.createTempFile("maestro-audible-",".abc");
        try{
            Files.writeString(file,"X:10\nT:First\nM:4/4\nL:1/8\nK:C\nC4 z4|\n\nX:20\nT:Second\nM:4/4\nL:1/8\nK:C\nE4 z4|\n\nX:30\nT:Third\nM:4/4\nL:1/8\nK:C\nG4 z4|\n");
            SwingUtilities.invokeAndWait(()->{try{run(file);}catch(Exception e){throw new AssertionError(e);}});
        }finally{Files.deleteIfExists(file);}
        System.exit(0);
    }
}
