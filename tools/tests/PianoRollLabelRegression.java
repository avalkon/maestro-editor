import com.digero.maestro.noteedit.*;
import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Verify actual rendered key labels, legibility threshold, and right-aligned dropdown. */
public class PianoRollLabelRegression {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void rowHeight(PianoRollPanel roll,int height){
        try{var f=PianoRollPanel.class.getDeclaredField("rowHeight");f.setAccessible(true);f.setInt(roll,height);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    static int labelPixels(BufferedImage image,int pitch,int height,int scale){
        int count=0,top=24+(127-pitch)*height;
        for(int y=(top+1)*scale;y<(top+height)*scale;y++)
            for(int x=4*scale;x<48*scale;x++){
                int rgb=image.getRGB(x,y);if(((rgb>>16)&255)>145&&((rgb>>8)&255)>145&&(rgb&255)>145)count++;
            }
        return count;
    }
    static int verifyLabels(int scale){
        PianoRollPanel roll=new PianoRollPanel();JComponent keyboard=roll.createStickyKeyboard();
        keyboard.setFont(new Font(Font.DIALOG,Font.PLAIN,12));
        int threshold=0;
        for(int height=5;height<=18;height++){
            rowHeight(roll,height);keyboard.setSize(keyboard.getPreferredSize());
            BufferedImage image=new BufferedImage(keyboard.getWidth()*scale,keyboard.getHeight()*scale,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics();g.scale(scale,scale);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            keyboard.paint(g);g.dispose();
            boolean all=true;for(int p=0;p<128;p++)if(labelPixels(image,p,height,scale)<3)all=false;
            if(height==5){check(!all,"smallest zoom should show octave landmarks only");check(labelPixels(image,60,height,scale)>0,"octave landmark missing");}
            if(all&&threshold==0)threshold=height;
            if(threshold>0){
                check(all,"labels disappeared at height "+height);
                // Row separators must not contain label ink.
                for(int p=0;p<128;p++)for(int x=4*scale;x<48*scale;x++){
                    int rgb=image.getRGB(x,(24+(127-p)*height)*scale);
                    check(((rgb>>16)&255)<145,"label crossed row boundary at height "+height+", pitch "+p);
                }
            }
        }
        check(threshold>=7&&threshold<=10,"all labels should appear at 7–10 logical pixels; got "+threshold);
        return threshold;
    }
    static void verifyToolbar(){
        JFrame frame=new JFrame();
        try{
            for(NoteDocument.Kind kind:NoteDocument.Kind.values()){
                var editor=new NoteEditorApp.EditorTab(kind,frame,null);editor.useEmbeddedMode();
                frame.setContentPane(editor);
                var f=NoteEditorApp.EditorTab.class.getDeclaredField("highlightKey");f.setAccessible(true);JComboBox<?> combo=(JComboBox<?>)f.get(editor);
                for(int width:new int[]{1000,1600}){
                    frame.setSize(width,720);frame.setVisible(true);frame.validate();
                    Container toolbar=combo.getParent();
                    check(combo.getX()+combo.getWidth()>=toolbar.getWidth()-12,"key selector not at right edge: "+combo.getBounds()+" / "+toolbar.getWidth());
                    check(combo.getWidth()>=65,"key selector clipped");
                    check(toolbar.getComponent(0) instanceof JLabel&&((JLabel)toolbar.getComponent(0)).getText().equals("Snap: "),"Snap should remain first");
                }
                editor.setHighlightKey("G minor");check("G minor".equals(combo.getSelectedItem()),"selector no longer synchronizes");
                editor.suspendEmbeddedView();
            }
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}finally{frame.dispose();}
    }
    public static void main(String[]args)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            FlatLightLaf.setup();
            int normal=verifyLabels(1),hidpi=verifyLabels(2);
            check(normal==hidpi,"label threshold should use logical pixels");
            verifyToolbar();
            System.out.println("PASS: all 128 labels visible at "+normal+"px rows (1x) / "+hidpi+"px (2x); no row overlap; both toolbar layouts right aligned");
        });System.exit(0);
    }
}
