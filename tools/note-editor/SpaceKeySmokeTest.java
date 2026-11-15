package com.digero.maestro.noteedit;

import javax.swing.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless regression: one Space press, irrespective of keyboard auto-repeat. */
public final class SpaceKeySmokeTest {
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        SpaceKeyLatch latch = new SpaceKeyLatch();
        check(latch.press(), "First press must fire");
        for(int n=0;n<100;n++) check(!latch.press(), "Key repeat must not fire");
        latch.release();
        check(latch.press(), "Release must rearm key");
        latch.release();

        SwingUtilities.invokeAndWait(() -> {
            PianoRollPanel roll = new PianoRollPanel();
            AtomicInteger toggles = new AtomicInteger();
            roll.setOnTogglePlayback(toggles::incrementAndGet);
            KeyStroke pressed = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false);
            KeyStroke released = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true);
            check("play".equals(roll.getInputMap(JComponent.WHEN_FOCUSED).get(pressed)),
                    "Standalone roll must handle Space press");
            check("playRelease".equals(roll.getInputMap(JComponent.WHEN_FOCUSED).get(released)),
                    "Standalone roll must handle Space release");
            Action press = roll.getActionMap().get("play");
            Action release = roll.getActionMap().get("playRelease");
            ActionEvent event = new ActionEvent(roll, ActionEvent.ACTION_PERFORMED, "Space");
            for (int i=0; i<100; i++) press.actionPerformed(event);
            check(toggles.get()==1, "Held Space should toggle once, not 100 times");
            release.actionPerformed(event);
            press.actionPerformed(event);
            check(toggles.get()==2, "Second physical press should toggle again");
            roll.setSpaceShortcutEnabled(false);
            check(roll.getInputMap(JComponent.WHEN_FOCUSED).get(pressed)==null,
                    "Embedded roll must not consume Maestro's global pressed Space");
            check(roll.getInputMap(JComponent.WHEN_FOCUSED).get(released)==null,
                    "Embedded roll must not consume Maestro's global released Space");
            roll.setSpaceShortcutEnabled(true);
            check("play".equals(roll.getInputMap(JComponent.WHEN_FOCUSED).get(pressed)),
                    "Standalone shortcut must be restorable");
            press.actionPerformed(event);
            check(toggles.get()==3, "Reset binding should rearm toggle");
        });
        System.out.println("PASS: Space held-key guard, separate presses, embedded keyboard ownership");
    }
}
