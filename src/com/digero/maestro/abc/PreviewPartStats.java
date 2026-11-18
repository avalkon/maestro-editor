package com.digero.maestro.abc;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consolidates metadata from generated and separately routed authored preview
 * tracks. A Song Part can own more than one MIDI preview track, so a per-track
 * assignment would discard counts as soon as the next track is encountered.
 */
public final class PreviewPartStats {
    private PreviewPartStats() {}

    public static void updateExportedNoteCounts(List<AbcExporter.ExportTrackInfo> tracks) {
        updateExportedNoteCounts(tracks, Set.of());
    }

    /** The generated track metadata describes the PRE-EDIT ABC. For parts with
     * removals, the preview overlay replaces that track's noteEvents with the
     * surviving originals. Counting the stale metadata would add the whole
     * original track to the authored additions, falsely doubling moved notes.
     * Unedited parts retain the exporter's own counts (including its policies).
     */
    public static void updateExportedNoteCounts(List<AbcExporter.ExportTrackInfo> tracks,
                                                Set<Integer> editedSourceParts) {
        if (tracks == null) return;
        Map<AbcPart, Integer> totals = new IdentityHashMap<>();
        for (AbcExporter.ExportTrackInfo track : tracks) {
            if (track.part == null) continue;
            int count = track.numberOfExportedNotes;
            if (!(track instanceof AuthoredPreviewTrackInfo) &&
                editedSourceParts.contains(track.part.getPartNumber()) && track.noteEvents != null)
                count = track.noteEvents.size();
            totals.merge(track.part, count, Integer::sum);
        }
        for (var entry : totals.entrySet())
            entry.getKey().numberOfExportedNotes = entry.getValue();
    }
}
