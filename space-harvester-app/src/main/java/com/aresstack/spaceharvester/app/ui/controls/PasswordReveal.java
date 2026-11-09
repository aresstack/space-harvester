package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPasswordField;
import javax.swing.border.Border;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Cursor;
import java.awt.Insets;

/**
 * Auge-Symbol rechts in einem {@link JPasswordField}: schaltet zwischen maskierter und sichtbarer Anzeige um.
 * Standard ist maskiert. Nur die Anzeige ändert sich; der Inhalt wird weder gelesen noch gespeichert.
 */
public final class PasswordReveal {

    public static final String SHOW = "👁";
    public static final String TOOLTIP_SHOW = "Anzeigen";
    public static final String TOOLTIP_HIDE = "Verbergen";

    private PasswordReveal() {
    }

    /** Installiert den Umschalter im Feld; nach dem Styling aufrufen. Gibt den Knopf zurück. */
    public static JButton install(JPasswordField field) {
        char mask = field.getEchoChar() == 0 ? '•' : field.getEchoChar();
        JButton toggle = new JButton(SHOW);
        toggle.setName("passwordReveal");
        toggle.setFocusable(false);
        toggle.setBorderPainted(false);
        toggle.setContentAreaFilled(false);
        toggle.setOpaque(false);
        toggle.setMargin(new Insets(0, 4, 0, 4));
        toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggle.setToolTipText(TOOLTIP_SHOW);
        toggle.getAccessibleContext().setAccessibleName("Passwort anzeigen");
        toggle.addActionListener(e -> {
            boolean masked = field.getEchoChar() != 0;
            field.setEchoChar(masked ? (char) 0 : mask);
            toggle.setToolTipText((masked ? TOOLTIP_HIDE : TOOLTIP_SHOW) + " (Alt+V)");
            toggle.getAccessibleContext().setAccessibleName(masked ? "Passwort verbergen" : "Passwort anzeigen");
        });
        field.setEchoChar(mask);
        int width = toggle.getPreferredSize().width;
        field.setLayout(new InsetLayout(toggle, width));
        field.add(toggle);
        field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("alt V"), "passwordReveal");
        field.getActionMap().put("passwordReveal", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                toggle.doClick(0);
            }
        });
        toggle.setToolTipText(TOOLTIP_SHOW + " (Alt+V)");
        Border current = field.getBorder();
        Border room = BorderFactory.createEmptyBorder(0, 0, 0, width);
        field.setBorder(current == null ? room : BorderFactory.createCompoundBorder(current, room));
        return toggle;
    }

    /** Legt den Knopf in den rechts reservierten Rand, nie in den Textbereich. */
    private record InsetLayout(Component button, int width) implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) { }
        @Override public Dimension preferredLayoutSize(Container parent) { return new Dimension(); }
        @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(); }
        @Override public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            button.setBounds(parent.getWidth() - in.right, in.top, width,
                    Math.max(0, parent.getHeight() - in.top - in.bottom));
        }
    }
}
