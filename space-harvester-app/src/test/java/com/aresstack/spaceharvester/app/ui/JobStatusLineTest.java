package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.event.ApplicationEventBus;
import com.aresstack.spaceharvester.app.event.JobProgressEvent;
import com.aresstack.spaceharvester.app.event.JobProgressEvent.Outcome;
import java.awt.Component;
import java.awt.Container;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Die Mitte der Statuszeile zeigt nur, was als {@link JobProgressEvent} auf dem Bus erscheint: den Gesamtstand aller
 * Aufträge, ohne eigene Knöpfe (Angelo 11.11.2026: Anhalten und Abbrechen stehen für alle in der Kopfzeile der
 * Aufträge).
 */
class JobStatusLineTest {

    @Test
    void rendersTheOverallStateWithoutButtons() throws Exception {
        ApplicationEventBus bus = new ApplicationEventBus();
        JobStatusLine[] line = new JobStatusLine[1];
        SwingUtilities.invokeAndWait(() -> {
            line[0] = new JobStatusLine(bus);
            assertThat(line[0].text.getText()).isEqualTo("Kein Auftrag läuft");
            assertThat(buttons(line[0])).as("keine Knöpfe mehr in der Statuszeile").isZero();

            bus.publish(new JobProgressEvent("Aufträge: 2 laufen · 40 %", 400, 1000, false, Outcome.RUNNING));
            assertThat(line[0].text.getText()).isEqualTo("Aufträge: 2 laufen · 40 %");
            assertThat(line[0].model().getValue()).isEqualTo(400);
            assertThat(line[0].model().getMaximum()).isEqualTo(1000);
            assertThat(line[0].endState.isVisible()).isFalse();

            bus.publish(new JobProgressEvent("Aufträge: 1 läuft", 0, 0, true, Outcome.RUNNING));
            assertThat(line[0].model().isIndeterminate()).isTrue();

            // Angehalten: der Balken bleibt stehen, wo er war, mittig „Angehalten“.
            bus.publish(new JobProgressEvent("Aufträge: 2 angehalten · 41 %", 410, 1000, false, Outcome.PAUSED));
            assertThat(line[0].model().isIndeterminate()).isFalse();
            assertThat(line[0].model().getValue()).isEqualTo(410);
            assertThat(line[0].endState.getText()).isEqualTo("Angehalten");

            bus.publish(new JobProgressEvent("Aufträge: 3 fertig", 0, 0, false, Outcome.COMPLETED));
            assertThat(line[0].model().getValue()).isEqualTo(line[0].model().getMaximum());
            assertThat(line[0].endState.isVisible()).isTrue();
            assertThat(line[0].endState.getText()).isEqualTo("Fertig");

            bus.publish(new JobProgressEvent("Aufträge: 1 fertig, 1 fehlgeschlagen", 0, 0, false, Outcome.FAILED));
            assertThat(line[0].endState.getText()).isEqualTo("Fehlgeschlagen");
            assertThat(line[0].model().getValue()).isZero();

            bus.publish(JobProgressEvent.none());
            assertThat(line[0].text.getText()).isEqualTo("Kein Auftrag läuft");
            assertThat(line[0].endState.isVisible()).isFalse();
            assertThat(buttons(line[0])).isZero();
        });
    }

    private static int buttons(Container container) {
        int count = 0;
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton) {
                count++;
            }
            if (child instanceof Container nested) {
                count += buttons(nested);
            }
        }
        return count;
    }
}
