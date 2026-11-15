package com.digero.maestro.shortcuts;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import javax.swing.*;

public final class ShortcutSmokeTest {
    private static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Preferences prefs = Preferences.userRoot().node("maestro-test-" + System.nanoTime());
        try (ShortcutManager manager = new ShortcutManager(prefs)) {
            Map<String, KeyStroke> bindings = manager.snapshot();
            check(bindings.get("bar.forward5").equals(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)), "forward five default");
            check(bindings.get("editor.copy").equals(KeyStroke.getKeyStroke(KeyEvent.VK_C,
                    InputEvent.CTRL_DOWN_MASK)), "copy default");
            check(bindings.get("editor.cut").equals(KeyStroke.getKeyStroke(KeyEvent.VK_X,
                    InputEvent.CTRL_DOWN_MASK)), "cut default");
            check(bindings.get("editor.paste").equals(KeyStroke.getKeyStroke(KeyEvent.VK_V,
                    InputEvent.CTRL_DOWN_MASK)), "paste default");
            check(bindings.get("editor.selectAll").equals(KeyStroke.getKeyStroke(KeyEvent.VK_A,
                    InputEvent.CTRL_DOWN_MASK)), "select all default");
            check(ShortcutManager.validate(bindings) == null, "conflict-free defaults");
            bindings.put("bar.forward5", bindings.get("bar.forward10"));
            check(ShortcutManager.validate(bindings) != null, "duplicate rejection");
            bindings.put("bar.forward5", KeyStroke.getKeyStroke(KeyEvent.VK_X,
                    InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
            javax.swing.JMenuItem menu = new javax.swing.JMenuItem("Forward five bars");
            manager.attachMenu("bar.forward5", menu);
            manager.setBindings(bindings);
            check(bindings.get("bar.forward5").equals(menu.getAccelerator()), "menu accelerator updated");
            check(manager.snapshot().get("bar.forward5").equals(bindings.get("bar.forward5")), "binding changed");
            try (ShortcutManager reloaded = new ShortcutManager(prefs)) {
                check(reloaded.snapshot().get("bar.forward5").equals(bindings.get("bar.forward5")), "Preferences persistence");
            }
            check(ShortcutManager.validate(Map.of("transport.play", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,0))) != null,
                    "Space reserved");
        } finally { prefs.removeNode(); }
        check(BarNavigation.barStart(1439, 0, 1440, 1, 10000) == 1440, "3/4 next bar");
        check(BarNavigation.barStart(1440, 0, 1440, -1, 10000) == 0, "previous bar");
        check(BarNavigation.barStart(4320, 0, 1440, 5, 20000) == 11520, "+5 bars");
        check(BarNavigation.barStart(4320, 0, 1440, 10, 6000) == 6000, "clamp");
        check(BarNavigation.barStart(2600, 1000, 1440, 1, 10000) == 3880, "ABC preview offset");
        System.out.println("PASS shortcut defaults, collision checks, persistence, bar navigation");
    }
}
