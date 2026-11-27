package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.group.GroupColor;
import com.aresstack.spaceharvester.app.project.ChatGroup;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.ColorSwatches;
import com.aresstack.spaceharvester.app.ui.controls.GroupColors;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.function.BiConsumer;

/**
 * Das kleine Gruppen-Popup (UX-Strang, 27.11.2026): Namensfeld, darunter die Farbwahl; Neu und Umbenennen in einem, kein
 * eigener Dialog. Enter übernimmt, Esc schließt ohne Änderung.
 */
final class GroupEditor extends JPopupMenu {

    final JTextField name = new JTextField(22);
    final ColorSwatches swatches;
    private final BiConsumer<String, GroupColor> accept;

    /**
     * @param group  die bisherige Gruppe oder {@code null} für eine neue
     * @param color  die vorgeschlagene Farbe einer neuen Gruppe
     * @param accept bekommt Name und Farbe beim Übernehmen
     */
    GroupEditor(ChatGroup group, GroupColor color, BiConsumer<String, GroupColor> accept) {
        this.accept = accept;
        GroupColor start = group == null ? color : group.color();
        swatches = new ColorSwatches(GroupColors.of(start), value -> GroupColor.valueOf(value.name()).label());
        name.setText(group == null ? "" : group.name());
        Comic.style(name);
        name.addActionListener(e -> commit());
        name.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-editor");
        name.getActionMap().put("close-editor", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setVisible(false);
            }
        });
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        body.add(Comic.muted(group == null ? "Neue Gruppe" : "Gruppe"), BorderLayout.NORTH);
        body.add(name, BorderLayout.CENTER);
        body.add(swatches, BorderLayout.SOUTH);
        add(body);
    }

    /** Übernimmt Name und Farbe; ein leerer Name übernimmt nichts. */
    void commit() {
        String text = name.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        setVisible(false);
        accept.accept(text, GroupColor.valueOf(swatches.selected().name()));
    }

    void open(Component anchor, int x, int y) {
        show(anchor, x, y);
        SwingUtilities.invokeLater(() -> {
            name.requestFocusInWindow();
            name.selectAll();
        });
    }
}
