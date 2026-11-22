package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JComponent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * Merkt sich für einen runden Knopf (oder ein anderes fokussierbares Element wie {@link SectionHeader}), ob sein Fokus über die Tastatur kam (Tab, Umschalt+Tab). Nur dann zeigt der
 * Knopf ihn mit seinem Kreis. Nach einem Mausklick, und wenn ein Menü den Fokus beim Schließen zurückgibt, bleibt er
 * durchsichtig; sonst blieb er gelb, bis der Fokus weiterzog (Angelo, 17.11.2026).
 */
public final class KeyboardFocusRing {

    private final JComponent button;
    private boolean keyboard;

    private KeyboardFocusRing(JComponent button) {
        this.button = button;
    }

    /** Hängt die Beobachtung an {@code button}; dessen {@code paintComponent} fragt danach {@link #shown()}. */
    public static KeyboardFocusRing install(JComponent button) {
        KeyboardFocusRing ring = new KeyboardFocusRing(Objects.requireNonNull(button, "button"));
        button.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                ring.keyboard = switch (e.getCause()) {
                    case TRAVERSAL, TRAVERSAL_FORWARD, TRAVERSAL_BACKWARD, TRAVERSAL_UP, TRAVERSAL_DOWN -> true;
                    default -> false;
                };
                button.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                ring.keyboard = false;
                button.repaint();
            }
        });
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                ring.keyboard = false;
            }
        });
        return ring;
    }

    /** Ob der Kreis den Fokus jetzt zeigt: Der Knopf hat ihn, und er kam über die Tastatur. */
    public boolean shown() {
        return keyboard && button.hasFocus();
    }

    /** Ob der Fokus zuletzt über die Tastatur kam, unabhängig davon, ob der Knopf ihn noch hat (für Tests). */
    public boolean fromKeyboard() {
        return keyboard;
    }
}
