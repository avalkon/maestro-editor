package com.digero.maestro.shortcuts;

import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.prefs.Preferences;

import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JMenuItem;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Application-scoped shortcut registry. Dispatches before Swing's focused
 * controls and menu accelerators, including modeless dialogs owned by Maestro.
 * SPACE is always reserved for transport (except while editing text).
 */
public final class ShortcutManager implements AutoCloseable {
    public record Definition(String id, String category, String title, KeyStroke defaultKey) { }
    private static final int MODIFIERS = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK
            | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;
    private static KeyStroke key(int code, int mod) { return KeyStroke.getKeyStroke(code, mod); }

    /** Optional piano-roll mouse gesture, separate from keyboard-only shortcuts. */
    public record NoteClickShortcut(boolean enabled, int modifiers) {
        public NoteClickShortcut {
            if ((modifiers & ~MODIFIERS) != 0 || (enabled && modifiers == 0))
                throw new IllegalArgumentException("Note creation requires Ctrl, Alt, Shift or Meta + left click");
        }
    }
    public static NoteClickShortcut defaultNoteClickShortcut() {
        return new NoteClickShortcut(false, InputEvent.CTRL_DOWN_MASK);
    }
    public static int mouseModifiersMask() { return MODIFIERS; }
    public static String prettyMouseModifiers(int modifiers) {
        if (modifiers == 0) return "Not assigned";
        StringBuilder sb = new StringBuilder();
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) sb.append("Alt+");
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) sb.append("Meta+");
        return sb.append("Click").toString();
    }
    private static final List<Definition> DEFAULTS = List.of(
            new Definition("transport.play", "Playback", "Play / Pause (Space is always active)", null),
            new Definition("transport.stop", "Playback", "Stop", key(KeyEvent.VK_PERIOD, InputEvent.CTRL_DOWN_MASK)),
            new Definition("bar.start", "Playhead", "Beginning of song", key(KeyEvent.VK_HOME, 0)),
            new Definition("bar.current", "Playhead", "Beginning of current bar", key(KeyEvent.VK_HOME, InputEvent.ALT_DOWN_MASK)),
            new Definition("bar.next", "Playhead", "Beginning of next bar", key(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK)),
            new Definition("bar.previous", "Playhead", "Beginning of previous bar", key(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK)),
            new Definition("bar.forward1", "Playhead", "Forward 1 bar", key(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("bar.back1", "Playhead", "Back 1 bar", key(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("bar.forward5", "Playhead", "Forward 5 bars", key(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)),
            new Definition("bar.back5", "Playhead", "Back 5 bars", key(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)),
            new Definition("bar.forward10", "Playhead", "Forward 10 bars", key(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)),
            new Definition("bar.back10", "Playhead", "Back 10 bars", key(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)),
            new Definition("window.section", "Windows", "Toggle Section Editor", key(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("window.part", "Windows", "Toggle Part Editor", key(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("window.tune", "Windows", "Toggle Tune Editor", key(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("window.theme", "Windows", "Toggle Color Editor", key(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("window.volume", "Windows", "Toggle note velocities for every track in selected part", key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("window.roll", "Windows", "Toggle Piano Roll", key(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)),
            new Definition("editor.copy", "Piano roll", "Copy selected notes", key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)),
            new Definition("editor.cut", "Piano roll", "Cut selected notes", key(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK)),
            new Definition("editor.paste", "Piano roll", "Paste notes at playhead", key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)),
            new Definition("editor.selectAll", "Piano roll", "Select all visible notes", key(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)),
            new Definition("editor.undo", "Piano roll", "Undo edit", key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)),
            new Definition("editor.redo", "Piano roll", "Redo edit", key(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK)),
            new Definition("editor.delete", "Piano roll", "Delete selected notes", key(KeyEvent.VK_DELETE, 0)),
            new Definition("editor.deleteBackspace", "Piano roll", "Delete notes (Backspace)", key(KeyEvent.VK_BACK_SPACE, 0)),
            new Definition("editor.clear", "Piano roll", "Clear selection", key(KeyEvent.VK_ESCAPE, 0)),
            new Definition("file.open", "File", "Open", key(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK)),
            new Definition("file.save", "File", "Save project", key(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK)),
            new Definition("file.saveAs", "File", "Save project as", key(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)),
            new Definition("file.export", "File", "Export ABC", key(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK)),
            new Definition("file.exportAs", "File", "Export ABC as", key(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)),
            new Definition("file.reload", "File", "Reload source", key(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)),
            new Definition("file.exit", "File", "Exit", key(KeyEvent.VK_F4, InputEvent.ALT_DOWN_MASK)),
            new Definition("tools.options", "Tools", "Options", key(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK)),
            new Definition("tools.help", "Tools", "Help", key(KeyEvent.VK_F5, 0)),
            new Definition("tools.updates", "Tools", "Check for updates", key(KeyEvent.VK_F6, 0)),
            new Definition("tools.about", "Tools", "About", key(KeyEvent.VK_F1, 0))
    );

    private static final String NOTE_CLICK_ENABLED_PREF = "pianoRoll.noteClickEnabled";
    private static final String NOTE_CLICK_MODIFIERS_PREF = "pianoRoll.noteClickModifiers";
    private NoteClickShortcut noteClickShortcut;
    private final Preferences prefs;
    private final LinkedHashMap<String, KeyStroke> bindings = new LinkedHashMap<>();
    private final Map<String, Runnable> actions = new LinkedHashMap<>();
    private final Map<String, JMenuItem> menuItems = new LinkedHashMap<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private Window owner;
    private final KeyEventDispatcher dispatcher = this::dispatch;
    private final PropertyChangeListener focusListener = e -> heldKeys.clear();
    private boolean installed;

    public ShortcutManager(Preferences prefs) {
        this.prefs = Objects.requireNonNull(prefs);
        int mouseMods = prefs.getInt(NOTE_CLICK_MODIFIERS_PREF,
                defaultNoteClickShortcut().modifiers());
        boolean mouseEnabled = prefs.getBoolean(NOTE_CLICK_ENABLED_PREF, false);
        try {
            noteClickShortcut = new NoteClickShortcut(mouseEnabled, mouseMods);
        } catch (IllegalArgumentException invalidPreferences) {
            noteClickShortcut = defaultNoteClickShortcut();
        }
        for (Definition d : DEFAULTS) {
            String stored = prefs.get(d.id(), null);
            KeyStroke parsed = null;
            if (stored != null && !stored.equals("<unbound>")) parsed = KeyStroke.getKeyStroke(stored);
            KeyStroke binding = stored == null || (parsed == null && !stored.equals("<unbound>"))
                    ? d.defaultKey() : parsed;
            bindings.put(d.id(), binding);
        }
        // Old/corrupt preferences cannot steal Space or cause duplicate bindings.
        Set<KeyStroke> used = new HashSet<>();
        for (Definition d : DEFAULTS) {
            KeyStroke binding = bindings.get(d.id());
            if (binding != null && (!isBindable(binding) || used.contains(binding))) {
                binding = d.defaultKey();
                if (binding != null && used.contains(binding)) binding = null;
                bindings.put(d.id(), binding);
            }
            if (binding != null) used.add(binding);
        }
    }

    public NoteClickShortcut getNoteClickShortcut() { return noteClickShortcut; }
    public void setNoteClickShortcut(NoteClickShortcut shortcut) {
        noteClickShortcut = Objects.requireNonNull(shortcut);
        prefs.putBoolean(NOTE_CLICK_ENABLED_PREF, shortcut.enabled());
        prefs.putInt(NOTE_CLICK_MODIFIERS_PREF, shortcut.modifiers());
    }
    public static List<Definition> definitions() { return DEFAULTS; }
    public Map<String, KeyStroke> snapshot() { return new LinkedHashMap<>(bindings); }
    public static Map<String, KeyStroke> defaults() {
        Map<String, KeyStroke> defaults = new LinkedHashMap<>();
        for (Definition d : DEFAULTS) defaults.put(d.id(), d.defaultKey());
        return defaults;
    }
    public static boolean isBindable(KeyStroke key) {
        if (key == null) return true;
        int code = key.getKeyCode();
        return key.getKeyEventType() == KeyEvent.KEY_PRESSED && code != KeyEvent.VK_SPACE
                && code != KeyEvent.VK_UNDEFINED && !Set.of(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL,
                        KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH).contains(code);
    }
    public static String validate(Map<String, KeyStroke> proposed) {
        Map<KeyStroke, String> used = new LinkedHashMap<>();
        for (Definition def : DEFAULTS) {
            KeyStroke key = proposed.get(def.id());
            if (!isBindable(key)) return "Space is reserved for Play/Pause, and modifier-only shortcuts are not supported.";
            if (key != null && used.putIfAbsent(key, def.title()) != null)
                return "Duplicate shortcut " + pretty(key) + " (" + used.get(key) + " / " + def.title() + ")";
        }
        return null;
    }
    public static String pretty(KeyStroke key) {
        if (key == null) return "Not assigned";
        StringBuilder sb = new StringBuilder();
        int mod = key.getModifiers();
        if ((mod & InputEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((mod & InputEvent.ALT_DOWN_MASK) != 0) sb.append("Alt+");
        if ((mod & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if ((mod & InputEvent.META_DOWN_MASK) != 0) sb.append("Meta+");
        sb.append(KeyEvent.getKeyText(key.getKeyCode()));
        return sb.toString();
    }
    public void setBindings(Map<String, KeyStroke> newBindings) {
        String error = validate(newBindings);
        if (error != null) throw new IllegalArgumentException(error);
        for (Definition d : DEFAULTS) {
            KeyStroke key = newBindings.get(d.id());
            bindings.put(d.id(), key);
            prefs.put(d.id(), key == null ? "<unbound>" : key.toString());
        }
        updateMenus();
    }
    public void register(String id, Runnable action) {
        if (!bindings.containsKey(id)) throw new IllegalArgumentException(id);
        actions.put(id, Objects.requireNonNull(action));
    }
    public void attachMenu(String id, JMenuItem item) {
        menuItems.put(id, item);
        register(id, item::doClick);
        updateMenus();
    }
    private void updateMenus() {
        menuItems.forEach((id, item) -> item.setAccelerator(bindings.get(id)));
    }
    public void install(Window owner) {
        if (installed) return;
        this.owner = owner;
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        manager.addKeyEventDispatcher(dispatcher);
        manager.addPropertyChangeListener("activeWindow", focusListener);
        installed = true;
    }
    @Override public void close() {
        if (!installed) return;
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        manager.removeKeyEventDispatcher(dispatcher);
        manager.removePropertyChangeListener("activeWindow", focusListener);
        heldKeys.clear(); installed = false;
    }
    private boolean isOurWindow(Window w) {
        for (Window p = w; p != null; p = p.getOwner()) if (p == owner) return true;
        return false;
    }
    private static boolean isTextEntry(Component focus) {
        if (focus instanceof JTextComponent text) return text.isEditable();
        if (focus instanceof JComboBox<?> box && box.isEditable()) return true;
        JComboBox<?> parentCombo = focus == null ? null : (JComboBox<?>) SwingUtilities.getAncestorOfClass(JComboBox.class, focus);
        return parentCombo != null && parentCombo.isEditable();
    }
    private static boolean capturing(Component focus) {
        if (focus == null) return false;
        JRootPane root = SwingUtilities.getRootPane(focus);
        return root != null && Boolean.TRUE.equals(root.getClientProperty("maestro.shortcut.capture"));
    }
    private boolean dispatch(KeyEvent e) {
        if (!installed || e.isConsumed()) return false;
        Component focus = e.getComponent();
        Window window = focus == null ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()
                : SwingUtilities.getWindowAncestor(focus);
        if (!isOurWindow(window) || capturing(focus)) return false;
        int code = e.getKeyCode();
        if (e.getID() == KeyEvent.KEY_RELEASED) {
            heldKeys.remove(code);
            // Stop the Space release from activating the focused control.
            if (code == KeyEvent.VK_SPACE && !isTextEntry(focus)) { e.consume(); return true; }
            return false;
        }
        if (e.getID() == KeyEvent.KEY_TYPED) {
            if (e.getKeyChar() == ' ' && !isTextEntry(focus)) { e.consume(); return true; }
            return false;
        }
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;
        boolean textEntry = isTextEntry(focus);
        // Space is globally reserved for playback on controls and menus, but not
        // in an editable title, part name, or Section Editor text field.
        if (code == KeyEvent.VK_SPACE) {
            if (textEntry) return false;
            e.consume();
            if (heldKeys.add(code)) invoke("transport.play");
            return true;
        }
        KeyStroke typed = KeyStroke.getKeyStroke(code, e.getModifiersEx() & MODIFIERS);
        String command = null;
        for (Map.Entry<String, KeyStroke> entry : bindings.entrySet()) {
            if (typed.equals(entry.getValue())) { command = entry.getKey(); break; }
        }
        if (command == null) return false;
        if (textEntry && (e.getModifiersEx() & MODIFIERS) == 0 &&
                (code == KeyEvent.VK_DELETE || code == KeyEvent.VK_BACK_SPACE ||
                 code == KeyEvent.VK_HOME || code == KeyEvent.VK_END ||
                 code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT))
            return false;
        // Never steal ordinary characters (including Shift+letter) from text
        // entry, even when a user binds that same keystroke as a shortcut.
        if (textEntry && (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK |
                InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) == 0) return false;
        // Preserve word/caret selection gestures in editable text fields.
        if (textEntry && (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) == 0 &&
                (code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT ||
                 code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN)) return false;
        // Let ordinary cut/copy/paste/undo work when typing, without sacrificing
        // Ctrl+Alt/Shift keyboard navigation in Section/Tune Editor dialogs.
        if (textEntry && (code == KeyEvent.VK_X || code == KeyEvent.VK_C || code == KeyEvent.VK_V
                || code == KeyEvent.VK_A || code == KeyEvent.VK_Z || code == KeyEvent.VK_Y)
                && (e.getModifiersEx() & MODIFIERS) == InputEvent.CTRL_DOWN_MASK) return false;
        e.consume();
        if (heldKeys.add(code)) invoke(command);
        return true;
    }
    private void invoke(String id) {
        Runnable r = actions.get(id);
        if (r != null) r.run();
    }
}
