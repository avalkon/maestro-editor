package com.digero.maestro.view;

import com.digero.common.util.AppInfo;
import com.digero.common.util.Themer;
import com.digero.maestro.MaestroMain;
import com.digero.maestro.abc.AbcSong;
import com.digero.maestro.noteedit.*;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/** Real Tune Editor Apply twice without allowing the async roll refresh to run. */
public final class AuthoredTuneApplyGuiRegression {
    static ProjectFrame frame;
    static AbcSong song;
    static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static Object frameField(String name)throws Exception{return field(frame,name);}
    static void invoke(String name)throws Exception{Method m=ProjectFrame.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(frame);}
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    interface Task{void run()throws Exception;}
    static void edt(Task task)throws Exception{SwingUtilities.invokeAndWait(()->{try{task.run();}catch(Exception e){throw new RuntimeException(e);}});}
    static void waitForRoll()throws Exception {
        for(int i=0;i<300;i++){
            boolean[] ready={false};edt(()->ready[0]=frameField("abcRollEditor")!=null&&!Boolean.TRUE.equals(frameField("noteEditorOpening")));
            if(ready[0])return;Thread.sleep(50);
        }
        throw new AssertionError("Piano roll did not open");
    }
    static JButton button(Container root,String text){
        for(Component child:root.getComponents()){
            if(child instanceof JButton b&&text.equals(b.getText()))return b;
            if(child instanceof Container c){JButton found=button(c,text);if(found!=null)return found;}
        }return null;
    }
    public static void main(String[]args){
        int status=1;
        try {
            new MaestroMain();AppInfo.maestro=true;AppInfo.APP_NAME=MaestroMain.APP_NAME;
            com.digero.common.i18n.LocaleManager.init(true);com.digero.common.i18n.UIText.init();
            Preferences prefs=Preferences.userNodeForPackage(MaestroMain.class).node("miscSettings");prefs.putBoolean("autoplayOnOpen",false);prefs.putBoolean("checkForUpdates",false);
            edt(()->{Themer.setLookAndFeel(Themer.FLAT_LIGHT_THEME,12);frame=new ProjectFrame();frame.openFile(new File(args[0]),false);song=(AbcSong)frameField("abcSong");check(song!=null,"Import failed");});
            int[] part={0};
            edt(()->{
                part[0]=song.getParts().getFirst().getPartNumber();
                song.setNoteEditorDeltas(Map.of(part[0],new NoteEditorDelta.Part(List.of(),List.of(new NoteEditorDelta.Value(60,90,9600,10560),new NoteEditorDelta.Value(62,100,11520,12480)),true)));
                ((JToggleButton)frameField("pianoRollModeRadioButton")).doClick(0);
            });
            waitForRoll();
            // Let any outstanding initial preview generation settle before opening the dialog.
            for(int i=0;i<300;i++){
                boolean[] ready={false};edt(()->ready[0]=Objects.equals(frameField("previewAppliedSeq"),frameField("previewRequestSeq")));
                if(ready[0])break;Thread.sleep(50);
            }
            edt(()->{
                check(Boolean.TRUE.equals(frameField("rollActive")),"Piano roll not active");
                var before=song.getNoteEditorDeltas().get(part[0]).added();
                TuneEditor.show(frame,song);
                Field dialogField=TuneEditor.class.getDeclaredField("openDialog");dialogField.setAccessible(true);JDialog dialog=(JDialog)dialogField.get(null);
                check(dialog!=null,"Tune dialog not open");
                @SuppressWarnings("unchecked") List<TuneEditorLine> lines=(List<TuneEditorLine>)field(dialog,"tuneInputs");
                TuneEditorLine line=lines.get(0);line.enable[0].setSelected(true);line.enable[1].setSelected(true);line.barA[0].setText("1");line.barB[0].setText("12");line.tempo.setText("20");line.accelerando.setText("12");
                JButton apply=button(dialog,"APPLY");check(apply!=null,"Apply button missing");
                apply.doClick(0);var warped=song.getNoteEditorDeltas().get(part[0]).added();
                check(warped.size()==before.size()&&!warped.equals(before),"Apply did not change authored timing");
                // Both calls are in the SAME EDT event. Neither the preview worker
                // nor the coalesced roll-baseline timer can intervene.
                apply.doClick(0);check(song.getNoteEditorDeltas().get(part[0]).added().equals(warped),"Immediate second Apply restored stale notes");
                invoke("stageRollEdits");check(song.getNoteEditorDeltas().get(part[0]).added().equals(warped),"Immediate save staging restored stale notes");
                TuneEditor.close();
            });
            System.out.println("PASS actual Tune Editor Apply twice and immediate Save staging with piano roll open");status=0;
        }catch(Throwable e){e.printStackTrace();}System.exit(status);
    }
}
