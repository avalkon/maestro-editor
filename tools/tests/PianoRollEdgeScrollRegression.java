package com.digero.maestro.noteedit;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.sound.midi.Sequence;
import java.util.UUID;

/** Uses real Swing timers and a visible viewport; no synthetic repeated drag events. */
public final class PianoRollEdgeScrollRegression {
    static JFrame frame;
    static JScrollPane scroll;
    static PianoRollPanel roll;
    static JComponent ruler;
    static NoteDocument doc;
    static UUID first, second;
    static int edits,seeks;
    static int x(long t){return roll.getKeyboardWidth()+(int)Math.round(t*85.0/480);}
    static int y(int p){return 24+(127-p)*18+6;}
    static void check(boolean yes,String msg){if(!yes)throw new AssertionError(msg);}
    static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
    static Point pos(){return scroll.getViewport().getViewPosition();}
    static int w(){return scroll.getViewport().getWidth();}
    static int h(){return scroll.getViewport().getHeight();}
    static void event(Component c,int type,int vx,int vy){
        JViewport v=c==ruler?scroll.getColumnHeader():scroll.getViewport();
        Point p=SwingUtilities.convertPoint(v,vx,vy,c);
        int button=type==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1;
        int mods=type==MouseEvent.MOUSE_RELEASED?0:InputEvent.BUTTON1_DOWN_MASK;
        c.dispatchEvent(new MouseEvent(c,type,System.currentTimeMillis(),mods,p.x,p.y,1,false,button));
    }
    static void press(Component c,int vx,int vy){event(c,MouseEvent.MOUSE_PRESSED,vx,vy);}
    static void drag(Component c,int vx,int vy){event(c,MouseEvent.MOUSE_DRAGGED,vx,vy);}
    static void release(Component c,int vx,int vy){event(c,MouseEvent.MOUSE_RELEASED,vx,vy);}
    static EditableNote note(UUID id){return doc.layer(0).notes.stream().filter(n->n.id().equals(id)).findFirst().orElseThrow();}
    static void setup(NoteDocument.Kind kind){
        doc=new NoteDocument(kind,480,Sequence.PPQ,null);
        var layer=new NoteDocument.Layer("Test");doc.layers.add(layer);
        EditableNote a=EditableNote.create(60,90,0,480,960),b=EditableNote.create(45,90,0,4800,5280);
        first=a.id();second=b.id();layer.notes.add(a);layer.notes.add(b);layer.notes.add(EditableNote.create(70,90,0,36000,36480));
        roll=new PianoRollPanel();roll.setDocument(doc,0);roll.setOnEdit(()->edits++);roll.setOnSeek(()->seeks++);
        scroll=new JScrollPane(roll);ruler=roll.createStickyRuler();scroll.setColumnHeaderView(ruler);roll.installFloatingKeyboard(scroll.getViewport());
        frame=new JFrame();frame.setContentPane(scroll);frame.setSize(680,500);frame.setVisible(true);frame.validate();
    }
    static void waitTicks()throws Exception{Thread.sleep(480);edt(()->{});}
    static void waitFor(java.util.function.BooleanSupplier predicate,String message)throws Exception{
        long until=System.nanoTime()+3_000_000_000L;
        boolean[] ok={false};
        do{edt(()->ok[0]=predicate.getAsBoolean());if(ok[0])return;Thread.sleep(30);}while(System.nanoTime()<until);
        throw new AssertionError(message);
    }
    static void run(NoteDocument.Kind kind)throws Exception{
        edt(()->setup(kind));edt(()->{});
        try{
            // Start near the first note and sweep diagonally into a second note off screen.
            edt(()->{roll.clearSelection();scroll.getViewport().setViewPosition(new Point(0,y(65)-20));
                press(roll,x(0)+2,10);drag(roll,w()-1,h()-1);});
            waitFor(()->pos().x>400&&pos().y>y(65)-20+180,"diagonal stationary marquee autoscroll");edt(()->{
                release(roll,w()-1,h()-1);check(roll.selectionSize()==2,"marquee preserves original anchor after scrolling: "+roll.selectionSize());
                check(!doc.canUndo(),"selection not an edit");});
            final Point[] stop={null};edt(()->stop[0]=pos());waitTicks();edt(()->check(pos().equals(stop[0]),"release stops timer"));
            // Moving a note (not its right edge) scrolls both axes and commits once.
            edt(()->{roll.clearSelection();scroll.getViewport().setViewPosition(new Point(0,y(60)-100));
                press(roll,x(480)+12,100);drag(roll,w()-1,h()-1);});
            waitFor(()->pos().x>400&&pos().y>y(60)-100+180,"move scroll axes");edt(()->{
                check(note(first).start()==480,"preview does not modify document before release");
                int previous=edits;release(roll,w()-1,h()-1);
                check(note(first).start()>4800&&note(first).pitch()<60,"move commits time and pitch");check(edits==previous+1,"one edit");
                doc.undo();check(note(first).start()==480&&note(first).pitch()==60,"undo move");});
            // Extending a note at the right edge should not scroll vertically.
            edt(()->{roll.clearSelection();scroll.getViewport().setViewPosition(new Point(0,y(60)-100));
                press(roll,x(960)-2,100);drag(roll,w()-1,h()-1);});
            waitFor(()->pos().x>400,"resize scrolls right");edt(()->{
                check(pos().y==y(60)-100,"resize horizontal only");
                release(roll,w()-1,h()-1);check(note(first).end()>4800&&note(first).start()==480&&note(first).pitch()==60,"resize end only");
                doc.undo();check(note(first).end()==960,"undo resize");});
            // Ruler seek at either edge, including above floating keyboard.
            edt(()->{scroll.getViewport().setViewPosition(new Point(0,400));press(ruler,200,8);drag(ruler,w()-1,8);});
            final long[] initial={0};edt(()->initial[0]=roll.getPlayheadTick());waitFor(()->pos().x>200,"ruler scrolls right");edt(()->{
                check(pos().x>200&&pos().y==400&&roll.getPlayheadTick()>initial[0],"right ruler drag seeks over time");
                drag(ruler,0,8);});
            waitFor(()->pos().x==0&&roll.getPlayheadTick()==0,"left ruler reaches song beginning");edt(()->{
                release(ruler,0,8);
                check(!roll.isDragInteractionActive(),"ruler release clears gesture");});
            // Left grid edge is beside the floating keyboard, and top scroll works.
            edt(()->{scroll.getViewport().setViewPosition(new Point(500,400));press(roll,200,100);drag(roll,roll.getKeyboardWidth()+1,1);});
            waitFor(()->pos().x<300&&pos().y<200,"left and up from grid edge");edt(()->{
                roll.clearSelection();stop[0]=pos();});
            waitTicks();edt(()->check(pos().equals(stop[0])&&!roll.isDragInteractionActive(),"Escape cancellation"));
            // Neutral zone stops scrolling without releasing; hiding cancels gesture.
            edt(()->{press(roll,200,100);drag(roll,w()-1,100);});waitTicks();
            edt(()->{drag(roll,w()/2,100);stop[0]=pos();});waitTicks();
            edt(()->{check(pos().equals(stop[0]),"neutral zone stops scrolling");drag(roll,w()-1,100);frame.setVisible(false);stop[0]=pos();});waitTicks();
            edt(()->check(pos().equals(stop[0])&&!roll.isDragInteractionActive(),"hide stops drag"));
            System.out.println("PASS "+kind+": stationary marquee, diagonal move, horizontal resize, bidirectional ruler, floating keyboard edge/up, undo, release, Escape, neutral zone and hide");
        }finally{edt(()->frame.dispose());}
    }
    public static void main(String[] args)throws Exception{for(var kind:NoteDocument.Kind.values())run(kind);System.exit(0);}
}
