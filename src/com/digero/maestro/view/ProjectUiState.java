package com.digero.maestro.view;

/** Pure view-state policies shared by the transport and the piano-roll loader. */
final class ProjectUiState {
    private ProjectUiState() {}

    /** An empty composition must keep Piano Roll selected while it loads. */
    static boolean shouldSelectOriginal(boolean hasPreviewNotes, boolean pianoRollActive) {
        return !hasPreviewNotes && !pianoRollActive;
    }

    /** Only the active sequencer contributes to Stop-button state. */
    static boolean stopEnabled(boolean uiEnabled, boolean loaded,
            boolean running, long tick, long previewStartTick) {
        return uiEnabled && loaded && (running || tick > Math.max(0L, previewStartTick));
    }
}
