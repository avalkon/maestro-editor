package com.digero.maestro.shortcuts;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/** Editable, staged shortcuts; Cancel does not modify Preferences. */
public final class ShortcutSettingsPanel extends JPanel {
    private final LinkedHashMap<String, KeyStroke> values = new LinkedHashMap<>();
    private final JTable table = new JTable(new Model());

    public ShortcutSettingsPanel(Map<String, KeyStroke> starting) {
        super(new BorderLayout(5, 5));
        values.putAll(starting);
        JLabel instructions = new JLabel("Select an action, click Change, then press Ctrl/Alt/Shift plus a key. Space always plays/pauses.");
        add(instructions, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(23, table.getRowHeight()));
        table.getColumnModel().getColumn(0).setPreferredWidth(95);
        table.getColumnModel().getColumn(1).setPreferredWidth(340);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton change = new JButton("Change...");
        JButton unbind = new JButton("Clear selected");
        JButton reset = new JButton("Restore shortcut defaults");
        change.addActionListener(e -> captureSelected());
        unbind.addActionListener(e -> updateSelected(null));
        reset.addActionListener(e -> { values.clear(); values.putAll(ShortcutManager.defaults()); refresh(); });
        actions.add(change); actions.add(unbind); actions.add(reset);
        actions.add(new JLabel("Esc cancels capture; Backspace clears a binding."));
        add(actions, BorderLayout.SOUTH);
        setPreferredSize(new java.awt.Dimension(675, 420));
    }
    public Map<String, KeyStroke> getBindings() { return new LinkedHashMap<>(values); }
    private void refresh() { ((AbstractTableModel)table.getModel()).fireTableDataChanged(); }
    private void updateSelected(KeyStroke key) {
        int row = table.getSelectedRow();
        if (row < 0) return;
        ShortcutManager.Definition def = ShortcutManager.definitions().get(table.convertRowIndexToModel(row));
        LinkedHashMap<String, KeyStroke> attempt = new LinkedHashMap<>(values);
        attempt.put(def.id(), key);
        String problem = ShortcutManager.validate(attempt);
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Shortcut conflict", JOptionPane.WARNING_MESSAGE);
            return;
        }
        values.put(def.id(), key);
        refresh(); table.setRowSelectionInterval(row, row);
    }
    private void captureSelected() {
        if (table.getSelectedRow() < 0) return;
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Press a shortcut", JDialog.ModalityType.APPLICATION_MODAL);
        JLabel keyPrompt = new JLabel("Press your key combination. Escape cancels, Backspace clears.", JLabel.CENTER);
        keyPrompt.setFont(keyPrompt.getFont().deriveFont(Font.BOLD, 14f));
        dialog.add(keyPrompt);
        dialog.setSize(500, 120);
        dialog.setLocationRelativeTo(this);
        // Make the global dispatcher defer to this focused capture dialog.
        dialog.getRootPane().putClientProperty("maestro.shortcut.capture", true);
        keyPrompt.setFocusable(true);
        keyPrompt.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ESCAPE) { dialog.dispose(); return; }
                if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE) {
                    dialog.dispose(); updateSelected(null); return;
                }
                KeyStroke stroke = KeyStroke.getKeyStroke(code,
                    e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK |
                        InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK));
                if (!ShortcutManager.isBindable(stroke)) {
                    keyPrompt.setText("Space is reserved; choose a non-modifier key.");
                    return;
                }
                dialog.dispose(); updateSelected(stroke);
            }
        });
        SwingUtilities.invokeLater(keyPrompt::requestFocusInWindow);
        dialog.setVisible(true);
    }
    private final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return ShortcutManager.definitions().size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int column) { return switch (column) { case 0 -> "Category"; case 1 -> "Action"; default -> "Shortcut"; }; }
        @Override public Object getValueAt(int row, int col) {
            ShortcutManager.Definition d = ShortcutManager.definitions().get(row);
            return switch (col) { case 0 -> d.category(); case 1 -> d.title();
                default -> d.id().equals("transport.play") ? "Space (fixed)" +
                        (values.get(d.id()) == null ? "" : ", " + ShortcutManager.pretty(values.get(d.id())))
                        : ShortcutManager.pretty(values.get(d.id())); };
        }
    }
}
