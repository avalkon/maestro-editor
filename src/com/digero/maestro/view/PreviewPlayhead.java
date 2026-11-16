package com.digero.maestro.view;

/** Select the transport to preserve across an ABC preview-sequence rebuild. */
public final class PreviewPlayhead {
    private PreviewPlayhead() {}

    public static long restoreTick(boolean abcPreviewActive,long sourceTick,long abcTick) {
        return Math.max(0,abcPreviewActive ? abcTick : sourceTick);
    }
}
