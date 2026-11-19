package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.Highlighter;
import org.junit.jupiter.api.Test;

/** Die Suche über der Statuszeile: markiert wie im Browser, zählt, schaltet durch und folgt dem Bereich. */
class FindBarTest {

    @Test
    void highlightsCountsAndCyclesThroughHits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("Neustart mit systemctl. Danach erneut Neustart prüfen.");
            JLabel property = new JLabel("Titel: Neustart");
            JPanel page = new JPanel();
            page.add(text);
            page.add(property);
            JComponent[] area = {page};
            FindBar bar = new FindBar(() -> area[0]);

            assertThat(bar.search("neustart")).isEqualTo(3);
            assertThat(bar.counterText()).isEqualTo("1 / 3");
            Highlighter.Highlight[] marks = text.getHighlighter().getHighlights();
            assertThat(marks).hasSize(2);
            assertThat(property.isOpaque()).isTrue();

            bar.next();
            assertThat(bar.counterText()).isEqualTo("2 / 3");
            bar.next();
            bar.next();
            assertThat(bar.counterText()).isEqualTo("1 / 3");
            bar.previous();
            assertThat(bar.counterText()).isEqualTo("3 / 3");

            assertThat(bar.search("gibt es nicht")).isZero();
            assertThat(bar.counterText()).isEqualTo("Keine Treffer");
            assertThat(text.getHighlighter().getHighlights()).isEmpty();

            bar.search("systemctl");
            bar.clear();
            assertThat(bar.counterText()).isEmpty();
            assertThat(text.getHighlighter().getHighlights()).isEmpty();

            // Anderer Bereich: alte Markierungen weg, der stehende Text sucht dort neu.
            JTextArea other = new JTextArea("systemctl restart");
            JPanel second = new JPanel();
            second.add(other);
            area[0] = second;
            bar.areaChanged();
            assertThat(bar.counterText()).isEqualTo("1 / 1");
            assertThat(other.getHighlighter().getHighlights()).hasSize(1);
            assertThat(text.getHighlighter().getHighlights()).isEmpty();
        });
    }

    /** Datei-Aktionen der Vorschau (Angelo, 19.11.2026): direkt neben der Lupe, die Werkzeuge füllen die Zeile. */
    @Test
    void fillingToolsStartRightOfTheMagnifierAndReachTheRightEdge() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FindBar bar = new FindBar(JPanel::new);
            JPanel actions = new JPanel(new java.awt.BorderLayout());
            actions.add(new javax.swing.JButton("S"), java.awt.BorderLayout.WEST);
            actions.add(new javax.swing.JLabel("Word · 4,0 MB"), java.awt.BorderLayout.EAST);
            actions.putClientProperty(View.TOOLS_FILL, Boolean.TRUE);
            bar.tools(actions);
            bar.setSize(800, 36);
            bar.doLayout();
            java.awt.Rectangle magnifier = bar.findButton().getBounds();
            java.awt.Rectangle tools = bar.toolsArea().getBounds();
            assertThat(tools.x).isGreaterThan(magnifier.x + magnifier.width - 1)
                    .isLessThanOrEqualTo(magnifier.x + magnifier.width + 8);
            assertThat(tools.x + tools.width).isEqualTo(800 - bar.getInsets().right);
            // Zurück ohne Eigenschaft: wieder mittig.
            actions.putClientProperty(View.TOOLS_FILL, Boolean.FALSE);
            bar.doLayout();
            assertThat(bar.toolsArea().getX()).isGreaterThan(tools.x);
        });
    }

    @Test
    void theMagnifierUnfoldsTheSearchOverTheToolsAndEscapeFoldsItAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("eins zwei eins");
            JPanel page = new JPanel();
            page.add(text);
            FindBar bar = new FindBar(() -> page);
            JPanel switches = new JPanel();
            switches.add(new javax.swing.JToggleButton("A"));
            bar.tools(switches);
            bar.setSize(800, 36);
            bar.doLayout();
            // Zu: nur die Lupe links, die Umschalter mittig.
            assertThat(bar.isOpen()).isFalse();
            assertThat(bar.searchArea().isVisible()).isFalse();
            assertThat(bar.toolsArea().isVisible()).isTrue();
            assertThat(bar.findButton().getX()).isLessThan(bar.toolsArea().getX());
            int middle = bar.toolsArea().getX() + bar.toolsArea().getWidth() / 2;
            assertThat(Math.abs(middle - 400)).isLessThanOrEqualTo(2);

            // Klick auf die Lupe: die Suche klappt nach rechts auf und überdeckt die Umschalter.
            bar.findButton().doClick();
            bar.doLayout();
            assertThat(bar.isOpen()).isTrue();
            assertThat(bar.searchArea().isVisible()).isTrue();
            assertThat(bar.toolsArea().isVisible()).isFalse();
            assertThat(bar.searchArea().getX()).isGreaterThan(bar.findButton().getX());
            assertThat(bar.search("eins")).isEqualTo(2);

            // Esc: Markierungen weg, zugeklappt, der Text bleibt für das nächste Aufklappen.
            bar.close();
            assertThat(bar.isOpen()).isFalse();
            assertThat(bar.toolsArea().isVisible()).isTrue();
            assertThat(text.getHighlighter().getHighlights()).isEmpty();
            assertThat(bar.field().getText()).isEqualTo("eins");
            bar.focus();
            assertThat(bar.counterText()).isEqualTo("1 / 2");
        });
    }

    @Test
    void expandingLowercaseDoesNotShiftOffsets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("İİİ Neustart");
            JPanel page = new JPanel();
            page.add(text);
            FindBar bar = new FindBar(() -> page);
            assertThat(bar.search("neustart")).isEqualTo(1);
            Highlighter.Highlight mark = text.getHighlighter().getHighlights()[0];
            assertThat(text.getText().substring(mark.getStartOffset(), mark.getEndOffset())).isEqualTo("Neustart");
        });
    }

    @Test
    void tablesAreSearchedByTheirDisplayedText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTable table = new javax.swing.JTable(new Object[][] {{Thread.State.RUNNABLE}},
                    new Object[] {"Zustand"});
            table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
                @Override
                protected void setValue(Object value) {
                    setText(value == Thread.State.RUNNABLE ? "Läuft" : String.valueOf(value));
                }
            });
            JPanel page = new JPanel();
            page.add(table);
            FindBar bar = new FindBar(() -> page);
            assertThat(bar.search("läuft")).isEqualTo(1);
            assertThat(bar.search("RUNNABLE")).isZero();
        });
    }

    @Test
    void changedContentIsSearchedAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("eins zwei eins zwei eins");
            JPanel page = new JPanel();
            page.add(text);
            FindBar bar = new FindBar(() -> page);
            assertThat(bar.search("eins")).isEqualTo(3);
            text.setText("eins");
            bar.next();
            assertThat(bar.counterText()).isEqualTo("1 / 1");
        });
    }

    @Test
    void longStatusTextLeavesRoomForTheJobLine() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel status = new JLabel("Space Harvester · Bestand " + "C:\\sehr\\langer\\Pfad".repeat(30));
            JLabel cancel = new JLabel("Abbrechen");
            StatusLine line = new StatusLine(new javax.swing.JProgressBar(), status, cancel, new JLabel("© AresStack"));
            line.setSize(960, 30);
            line.doLayout();
            line.setSize(960, 30);
            line.doLayout();
            assertThat(cancel.getWidth()).isGreaterThanOrEqualTo(cancel.getPreferredSize().width);
        });
    }
}
