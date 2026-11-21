package com.digero.maestro.noteedit;

import com.digero.maestro.abc.LotroDrumInfo;
import com.digero.common.midi.MidiDrum;
import javax.swing.*;
import javax.sound.midi.Sequence;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.List;

/** Exercise actual keyboard/note-block rendering, registry names, mixed views and tooltips. */
public class PianoRollDrumNameRegression {
    static final Font FONT=PianoRollPitchNameRegression.FONT;
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static String tip(JComponent c,int x,int p,int h){return c.getToolTipText(new MouseEvent(c,MouseEvent.MOUSE_MOVED,0,0,x,24+(127-p)*h+5,0,false));}
    static BufferedImage keyboardImage(JComponent keyboard,int scale){
        keyboard.setFont(FONT);keyboard.setSize(keyboard.getPreferredSize());
        BufferedImage image=new BufferedImage(keyboard.getWidth()*scale,keyboard.getHeight()*scale,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();g.scale(scale,scale);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        keyboard.paint(g);g.dispose();return image;
    }
    static void verifyKey(BufferedImage actual,int pitch,int height,int scale,String name){
        BufferedImage expected=new BufferedImage(actual.getWidth(),height*scale,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=expected.createGraphics();g.scale(scale,scale);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        if(name!=null){
            GlyphVector glyph=null;Rectangle ink=null;
            for(int size=12;size>=9;size--){
                GlyphVector candidate=FONT.deriveFont((float)size).createGlyphVector(g.getFontRenderContext(),name);
                Rectangle b=candidate.getVisualBounds().getBounds();
                if(b.width<=actual.getWidth()/scale-10 && b.height<=height-1){glyph=candidate;ink=b;break;}
            }
            check(glyph!=null,"expected keyboard label to fit: "+name);
            g.setColor(new Color(222,228,238));g.drawGlyphVector(glyph,5-ink.x,1+(height-1-ink.height)/2-ink.y);
        }
        g.dispose();int color=new Color(222,228,238).getRGB(),count=0,top=(24+(127-pitch)*height)*scale;
        for(int y=0;y<height*scale;y++)for(int x=0;x<actual.getWidth();x++){
            boolean shown=actual.getRGB(x,top+y)==color;
            check(shown==(expected.getRGB(x,y)==color),"keyboard rendering mismatch "+pitch+" "+name+" at "+x+","+y);
            if(shown)count++;
        }
        check(name==null?count==0:count>0,"keyboard label visibility "+name);
    }
    static void run()throws Exception{
        NoteDocument doc=AbcDocumentIO.readText("X:1\nT:Percussion\n%%made-for Basic Drum\nM:4/4\nL:1/8\nK:C\nz8|\n\nX:2\nT:Horn\n%%made-for Basic Horn\nM:4/4\nL:1/8\nK:C\nz8|\n");
        var drum=doc.layer(0);var horn=doc.layer(1);
        check(DrumLabels.isBasicDrum(drum.instrumentName)&&!DrumLabels.isBasicDrum(horn.instrumentName),"instrument metadata");
        for(int p=36;p<=72;p++)drum.notes.add(EditableNote.create(p,90,0,doc.ppq,3L*doc.ppq));
        horn.notes.add(EditableNote.create(60,90,0,0,doc.ppq));
        PianoRollPanel roll=new PianoRollPanel();roll.setFont(FONT);JComponent keyboard=roll.createStickyKeyboard();
        roll.setDocument(doc,0);roll.setHighlightKey("G minor");roll.setShowAllParts(true);
        var dimensions=roll.getPreferredSize();int headerWidth=keyboard.getPreferredSize().width;
        check(headerWidth>64,"wider percussion gutter");
        for(int scale:new int[]{1,2}){
            PianoRollPitchNameRegression.setHeight(roll,18);
            var image=PianoRollPitchNameRegression.paint(roll,scale);var keys=keyboardImage(keyboard,scale);
            for(int p=36;p<=72;p++){
                String name=LotroDrumInfo.getById(p).nameMinimal;
                PianoRollPitchNameRegression.verify(image,p,18,scale,headerWidth+85,170,name);
                verifyKey(keys,p,18,scale,name);
            }
            verifyKey(keys,35,18,scale,null);verifyKey(keys,73,18,scale,null);
            PianoRollPitchNameRegression.verify(image,60,18,scale,headerWidth,85,"C4");
            PianoRollPitchNameRegression.setHeight(roll,11);keys=keyboardImage(keyboard,scale);
            // Every Basic Drum name fits at the earlier zoom level on the test font.
            for(int p=36;p<=72;p++)verifyKey(keys,p,11,scale,LotroDrumInfo.getById(p).nameMinimal);
        }
        PianoRollPitchNameRegression.setHeight(roll,18);
        check(tip(keyboard,10,36,18).contains(LotroDrumInfo.getById(36).nameMinimal),"drum key tooltip");
        check(tip(roll,headerWidth+91,36,18).contains(LotroDrumInfo.getById(36).nameMinimal),"drum block tooltip");
        roll.setDocument(doc,1);check(keyboard.getPreferredSize().width==headerWidth,"gutter stable during track switching");
        check(roll.getPreferredSize().equals(dimensions),"zoom/timeline unchanged");
        check(tip(keyboard,10,36,18)==null,"melodic keyboard mode restored");
        var mixed=PianoRollPitchNameRegression.paint(roll,1);
        PianoRollPitchNameRegression.verify(mixed,36,18,1,headerWidth+85,170,LotroDrumInfo.getById(36).nameMinimal);
        PianoRollPitchNameRegression.verify(mixed,60,18,1,headerWidth,85,"C4");
        check(roll.getHighlightKey().equals("G minor")&&!doc.isDirty()&&!doc.canUndo(),"view-only changes");
        roll.setShowAllParts(false);check(!tip(roll,headerWidth+91,36,18).contains("Conga"),"hidden drum note");
        roll.setDocument(doc,0);roll.setShowAllParts(true);roll.setShowOnlyAudible(true);roll.setPartMuteSoloStates(java.util.Map.of(1,new boolean[]{true,false},2,new boolean[]{false,false}));
        check(!tip(roll,headerWidth+91,36,18).contains("Conga"),"muted drums hidden");
        roll.setShowAllParts(false);check(tip(roll,headerWidth+91,36,18).contains("Conga"),"muted single drum part remains editable");
        NoteDocument midi=new NoteDocument(NoteDocument.Kind.MIDI,480,Sequence.PPQ,null);
        var ml=new NoteDocument.Layer("Percussion");var mixedLayer=new NoteDocument.Layer("Mixed channel track");midi.layers.addAll(List.of(ml,mixedLayer));
        ml.notes.add(EditableNote.create(36,90,9,480,1440));
        mixedLayer.notes.add(EditableNote.create(38,90,9,480,1440));
        mixedLayer.notes.add(EditableNote.create(60,90,0,0,480));
        roll.setDocument(midi,0);roll.setShowAllParts(true);
        check(tip(keyboard,10,36,18).contains(MidiDrum.fromId(36).name),"MIDI drum keyboard");
        var midiImage=PianoRollPitchNameRegression.paint(roll,1);
        PianoRollPitchNameRegression.verify(midiImage,36,18,1,headerWidth+85,170,MidiDrum.fromId(36).name);
        PianoRollPitchNameRegression.verify(midiImage,38,18,1,headerWidth+85,170,MidiDrum.fromId(38).name);
        roll.setDocument(midi,1);check(tip(keyboard,10,36,18)==null,"mixed MIDI track retains pitch keyboard");
        check(tip(roll,headerWidth+91,38,18).contains(MidiDrum.fromId(38).name),"mixed MIDI track drum note name");
        System.out.println("PASS: all 37 LOTRO drum names, 1x/2x keyboard and note rendering, 11px keyboard rows, mixed instrument views, stable gutter/zoom, audible filter, raw MIDI channel 10, tooltips, unchanged data");
    }
    public static void main(String[]args)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{run();}catch(Exception e){throw new AssertionError(e);}});System.exit(0);
    }
}
