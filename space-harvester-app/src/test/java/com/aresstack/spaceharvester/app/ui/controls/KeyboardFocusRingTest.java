package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** AT zu #854: Auch der Kopier-Knopf blieb nach einem Mausklick gelb, bis der Fokus weiterzog. */
class KeyboardFocusRingTest {

    @Test
    void theCopyButtonShowsItsCircleForTabFocusButNotAfterAClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CopyButton button = new CopyButton(() -> "Text", new ArrayList<String>()::add);

            for (MouseListener listener : button.getMouseListeners()) {
                listener.mousePressed(new MouseEvent(button, MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false));
            }
            focus(button, FocusEvent.Cause.MOUSE_EVENT);
            assertThat(button.showsKeyboardFocus()).as("kein Kreis nach Mausklick").isFalse();

            focus(button, FocusEvent.Cause.TRAVERSAL_FORWARD);
            assertThat(button.showsKeyboardFocus()).as("Tab zeigt den Kreis").isTrue();

            for (FocusListener listener : button.getFocusListeners()) {
                listener.focusLost(new FocusEvent(button, FocusEvent.FOCUS_LOST));
            }
            assertThat(button.showsKeyboardFocus()).isFalse();
        });
    }

    private static void focus(CopyButton button, FocusEvent.Cause cause) {
        FocusEvent event = new FocusEvent(button, FocusEvent.FOCUS_GAINED, false, null, cause);
        for (FocusListener listener : button.getFocusListeners()) {
            listener.focusGained(event);
        }
    }
}
