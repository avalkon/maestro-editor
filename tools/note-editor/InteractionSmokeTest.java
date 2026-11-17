package com.digero.maestro.noteedit;

import javax.swing.*;
import javax.sound.midi.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.UUID;

/** Headless verification of edit gestures, shared selections, grids, zoom and playback patches. */
public final class InteractionSmokeTest {
    private static void check(boolean yes,String message) { if(!yes) throw new AssertionError(message); }
    private static void mouse(PianoRollPanel roll,int id,int x,int y,int mods,int clicks) {
        roll.dispatchEvent(new MouseEvent(roll,id,System.currentTimeMillis(),mods,x,y,clicks,false,MouseEvent.BUTTON1));
    }
    public static void main(String[]args) throws Exception {
        SwingUtilities.invokeAndWait(InteractionSmokeTest::testUI);
        System.out.println("PASS: marquee, right-click deletion/undo, new-note audition, sticky keys, triplet snap, XY zoom");
    }
    private static void testUI() {
        try {
            NoteDocument doc=new NoteDocument(NoteDocument.Kind.ABC,960,Sequence.PPQ,new File("test.abc"));
            NoteDocument.Layer lute=new NoteDocument.Layer("Lute of Ages");
            lute.instrumentName="Lute of Ages";lute.lotroProgram=24;
            NoteDocument.Layer harp=new NoteDocument.Layer("Basic Harp");
            harp.instrumentName="Basic Harp";harp.lotroProgram=46;
            lute.notes.add(EditableNote.create(60,90,0,0,960));
            harp.notes.add(EditableNote.create(62,90,0,0,960));
            harp.notes.add(EditableNote.create(65,90,0,1920,2880));
            doc.layers.add(lute);doc.layers.add(harp);
            PianoRollPanel roll=new PianoRollPanel();roll.setDocument(doc,0);roll.setShowAllParts(true);
            roll.setSize(1800,2500);
            // The box intersects both tracks; shift-add a second box on an empty region.
            mouse(roll,MouseEvent.MOUSE_PRESSED,220,1270,0,1);
            mouse(roll,MouseEvent.MOUSE_DRAGGED,65,1190,0,0);
            mouse(roll,MouseEvent.MOUSE_RELEASED,65,1190,0,1);
            check(roll.selectionSize()==2,"Multi-part box selection");
            mouse(roll,MouseEvent.MOUSE_PRESSED,400,1160,InputEvent.SHIFT_DOWN_MASK,1);
            mouse(roll,MouseEvent.MOUSE_DRAGGED,240,1110,InputEvent.SHIFT_DOWN_MASK,0);
            mouse(roll,MouseEvent.MOUSE_RELEASED,240,1110,InputEvent.SHIFT_DOWN_MASK,1);
            check(roll.selectionSize()==3,"Shift+marquee should extend selection");
            // Delete across parts should be a single undo unit.
            roll.removeSelected();
            check(lute.notes.isEmpty()&&harp.notes.isEmpty(),"Multi-part delete");
            doc.undo();
            check(lute.notes.size()==1&&harp.notes.size()==2,"Atomic undo across parts");
            // Add one note at the left edge of the grid cell clicked (1/3 MEASURE).
            roll.setDocument(doc,0);roll.setSnapDivisor(3);
            mouse(roll,MouseEvent.MOUSE_CLICKED,200,1035,0,2);
            check(lute.notes.size()==2,"Double-click add");
            EditableNote inserted=lute.notes.getLast();
            check(inserted.start()==1280&&inserted.end()==2560,"1/3 of 4/4 measure is 1280 ticks");
            roll.setSnapDivisor(6);
            mouse(roll,MouseEvent.MOUSE_CLICKED,125,1004,0,2);
            check(lute.notes.getLast().start()==640,"1/6 of 4/4 measure is 640 ticks");
            roll.setSnapDivisor(12);
            mouse(roll,MouseEvent.MOUSE_CLICKED,95,972,0,2);
            check(lute.notes.getLast().start()==320,"1/12 of 4/4 measure is 320 ticks");
            roll.setSnapDivisor(1);
            mouse(roll,MouseEvent.MOUSE_CLICKED,450,935,0,2);
            check(lute.notes.getLast().start()==3840 && lute.notes.getLast().end()==7680,
                "1/1 measure should add a full bar");
            roll.setSnapDivisor(2);
            mouse(roll,MouseEvent.MOUSE_CLICKED,240,900,0,2);
            check(lute.notes.getLast().start()==1920 && lute.notes.getLast().end()==3840,
                "1/2 measure should add half a bar");
            int afterHalf=lute.notes.size();
            // Right-click on the just-added note must remove only that note.
            roll.dispatchEvent(new MouseEvent(roll,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),
                InputEvent.BUTTON3_DOWN_MASK,240,900,1,true,MouseEvent.BUTTON3));
            check(lute.notes.size()==afterHalf-1,"Right click should remove hovered note");
            doc.undo();
            check(lute.notes.size()==afterHalf,"Right-click deletion should be undoable");
            check(roll.createStickyKeyboard().getPreferredSize().width==64,
                "Sticky piano key strip must remain a fixed width");
            // Creating a note should trigger the edit callback BEFORE audition.
            StringBuilder events=new StringBuilder();
            roll.setOnEdit(()->events.append('E'));
            roll.setOnAudition((n,l)->events.append('A'));
            mouse(roll,MouseEvent.MOUSE_CLICKED,530,1070,0,2);
            check("EA".equals(events.toString()),"New note audition should follow edit notification");
            lute.notes.add(EditableNote.create(55,90,0,19200,20160));
            Dimension before=roll.getPreferredSize();
            roll.dispatchEvent(new MouseWheelEvent(roll,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),
                    InputEvent.CTRL_DOWN_MASK,400,900,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-1));
            Dimension after=roll.getPreferredSize();
            check(after.width>before.width&&after.height>before.height,"Ctrl-wheel should zoom both axes");
            java.util.concurrent.atomic.AtomicInteger seeks=new java.util.concurrent.atomic.AtomicInteger();
            roll.setOnSeek(seeks::incrementAndGet);
            mouse(roll,MouseEvent.MOUSE_PRESSED,200,12,0,1);
            mouse(roll,MouseEvent.MOUSE_DRAGGED,400,12,0,0);
            mouse(roll,MouseEvent.MOUSE_RELEASED,400,12,0,1);
            check(roll.getPlayheadTick()>0 && seeks.get()>=2,"Timeline playhead drag");
            Sequence seq=EditorPlayback.sequenceFor(doc,-1);
            check(seq.getTracks().length==3,"ABC all-part sequence tracks");
            check(hasProgram(seq.getTracks()[1],0,24) && hasProgram(seq.getTracks()[2],1,46),"Instrument program changes");
        }catch(Exception e){throw new RuntimeException(e);}
    }
    private static boolean hasProgram(Track track,int channel,int patch) {
        for(int i=0;i<track.size();i++){
            MidiMessage m=track.get(i).getMessage();
            if(m instanceof ShortMessage s&&s.getCommand()==ShortMessage.PROGRAM_CHANGE&&
                    s.getChannel()==channel&&s.getData1()==patch)return true;
        }
        return false;
    }
}
