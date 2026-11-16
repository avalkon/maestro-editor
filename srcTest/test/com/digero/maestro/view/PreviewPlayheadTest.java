package com.digero.maestro.view;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewPlayheadTest {
    @Test void partChangesKeepTheCurrentlyPlayingAbcPosition() {
        assertEquals(12000,PreviewPlayhead.restoreTick(true,0,12000));
        assertEquals(12000,PreviewPlayhead.restoreTick(true,240,12000));
    }
    @Test void originalModeRetainsItsOwnPosition() {
        assertEquals(3600,PreviewPlayhead.restoreTick(false,3600,12000));
    }
    @Test void negativePositionsAreNeverRestored() {
        assertEquals(0,PreviewPlayhead.restoreTick(true,0,-1));
    }
}
