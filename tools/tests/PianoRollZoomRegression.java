import com.digero.maestro.noteedit.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import javax.sound.midi.*;
import javax.swing.*;

/** Swing integration regression; run on a desktop or with xvfb-run. */
public class PianoRollZoomRegression {
    static JFrame frame;
    static JPanel cards;
    static NoteEditorApp.EditorTab abc,midi;
    static PianoRollPanel roll(NoteEditorApp.EditorTab editor) { return field(editor,"roll",PianoRollPanel.class); }
    static JScrollPane scroll(NoteEditorApp.EditorTab editor) { return field(editor,"scroller",JScrollPane.class); }
    static <T> T field(Object o,String name,Class<T> type) {
        try { var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o)); }
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    static void settle()throws Exception{for(int i=0;i<4;i++)edt(()->frame.validate());}
    record View(Dimension size,Point pos){}
    static View view(NoteEditorApp.EditorTab editor){return new View(roll(editor).getPreferredSize(),scroll(editor).getViewport().getViewPosition());}
    static void checkView(NoteEditorApp.EditorTab editor,View expected,String label){
        View actual=view(editor);check(actual.equals(expected),label+": expected "+expected+" but got "+actual);
    }
    static void show(String name){((CardLayout)cards.getLayout()).show(cards,name);frame.validate();}
    static void zoom(NoteEditorApp.EditorTab editor){
        PianoRollPanel r=roll(editor);Point p=scroll(editor).getViewport().getViewPosition();
        r.dispatchEvent(new MouseWheelEvent(r,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),InputEvent.CTRL_DOWN_MASK,p.x+250,p.y+120,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-1));
    }
    static void selectMidi(int i){field(midi,"layers",JComboBox.class).setSelectedIndex(i);}
    static void notes(Track t,long start,long end)throws Exception{
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,0,60,90),start));
        t.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,0,60,0),end));
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("maestro-zoom-");
        try {
            String abcText="%abc-2.1\nX:1\nT:Long\nM:4/4\nL:1/8\nK:C\n"+"C2 D2 E2 F2|".repeat(32)+"\n\nX:2\nT:Short\nM:4/4\nL:1/8\nK:C\nC2 D2 E2 F2|\n";
            String abcInput = args.length>0 && args[0].equals("drums") ? abcText.replace("T:Long\n", "T:Long\n%%made-for Basic Horn\n").replace("T:Short\n", "T:Short\n%%made-for Basic Drum\n") : abcText;
            Path abcFile=dir.resolve("test.abc"),midiFile=dir.resolve("test.mid");
            Files.writeString(abcFile,abcInput);
            Sequence sequence=new Sequence(Sequence.PPQ,480);notes(sequence.createTrack(),0,480*128);notes(sequence.createTrack(),0,480);
            MidiSystem.write(sequence,1,midiFile.toFile());
            edt(()->{
                frame=new JFrame();cards=new JPanel(new CardLayout());
                abc=new NoteEditorApp.EditorTab(NoteDocument.Kind.ABC,frame,null);
                midi=new NoteEditorApp.EditorTab(NoteDocument.Kind.MIDI,frame,null);
                cards.add(abc,"abc");cards.add(midi,"midi");cards.add(new JPanel(),"arrangement");
                frame.setContentPane(cards);frame.setSize(1150,750);show("arrangement");frame.setVisible(true);
                abc.load(abcFile.toFile());midi.load(midiFile.toFile());
            });
            settle();edt(()->show("abc"));settle();
            edt(()->{check(view(abc).size.height<1000,"initial fit on first visible layout");abc.setHighlightKey("D minor");});
            for(int i=0;i<5;i++){edt(()->zoom(abc));settle();}
            View[] expected=new View[2];
            edt(()->{scroll(abc).getViewport().setViewPosition(new Point(2200,330));expected[0]=view(abc);check(expected[0].pos.x==2200&&expected[0].pos.y==330,"ABC test must be scrolled on both axes");abc.selectPartNumber(2);});
            settle();edt(()->{check(abc.currentPartNumber()==2,"ABC selection");checkView(abc,expected[0],"short ABC part");abc.selectPartNumber(1);});settle();
            edt(()->{checkView(abc,expected[0],"return to ABC part");check(roll(abc).getHighlightKey().equals("D minor"),"manual key retained");
                try{abc.reloadGeneratedAbc(abcInput.replace("T:Short","T:Renamed"),java.util.Map.of());}catch(Exception e){throw new AssertionError(e);}
            });settle();edt(()->{checkView(abc,expected[0],"generated ABC refresh");show("arrangement");});settle();
            edt(()->{show("abc");abc.fitView();});settle();edt(()->{checkView(abc,expected[0],"reenter piano roll");show("midi");midi.fitView();});settle();
            edt(()->check(view(midi).size.height<1000,"hidden MIDI tab initial fit"));
            for(int i=0;i<4;i++){edt(()->zoom(midi));settle();}
            edt(()->{scroll(midi).getViewport().setViewPosition(new Point(1800,270));expected[1]=view(midi);check(expected[1].pos.x==1800&&expected[1].pos.y==270,"MIDI test must be scrolled on both axes");selectMidi(1);});settle();
            edt(()->{checkView(midi,expected[1],"short MIDI track");selectMidi(0);});settle();
            edt(()->{checkView(midi,expected[1],"return to MIDI track");show("abc");abc.fitView();});settle();edt(()->checkView(abc,expected[0],"independent cached ABC view"));
            System.out.println("PASS: initial visible fit; ABC and MIDI track switches preserve zoom and scroll; generated ABC refresh; cached view reentry; independent views; manual key retained");
        }finally{if(frame!=null)edt(()->{abc.suspendEmbeddedView();midi.suspendEmbeddedView();frame.dispose();});try(var files=Files.list(dir)){for(Path p:files.toList())Files.deleteIfExists(p);}Files.deleteIfExists(dir);}
        System.exit(0);
    }
}
