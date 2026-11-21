package com.digero.maestro.noteedit;

import com.digero.maestro.abc.LotroDrumInfo;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** The actual scroll-pane corner and toolbar separator must align after layout. */
public class PianoRollGutterRegression {
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static JFrame frame;
    static NoteEditorApp.EditorTab editor;
    static <T extends Component> T find(Container parent, Class<T> type, String name){
        for(Component c:parent.getComponents()){
            if(type.isInstance(c)&&(name==null||name.equals(c.getName())))return type.cast(c);
            if(c instanceof Container sub){T match=find(sub,type,name);if(match!=null)return match;}
        }
        return null;
    }
    static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
    static void settle()throws Exception{for(int i=0;i<5;i++)edt(()->frame.validate());}
    static int pixel(Component c,int x,int y){
        BufferedImage image=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();c.paint(g);g.dispose();return image.getRGB(x,y);
    }
    static void verify(boolean drums)throws Exception{
        JScrollPane scroll=find(editor,JScrollPane.class,null);
        var sep=find(editor,JToolBar.Separator.class,"snapVelocitySeparator");
        var roll=(PianoRollPanel)scroll.getViewport().getView();
        var keys=(JComponent)roll.getComponent(0);
        var ruler=scroll.getColumnHeader().getView();
        int separator=SwingUtilities.convertPoint(sep,sep.getWidth()/2,0,editor).x;
        int grid=SwingUtilities.convertPoint(scroll.getViewport(),0,0,editor).x;
        int width=keys.getWidth();
        check(drums?separator==grid+width:width==64,"keyboard width/alignment: separator="+separator+" grid="+grid+" width="+width);
        check(scroll.getRowHeader()==null && scroll.getCorner(JScrollPane.UPPER_LEFT_CORNER)==null,"no duplicate row header or corner");
        check(scroll.getColumnHeader().getX()==scroll.getViewport().getX() && scroll.getColumnHeader().getWidth()==scroll.getViewport().getWidth(),"full-width ruler");
        check(pixel(ruler,2,2)==new Color(32,36,47).getRGB(),"ruler begins at left edge");
        if(drums){
            check(width<152,"percussion gutter shortened at default font");
            PianoRollPitchNameRegression.setHeight(roll,18);
            if(editor.currentPartNumber()==1){
                for(int height:new int[]{11,18})for(int scale:new int[]{1,2}){
                    PianoRollPitchNameRegression.setHeight(roll,height);
                    var image=PianoRollDrumNameRegression.keyboardImage(keys,scale);
                    for(int p=36;p<=72;p++)PianoRollDrumNameRegression.verifyKey(image,p,height,scale,LotroDrumInfo.getById(p).nameMinimal);
                }
                PianoRollPitchNameRegression.setHeight(roll,18);
            }
        }
        System.out.println("PASS: "+UIManager.getLookAndFeel().getName()+" gutter="+width+" separator="+separator+" grid="+grid+" full-width ruler");
    }
    public static void main(String[]args)throws Exception{
        Path drum=Files.createTempFile("gutter-drums-",".abc"), melodic=Files.createTempFile("gutter-melodic-",".abc");
        Files.writeString(drum,"X:1\nT:Drum\n%%made-for Basic Drum\nM:4/4\nL:1/8\nK:C\nC8|\n\nX:2\nT:Horn\n%%made-for Basic Horn\nM:4/4\nL:1/8\nK:C\nC8|\n");
        Files.writeString(melodic,"X:1\nT:Horn\n%%made-for Basic Horn\nM:4/4\nL:1/8\nK:C\nC8|\n");
        try{
            for(LookAndFeel laf:new LookAndFeel[]{new FlatMacLightLaf(),new FlatMacDarkLaf(),new javax.swing.plaf.metal.MetalLookAndFeel()}){
                edt(()->{try{
                    UIManager.setLookAndFeel(laf);
                    frame=new JFrame();editor=new NoteEditorApp.EditorTab(NoteDocument.Kind.ABC,frame,null);
                    frame.setContentPane(editor);frame.setSize(1250,750);frame.setVisible(true);editor.load(drum.toFile());
                }catch(Exception e){throw new AssertionError(e);}});
                settle();edt(()->{try{verify(true);}catch(Exception e){throw new AssertionError(e);}});
                edt(()->{editor.selectPartNumber(2);frame.setSize(1080,700);});settle();
                edt(()->{try{verify(true);}catch(Exception e){throw new AssertionError(e);}});
                edt(()->editor.load(melodic.toFile()));settle();edt(()->{try{verify(false);}catch(Exception e){throw new AssertionError(e);}});
                edt(()->{editor.suspendEmbeddedView();frame.dispose();});
            }
        }finally{if(frame!=null)edt(()->frame.dispose());Files.deleteIfExists(drum);Files.deleteIfExists(melodic);}
        System.exit(0);
    }
}
