package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Das Angebot mitten in einer leeren Fläche (Angelo 28.11.2026). */
class CenterOfferTest {

    @Test
    void theButtonStandsInTheMiddleAndLeavesTheViewBelowToTheMouse() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel();
            AtomicInteger accepted = new AtomicInteger();
            CenterOffer offer = new CenterOffer(content, "Jetzt analysieren", "Analyse starten")
                    .onAccept(accepted::incrementAndGet);
            offer.setSize(400, 300);
            layout(offer);
            assertThat(offer.offered()).as("anfangs kein Angebot").isFalse();
            assertThat(offer.findComponentAt(200, 150)).isSameAs(content);

            offer.offer(true);
            layout(offer);
            assertThat(offer.offered()).isTrue();
            assertThat(content.getSize()).as("die Ansicht füllt die Fläche weiter").isEqualTo(offer.getSize());
            java.awt.Rectangle button = SwingUtilities.convertRectangle(offer.button().getParent(),
                    offer.button().getBounds(), offer);
            assertThat(button.getCenterX()).isCloseTo(200, org.assertj.core.data.Offset.offset(1.0));
            assertThat(button.getCenterY()).isCloseTo(150, org.assertj.core.data.Offset.offset(1.0));
            assertThat(offer.findComponentAt(200, 150)).isSameAs(offer.button());
            assertThat(offer.findComponentAt(10, 10)).as("neben dem Knopf gehört die Maus der Ansicht")
                    .isSameAs(content);
            assertThat(offer.button().getToolTipText()).isEqualTo("Analyse starten");

            offer.button().doClick();
            assertThat(accepted).hasValue(1);

            offer.offer(false);
            assertThat(offer.findComponentAt(200, 150)).isSameAs(content);
        });
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layout(inner);
            }
        }
    }
}
