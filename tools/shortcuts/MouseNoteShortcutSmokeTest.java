package com.digero.maestro.noteedit;

import com.digero.maestro.shortcuts.ShortcutManager;
import com.digero.maestro.shortcuts.ShortcutSettingsPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.prefs.Preferences;
import javax.sound.midi.Sequence;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

/** Headless regression for optional modifier+click, preference staging, and double-click. */
public final class MouseNoteShortcutSmokeTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void click(PianoRollPanel roll, int x, int y, int mods, int count) {
        long when = System.currentTimeMillis();
        roll.dispatchEvent(new MouseEvent(roll, MouseEvent.MOUSE_PRESSED, when, mods, x, y, count, false,
                MouseEvent.BUTTON1));
        roll.dispatchEvent(new MouseEvent(roll, MouseEvent.MOUSE_RELEASED, when + 1, mods, x, y, count, false,
                MouseEvent.BUTTON1));
        roll.dispatchEvent(new MouseEvent(roll, MouseEvent.MOUSE_CLICKED, when + 2, mods, x, y, count, false,
                MouseEvent.BUTTON1));
    }
    private static JButton button(Component parent, String label) {
        if (parent instanceof JButton b && b.getText().equals(label)) return b;
        if (parent instanceof Container c) for (Component child : c.getComponents()) {
            JButton found = button(child, label);
            if (found != null) return found;
        }
        return null;
    }
    private static JCheckBox checkbox(Component parent) {
        if (parent instanceof JCheckBox b && b.getText().contains("modifier + left-click")) return b;
        if (parent instanceof Container c) for (Component child : c.getComponents()) {
            JCheckBox found = checkbox(child);
            if (found != null) return found;
        }
        return null;
    }
    private static void run() {
        Preferences pref = Preferences.userRoot().node("maestro-mouse-shortcut-" + System.nanoTime());
        try {
            int ctrl = InputEvent.CTRL_DOWN_MASK, alt = InputEvent.ALT_DOWN_MASK,
                    shift = InputEvent.SHIFT_DOWN_MASK;
            try (ShortcutManager manager = new ShortcutManager(pref)) {
                check(!manager.getNoteClickShortcut().enabled(), "new preference must be off by default");
                check(manager.getNoteClickShortcut().modifiers() == ctrl, "default suggested binding Ctrl+Click");
                ShortcutSettingsPanel options = new ShortcutSettingsPanel(manager.snapshot(),
                        manager.getNoteClickShortcut());
                JButton clear = button(options, "Clear");
                JButton restore = button(options, "Restore");
                JCheckBox enabled = checkbox(options);
                check(clear != null && restore != null && enabled != null, "options controls exist");
                enabled.doClick();
                check(options.getNoteClickShortcut().enabled(), "checkbox stages enable");
                check(!manager.getNoteClickShortcut().enabled(), "staged change doesn't apply until OK");
                clear.doClick();
                check(!options.getNoteClickShortcut().enabled() &&
                        options.getNoteClickShortcut().modifiers() == 0, "Clear removes mouse shortcut");
                restore.doClick();
                check(!options.getNoteClickShortcut().enabled() &&
                        options.getNoteClickShortcut().modifiers() == ctrl, "Restore restores off + Ctrl");
                manager.setNoteClickShortcut(new ShortcutManager.NoteClickShortcut(true, alt | shift));
                try (ShortcutManager reloaded = new ShortcutManager(pref)) {
                    check(reloaded.getNoteClickShortcut().enabled() &&
                            reloaded.getNoteClickShortcut().modifiers() == (alt | shift),
                            "mouse gesture survives restart");
                }
                boolean rejected = false;
                try { new ShortcutManager.NoteClickShortcut(true, 0); }
                catch (IllegalArgumentException ex) { rejected = true; }
                check(rejected, "plain click binding must be rejected");
                check("Alt+Shift+Click".equals(ShortcutManager.prettyMouseModifiers(alt | shift)),
                        "mouse shortcut label");

                NoteDocument doc = new NoteDocument(NoteDocument.Kind.ABC, 960, Sequence.PPQ,
                        new File("smoke.abc"));
                NoteDocument.Layer part = new NoteDocument.Layer("Lute of Ages");
                part.instrumentName = "Lute of Ages";
                doc.layers.add(part);
                PianoRollPanel roll = new PianoRollPanel();
                roll.setDocument(doc, 0);
                roll.setSize(1400, 2500);
                roll.setSnapDivisor(16);
                int y = 1000; // note area, not ruler
                click(roll, 200, y, ctrl, 1);
                check(part.notes.isEmpty(), "disabled single click preserves legacy behavior");
                roll.setNoteCreationMouseGesture(true, alt | shift);
                click(roll, 200, y, alt, 1);
                click(roll, 200, y, shift, 1);
                click(roll, 200, y, ctrl, 1);
                check(part.notes.isEmpty(), "exact modifiers required");
                click(roll, 200, y, alt | shift, 1);
                check(part.notes.size() == 1, "modifier + single click inserts exactly one note");
                click(roll, 200, y, alt | shift, 1);
                check(part.notes.size() == 1, "clicking existing note doesn't duplicate");
                click(roll, 310, y + 50, 0, 2);
                check(part.notes.size() == 2, "plain double click still inserts when enabled");
                click(roll, 420, y + 100, alt | shift, 2);
                check(part.notes.size() == 3, "modifier double click inserts only once");
                roll.setNoteCreationMouseGesture(false, alt | shift);
                click(roll, 530, y + 150, alt | shift, 1);
                check(part.notes.size() == 3, "disabled binding does nothing");
                click(roll, 530, y + 150, 0, 2);
                check(part.notes.size() == 4, "double click still works with binding disabled");
                doc.undo();
                check(part.notes.size() == 3, "mouse shortcut addition is undoable");
            }
        } finally {
            try { pref.removeNode(); } catch (Exception ex) { throw new RuntimeException(ex); }
        }
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(MouseNoteShortcutSmokeTest::run);
        System.out.println("PASS optional mouse shortcut, staging/clear/restore, persistence, exact modifiers, undo, double-click");
    }
}
