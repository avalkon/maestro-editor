package com.digero.maestro.noteedit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Requires a graphical session (e.g. xvfb-run). Exercises actual focus and key routing. */
public final class SpaceKeyWindowSmokeTest {
    private static void check(boolean yes, String description) {
        if (!yes) throw new AssertionError(description);
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger toggleCount = new AtomicInteger();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<PianoRollPanel> rollRef = new AtomicReference<>();
        AtomicReference<JTextField> textRef = new AtomicReference<>();
        AtomicReference<JRadioButton> radioRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SpaceKeyBindings.reserveSpaceForTransport(); // Same routing installed by ProjectFrame.
            JFrame frame = new JFrame("Space focus smoke test");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout());
            PianoRollPanel roll = new PianoRollPanel();
            roll.setSpaceShortcutEnabled(false); // As with Maestro's embedded mode
            content.add(roll, BorderLayout.CENTER);
            JRadioButton radio = new JRadioButton("ABC Preview");
            radio.setSelected(true);
            content.add(radio, BorderLayout.NORTH);
            JTextField text = new JTextField();
            content.add(text, BorderLayout.SOUTH);
            frame.setContentPane(content);
            SpaceKeyLatch latch = new SpaceKeyLatch();
            InputMap input = frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap actions = frame.getRootPane().getActionMap();
            input.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false), "space-press");
            input.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true), "space-release");
            actions.put("space-press", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    if (!latch.press()) return;
                    Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if (focus instanceof javax.swing.text.JTextComponent) return;
                    toggleCount.incrementAndGet();
                }
            });
            actions.put("space-release", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {latch.release();}
            });
            frame.setSize(720, 420);
            frame.setLocation(30, 30);
            frame.setVisible(true);
            frameRef.set(frame);
            rollRef.set(roll);
            textRef.set(text);
            radioRef.set(radio);
            roll.requestFocusInWindow();
        });
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(90);
            robot.waitForIdle();
            SwingUtilities.invokeAndWait(() -> {
                frameRef.get().toFront();
                frameRef.get().requestFocus();
                rollRef.get().requestFocus();
            });
            robot.waitForIdle();
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            check(focus == rollRef.get(), "Piano roll must accept focus; was " + focus);
            robot.keyPress(KeyEvent.VK_SPACE);
            Thread.sleep(650); // held key should not toggle twice
            robot.keyRelease(KeyEvent.VK_SPACE);
            robot.waitForIdle();
            check(toggleCount.get() == 1, "Space while roll focused must toggle exactly once");
            robot.keyPress(KeyEvent.VK_SPACE);
            robot.keyRelease(KeyEvent.VK_SPACE);
            robot.waitForIdle();
            check(toggleCount.get() == 2, "Subsequent Space must toggle again");
            // Regression: selecting a Maestro mode leaves its radio button focused.
            SwingUtilities.invokeAndWait(() -> radioRef.get().requestFocusInWindow());
            robot.waitForIdle();
            check(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == radioRef.get(),
                    "Radio button must have focus for mode-switch regression");
            robot.keyPress(KeyEvent.VK_SPACE);
            robot.keyRelease(KeyEvent.VK_SPACE);
            robot.waitForIdle();
            check(toggleCount.get() == 3, "Space while mode radio focused must toggle playback once");
            check(radioRef.get().isSelected(), "Space must not toggle the selected radio mode");
            SwingUtilities.invokeAndWait(() -> {
                textRef.get().requestFocusInWindow();
            });
            robot.waitForIdle();
            robot.keyPress(KeyEvent.VK_SPACE);
            robot.keyRelease(KeyEvent.VK_SPACE);
            robot.waitForIdle();
            check(toggleCount.get() == 3, "Text-input Space must not trigger transport");
            check(textRef.get().getText().contains(" "), "Text input must receive Space");
            System.out.println("PASS: piano roll, held Space, mode radio, and text-field focus routing");
        } finally {
            SwingUtilities.invokeAndWait(() -> frameRef.get().dispose());
        }
    }
}
