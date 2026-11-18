package com.digero.maestro.abc;

import com.digero.maestro.noteedit.AbcDocumentIO;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Editor ABC ticks are written lengths at the primary export BPM, not MIDI
 * source ticks. Integrate the same tempo events that are sent to preview, and
 * invert that mapping when inserting authored notes into the MIDI sequence. */
public final class AuthoredNoteTiming {
    private record Segment(double micros, double microsPerTick, long tick) {}
    private final NavigableMap<Long, Segment> byTick = new TreeMap<>();
    private final NavigableMap<Double, Segment> byMicros = new TreeMap<>();
    private final double microsPerEditorTick;
    private final double originMicros;

    public AuthoredNoteTiming(QuantizedTimingInfo timing, long originTick) {
        microsPerEditorTick = 60_000_000.0 / (timing.getPrimaryExportTempoBPM() * (double) AbcDocumentIO.PPQ);
        var events = timing.isOrganic() ? timing.getTimingInfoByTickOrganic() : timing.getTimingInfoByTick();
        Segment previous = null;
        for (var event : events.values()) {
            double micros = previous == null ? 0 : previous.micros +
                (event.tick() - previous.tick) * previous.microsPerTick;
            Segment next = new Segment(micros, event.info().getExportTempoMPQ() /
                (double) timing.getMidiResolution(), event.tick());
            byTick.put(next.tick, next);
            byMicros.put(next.micros, next);
            previous = next;
        }
        originMicros = microsAt(originTick);
    }

    private double microsAt(double tick) {
        var entry = byTick.floorEntry((long) Math.floor(tick));
        Segment segment = entry == null ? byTick.firstEntry().getValue() : entry.getValue();
        return segment.micros + (tick - segment.tick) * segment.microsPerTick;
    }

    public double toSourceTick(long editorTick) {
        double micros = originMicros + editorTick * microsPerEditorTick;
        var entry = byMicros.floorEntry(micros);
        Segment segment = entry == null ? byMicros.firstEntry().getValue() : entry.getValue();
        return segment.tick + (micros - segment.micros) / segment.microsPerTick;
    }

    public long toEditorTick(double sourceTick) {
        return Math.max(0, Math.round((microsAt(sourceTick) - originMicros) / microsPerEditorTick));
    }

    public long toPreviewTick(long editorTick) {
        return Math.max(0, Math.round(toSourceTick(editorTick)));
    }
}
