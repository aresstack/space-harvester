package com.aresstack.spaceharvester.app.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * Eine Einstellung als Zeile: Bezeichnung, Wert, rechts ein Stift. Je nach Art bearbeitet der Stift den Wert direkt
 * in der Zeile (Textfeld mit Übernehmen/Verwerfen, Enter und Escape) oder öffnet einen passenden Dialog. Ohne Stift
 * ist die Zeile nur zur Ansicht. Gespeichert wird über {@link Saver}; scheitert es, bleibt die Eingabe stehen und der
 * Grund erscheint unter dem Feld.
 */
final class EditableProperty extends JPanel {

    /** Speichert einen neuen Wert und meldet das Ergebnis auf dem Swing-Thread. */
    interface Saver {
        void save(String value, Runnable saved, Consumer<Throwable> failed);
    }

    private static final int LABEL_WIDTH = 150;

    private final JLabel label = new JLabel();
    private final JTextArea value = Comic.note("");
    private final JTextArea error = Comic.note("");
    private final JPanel center = new JPanel(new BorderLayout(0, 2));
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    private final IconButton edit;
    private final IconButton accept = new IconButton(Glyph.ACCEPT, "Übernehmen (Enter)");
    private final IconButton cancel = new IconButton(Glyph.CANCEL, "Verwerfen (Escape)");
    private final JTextField field = Comic.field(20);
    private String current = "";
    private Saver saver;
    private Runnable action;
    private boolean editing;

    private EditableProperty(String name, String text, Color color, String tooltip) {
        super(new BorderLayout(10, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        label.setText(name);
        label.setFont(Comic.semiBold(12.5f));
        label.setForeground(Comic.INK);
        label.setVerticalAlignment(JLabel.TOP);
        label.setPreferredSize(new Dimension(LABEL_WIDTH, label.getPreferredSize().height));
        label.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        add(label, BorderLayout.WEST);
        value.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        error.setForeground(Comic.RED);
        error.setVisible(false);
        center.setOpaque(false);
        center.add(value, BorderLayout.CENTER);
        center.add(error, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
        buttons.setOpaque(false);
        edit = new IconButton(Glyph.PENCIL, tooltip == null ? name + " ändern" : tooltip);
        edit.setVisible(false);
        buttons.add(edit);
        buttons.add(accept);
        buttons.add(cancel);
        accept.setVisible(false);
        cancel.setVisible(false);
        add(buttons, BorderLayout.EAST);
        show(text, color);

        edit.addActionListener(e -> {
            if (action != null) {
                action.run();
            } else if (saver != null) {
                begin();
            }
        });
        accept.addActionListener(e -> commit());
        cancel.addActionListener(e -> end());
        field.addActionListener(e -> commit());
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelEdit");
        field.getActionMap().put("cancelEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                end();
            }
        });
        field.getAccessibleContext().setAccessibleName(name);
    }

    /** Nur zur Ansicht. */
    static EditableProperty view(String name, String text, Color color) {
        EditableProperty property = new EditableProperty(name, text, color, null);
        // Gleiche Zeilenhöhe wie mit Stift, damit die Werte untereinander stehen.
        property.buttons.add(Box.createRigidArea(property.edit.getPreferredSize()));
        return property;
    }

    /** Der Stift bearbeitet {@code initial} direkt in der Zeile. */
    static EditableProperty text(String name, String text, String initial, String tooltip, Saver saver) {
        EditableProperty property = new EditableProperty(name, text, Comic.INK, tooltip);
        property.current = initial == null ? "" : initial;
        property.saver = saver;
        property.edit.setVisible(true);
        return property;
    }

    /** Der Stift öffnet einen Dialog oder führt woandershin. */
    static EditableProperty action(String name, String text, Color color, String tooltip, Runnable action) {
        EditableProperty property = new EditableProperty(name, text, color, tooltip);
        property.action = action;
        property.edit.setVisible(true);
        return property;
    }

    boolean editing() {
        return editing;
    }

    String name() {
        return label.getText();
    }

    /** Wie ein Klick auf den Stift. */
    void edit() {
        edit.doClick();
    }

    JTextField field() {
        return field;
    }

    String error() {
        return error.isVisible() ? error.getText() : null;
    }

    private void show(String text, Color color) {
        value.setText(text == null || text.isBlank() ? "–" : text);
        value.setForeground(color == null ? Comic.INK : color);
    }

    private void begin() {
        editing = true;
        field.setText(current);
        field.setEnabled(true);
        center.remove(value);
        center.add(field, BorderLayout.CENTER);
        error.setVisible(false);
        edit.setVisible(false);
        accept.setVisible(true);
        cancel.setVisible(true);
        accept.setEnabled(true);
        refresh();
        SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
    }

    private void commit() {
        if (!editing || !field.isEnabled()) {
            return;
        }
        String typed = field.getText().trim();
        if (typed.equals(current.trim())) {
            end();
            return;
        }
        field.setEnabled(false);
        accept.setEnabled(false);
        error.setVisible(false);
        saver.save(typed, () -> {
            current = typed;
            end();
        }, failure -> {
            field.setEnabled(true);
            accept.setEnabled(true);
            error.setText(Problems.describe(failure).message());
            error.setVisible(true);
            refresh();
            field.requestFocusInWindow();
        });
    }

    private void end() {
        editing = false;
        center.remove(field);
        center.add(value, BorderLayout.CENTER);
        error.setVisible(false);
        edit.setVisible(true);
        accept.setVisible(false);
        cancel.setVisible(false);
        refresh();
    }

    private void refresh() {
        revalidate();
        repaint();
    }
}
