package com.digero.maestro.abc;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolidates metadata from generated and separately routed authored preview
 * tracks. A Song Part can own more than one MIDI preview track, so a per-track
 * assignment would discard counts as soon as the next track is encountered.
 */
public final class PreviewPartStats {
    private PreviewPartStats() {}

    public static void updateExportedNoteCounts(List<AbcExporter.ExportTrackInfo> tracks) {
        if (tracks == null) return;
        Map<AbcPart, Integer> totals = new IdentityHashMap<>();
        for (AbcExporter.ExportTrackInfo track : tracks) {
            if (track.part != null)
                totals.merge(track.part, track.numberOfExportedNotes, Integer::sum);
        }
        for (var entry : totals.entrySet())
            entry.getKey().numberOfExportedNotes = entry.getValue();
    }
}
