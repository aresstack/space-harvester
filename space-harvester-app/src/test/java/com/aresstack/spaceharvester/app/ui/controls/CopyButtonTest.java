package com.aresstack.spaceharvester.app.ui.controls;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyButtonTest {

    @Test
    void aDeferredButtonCopiesOnceItsTextArrives() throws Exception {
        List<String> clipboard = new ArrayList<>();
        List<Consumer<String>> waiting = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            CopyButton button = CopyButton.deferred("Transkript kopieren", waiting::add, clipboard::add);
            assertEquals("Transkript kopieren", button.getToolTipText());
            button.doClick();
            // Der Text wird erst geholt: noch nichts kopiert, kein Haken.
            assertTrue(clipboard.isEmpty());
            assertFalse(button.copiedShown());
            // Ohne Text bleibt die Zwischenablage, wie sie ist.
            waiting.get(0).accept("");
            assertTrue(clipboard.isEmpty());
            assertFalse(button.copiedShown());
            waiting.get(0).accept("Gastgeber: Willkommen.");
            assertEquals(List.of("Gastgeber: Willkommen."), clipboard);
            assertTrue(button.copiedShown());
            assertEquals(CopyButton.COPIED, button.getToolTipText());
        });
    }
}
