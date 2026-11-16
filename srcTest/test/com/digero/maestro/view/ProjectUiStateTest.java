package com.digero.maestro.view;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectUiStateTest {
    @Test void stoppedAtStartDisablesStop() {
        assertFalse(ProjectUiState.stopEnabled(true, true, false, 0, 0));
        assertFalse(ProjectUiState.stopEnabled(true, true, false, 480, 480));
    }

    @Test void nonzeroActivePositionOrPlayingEnablesStop() {
        assertTrue(ProjectUiState.stopEnabled(true, true, false, 960, 480));
        assertTrue(ProjectUiState.stopEnabled(true, true, true, 480, 480));
    }

    @Test void disabledOrUnloadedTransportCannotEnableStop() {
        assertFalse(ProjectUiState.stopEnabled(false, true, true, 999, 0));
        assertFalse(ProjectUiState.stopEnabled(true, false, false, 999, 0));
    }

    @Test void blankCompositionRetainsPianoRollModeDuringLoading() {
        assertFalse(ProjectUiState.shouldSelectOriginal(false, true));
        assertTrue(ProjectUiState.shouldSelectOriginal(false, false));
        assertFalse(ProjectUiState.shouldSelectOriginal(true, false));
    }
}
