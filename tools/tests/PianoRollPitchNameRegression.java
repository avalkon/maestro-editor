package com.digero.maestro.noteedit;

import javax.swing.*;
import javax.sound.midi.Sequence;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;

/** Verify actual note-block text, accidentals and octaves, fit and tooltip. */
public class PianoRollPitchNameRegression {
    static final Font FONT=new Font(Font.DIALOG,Font.PLAIN,12);
    static final int INK=new Color(15,26,43).getRGB();
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void setHeight(PianoRollPanel roll,int h)throws Exception{
        Field f=PianoRollPanel.class.getDeclaredField("rowHeight");f.setAccessible(true);f.setInt(roll,h);
    }
    static BufferedImage paint(PianoRollPanel roll,int scale){
        roll.setSize(420,roll.getPreferredSize().height);
        BufferedImage image=new BufferedImage(420*scale,roll.getHeight()*scale,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();g.scale(scale,scale);g.setFont(FONT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        roll.paint(g);g.dispose();return image;
    }
    static void verify(BufferedImage image,int pitch,int height,int scale,int start,int width,String text){
        int yy=24+(127-pitch)*height;
        BufferedImage expected=new BufferedImage(image.getWidth(),height*scale,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=expected.createGraphics();g.scale(scale,scale);g.setFont(FONT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        if(text!=null){
            GlyphVector glyph=null;Rectangle ink=null;
            for(int size=12;size>=9;size--){
                GlyphVector candidate=FONT.deriveFont((float)size).createGlyphVector(g.getFontRenderContext(),text);
                Rectangle bounds=candidate.getVisualBounds().getBounds();
                if(bounds.width<=width-10 && bounds.height<=height-5){glyph=candidate;ink=bounds;break;}
            }
            check(glyph!=null,"test setup: expected label must fit: "+text);
            g.setColor(new Color(INK));g.drawGlyphVector(glyph,start+5-ink.x,3+(height-5-ink.height)/2-ink.y);
        }
        g.dispose();int count=0;
        for(int y=0;y<height*scale;y++)for(int x=(start+3)*scale;x<(start+width-3)*scale;x++){
            boolean actual=image.getRGB(x,yy*scale+y)==INK;
            boolean wanted=expected.getRGB(x,y)==INK;
            check(actual==wanted,"render mismatch pitch="+pitch+" text="+text+" height="+height+" scale="+scale+" at "+x+","+y);
            if(actual)count++;
        }
        check(text==null?count==0:count>0,"expected label visibility: "+text);
    }
    static void run()throws Exception{
        String[] names={"C","C#","D","Eb","E","F","F#","G","Ab","A","Bb","B"};
        for(NoteDocument.Kind kind:NoteDocument.Kind.values()){
            NoteDocument doc=new NoteDocument(kind,480,Sequence.PPQ,null);
            NoteDocument.Layer layer=new NoteDocument.Layer("Pitch labels");doc.layers.add(layer);
            for(int p=0;p<128;p++)layer.notes.add(EditableNote.create(p,90,0,480,960));
            // Second narrow note with too little width for a full label.
            layer.notes.add(EditableNote.create(61,90,0,1440,1500));
            PianoRollPanel roll=new PianoRollPanel();roll.setFont(FONT);roll.setDocument(doc,0);
            for(int scale:new int[]{1,2})for(int height:new int[]{13,18}){
                setHeight(roll,height);BufferedImage image=paint(roll,scale);
                for(int p=0;p<128;p++)verify(image,p,height,scale,149,85,names[p%12]+(p/12-1));
                verify(image,61,height,scale,319,11,null);
            }
            setHeight(roll,8);BufferedImage small=paint(roll,1);verify(small,60,8,1,149,85,null);
            setHeight(roll,18);
            String tip=roll.getToolTipText(new MouseEvent(roll,MouseEvent.MOUSE_MOVED,0,0,160,24+67*18+6,0,false));
            check(tip.contains("Pitch C4 (MIDI 60)"),"tooltip pitch name: "+tip);
            check(!doc.isDirty() && layer.notes.size()==129,"rendering should not modify document");
        }
        System.out.println("PASS: 128 named note pitches in MIDI and ABC, 1x/2x, 13px/18px rows, narrow notes hidden, tiny rows hidden, tooltip and unchanged notes");
    }
    public static void main(String[]args)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{run();}catch(Exception e){throw new AssertionError(e);}});
    }
}
