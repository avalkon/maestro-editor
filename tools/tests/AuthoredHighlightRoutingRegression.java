package com.digero.maestro.view;

import com.digero.maestro.abc.*;
import com.digero.maestro.midi.*;
import com.digero.maestro.util.*;
import com.digero.maestro.noteedit.*;
import java.io.*;
import java.util.*;
import java.util.prefs.*;

/** Regression: a source row must light up in any selected part's view when
 * the source is audible in another destination, even if its local checkbox
 * is unchecked. */
public final class AuthoredHighlightRoutingRegression {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void populatePreviewTracks(AbcSong song) throws Exception {
        SequenceInfo info=SequenceInfo.fromAbcParts(song,true,true);
        for (AbcPart part:song.getParts()) part.clearAuthoredPreviewTracks();
        for (var track:info.getLastTrackInfos()) {
            if (track instanceof AuthoredPreviewTrackInfo authored)
                track.part.addAuthoredPreviewTrack(authored);
        }
    }

    static List<Integer> trackNumbersForSource(AbcSong song, int source) {
        List<Integer> result = new ArrayList<>();
        for (AbcPart part : song.getParts())
            for (var track : part.getAuthoredPreviewTracks())
                if (track.sourcePartNumber==source) result.add(track.trackNumber);
        return result;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        com.digero.common.i18n.LocaleManager.init();
        com.digero.common.i18n.UIText.init();
        Preferences prefs=Preferences.userRoot().node("maestro-highlight-routing-"+System.nanoTime());
        File source=new File(args[1]);
        FileResolver resolver=new FileResolver(){
            public File locateFile(File f,String s){return source;}
            public File resolveFile(File f,String s){return source;}
        };
        AbcSong song=new AbcSong(new File(args[0]),new PartAutoNumberer(prefs.node("number")),
            new PartNameTemplate(prefs.node("parts")), new ExportFilenameTemplate(prefs.node("export")),
            new InstrNameSettings(prefs.node("names")),resolver,new MiscSettings(null,false),
            new SaveAndExportSettings(prefs.node("settings")));

        // Source X:2 is displayed in every part's authored list, but is only
        // actually routed to X:1. Looking at X:2 (checkbox selected=false)
        // must not suppress the lighting for the active X:1 preview track.
        song.setAuthoredTrackRouting(Map.of(2,Set.of(1)));
        populatePreviewTracks(song);
        List<Integer> tracks=trackNumbersForSource(song,2);
        check(tracks.size()==1,"Expected one routed preview track for source X:2, got "+tracks);
        int track=tracks.getFirst();
        AbcPart visibleX2=song.getParts().stream().filter(p->p.getPartNumber()==2).findFirst().orElseThrow();
        check(visibleX2.getAuthoredPreviewTracks().stream().noneMatch(t->t.sourcePartNumber==2),
            "Source X:2 was unexpectedly routed to the displayed part X:2");
        check(AuthoredTrackPanel.hasActiveAuthoredPreviewTrack(song,2,n->n==track),
            "An audible authored source fails to highlight when a different part is selected");
        check(!AuthoredTrackPanel.hasActiveAuthoredPreviewTrack(song,2,n->false),
            "Muted or solo-excluded authored sources must not highlight");
        check(!AuthoredTrackPanel.hasActiveAuthoredPreviewTrack(song,999,n->true),
            "Missing authored source must not highlight");

        // Multi-destination routing should light up if ANY routed track plays,
        // and go dark only if ALL are inactive, honoring track-specific mute/solo.
        song.setAuthoredTrackRouting(Map.of(2,Set.of(1,3)));
        populatePreviewTracks(song);
        tracks=trackNumbersForSource(song,2);
        check(tracks.size()==2,"Expected two destination preview tracks, got "+tracks);
        int first=tracks.get(0),second=tracks.get(1);
        check(AuthoredTrackPanel.hasActiveAuthoredPreviewTrack(song,2,n->n==first),
            "Active first destination not recognized");
        check(AuthoredTrackPanel.hasActiveAuthoredPreviewTrack(song,2,n->n==second),
            "Active second destination not recognized");
        check(!AuthoredTrackPanel.hasActiveAuthoredPreviewTrack(song,2,n->false),
            "Both inactive destinations should not highlight");

        // Explicitly unassigned authored notes should not highlight at all.
        song.setAuthoredTrackRouting(Map.of(2,Set.of()));
        populatePreviewTracks(song);
        check(!AuthoredTrackPanel.hasActiveAuthoredPreviewTrack(song,2,n->true),
            "Unassigned authored source must not highlight");
        System.out.println("PASS authored highlighting across selected parts, multi-route, mute/solo and unassigned states");
    }
}
