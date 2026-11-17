package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.junit.jupiter.api.Test;

/** Angelo, 17.11.2026: Nach dem Schließen des Hilfefensters blieb das Fragezeichen gelb, bis man erneut darüberfuhr. */
class PopupHoverTest {

    @Test
    void theHelpButtonLosesItsHoverColourWhenItsPopupCloses() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HelpButton button = new HelpButton(24, "Updates", "Text");
            button.getModel().setRollover(true);
            for (PopupMenuListener listener : button.popup().getPopupMenuListeners()) {
                listener.popupMenuWillBecomeInvisible(new PopupMenuEvent(button.popup()));
            }
            assertThat(button.getModel().isRollover()).isFalse();
        });
    }
}
