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

    /** Zwei Hinweise zugleich (Einmalcode und Proxy-Skript) teilen sich die Breite, statt umzubrechen (Codex zu #801). */
    @Test
    void twoAlertsShareTheWidthInsteadOfWrapping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel signIn = new JLabel("Confluence HaVdB, confluence.example.org verlangen einen Einmalcode");
            JLabel proxy = new JLabel("Proxy-Skript seit 16.11.2026 17:03:12 nicht erneuert");
            JLabel hidden = new JLabel("unsichtbar");
            hidden.setVisible(false);
            javax.swing.JPanel alerts = StatusLine.alerts(signIn, proxy, hidden);
            StatusLine line = new StatusLine(new JProgressBar(), alerts, new JLabel("Seite 3"), new JLabel(" "),
                    new JLabel(" "), new JLabel("© 2026 AresStack"));
            // Ohne Fenster (headless): Größe setzen und von oben nach unten auslegen.
            line.setSize(960, 32);
            layOut(line);
            int rowHeight = alerts.getHeight();
            assertThat(signIn.getWidth()).isGreaterThan(0);
            assertThat(proxy.getWidth()).isGreaterThan(0);
            assertThat(proxy.getParent().getY()).as("in derselben Zeile").isLessThan(rowHeight);
            assertThat(proxy.getParent().getY() + proxy.getParent().getHeight()).isLessThanOrEqualTo(rowHeight);
            // Der Abstand (12) liegt im Rahmen des zweiten Hinweises, der Text beginnt dahinter.
            assertThat(proxy.getParent().getX() + proxy.getX()).isGreaterThan(signIn.getParent().getX()
                    + signIn.getWidth());
            assertThat(hidden.getParent().getWidth()).as("unsichtbar ohne Platz").isZero();
        });
    }

    private static void layOut(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layOut(inner);
            }
        }
    }
}
