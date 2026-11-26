package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Angelo 26.11.2026: Spalten nach Inhalt statt gleichmäßig; schmale Beschriftung, breiter Text. */
public class ColumnWidthsTest {

    @Test
    public void contentThatFitsKeepsItsNaturalWidths() {
        assertArrayEquals(new int[] {80, 120}, ColumnWidths.distribute(new int[] {40, 60}, new int[] {80, 120}, 500));
    }

    @Test
    public void spareWidthGoesToTheColumnThatWantsMore() {
        // Beschriftung: Wort 100, ganz 200. Text: Wort 120, ganz 2000. Platz 350.
        int[] widths = ColumnWidths.distribute(new int[] {100, 120}, new int[] {200, 2000}, 350);
        assertEquals(350, Arrays.stream(widths).sum());
        assertTrue(widths[0] < 110, Arrays.toString(widths));
        assertTrue(widths[1] > 2 * widths[0], Arrays.toString(widths));
    }

    @Test
    public void aSingleLongWordNeverPushesTheTableBeyondTheWidth() {
        int[] widths = ColumnWidths.distribute(new int[] {600, 80}, new int[] {600, 900}, 300);
        assertEquals(300, Arrays.stream(widths).sum());
        assertTrue(widths[1] >= 80, Arrays.toString(widths));
    }

    @Test
    public void chatTableGivesTheTextColumnMostOfTheSidebar() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            String markdown = "| Punkt | Beschreibung | Schritte |\n|---|---|---|\n"
                    + "| Berechtigung und Aktualität | " + "Die Antwort prüft, ob die Quelle noch gilt und wer sie lesen darf. ".repeat(4)
                    + " | <ul><li>prüfen</li><li>melden</li></ul> |\n"
                    + "| Suche | " + "Gesucht wird zuerst im lokalen Index, danach in den Treffern. ".repeat(3)
                    + " | <ul><li>suchen</li><li>lesen</li></ul> |\n";
            MarkdownTheme theme = MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);
            Container rendered = new FlexmarkSwingRenderer(theme, url -> { }, null).render(markdown, true);
            MarkdownTablePanel panel = find(rendered, MarkdownTablePanel.class);
            assertNotNull(panel);
            JTable table = (JTable) panel.getViewport().getView();
            int width = 420;
            panel.setSize(width, panel.preferredHeightForWidth(width));
            panel.doLayout();
            int label = table.getColumnModel().getColumn(0).getWidth();
            int text = table.getColumnModel().getColumn(1).getWidth();
            int list = table.getColumnModel().getColumn(2).getWidth();
            assertTrue(text > 2 * label, "label " + label + ", text " + text);
            // Listenpunkte stehen untereinander: die Spalte wird nicht breiter als ihr längster Punkt samt Rand.
            assertTrue(list < text, "list " + list + ", text " + text);
            // Die Beschriftung bricht um: ihre Zeile ist höher als eine Textzeile.
            int lineHeight = table.getFontMetrics(table.getFont()).getHeight();
            assertTrue(((MarkdownTablePanel.RichCell) table.getValueAt(0, 0)).pane.heightForWidth(label) > lineHeight);
        });
    }

    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) {
            return type.cast(component);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
