package com.digero.maestro.view;

import com.digero.common.util.AppInfo;
import com.digero.common.util.Themer;
import com.digero.common.view.ColorTable;
import com.digero.maestro.MaestroMain;
import com.digero.maestro.abc.AbcPart;
import com.digero.maestro.abc.AbcSong;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;

/** Exercises the real ProjectFrame preview worker and existing track rows.
 * Run with a display and an available MIDI receiver. */
public final class ImportedTrackAppearanceRefreshRegression {
    private static ProjectFrame frame;
    private static AbcSong song;
    private static ArrangementView view;
    private static SongPartsListPanel parts;
    static Object frameField(String name) throws Exception {
        Field f=ProjectFrame.class.getDeclaredField(name); f.setAccessible(true); return f.get(frame);
    }
    static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    interface Task {void run() throws Exception;}
    static void edt(Task task) throws Exception {
        SwingUtilities.invokeAndWait(()->{try{task.run();}catch(Exception e){throw new RuntimeException(e);}});
    }
    static void awaitPreview() throws Exception {
        for(int i=0;i<600;i++) {
            boolean[] ready={false};
            edt(()->ready[0]=Objects.equals(frameField("previewRequestSeq"),frameField("previewAppliedSeq")));
            if(ready[0]) {edt(()->{});return;}
            Thread.sleep(50);
        }
        throw new AssertionError("Preview rebuild timed out");
    }
    static int originalTrack(AbcPart p) {
        for(int n=0;n<p.getTrackCount();n++)if(p.isTrackEnabled(n))return n;
        throw new AssertionError("No original source for "+p.getTitle());
    }
    static void assertColors() throws Exception {
        AbcPart visible=parts.getSelectedPart();
        for(var entry:view.trackPanels.entrySet()) {
            int track=entry.getKey(); boolean selected=visible.isTrackEnabled(track),assigned=false;
            for(AbcPart p:song.getParts())assigned|=p.isTrackEnabled(track);
            ColorTable expected=selected?ColorTable.NOTE_ENABLED:assigned?ColorTable.NOTE_DISABLED:ColorTable.NOTE_OFF;
            ColorTable actual=ImportedTrackSelectionRegression.color(entry.getValue());
            check(actual==expected,"Wrong color: visible part="+visible.getTitle()+", source="+track+", expected="+expected.name()+", actual="+actual.name());
            check(ImportedTrackSelectionRegression.box(entry.getValue()).isSelected()==selected,"Checkbox mismatch: "+track);
        }
    }
    static List<List<Boolean>> assignments() {
        List<List<Boolean>> snapshot=new ArrayList<>();
        for(AbcPart p:song.getParts()) {
            List<Boolean> flags=new ArrayList<>();
            for(int n=0;n<p.getTrackCount();n++)flags.add(p.isTrackEnabled(n));
            snapshot.add(flags);
        }
        return snapshot;
    }
    static void toggleAndCheck(int track) throws Exception {
        List<List<Boolean>> expected=new ArrayList<>();
        edt(()->{
            expected.addAll(assignments());
            int part=parts.getSelectedIndex();
            expected.get(part).set(track,!expected.get(part).get(track));
            ImportedTrackSelectionRegression.box(view.trackPanels.get(track)).doClick(0);
        });
        awaitPreview();edt(()->{
            check(assignments().equals(expected),"Another imported assignment changed");
            assertColors();
        });
    }
    static void rapidToggleAndCheck(int track) throws Exception {
        List<List<Boolean>> expected=new ArrayList<>();
        edt(()->{
            expected.addAll(assignments());
            var checkbox=ImportedTrackSelectionRegression.box(view.trackPanels.get(track));
            checkbox.doClick(0);checkbox.doClick(0);
        });
        awaitPreview();edt(()->{
            check(assignments().equals(expected),"Rapid toggle changed assignments");
            assertColors();
        });
    }
    public static void main(String[] args) {
        int status=1;
        try {
            new MaestroMain(); AppInfo.maestro=true; AppInfo.APP_NAME=MaestroMain.APP_NAME;
            com.digero.common.i18n.LocaleManager.init(true);com.digero.common.i18n.UIText.init();
            Preferences p=Preferences.userNodeForPackage(MaestroMain.class).node("miscSettings");
            p.putBoolean("autoplayOnOpen",false);p.putBoolean("checkForUpdates",false);
            edt(()->{
                Themer.setLookAndFeel(Themer.FLAT_LIGHT_THEME,12);frame=new ProjectFrame();
                frame.openFile(new File(args[0]),false);song=(AbcSong)frameField("abcSong");
                view=(ArrangementView)frameField("arrangementView");parts=(SongPartsListPanel)frameField("songPartsListPanel");
                check(song!=null,"Import failed");
            });
            awaitPreview();edt(ImportedTrackAppearanceRefreshRegression::assertColors);
            for(int i=0;i<song.getParts().size();i++) {
                int index=i;int[] original={-1};
                edt(()->{parts.selectPart(index);original[0]=originalTrack(parts.getSelectedPart());});
                toggleAndCheck(original[0]);toggleAndCheck(original[0]);rapidToggleAndCheck(original[0]);
            }
            System.out.println("PASS original ABC: every imported row color and checkbox after deselect/reselect, without changing displayed part");status=0;
        } catch(Throwable t){t.printStackTrace();}
        System.exit(status);
    }
}
