package com.digero.maestro.noteedit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Verifies that the embedded editor delegates transport instead of starting its own sequencer. */
public final class TransportBridgeSmokeTest {
    private static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path abc=Files.createTempFile("maestro-transport-test-", ".abc");
        try {
            Files.writeString(abc,"X:1\nT:Test\nM:4/4\nL:1/8\nQ:120\nK:C\nC2 D2 E2 F2 |\n");
            SwingUtilities.invokeAndWait(()->{
                NoteEditorApp.EditorTab tab=new NoteEditorApp.EditorTab(NoteDocument.Kind.ABC,null,
                        new NoteEditorApp.ProjectBridge(){
                            @Override public void save(NoteDocument.Kind kind,byte[] data) {}
                        });
                tab.load(abc.toFile());
                tab.useSongPartsSelection();
                AtomicInteger play=new AtomicInteger(),stop=new AtomicInteger(),seeks=new AtomicInteger();
                AtomicLong pos=new AtomicLong(1920);
                tab.setSharedTransport(new NoteEditorApp.TransportBridge(){
                    @Override public void playOrPause(){play.incrementAndGet();}
                    @Override public void stop(){stop.incrementAndGet();}
                    @Override public void seek(long ticks,int ppq){seeks.incrementAndGet();pos.set(ticks);check(ppq==960,"Editor PPQ");}
                    @Override public long position(int ppq){return pos.get();}
                    @Override public boolean isRunning(){return false;}
                });
                tab.playOrPause();check(play.get()==1,"Shared playback not delegated");
                JScrollPane pane=find(tab,JScrollPane.class);
                check(pane!=null && pane.getRowHeader()!=null,"Sticky keyboard missing");
                check(pane.getColumnHeader()!=null,"Sticky ruler missing");
                JComponent ruler=(JComponent)pane.getColumnHeader().getView();
                ruler.dispatchEvent(new MouseEvent(ruler,MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),0,250,8,1,false,MouseEvent.BUTTON1));
                check(seeks.get()==1 && pos.get()>0,"Sticky ruler should scrub shared transport");
                tab.stopPlayback();check(stop.get()==1,"Shared stop not delegated");
                tab.suspendEmbeddedView();
            });
            System.out.println("PASS: embedded Maestro transport bridge, shared seek/stop, sticky axes");
        } finally {Files.deleteIfExists(abc);}
    }
    private static <T extends Component> T find(Container root,Class<T> wanted){
        for(Component c:root.getComponents()){
            if(wanted.isInstance(c))return wanted.cast(c);
            if(c instanceof Container child){T found=find(child,wanted);if(found!=null)return found;}
        }
        return null;
    }
}
