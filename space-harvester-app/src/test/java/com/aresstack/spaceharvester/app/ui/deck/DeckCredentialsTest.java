package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** „Zugangsdaten ändern“ (Angelo, 24.11.2026): derselbe Schlüssel und derselbe Menüeintrag in jedem Bereich. */
class DeckCredentialsTest {

    @Test
    void keyAndMenuCallTheSameEdit() throws Exception {
        AtomicInteger edits = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            var button = DeckCredentials.button(edits::incrementAndGet);
            assertThat(button.getToolTipText()).isEqualTo(DeckCredentials.TIP);
            assertThat(button.getText()).as("nur ein Symbol, kein Textknopf").isNullOrEmpty();
            button.doClick();
            JMenuItem item = (JMenuItem) DeckCredentials.popupMenu(edits::incrementAndGet).getComponent(0);
            assertThat(item.getText()).isEqualTo(DeckCredentials.MENU_TEXT);
            item.doClick();
        });
        assertThat(edits.get()).isEqualTo(2);
    }

    @Test
    void theLockReasonIsShownOnlyForALockedEntry() {
        StringBuilder shown = new StringBuilder();
        String entry = "DeckCredentialsTest-" + System.nanoTime();
        try {
            DeckCredentials.showLock(entry, shown::append);
            assertThat(shown).isEmpty();
            DeckLogin.guard().failed(entry, "Kennwort abgelaufen");
            DeckCredentials.showLock(entry, shown::append);
            assertThat(shown.toString()).contains("Kennwort abgelaufen");
        } finally {
            DeckLogin.guard().succeeded(entry);
        }
    }
}
