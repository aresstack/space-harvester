package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Die Meldungsbox ist so hoch wie ihr Text und nie höher als ein paar Zeilen: Sie verdrängt das Formular darüber
 * nicht, auch nicht beim ersten Einblenden (Angelo, 09.11. 20:19: Box füllte den ganzen Quellen-Dialog, kurzer Text,
 * nichts mehr bedienbar; in den Einstellungen dasselbe, bis zum Reiterwechsel).
 */
class FeedbackTest {

    private static final String SHORT = "Zertifikat fehlt oder wird nicht vertraut";

    /** Aufbau wie im Quellen-Dialog: Formular in der Mitte, darunter Meldung und Knopfleiste. */
    private static final class Dialog {
        final JPanel root = new JPanel(new BorderLayout(0, 10));
        final JPanel form = new JPanel(new BorderLayout());
        final JTextField field = new JTextField("https://confluence.example.invalid");
        final Feedback feedback = new Feedback();

        Dialog(int width, int height) {
            form.add(field, BorderLayout.NORTH);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(new JButton("Verbindung testen"));
            JPanel south = new JPanel(new BorderLayout(0, 8));
            south.add(feedback, BorderLayout.NORTH);
            south.add(buttons, BorderLayout.SOUTH);
            root.add(form, BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);
            root.setSize(width, height);
            // Wie ein angezeigtes Fenster: validate() legt dann genau einmal aus, auch die noch unsichtbare Meldung
            // (mit Breite 0), und merkt sich die Wunschgrößen.
            root.addNotify();
            root.validate();
        }

        /** Genau ein Layout-Durchlauf, wie ihn Swing nach dem Einblenden macht. */
        void layoutOnce() {
            root.validate();
        }
    }

    @Test
    void aShortMessageIsOnlyAsTallAsItsTextOnFirstAppearance() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Dialog dialog = new Dialog(720, 435);

            dialog.feedback.warning("Verbindung fehlgeschlagen", SHORT);
            dialog.layoutOnce();

            int line = dialog.feedback.getFontMetrics(Comic.regular(12.5f)).getHeight();
            assertThat(dialog.feedback.getHeight()).as("Überschrift, eine Zeile Text und Rand")
                    .isLessThanOrEqualTo(3 * line + 40);
            assertThat(dialog.form.getHeight()).as("Formular bleibt sichtbar").isGreaterThan(250);
            assertThat(dialog.form.getY()).as("Formular beginnt oben").isZero();
            assertThat(dialog.field.getHeight()).as("Feld bleibt bedienbar").isPositive();
            assertThat(dialog.feedback.getY()).as("Meldung beginnt im sichtbaren Bereich").isGreaterThanOrEqualTo(0);
        });
    }

    /** Der Ablauf von „Verbindung testen“: erst „wird geprüft“ ohne Text, dann die Warnung mit Text. */
    @Test
    void theMessageAfterAWorkingNoteIsOnlyAsTallAsItsText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Dialog dialog = new Dialog(720, 435);
            dialog.feedback.working("Verbindung wird geprüft …");
            dialog.layoutOnce();

            dialog.feedback.warning("Verbindung fehlgeschlagen", SHORT);
            dialog.layoutOnce();

            int line = dialog.feedback.getFontMetrics(Comic.regular(12.5f)).getHeight();
            assertThat(dialog.feedback.getHeight()).isLessThanOrEqualTo(3 * line + 40);
            assertThat(dialog.form.getHeight()).as("Formular bleibt sichtbar").isGreaterThan(250);
        });
    }

    @Test
    void aVeryLongMessageWithoutSpacesStaysBelowTheMaximumHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Dialog dialog = new Dialog(720, 435);

            dialog.feedback.warning("Verbindung fehlgeschlagen", "x".repeat(5000));
            dialog.layoutOnce();

            assertThat(dialog.feedback.getHeight()).as("höchstens die Maximalhöhe")
                    .isLessThanOrEqualTo(dialog.feedback.maximumHeight());
            assertThat(dialog.form.getHeight()).as("Formular bleibt sichtbar").isGreaterThan(200);
            assertThat(dialog.field.getHeight()).as("Feld bleibt bedienbar").isPositive();
            assertThat(dialog.field.isEnabled()).isTrue();
            assertThat(dialog.feedback.getWidth()).as("kein Wachstum in die Breite").isEqualTo(720);
            for (JTextArea area : SourceFormLayoutTest.find(dialog.feedback, JTextArea.class)) {
                assertThat(area.getWidth()).as("Text bricht um, statt nach rechts hinauszuragen")
                        .isLessThanOrEqualTo(720);
            }
        });
    }

    /** Auch aufgeklappte technische Details bleiben unter der Maximalhöhe; der Rest wird in der Box gescrollt. */
    @Test
    void openTechnicalDetailsStayBelowTheMaximumHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Dialog dialog = new Dialog(720, 435);
            dialog.feedback.problem(new IllegalStateException("PKIX path building failed: " + "y".repeat(5000)));
            dialog.layoutOnce();
            int closed = dialog.feedback.getHeight();

            SourceFormLayoutTest.find(dialog.feedback, JButton.class).stream()
                    .filter(button -> "Technische Details".equals(button.getText())).findFirst().orElseThrow()
                    .doClick();
            dialog.layoutOnce();

            assertThat(dialog.feedback.getHeight()).as("aufgeklappt höher als zugeklappt").isGreaterThan(closed);
            assertThat(dialog.feedback.getHeight()).isLessThanOrEqualTo(dialog.feedback.maximumHeight());
            assertThat(dialog.form.getHeight()).as("Formular bleibt sichtbar").isGreaterThan(150);
        });
    }

    /**
     * Wird das Fenster schmaler, bricht der Text neu um und die Box wächst mit: Sie verlangt den zweiten
     * Layout-Durchlauf selbst, ohne dass jemand nachhilft.
     */
    @Test
    void theHeightFollowsTheWidth() throws Exception {
        Dialog[] dialog = new Dialog[1];
        int[] wide = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            dialog[0] = new Dialog(720, 435);
            String text = "Die Adresse antwortet nicht. Netzweg, Proxy und Zertifikat prüfen und erneut versuchen.";
            dialog[0].feedback.warning("Verbindung fehlgeschlagen", text);
            dialog[0].layoutOnce();
            wide[0] = dialog[0].feedback.getHeight();

            dialog[0].root.setSize(300, 435);
            dialog[0].layoutOnce();
        });
        // Dazwischen läuft, was die Box selbst eingereiht hat.
        SwingUtilities.invokeAndWait(() -> {
            assertThat(dialog[0].root.isValid()).as("die Box hat ein neues Auslegen verlangt").isFalse();
            dialog[0].layoutOnce();

            assertThat(dialog[0].feedback.getHeight()).as("mehr Zeilen bei weniger Breite").isGreaterThan(wide[0]);
            assertThat(dialog[0].feedback.getHeight()).isLessThanOrEqualTo(dialog[0].feedback.maximumHeight());
            assertThat(dialog[0].feedback.getY() + dialog[0].feedback.getHeight())
                    .as("die Box bleibt über der Knopfleiste")
                    .isLessThanOrEqualTo(dialog[0].feedback.getParent().getHeight());
        });
    }

    @Test
    void clearingHidesTheMessageAndGivesTheSpaceBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Dialog dialog = new Dialog(720, 435);
            int before = dialog.form.getHeight();
            dialog.feedback.warning("Bitte prüfen", SHORT);
            dialog.layoutOnce();

            dialog.feedback.clear();
            dialog.layoutOnce();

            assertThat(dialog.feedback.isVisible()).isFalse();
            assertThat(dialog.form.getHeight()).isEqualTo(before);
        });
    }

    /**
     * Über einer Meldung ohne eigenen Scrollbalken scrollt das Mausrad die Seite darum (Einstellungen); über einer
     * überlangen Meldung scrollt es deren Text.
     */
    @Test
    void theMouseWheelScrollsThePageUnlessTheMessageItselfScrolls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Feedback feedback = new Feedback();
            JPanel tall = new JPanel();
            tall.setPreferredSize(new java.awt.Dimension(400, 2000));
            JPanel page = new JPanel(new BorderLayout());
            page.add(feedback, BorderLayout.NORTH);
            page.add(tall, BorderLayout.CENTER);
            JScrollPane outer = new JScrollPane(page);
            outer.setSize(500, 300);
            outer.addNotify();
            outer.validate();
            feedback.warning("Bitte prüfen", SHORT);
            outer.validate();
            JScrollPane inner = SourceFormLayoutTest.find(feedback, JScrollPane.class).get(0);

            inner.dispatchEvent(wheel(inner));

            assertThat(inner.getVerticalScrollBar().isVisible()).as("kurze Meldung ohne Scrollbalken").isFalse();
            assertThat(outer.getVerticalScrollBar().getValue()).as("die Seite scrollt").isPositive();

            outer.getVerticalScrollBar().setValue(0);
            feedback.warning("Bitte prüfen", "x".repeat(5000));
            outer.validate();
            inner.dispatchEvent(wheel(inner));

            assertThat(inner.getVerticalScrollBar().isVisible()).as("überlange Meldung mit Scrollbalken").isTrue();
            assertThat(inner.getViewport().getViewPosition().y).as("der Text der Meldung scrollt").isPositive();
            assertThat(outer.getVerticalScrollBar().getValue()).as("die Seite bleibt stehen").isZero();
        });
    }

    /** Eine überlange Meldung beginnt oben, nicht an ihrem Ende. */
    @Test
    void aLongMessageStartsAtItsTop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Dialog dialog = new Dialog(720, 435);
            dialog.feedback.warning("Verbindung fehlgeschlagen", "Anfang " + "x".repeat(5000));
            dialog.layoutOnce();

            JScrollPane inner = SourceFormLayoutTest.find(dialog.feedback, JScrollPane.class).get(0);
            assertThat(inner.getViewport().getViewPosition().y).isZero();
        });
    }

    /** Codex zu #566: „Seite wird geladen …“ stand in der Statuszeile, bis etwas anderes sie ablöste. */
    @Test
    void clearingEndsARunningMessageInTheStatusLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> line = new java.util.ArrayList<>();
            Feedback feedback = new Feedback();
            feedback.statusLine((text, tone) -> line.add(text));
            feedback.working("Seite wird geladen …");
            assertThat(line).containsExactly("Seite wird geladen …");
            assertThat(feedback.isVisible()).isFalse();

            feedback.clear();
            assertThat(line).as("die Laufmeldung endet mit dem Vorgang").containsExactly("Seite wird geladen …", "");

            feedback.clear();
            assertThat(line).as("ohne eigene Meldung bleibt die Statuszeile unberührt").hasSize(2);
        });
    }

    private static java.awt.event.MouseWheelEvent wheel(java.awt.Component source) {
        return new java.awt.event.MouseWheelEvent(source, java.awt.event.MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 5, 5, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1);
    }
}
