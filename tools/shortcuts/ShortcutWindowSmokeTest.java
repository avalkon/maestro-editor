package com.digero.maestro.shortcuts;

import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import javax.swing.*;

/** Real Swing focus test; run with xvfb-run on Linux. */
public final class ShortcutWindowSmokeTest {
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void press(Robot robot,int key){robot.keyPress(key);robot.delay(70);robot.keyRelease(key);robot.delay(125);}
    public static void main(String[] args) throws Exception {
        Preferences prefs=Preferences.userRoot().node("maestro-gui-test-"+System.nanoTime());
        JFrame frame=new JFrame("test");
        ShortcutManager keys=new ShortcutManager(prefs);
        AtomicInteger playback=new AtomicInteger(), bar=new AtomicInteger(), copy=new AtomicInteger(), select=new AtomicInteger();
        JButton button=new JButton("Button");
        JRadioButton radio=new JRadioButton("Radio");
        JTextField text=new JTextField(16);
        JPanel panel=new JPanel();panel.add(button);panel.add(radio);panel.add(text);
        JDialog child=new JDialog(frame,"Section Editor",false);
        JButton childButton=new JButton("Section button");child.add(childButton);child.pack();
        keys.register("transport.play",playback::incrementAndGet);
        keys.register("bar.forward1",bar::incrementAndGet);
        keys.register("editor.copy",copy::incrementAndGet);
        keys.register("editor.selectAll",select::incrementAndGet);
        SwingUtilities.invokeAndWait(()->{frame.add(panel);frame.pack();frame.setLocation(100,100);frame.setVisible(true); keys.install(frame);button.requestFocusInWindow();});
        Robot robot=new Robot();robot.setAutoDelay(20);robot.delay(300);
        try{
            press(robot,KeyEvent.VK_SPACE);
            check(playback.get()==1,"Space on button triggers playback");
            check(!button.getModel().isPressed(),"button not activated");
            SwingUtilities.invokeAndWait(radio::requestFocusInWindow);robot.delay(140);
            press(robot,KeyEvent.VK_SPACE);
            check(playback.get()==2 && !radio.isSelected(),"Space on radio plays but does not select");
            robot.keyPress(KeyEvent.VK_SPACE);robot.delay(500);
            check(playback.get()==3,"Space held should trigger once");
            robot.keyRelease(KeyEvent.VK_SPACE);robot.delay(140);
            SwingUtilities.invokeAndWait(button::requestFocusInWindow);robot.delay(120);
            robot.keyPress(KeyEvent.VK_CONTROL);press(robot,KeyEvent.VK_C);press(robot,KeyEvent.VK_A);
            robot.keyRelease(KeyEvent.VK_CONTROL);robot.delay(120);
            check(copy.get()==1 && select.get()==1,"Copy and select-all reach shortcut registry on ordinary controls");
            SwingUtilities.invokeAndWait(()->{text.setText("editable text");text.requestFocusInWindow();});robot.delay(120);
            robot.keyPress(KeyEvent.VK_CONTROL);press(robot,KeyEvent.VK_C);press(robot,KeyEvent.VK_A);
            robot.keyRelease(KeyEvent.VK_CONTROL);robot.delay(120);
            check(copy.get()==1 && select.get()==1,"Text-field clipboard shortcuts not stolen");
            press(robot,KeyEvent.VK_SPACE);
            check(playback.get()==3 && text.getText().contains(" "),"typing space preserved");
            SwingUtilities.invokeAndWait(()->{child.setLocation(110,240);child.setVisible(true);childButton.requestFocusInWindow();});
            robot.delay(250);press(robot,KeyEvent.VK_SPACE);
            check(playback.get()==4,"Space works in owned editor window");
            robot.keyPress(KeyEvent.VK_CONTROL);robot.keyPress(KeyEvent.VK_ALT);press(robot,KeyEvent.VK_RIGHT);
            robot.keyRelease(KeyEvent.VK_ALT);robot.keyRelease(KeyEvent.VK_CONTROL);robot.delay(140);
            check(bar.get()==1,"Ctrl+Alt+Right works in owned editor window");
            System.out.println("PASS Space on buttons/radio, text, hold, and owned Section Editor; navigation shortcut");
        } finally {
            SwingUtilities.invokeAndWait(()->{child.dispose();frame.dispose();keys.close();});
            prefs.removeNode();
        }
    }
}
