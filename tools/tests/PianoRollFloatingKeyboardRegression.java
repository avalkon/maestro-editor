package com.digero.maestro.noteedit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.lang.reflect.*;

/** Real viewport layout, overlay hit-testing, full-width ruler and bar-one access. */
public class PianoRollFloatingKeyboardRegression {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    static JFrame frame;
    static NoteEditorApp.EditorTab editor;
    static JScrollPane scroll;
    static PianoRollPanel roll;
    static JComponent keys,ruler;
    static int seeks;
    static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
    static void settle()throws Exception{for(int i=0;i<4;i++)edt(()->frame.validate());}
    static void mouse(Component c,int id,int x,int y,int clicks,int button){c.dispatchEvent(new MouseEvent(c,id,System.currentTimeMillis(),0,x,y,clicks,false,button));}
    static void seekPixel(int x){mouse(ruler,MouseEvent.MOUSE_PRESSED,x,8,1,MouseEvent.BUTTON1);}
    static void verifyHome(){
        check(scroll.getViewport().getViewPosition().x==0,"home scroll");
        check(keys.getX()==0 && keys.getWidth()==roll.getKeyboardWidth(),"overlay at left edge");
        roll.setPlayheadTick(0);check(roll.playheadPixel()==keys.getWidth(),"bar one exactly beside keys");
        seekPixel(2);check(roll.getPlayheadTick()==0,"left blank ruler seeks to song start");
        seekPixel(keys.getWidth()+85);check(roll.getPlayheadTick()==960,"first bar beat seek aligns with grid");
    }
    public static void main(String[] args)throws Exception{
        Path f=Files.createTempFile("floating-piano-",".abc");
        Files.writeString(f,"X:1\nT:Drum\n%%made-for Basic Drum\nM:4/4\nL:1/8\nK:C\n"+"C2 D2 E2 F2|".repeat(32)+"\n\nX:2\nT:Horn\n%%made-for Basic Horn\nM:4/4\nL:1/8\nK:C\n"+"C2 D2 E2 F2|".repeat(32)+"\n");
        try{
            edt(()->{
                frame=new JFrame();editor=new NoteEditorApp.EditorTab(NoteDocument.Kind.ABC,frame,null);frame.setContentPane(editor);frame.setSize(1100,720);frame.setVisible(true);editor.load(f.toFile());
                scroll=PianoRollGutterRegression.find(editor,JScrollPane.class,null);roll=(PianoRollPanel)scroll.getViewport().getView();keys=(JComponent)roll.getComponent(0);ruler=(JComponent)scroll.getColumnHeader().getView();roll.setOnSeek(()->seeks++);
            });settle();
            edt(()->{
                check(scroll.getRowHeader()==null,"no reserved second gutter");
                check(scroll.getColumnHeader().getBounds().x==scroll.getViewport().getBounds().x,"ruler spans keyboard area");verifyHome();
                int w=keys.getWidth();int left=w+340;scroll.getViewport().setViewPosition(new Point(left,0));
                check(keys.getX()==left,"keyboard follows scroll");check(scroll.getColumnHeader().getViewPosition().x==left,"ruler follows timeline");
                check(SwingUtilities.getDeepestComponentAt(roll,left+5,60)==keys,"keyboard intercepts mouse input");
                seekPixel(left+5);check(roll.getPlayheadTick()==3840,"seek above keyboard to second bar");
                seekPixel(left+85);check(roll.getPlayheadTick()==4800,"seek within floating keyboard area");
                mouse(ruler,MouseEvent.MOUSE_DRAGGED,left+170,8,1,MouseEvent.NOBUTTON);check(roll.getPlayheadTick()==5760,"ruler drag over/through overlay");
                int before=roll.selectionSize();mouse(keys,MouseEvent.MOUSE_CLICKED,5,60,2,MouseEvent.BUTTON1);check(roll.selectionSize()==before,"keyboard double click does not add a note");
                check(!roll.deleteAt(new Point(left+4,60)),"hidden grid cannot be deleted through overlay");
                editor.selectPartNumber(2);check(scroll.getViewport().getViewPosition().x==left && keys.getX()==left && keys.getWidth()==w,"part switch retains overlay and scroll");
                scroll.getHorizontalScrollBar().setValue(0);verifyHome();
                scroll.getViewport().setViewPosition(new Point(left,0));
                roll.getActionMap().get("begin").actionPerformed(new ActionEvent(roll,0,"begin"));
                check(scroll.getViewport().getViewPosition().x==0 && roll.getPlayheadTick()==0,"Home reveals start");
                verifyHome();
            });
            // User zoom from the home edge must not conceal bar one under the keyboard.
            edt(()->keys.dispatchEvent(new MouseWheelEvent(keys,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),InputEvent.CTRL_DOWN_MASK,5,60,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-1)));
            settle();edt(()->{
                check(scroll.getViewport().getViewPosition().x==0,"zoom over keyboard keeps home accessible");
                roll.setPlayheadTick(0);check(roll.playheadPixel()==keys.getWidth(),"bar one accessible after zoom");
                check(seeks>=7,"seek callback fired for ruler interactions");
                // Test hit and creation at the first cell, not only its seek mapping.
                try{
                    var field=NoteEditorApp.EditorTab.class.getDeclaredField("doc");field.setAccessible(true);
                    NoteDocument doc=(NoteDocument)field.get(editor);
                    var note=doc.layer(1).notes.getFirst();
                    var ym=PianoRollPanel.class.getDeclaredMethod("y",int.class);ym.setAccessible(true);
                    int x=keys.getWidth()+1,y=(Integer)ym.invoke(roll,note.pitch())+4;
                    check(roll.getToolTipText(new MouseEvent(roll,MouseEvent.MOUSE_MOVED,0,0,x,y,0,false)).contains("Pitch "),"first note is hittable");
                    int count=doc.layer(1).notes.size();int otherY=(Integer)ym.invoke(roll,note.pitch()+1)+4;
                    mouse(roll,MouseEvent.MOUSE_CLICKED,x,otherY,2,MouseEvent.BUTTON1);
                    check(doc.layer(1).notes.size()==count+1 && doc.layer(1).notes.stream().anyMatch(n->n.pitch()==note.pitch()+1 && n.start()==0),"first cell accepts new note");
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            });
            System.out.println("PASS: no duplicate gap; full-width ruler; seek and drag above floating keys; pinned keyboard, mouse safety, first bar accessible, part switch and zoom");
        }finally{if(frame!=null)edt(()->{editor.suspendEmbeddedView();frame.dispose();});Files.deleteIfExists(f);}
        System.exit(0);
    }
}
