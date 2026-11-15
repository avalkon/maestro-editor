package com.digero.maestro.noteedit;

import javax.swing.InputMap;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

/**
 * Reserves Space for Maestro's window-level transport action rather than the
 * focused button's default click/toggle action. Only used by the integrated
 * application; standalone note-editing still has its own guarded shortcut.
 */
public final class SpaceKeyBindings {
    private SpaceKeyBindings() { }

    public static void reserveSpaceForTransport() {
        for (String key : new String[] {
                "Button.focusInputMap", "CheckBox.focusInputMap",
                "RadioButton.focusInputMap", "ToggleButton.focusInputMap" }) {
            Object value = UIManager.get(key);
            if (value instanceof InputMap map) {
                map.put(KeyStroke.getKeyStroke("pressed SPACE"), "none");
                map.put(KeyStroke.getKeyStroke("released SPACE"), "none");
            }
        }
    }
}
