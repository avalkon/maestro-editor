package com.digero.maestro.noteedit;

/**
 * Edge-triggered keyboard shortcut guard: one action per physical key press.
 * Key autorepeat generates additional KEY_PRESSED events, not KEY_RELEASED.
 */
public final class SpaceKeyLatch {
    private boolean held;

    /** Returns true once per press; false for held-key auto-repeat. */
    public boolean press() {
        if (held) return false;
        held = true;
        return true;
    }

    /** Arms the next press, including after a window or component loses focus. */
    public void release() { held = false; }
}
