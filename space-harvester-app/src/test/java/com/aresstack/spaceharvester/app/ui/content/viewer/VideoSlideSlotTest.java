package com.aresstack.spaceharvester.app.ui.content.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * UX 21.11.2026: Solange „Folienwechsel erkennen“ wartet oder läuft, dreht an der Stelle der Folienknöpfe ein kleiner
 * Ring, gleich breit wie die Knöpfe; danach die Knöpfe, ohne Ergebnis gesperrt mit dem Grund im Tooltip.
 */
final class VideoSlideSlotTest {

    @Test
    void theRingHoldsThePlaceOfTheSlideButtonsAndGivesWayToThemOnceMarksAreKnown() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTransportBar bar = new VideoTransportBar(value -> { });
            AtomicInteger opened = new AtomicInteger();
            assertThat(bar.slideSlot().isVisible()).as("ohne Marken und ohne Erkennung kein Platz").isFalse();
            int buttonsWidth = bar.slideSlot().getPreferredSize().width;

            bar.slidesDetecting("Folienwechsel werden erkannt …", opened::incrementAndGet);

            assertThat(bar.slideRingShown()).isTrue();
            assertThat(bar.slideSlot().getPreferredSize().width).as("nichts verrutscht").isEqualTo(buttonsWidth);
            assertThat(bar.slideRing().ringControl().getPreferredSize().width).isEqualTo(16);

            bar.slides(List.of(4_000L, 9_000L));

            assertThat(bar.slideRingShown()).isFalse();
            assertThat(bar.previousSlide().isShowing() || bar.previousSlide().isVisible()).isTrue();
            assertThat(bar.previousSlide().isEnabled()).isTrue();
            assertThat(bar.nextSlide().getToolTipText()).contains("2 Folienwechsel erkannt");
        });
    }

    @Test
    void withoutAResultTheSlideButtonsStayDisabledWithTheReason() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTransportBar bar = new VideoTransportBar(value -> { });
            bar.slidesDetecting("wartet", () -> { });

            bar.slidesUnavailable("Folienwechsel nicht erkannt: Auftrag abgebrochen");

            assertThat(bar.slideSlot().isVisible()).isTrue();
            assertThat(bar.slideRingShown()).isFalse();
            assertThat(bar.previousSlide().isEnabled()).isFalse();
            assertThat(bar.nextSlide().getToolTipText()).isEqualTo("Folienwechsel nicht erkannt: Auftrag abgebrochen");

            bar.noSlides();
            assertThat(bar.slideSlot().isVisible()).isFalse();
        });
    }

    @Test
    void aFailureShowsARedPlateAndACancelOrPauseAYellowOne() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MessagePlate plate = new MessagePlate();
            AtomicInteger retried = new AtomicInteger();

            plate.showError("Folienwechsel nicht erkannt: Index nicht lesbar", "wiederholen", retried::incrementAndGet);
            assertThat(plate.error()).isTrue();

            plate.show("Folienwechsel nicht erkannt: Auftrag abgebrochen", "wiederholen", retried::incrementAndGet);
            assertThat(plate.error()).as("gelb").isFalse();
            plate.clickAction();
            assertThat(retried).hasValue(1);
            assertThat(plate.isVisible()).isFalse();
        });
    }
}
