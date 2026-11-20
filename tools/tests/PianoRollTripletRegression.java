package com.digero.maestro.noteedit;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.reflect.*;
import javax.sound.midi.Sequence;
import javax.swing.*;
import java.util.List;

/** Triplet snap choices, placement, fractional-tick boundaries and ABC round-trip. */
public class PianoRollTripletRegression {
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception{
        Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
    }
    static Field field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    static JComboBox<?> snap(Container c){
        for(Component x:c.getComponents()){
            if(x instanceof JComboBox<?> b && b.getItemCount()>0 && "1/1".equals(b.getItemAt(0)))return b;
            if(x instanceof Container nested){JComboBox<?> found=snap(nested);if(found!=null)return found;}
        }return null;
    }
    static MouseEvent mouse(PianoRollPanel r,int type,int x,int y){return new MouseEvent(r,type,0,0,x,y,1,false,MouseEvent.BUTTON1);}
    static void run()throws Exception{
        for(var kind:NoteDocument.Kind.values()){
            var editor=new NoteEditorApp.EditorTab(kind,null,(k,b)->{});var combo=snap(editor);
            var roll=(PianoRollPanel)field(editor,"roll").get(editor);
            check(combo!=null && "1/16".equals(combo.getSelectedItem()),"default snap");
            for(String name:List.of("1/24","1/48")){combo.setSelectedItem(name);check(roll.getSnapDivisor()==Integer.parseInt(name.substring(2)),"wired dropdown "+kind+name);}
            editor.suspendEmbeddedView();
        }
        for(int div:new int[]{24,48})for(int ppq:new int[]{960,100}){
            NoteDocument doc=ppq==960?AbcDocumentIO.readText("X:1\nT:Basic Flute\nM:4/4\nL:1/8\nQ:1/4=80\nK:C\nz8|\n"):new NoteDocument(NoteDocument.Kind.MIDI,ppq,Sequence.PPQ,null);
            if(ppq!=960)doc.layers.add(new NoteDocument.Layer("MIDI"));
            var roll=new PianoRollPanel();roll.setDocument(doc,0);roll.setSnapDivisor(div);field(roll,"quarterWidth").setInt(roll,480);
            int y=(Integer)call(roll,"y",new Class[]{int.class},60)+5;
            for(int i=0;i<div;i++){
                int x=64+(int)Math.round((i+0.5)*1920/div);
                call(roll,"add",new Class[]{MouseEvent.class},mouse(roll,MouseEvent.MOUSE_CLICKED,x,y));
            }
            var notes=doc.layer(0).notes;check(notes.size()==div,"one note per cell");
            for(int i=0;i<div;i++){
                long start=Math.round(i*4.0*ppq/div),end=Math.round((i+1)*4.0*ppq/div);
                check(notes.get(i).start()==start&&notes.get(i).end()==end,"exact cell endpoints: "+div+" ppq "+ppq+" cell "+i);
                check((Long)call(roll,"snapped",new Class[]{long.class},start)==start,"ruler snap");
                field(roll,"dragGridIndex").setLong(roll,i);
                check((Long)call(roll,"gridDragDelta",new Class[]{double.class},4.0*ppq/div)==end-start,"fractional drag delta");
            }
            if(ppq==960){
                String abc=AbcDocumentIO.toText(doc);var restored=AbcDocumentIO.readText(abc).layer(0).notes;
                check(restored.size()==notes.size(),"ABC export count");
                for(int i=0;i<div;i++)check(restored.get(i).start()==notes.get(i).start()&&restored.get(i).end()==notes.get(i).end(),"ABC timing round-trip at 80 BPM");
            }
            check(doc.canUndo(),"undo available");doc.undo();check(doc.layer(0).notes.size()==div-1,"undo works");doc.redo();check(doc.layer(0).notes.size()==div,"redo works");
        }
        System.out.println("PASS: MIDI/ABC dropdowns, 24/48 exact cell placement and seek, fractional PPQ drag offsets, undo/redo, ABC round-trip at 80 BPM");
    }
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{run();}catch(Exception e){throw new AssertionError(e);}});System.exit(0);
    }
}
