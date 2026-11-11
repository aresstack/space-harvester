package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.event.ApplicationEventBus;
import com.aresstack.spaceharvester.app.event.JobProgressEvent;
import com.aresstack.spaceharvester.app.event.JobProgressEvent.Outcome;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Die Mitte der Statuszeile zeigt nur, was als {@link JobProgressEvent} auf dem Bus erscheint. */
class JobStatusLineTest {

    @Test
    void rendersOnlyPublishedJobEvents() throws Exception {
        ApplicationEventBus bus = new ApplicationEventBus();
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger paused = new AtomicInteger();
        JobStatusLine[] line = new JobStatusLine[1];
        SwingUtilities.invokeAndWait(() -> {
            line[0] = new JobStatusLine(bus, cancelled::incrementAndGet, paused::incrementAndGet);
            assertThat(line[0].text.getText()).isEqualTo("Kein Auftrag läuft");
            assertThat(line[0].cancel.isVisible()).isFalse();
            assertThat(line[0].pause.isVisible()).isFalse();
            assertThat(line[0].resume.isVisible()).isFalse();

            bus.publish(new JobProgressEvent("j1", "Download HB · 40 von 127", 40, 127, false, Outcome.RUNNING, false, true));
            assertThat(line[0].text.getText()).isEqualTo("Download HB · 40 von 127");
            assertThat(line[0].model().getValue()).isEqualTo(40);
            assertThat(line[0].model().getMaximum()).isEqualTo(127);
            assertThat(line[0].cancel.isVisible()).isTrue();
            line[0].cancel.doClick();
            assertThat(cancelled).hasValue(1);
            // Läuft und lässt sich anhalten: Pause neben Abbrechen, kein Play.
            assertThat(line[0].pause.isVisible()).isTrue();
            assertThat(line[0].resume.isVisible()).isFalse();
            line[0].pause.doClick();
            assertThat(paused).hasValue(1);

            bus.publish(new JobProgressEvent("j1", "Download HB", 0, 0, true, Outcome.RUNNING, true, true));
            assertThat(line[0].model().isIndeterminate()).isTrue();
            assertThat(line[0].cancel.isEnabled()).isFalse();
            assertThat(line[0].cancel.getText()).isEqualTo("Abbruch angefordert");
            assertThat(line[0].pause.isEnabled()).isFalse();

            bus.publish(new JobProgressEvent("j1", "Download HB", 0, 0, false, Outcome.COMPLETED, false, false));
            assertThat(line[0].model().isIndeterminate()).isFalse();
            assertThat(line[0].model().getValue()).isEqualTo(line[0].model().getMaximum());
            assertThat(line[0].endState.isVisible()).isTrue();
            assertThat(line[0].endState.getText()).isEqualTo("Fertig");
            assertThat(line[0].cancel.isVisible()).isFalse();

            bus.publish(new JobProgressEvent("j2", "Export X", 0, 0, false, Outcome.FAILED, false, true));
            assertThat(line[0].endState.getText()).isEqualTo("Fehlgeschlagen");
            assertThat(line[0].resume.isVisible()).isFalse();

            // Angehalten: „Angehalten“ und Play, weder Pause noch Abbrechen.
            bus.publish(new JobProgressEvent("j2", "Export X", 0, 0, false, Outcome.PAUSED, false, true));
            assertThat(line[0].endState.getText()).isEqualTo("Angehalten");
            assertThat(line[0].resume.isVisible()).isTrue();
            assertThat(line[0].pause.isVisible()).isFalse();
            assertThat(line[0].cancel.isVisible()).isFalse();
            line[0].resume.doClick();
            assertThat(paused).hasValue(2);

            // Läuft, lässt sich aber nicht anhalten (andere Auftragsart): nur Abbrechen.
            bus.publish(new JobProgressEvent("j3", "Anreichern", 0, 0, true, Outcome.RUNNING, false, false));
            assertThat(line[0].cancel.isVisible()).isTrue();
            assertThat(line[0].pause.isVisible()).isFalse();
        });
    }
}
