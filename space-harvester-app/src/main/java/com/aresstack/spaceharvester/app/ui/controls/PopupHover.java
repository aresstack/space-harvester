package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.AbstractButton;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.util.Objects;

/**
 * Nimmt einem Knopf die Hover-Farbe, sobald das Menü oder Hinweisfenster schließt, das er geöffnet hat. Solange es
 * offen ist, bekommt der Knopf das Verlassen der Maus nicht mit; ohne das blieb er gelb, bis man erneut darüber fuhr
 * (Angelo, 17.11.2026).
 */
public final class PopupHover {

    private PopupHover() {
    }

    /** Hängt das Zurücksetzen an {@code popup} und gibt es zurück, damit es gleich gezeigt werden kann. */
    public static <P extends JPopupMenu> P clearOnClose(P popup, AbstractButton button) {
        Objects.requireNonNull(button, "button");
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                button.repaint();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                clear(button);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                clear(button);
            }
        });
        return popup;
    }

    private static void clear(AbstractButton button) {
        // Steht die Maus noch auf dem Knopf, bleibt er hervorgehoben; sonst nicht mehr.
        button.getModel().setRollover(button.isShowing() && button.getMousePosition() != null);
        button.getModel().setPressed(false);
        button.repaint();
    }
}
