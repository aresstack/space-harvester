package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
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

    /**
     * Der Stand des Bereichs reicht bis an das Copyright, solange die Mitte nichts braucht, und kürzt sich erst dort
     * (UX 22.11.2026: vorher nach etwa der halben Breite gekürzt); Rot samt Kreispfeil bleibt dabei ganz.
     */
    @Test
    void theLeadFillsTheWidthUpToTheCopyrightAndOnlyThenCuts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel lead = new StatusLabel();
            String failed = "Vorstufe „Aussagenanalyse“ gescheitert in ‚DEV‘";
            String skipped = "„Bilder beschreiben“ ist abgeschaltet und wurde bewusst ausgelassen";
            String tail = "Einzelheiten unter Hintergrund";
            String text = "Wissensgraph mit Fehler beendet · " + failed + " · " + skipped + " · " + tail;
            lead.show(new StatusNote(text, StatusNote.Tone.ERROR, "Hintergrund", () -> { },
                    List.of(new StatusNote.Mark(skipped, StatusNote.Tone.WARNING),
                            new StatusNote.Mark(tail, StatusNote.Tone.PLAIN)),
                    new StatusNote.IconLink(Glyph.REFRESH, failed, "„Aussagenanalyse“ wiederholen", () -> { })));
            JLabel east = new JLabel("© 2026 AresStack");
            JPanel alert = new JPanel();
            alert.setOpaque(false);
            StatusLine line = new StatusLine(new JProgressBar(), alert, lead, new JLabel(" "), new JLabel(" "), east);
            int whole = lead.getPreferredSize().width;

            // Breit genug: alles steht, nichts gekürzt.
            line.setSize(whole + east.getPreferredSize().width + 120, 28);
            layOut(line);
            assertThat(lead.getWidth()).isEqualTo(whole);
            assertThat(lead.getText()).doesNotContain("…");

            // Schmaler: der Stand nimmt alles bis an das Copyright (weit über 60 % der Breite) und kürzt sich erst dort.
            int width = whole * 3 / 4 + east.getPreferredSize().width;
            line.setSize(width, 28);
            layOut(line);
            int leadRight = SwingUtilities.convertRectangle(lead.getParent(), lead.getBounds(), line).x
                    + lead.getWidth();
            assertThat(lead.getWidth()).as("mehr als der alte Anteil").isGreaterThan((int) (width * 0.6));
            assertThat(east.getX() - leadRight).as("bis an das Copyright, nur die Abstände dazwischen")
                    .isLessThanOrEqualTo(40);
            assertThat(lead.getText()).contains(failed).contains("&nbsp;").endsWith("…</nobr></html>");
            assertThat(lead.getToolTipText()).isEqualTo(text);

            // Knapp: das Gelbe wird gekürzt, der Fehler samt Kreispfeil bleibt ganz.
            line.setSize(lead.keptWidth() + east.getPreferredSize().width + 200, 28);
            layOut(line);
            leadRight = SwingUtilities.convertRectangle(lead.getParent(), lead.getBounds(), line).x + lead.getWidth();
            assertThat(lead.getWidth()).as("nie schmaler als der Fehler samt Kreispfeil").isGreaterThanOrEqualTo(
                    lead.keptWidth());
            assertThat(leadRight).as("links vom Copyright").isLessThanOrEqualTo(east.getX());
            assertThat(lead.getText()).contains(failed).contains("&nbsp;").doesNotContain(skipped)
                    .endsWith("…</nobr></html>");

            // Sehr schmal: nie über das Copyright hinaus, es bleibt sichtbar und anklickbar.
            line.setSize(lead.keptWidth() / 2 + east.getPreferredSize().width, 28);
            layOut(line);
            assertCopyrightFree(line, lead, east);
        });
    }

    /**
     * Mehrere Fehler (je Stufe und Space) und Gelbes in einer Statuszeile in der kleinsten Breite des Hauptfensters:
     * der linke Teil reicht nie über das Copyright, der erste Fehler samt Kreispfeil bleibt ganz, gekürzt wird hinten.
     */
    @Test
    void manyFailuresNeverRunIntoTheCopyright() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel lead = new StatusLabel();
            String failed = "Vorstufe „Aussagenanalyse“ gescheitert in ‚DEV‘";
            String skipped = "„Bilder beschreiben“ ist abgeschaltet und wurde bewusst ausgelassen";
            String hidden = "3 Seiten ohne Zugriff ausgelassen";
            String tail = "Einzelheiten unter Hintergrund";
            String text = "Wissensgraph mit Fehler beendet · " + failed
                    + " · Vorstufe „Aussagenanalyse“ gescheitert in ‚Plan‘"
                    + " · Vorstufe „Aussagenanalyse“ gescheitert in ‚Ziel‘ · " + skipped + " · " + hidden + " · "
                    + tail;
            lead.show(new StatusNote(text, StatusNote.Tone.ERROR, "Hintergrund", () -> { },
                    List.of(new StatusNote.Mark(skipped, StatusNote.Tone.WARNING),
                            new StatusNote.Mark(tail, StatusNote.Tone.PLAIN)),
                    new StatusNote.IconLink(Glyph.REFRESH, failed, "„Aussagenanalyse“ wiederholen", () -> { })));
            JLabel east = new JLabel("© 2026 AresStack");
            JPanel alert = new JPanel();
            alert.setOpaque(false);
            StatusLine line = new StatusLine(new JProgressBar(), alert, lead, new JLabel(" "), new JLabel(" "), east);
            for (int width : new int[] {960, 1200}) {
                line.setSize(width, 28);
                layOut(line);
                assertCopyrightFree(line, lead, east);
                assertThat(lead.getText()).as("bei %d", width).contains(failed).contains("&nbsp;")
                        .doesNotContain(hidden).endsWith("…</nobr></html>");
                assertThat(lead.getToolTipText()).isEqualTo(text);
            }
        });
    }

    /** Der Stand links endet vor dem Copyright; ein Klick auf das Copyright trifft das Copyright. */
    private static void assertCopyrightFree(StatusLine line, StatusLabel lead, JLabel east) {
        int leadRight = SwingUtilities.convertRectangle(lead.getParent(), lead.getBounds(), line).x + lead.getWidth();
        java.awt.Component west = ((java.awt.BorderLayout) line.getLayout()).getLayoutComponent(
                java.awt.BorderLayout.WEST);
        assertThat(leadRight).as("der Stand endet vor dem Copyright").isLessThanOrEqualTo(east.getX());
        assertThat(west.getX() + west.getWidth()).as("der linke Teil endet vor dem Copyright")
                .isLessThanOrEqualTo(east.getX());
        assertThat(east.getX() + east.getWidth()).as("das Copyright ganz in der Zeile")
                .isLessThanOrEqualTo(line.getWidth());
        assertThat(SwingUtilities.getDeepestComponentAt(line, east.getX() + east.getWidth() / 2,
                east.getY() + east.getHeight() / 2)).as("ein Klick auf das Copyright trifft es").isSameAs(east);
    }

    /** Braucht die Mitte (Stand der Aufträge) Platz, behält sie ihn; links gilt dann höchstens der alte Anteil. */
    @Test
    void aBusyCenterKeepsItsRoom() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel lead = new StatusLabel();
            String skipped = "„Bilder beschreiben“ ist abgeschaltet und wurde bewusst ausgelassen";
            lead.show(new StatusNote("Wissensgraph wird erzeugt · " + skipped + " · " + skipped.toUpperCase()
                    + " · " + skipped + " · " + skipped.toUpperCase(),
                    StatusNote.Tone.PLAIN, null, null, List.of(new StatusNote.Mark(skipped, StatusNote.Tone.WARNING)),
                    null));
            JLabel center = new JLabel("Download DEV: 3 von 10 Seiten");
            JLabel east = new JLabel("© 2026 AresStack");
            StatusLine line = new StatusLine(new JProgressBar(), lead, new JLabel(" "), center, east);
            // Die Breite hängt nicht an den Schriftmaßen: Mitte und Copyright passen sicher in die 40 %, die der
            // linke Teil (LEFT_SHARE) frei lässt; der lange Stand links kürzt trotzdem.
            int width = Math.max(900, (int) Math.ceil((center.getPreferredSize().width
                    + east.getPreferredSize().width + 120) / (1 - StatusLine.LEFT_SHARE)));
            line.setSize(width, 28);
            layOut(line);
            assertThat(lead.getPreferredSize().width).as("der Stand ist länger als die Zeile").isGreaterThan(width);
            assertThat(center.getWidth()).as("die Mitte ganz").isGreaterThanOrEqualTo(
                    center.getPreferredSize().width);
            assertThat(lead.getText()).endsWith("…</nobr></html>");
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

    /** Reicht die Breite nicht, rutscht kein Teil des Standes über ein anderes (Codex zu #1200). */
    @Test
    void partsNeverOverlapWhenTheWidthRunsOut() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel lead = new JLabel("Stand des Bereichs mit einem langen erfundenen Text");
            JLabel west = new JLabel("Stand der App mit einem ebenso langen erfundenen Text");
            StatusLine line = new StatusLine(new JProgressBar(), lead, west, new JLabel(" "), new JLabel("©"));
            for (int width : new int[] {1200, 400, 160, 90}) {
                line.setSize(width, StatusLine.HEIGHT);
                layoutAll(line);
                java.awt.Container left = lead.getParent().getParent();
                java.awt.Component[] parts = left.getComponents();
                for (int a = 0; a < parts.length; a++) {
                    for (int b = a + 1; b < parts.length; b++) {
                        if (parts[a].isVisible() && parts[b].isVisible() && !parts[a].getBounds().isEmpty()
                                && !parts[b].getBounds().isEmpty()) {
                            assertThat(parts[a].getBounds().intersects(parts[b].getBounds()))
                                    .as("Breite %d: %s über %s", width, parts[a].getBounds(), parts[b].getBounds())
                                    .isFalse();
                        }
                    }
                }
            }
        });
    }

    private static void layoutAll(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) {
                layoutAll(inner);
            }
        }
    }
}
