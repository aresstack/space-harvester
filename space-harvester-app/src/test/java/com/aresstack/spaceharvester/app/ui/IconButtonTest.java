package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.junit.jupiter.api.Test;

/** Angelo, 17.11.2026: ⋯ blieb nach dem Menü gelb, weil das Menü den Fokus an den Knopf zurückgab. */
class IconButtonTest {

    @Test
    void afterAMenuOpenedByMouseClosesTheButtonShowsNoCircleButTabFocusStillDoes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IconButton button = new IconButton(Glyph.MORE, "Weitere Aktionen");
            JPopupMenu menu = PopupHover.clearOnClose(new JPopupMenu(), button);

            // Per Maus geöffnet, Maus weg, Menü zu; das Menü gibt den Fokus zurück.
            button.getModel().setRollover(true);
            for (MouseListener listener : button.getMouseListeners()) {
                listener.mousePressed(new MouseEvent(button, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false));
            }
            for (PopupMenuListener listener : menu.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeInvisible(new PopupMenuEvent(menu));
            }
            focus(button, FocusEvent.Cause.ACTIVATION);
            assertThat(button.getModel().isRollover()).isFalse();
            assertThat(button.showsKeyboardFocus()).as("kein Kreis nach dem Menü").isFalse();

            focus(button, FocusEvent.Cause.MOUSE_EVENT);
            assertThat(button.showsKeyboardFocus()).as("kein Kreis nach Mausklick").isFalse();

            focus(button, FocusEvent.Cause.TRAVERSAL_FORWARD);
            assertThat(button.showsKeyboardFocus()).as("Tab zeigt den Kreis").isTrue();
            focus(button, FocusEvent.Cause.TRAVERSAL_BACKWARD);
            assertThat(button.showsKeyboardFocus()).as("Umschalt+Tab zeigt den Kreis").isTrue();
        });
    }

    private static void focus(IconButton button, FocusEvent.Cause cause) {
        FocusEvent event = new FocusEvent(button, FocusEvent.FOCUS_GAINED, false, null, cause);
        for (FocusListener listener : button.getFocusListeners()) {
            listener.focusGained(event);
        }
    }
}
