package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der Fortschritt im Hintergrund der Statuszeile: füllt sich, bleibt bei 100 % kurz stehen und verschwindet dann. */
class StatusLineTest {

    @Test
    void fillsWithProgressAndFadesAfterCompletion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JProgressBar model = new JProgressBar();
            StatusLine line = new StatusLine(model, new JLabel("Download gestartet"), new JLabel(" "),
                    new JLabel("© 2026 AresStack"));
            assertThat(line.fillVisible()).isFalse();

            model.setMaximum(10);
            model.setValue(4);
            assertThat(line.fillVisible()).isTrue();
            assertThat(line.fraction()).isEqualTo(0.4);
            assertThat(line.fadeTimer().isRunning()).isFalse();

            model.setValue(10);
            assertThat(line.fillVisible()).isTrue();
            assertThat(line.fadeTimer().isRunning()).isTrue();
            assertThat(line.fadeTimer().getInitialDelay()).isEqualTo(StatusLine.FADE_DELAY_MS);

            // Das Ausblenden: der Timer feuert, der Balken ist weg; weitere Abfragen mit 100 % holen ihn nicht zurück.
            line.fadeTimer().stop();
            for (var listener : line.fadeTimer().getActionListeners()) {
                listener.actionPerformed(null);
            }
            assertThat(line.fillVisible()).isFalse();
            model.setMaximum(1);
            model.setValue(1);
            assertThat(line.fillVisible()).isFalse();
            assertThat(line.fadeTimer().isRunning()).isFalse();

            // Ein neuer Auftrag läuft wieder sichtbar.
            model.setIndeterminate(true);
            assertThat(line.fillVisible()).isTrue();
            model.setIndeterminate(false);
            model.setValue(0);
            assertThat(line.fillVisible()).isFalse();
        });
    }
}
