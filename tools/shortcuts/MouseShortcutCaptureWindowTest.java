package com.digero.maestro.shortcuts;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/** Headful capture-dialog regression; run under xvfb on CI/Linux. */
public final class MouseShortcutCaptureWindowTest {
    private static JButton findMouseChange(Component root) {
        if (root instanceof javax.swing.JPanel panel &&
            panel.getBorder() instanceof javax.swing.border.TitledBorder titled &&
            titled.getTitle().contains("Piano roll note creation")) {
            return findButton(panel);
        }
        if (root instanceof Container c) for (Component child : c.getComponents()) {
            JButton button = findMouseChange(child);
            if (button != null) return button;
        }
        return null;
    }
    private static JButton findButton(Component root) {
        if (root instanceof JButton b && b.getText().equals("Change...")) return b;
        if (root instanceof Container c) for (Component child : c.getComponents()) {
            JButton button = findButton(child);
            if (button != null) return button;
        }
        return null;
    }
    private static JLabel findPrompt(Component root) {
        if (root instanceof JLabel l && l.getText().contains("LEFT-CLICK")) return l;
        if (root instanceof Container c) for (Component child : c.getComponents()) {
            JLabel found = findPrompt(child);
            if (found != null) return found;
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        final ShortcutSettingsPanel[] options = new ShortcutSettingsPanel[1];
        final JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            options[0] = new ShortcutSettingsPanel(ShortcutManager.defaults());
            frame[0] = new JFrame("Shortcut capture test");
            frame[0].setContentPane(options[0]);
            frame[0].pack();
            frame[0].setVisible(true);
            JButton change = findMouseChange(options[0]);
            if (change == null) throw new AssertionError("Change button missing");
            SwingUtilities.invokeLater(change::doClick);
        });
        JDialog dialog = null;
        for (int attempts=0; attempts<200 && dialog==null; attempts++) {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog d && d.isShowing() && d.getTitle().contains("modifier + click")) {
                    dialog = d;
                    break;
                }
            }
            if (dialog == null) Thread.sleep(15);
        }
        if (dialog == null) throw new AssertionError("capture dialog did not open");
        final JDialog target = dialog;
        SwingUtilities.invokeAndWait(() -> {
            JLabel prompt = findPrompt(target);
            if (prompt == null) throw new AssertionError("capture prompt missing");
            prompt.dispatchEvent(new MouseEvent(prompt, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK | InputEvent.BUTTON1_DOWN_MASK,
                25, 25, 1, false, MouseEvent.BUTTON1));
        });
        if (!options[0].getNoteClickShortcut().enabled() ||
            options[0].getNoteClickShortcut().modifiers() !=
                (InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK))
            throw new AssertionError("capture failed");
        SwingUtilities.invokeAndWait(frame[0]::dispose);
        System.out.println("PASS live shortcut Change dialog captures Alt+Shift+Click");
    }
}
