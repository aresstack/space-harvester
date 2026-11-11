package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Dimension;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Codex zu #570: lange Überschriften brechen um, viele Zeilen scrollen statt über den Bildschirm hinauszuwachsen. */
class InfoPopupTest {

    @Test
    void aLongHeadingWrapsAndManyLinesScrollWithinTheAvailableHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            InfoPopup popup = new InfoPopup();
            popup.text("Kurz", "Eine Zeile");
            int oneLine = popup.sizeFor(600).height;

            popup.text("Dokument „" + "Sehr langer Seitentitel ".repeat(12) + "“", "Eine Zeile");
            Dimension wrapped = popup.sizeFor(600);
            assertThat(wrapped.width).isEqualTo(InfoPopup.MAX_WIDTH);
            assertThat(wrapped.height).as("die Überschrift bricht um und wird mitgezählt").isGreaterThan(oneLine + 10);
            assertThat(popup.scrolls()).isFalse();

            popup.text("Erwähnung: Beleg", "• Kante\n".repeat(49));
            Dimension tall = popup.sizeFor(500);
            assertThat(tall.height).isLessThanOrEqualTo(500);
            assertThat(popup.scrolls()).as("der Text scrollt, Überschrift und Links bleiben stehen").isTrue();
        });
    }

    @Test
    void enterOnTheLinkRunsItsAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> ran = new java.util.ArrayList<>();
            InfoPopup popup = new InfoPopup();
            popup.text("Titel", "Text").clearLinks().link("Zum Beleg im Text", () -> ran.add("beleg"));
            popup.pressEnterOnLink(0);
            assertThat(ran).containsExactly("beleg");
            assertThat(popup.linkTexts()).containsExactly("Zum Beleg im Text");
        });
    }
}
