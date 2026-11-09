package com.aresstack.spaceharvester.app.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPasswordField;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Insets;

/**
 * Auge-Symbol rechts in einem {@link JPasswordField}: schaltet zwischen maskierter und sichtbarer Anzeige um.
 * Standard ist maskiert. Nur die Anzeige ändert sich; der Inhalt wird weder gelesen noch gespeichert.
 */
public final class PasswordReveal {

    static final String SHOW = "👁";
    static final String TOOLTIP_SHOW = "Anzeigen";
    static final String TOOLTIP_HIDE = "Verbergen";

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
            toggle.setToolTipText(masked ? TOOLTIP_HIDE : TOOLTIP_SHOW);
            toggle.getAccessibleContext().setAccessibleName(masked ? "Passwort verbergen" : "Passwort anzeigen");
        });
        field.setEchoChar(mask);
        field.setLayout(new BorderLayout());
        field.add(toggle, BorderLayout.EAST);
        int width = toggle.getPreferredSize().width;
        Border current = field.getBorder();
        Border room = BorderFactory.createEmptyBorder(0, 0, 0, width);
        field.setBorder(current == null ? room : BorderFactory.createCompoundBorder(current, room));
        return toggle;
    }
}
