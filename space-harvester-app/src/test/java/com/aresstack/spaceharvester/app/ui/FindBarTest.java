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
