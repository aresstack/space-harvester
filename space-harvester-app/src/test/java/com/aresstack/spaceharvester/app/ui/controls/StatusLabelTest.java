package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.FontMetrics;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class StatusLabelTest {

    @Test
    void onlyAClickOnTheLinkWordRunsItsAction() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            label.show(new StatusNote("Fortschritt und Abbruch unter Aufträge.", StatusNote.Tone.PLAIN, "Aufträge",
                    opened::incrementAndGet));
            label.setSize(label.getPreferredSize());
            FontMetrics metrics = label.getFontMetrics(label.getFont());
            int start = metrics.stringWidth("Fortschritt und Abbruch unter ");

            click(label, 2);
            assertThat(opened).as("Klick auf den Text davor").hasValue(0);
            click(label, start + metrics.stringWidth("Auf"));
            assertThat(opened).hasValue(1);
            assertThat(label.getText()).contains("<u>").contains("Aufträge");
        });
    }

    @Test
    void theLinkIsReachableByKeyboardAndNamedForScreenReaders() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            assertThat(label.isFocusable()).as("ohne Link kein Tab-Halt").isFalse();
            label.show(new StatusNote("Einzelheiten unter Aufträge.", StatusNote.Tone.PLAIN, "Aufträge",
                    opened::incrementAndGet));
            assertThat(label.isFocusable()).isTrue();
            assertThat(label.getAccessibleContext().getAccessibleName()).isEqualTo("Einzelheiten unter Aufträge.");
            assertThat(label.getAccessibleContext().getAccessibleDescription()).contains("Aufträge");
            label.getActionMap().get(label.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(javax.swing.KeyStroke.getKeyStroke("ENTER"))).actionPerformed(null);
            assertThat(opened).hasValue(1);
            label.show(StatusNote.plain("fertig"));
            assertThat(label.isFocusable()).isFalse();
        });
    }

    @Test
    void toneSetsTheColourAndPlainTextStaysPlain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            label.show(new StatusNote("3 Seiten bewusst ausgelassen", StatusNote.Tone.WARNING, null, null));
            assertThat(label.getForeground()).isEqualTo(StatusLabel.WARNING);
            assertThat(label.getText()).isEqualTo("3 Seiten bewusst ausgelassen");
            label.show(new StatusNote("gescheitert", StatusNote.Tone.ERROR, null, null));
            assertThat(label.getForeground()).isEqualTo(Comic.RED);
            label.show(StatusNote.plain("50 Knoten <und> 51 Kanten"));
            assertThat(label.getForeground()).isEqualTo(Comic.MUTED);
            assertThat(label.getText()).isEqualTo("50 Knoten <und> 51 Kanten");
            assertThat(label.overLink(10)).isFalse();
        });
    }

    @Test
    void aLinkWhoseWordIsMissingIsDropped() {
        StatusNote note = new StatusNote("fertig", StatusNote.Tone.PLAIN, "Aufträge", () -> { });
        assertThat(note.hasLink()).isFalse();
        StatusNote linked = new StatusNote("unter Aufträge", StatusNote.Tone.PLAIN, "Aufträge", () -> { });
        assertThat(linked.withText("unter Aufträge · Ablage: MaltParser").hasLink()).isTrue();
    }

    /** Teile in eigenem Ton: rot „ohne Zugriff“ in einer gelben Zeile, der Rest in der Farbe der Zeile. */
    @Test
    void marksColourTheirPartAndTheRestKeepsTheLineTone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            String hidden = "2 Seiten ohne Zugriff ausgelassen";
            label.show(new StatusNote("Wissensgraph erzeugt · 3 erst laden · " + hidden, StatusNote.Tone.WARNING,
                    null, null, java.util.List.of(new StatusNote.Mark(hidden, StatusNote.Tone.ERROR),
                    new StatusNote.Mark("Wissensgraph erzeugt", StatusNote.Tone.PLAIN)), null));
            assertThat(label.getForeground()).isEqualTo(StatusLabel.WARNING);
            assertThat(label.getText()).startsWith("<html>")
                    .contains(hex(Comic.RED) + "'>" + hidden + "</font>")
                    .contains(hex(Comic.MUTED) + "'>Wissensgraph erzeugt</font>")
                    .contains(" · 3 erst laden · ");
            assertThat(label.isFocusable()).as("ohne Link und Symbol kein Tab-Halt").isFalse();
            assertThat(label.getAccessibleContext().getAccessibleName())
                    .isEqualTo("Wissensgraph erzeugt · 3 erst laden · " + hidden);
        });
    }

    /**
     * Ein Symbol als Link (Kreispfeil) direkt hinter seinem Teil: nur ein Klick darauf löst es aus, mit eigenem
     * Tooltip; das Link-Wort dahinter bleibt klickbar; Strg+Enter löst das Symbol aus, Enter das Wort.
     */
    @Test
    void anIconLinkSitsRightAfterItsPartAndIsClickableBesideTheLinkWord() throws Exception {
        AtomicInteger retried = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel label = new StatusLabel();
            String failed = "Vorstufe „Aussagenanalyse“ gescheitert (DEV)";
            String text = "Wissensgraph mit Fehler beendet · " + failed + " · Einzelheiten unter Hintergrund";
            label.show(new StatusNote(text, StatusNote.Tone.ERROR, "Hintergrund", opened::incrementAndGet,
                    java.util.List.of(), new StatusNote.IconLink(com.aresstack.spaceharvester.app.ui.Glyph.REFRESH,
                    failed, "„Aussagenanalyse“ wiederholen", retried::incrementAndGet)));
            label.setSize(label.getPreferredSize());
            FontMetrics metrics = label.getFontMetrics(label.getFont());
            int after = metrics.stringWidth("Wissensgraph mit Fehler beendet · " + failed);
            int[] icon = label.iconSpan();
            assertThat(icon[0]).as("direkt hinter dem Teil").isGreaterThanOrEqualTo(after)
                    .isLessThan(after + metrics.stringWidth("    "));
            assertThat(icon[1] - icon[0]).isEqualTo(StatusLabel.ICON);
            assertThat(label.getText()).contains("&nbsp;");

            click(label, after - 4);
            assertThat(retried).as("Klick auf den Text davor").hasValue(0);
            click(label, (icon[0] + icon[1]) / 2);
            assertThat(retried).hasValue(1);
            assertThat(opened).hasValue(0);
            assertThat(label.getToolTipText(new MouseEvent(label, MouseEvent.MOUSE_MOVED, 0L, 0, icon[0] + 4, 5, 0,
                    false))).isEqualTo("„Aussagenanalyse“ wiederholen");

            int word = metrics.stringWidth(text.substring(0, text.lastIndexOf("Hintergrund")))
                    + metrics.stringWidth(" ".repeat(10));
            // Das Wort steht hinter dem Platz des Symbols: dorthin klicken, wo es gezeichnet ist.
            boolean hit = false;
            for (int x = word - 40; x < word + 80 && !hit; x += 2) {
                click(label, x);
                hit = opened.get() > 0;
            }
            assertThat(hit).as("das Link-Wort hinter dem Symbol bleibt klickbar").isTrue();
            assertThat(retried).hasValue(1);

            assertThat(label.isFocusable()).isTrue();
            assertThat(label.getAccessibleContext().getAccessibleDescription()).contains("Hintergrund")
                    .contains("„Aussagenanalyse“ wiederholen").contains("Strg+Enter");
            label.getActionMap().get(label.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                    .get(javax.swing.KeyStroke.getKeyStroke("ctrl ENTER"))).actionPerformed(null);
            assertThat(retried).hasValue(2);
        });
    }

    /**
     * Mit Tönen, Link oder Symbol bleibt die Meldung eine Zeile: schmaler gesetzt endet sie mit „…“ statt umzubrechen;
     * weggekürzte Symbole und Link-Wörter sind nicht mehr anklickbar, der ganze Text steht im Tooltip.
     */
    @Test
    void aLongMarkedNoteStaysOneLineAndEndsWithAnEllipsis() throws Exception {
        AtomicInteger retried = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            StatusLabel plain = new StatusLabel();
            plain.show(StatusNote.plain("Plan"));
            StatusLabel label = new StatusLabel();
            String failed = "Vorstufe „Aussagenanalyse“ gescheitert (DEV)";
            String skipped = "„Bilder beschreiben“ ist abgeschaltet und wurde bewusst ausgelassen";
            String text = "Wissensgraph mit Fehler beendet · " + failed + " · " + skipped
                    + " · Einzelheiten unter Hintergrund";
            label.show(new StatusNote(text, StatusNote.Tone.ERROR, "Hintergrund", () -> { },
                    java.util.List.of(new StatusNote.Mark(skipped, StatusNote.Tone.WARNING)),
                    new StatusNote.IconLink(com.aresstack.spaceharvester.app.ui.Glyph.REFRESH, failed,
                            "„Aussagenanalyse“ wiederholen", retried::incrementAndGet)));
            FontMetrics metrics = label.getFontMetrics(label.getFont());
            int whole = label.getPreferredSize().width;
            assertThat(whole).as("so breit wie der ganze Text").isGreaterThan(metrics.stringWidth(text));
            assertThat(label.getPreferredSize().height).as("eine Zeile").isEqualTo(plain.getPreferredSize().height);

            int narrow = metrics.stringWidth("Wissensgraph mit Fehler beendet · " + failed) + 60;
            label.setBounds(0, 0, narrow, label.getPreferredSize().height);
            assertThat(label.getText()).startsWith("<html><nobr>").endsWith("…</nobr></html>")
                    .doesNotContain("Hintergrund").doesNotContain("<u>");
            assertThat(label.getPreferredSize().width).as("die gewünschte Breite bleibt die volle").isEqualTo(whole);
            assertThat(label.getToolTipText()).isEqualTo(text);
            int[] icon = label.iconSpan();
            assertThat(icon[1]).as("der Kreispfeil passt noch").isLessThanOrEqualTo(narrow);
            click(label, (icon[0] + icon[1]) / 2);
            assertThat(retried).hasValue(1);
            assertThat(label.overLink(narrow - 5)).as("das weggekürzte Link-Wort").isFalse();

            label.setBounds(0, 0, metrics.stringWidth("Wissensgraph mit Fehler beendet · Vorstufe"),
                    label.getPreferredSize().height);
            assertThat(label.getText()).doesNotContain("&nbsp;");
            click(label, label.getWidth() - 3);
            assertThat(retried).as("ein weggekürztes Symbol löst nichts aus").hasValue(1);

            label.setBounds(0, 0, whole, label.getPreferredSize().height);
            assertThat(label.getText()).doesNotContain("…").contains("<u>").contains("&nbsp;");
        });
    }

    /** Was hinter einer gelben oder roten Meldung angehängt wird (etwa der Stand des Graphen), bleibt grau. */
    @Test
    void anAppendedStandStaysPlainBehindAColouredNote() {
        StatusNote warning = new StatusNote("2 erst laden", StatusNote.Tone.WARNING, null, null);
        StatusNote shown = warning.plus(" · 5 Knoten, 4 Kanten");
        assertThat(shown.text()).isEqualTo("2 erst laden · 5 Knoten, 4 Kanten");
        assertThat(shown.tone()).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(shown.toneOf("2 erst laden")).isEqualTo(StatusNote.Tone.WARNING);
        assertThat(shown.toneOf("5 Knoten, 4 Kanten")).isEqualTo(StatusNote.Tone.PLAIN);
        assertThat(StatusNote.plain("Plan").plus(" · Ablage: Parser").marks()).as("grau braucht keinen Teil").isEmpty();
        assertThat(warning.plus("")).isSameAs(warning);
    }

    @Test
    void marksAndIconsFollowTheTextAndVanishWithTheirPart() {
        StatusNote note = new StatusNote("A gescheitert · B", StatusNote.Tone.ERROR, null, null,
                java.util.List.of(new StatusNote.Mark("B", StatusNote.Tone.WARNING)),
                new StatusNote.IconLink(com.aresstack.spaceharvester.app.ui.Glyph.REFRESH, "A gescheitert", "", () -> { }));
        StatusNote longer = note.withText(note.text() + " · Ablage: Parser");
        assertThat(longer.hasIcon()).isTrue();
        assertThat(longer.toneOf("B")).isEqualTo(StatusNote.Tone.WARNING);
        StatusNote other = note.withText("fertig");
        assertThat(other.hasIcon()).isFalse();
        assertThat(other.marks()).isEmpty();
        assertThat(other.toneOf("fertig")).isEqualTo(StatusNote.Tone.ERROR);
    }

    private static String hex(java.awt.Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static void click(StatusLabel label, int x) {
        label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0L, 0, x, 5, 1, false,
                MouseEvent.BUTTON1));
    }
}
