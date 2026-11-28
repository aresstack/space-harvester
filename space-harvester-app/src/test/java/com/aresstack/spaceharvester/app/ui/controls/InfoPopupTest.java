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
    void aShortHeadingTakesTheFullWidthAndDoesNotBreakInsideAWord() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            InfoPopup popup = new InfoPopup();
            popup.text("Updates", "Ein Update entsteht mit jeder manuellen Synchronisierung. ".repeat(8));
            Dimension size = popup.sizeFor(600);
            popup.setSize(size);
            popup.doLayout();
            javax.swing.JComponent content = (javax.swing.JComponent) popup.getComponent(0);
            content.setSize(content.getPreferredSize());
            content.doLayout();
            WrappedText title = (WrappedText) content.getComponent(0);
            int inner = size.width - 2 * 12;
            assertThat(title.getWidth()).as("die Überschrift bekommt die ganze Breite").isEqualTo(inner);
            int oneLine = title.getFontMetrics(title.getFont()).getHeight();
            assertThat(title.getHeight()).as("„Updates“ bleibt in einer Zeile").isLessThan(2 * oneLine);
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

    /** Angelo 28.11.2026: Viele Fundstellen standen in einer Zeile und waren ab der dritten abgeschnitten. */
    @Test
    void linksThatDoNotFitSideBySideStackAndScrollWhenThereAreMany() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            InfoPopup popup = new InfoPopup();
            popup.text("Fundstellen von „Sammelkonto“", "2 Seiten").link("Zu Beleg 1", () -> { })
                    .link("Zu Beleg 2", () -> { });
            popup.sizeFor(600);
            assertThat(popup.linksStacked()).as("kurze Links bleiben nebeneinander").isFalse();

            popup.clearLinks().text("Fundstellen von „Sammelkonto“", "11 Seiten");
            for (int i = 1; i <= 11; i++) {
                popup.link("Erfundene Seite Nummer " + i + " mit einem langen Titel · 1 Fundstelle", () -> { });
            }
            Dimension size = popup.sizeFor(600);
            assertThat(popup.linksStacked()).isTrue();
            assertThat(popup.linksScroll()).as("elf Zeilen passen in 600 Pixel").isFalse();
            assertThat(size.width).isLessThanOrEqualTo(InfoPopup.MAX_WIDTH);
            assertThat(popup.linkTexts()).hasSize(11);

            Dimension low = popup.sizeFor(200);
            assertThat(low.height).isLessThanOrEqualTo(200);
            assertThat(popup.linksScroll()).as("bei wenig Platz scrollen die Links").isTrue();

            // Viele Links kosten keine quadratische Zeit: ausgelegt wird einmal, nicht je Link (Codex zu #1755).
            popup.clearLinks();
            for (int i = 0; i < 4000; i++) {
                popup.link("Seite " + i, () -> { });
            }
            assertThat(popup.linkTexts()).hasSize(4000);
            assertThat(popup.sizeFor(600).height).isLessThanOrEqualTo(600);
        });
    }
}
