package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Angelos Fehlerbild: Zellen mit HTML-Liste und Link erschienen als Quelltext; jetzt gesetzt und klickbar. */
public class MarkdownTablePanelTest {

    private static final String URL = "https://example.org/docs";

    @Test
    public void tableCellsWithListAndLinkShowNoTagTextAndKeepLinkClickable() throws Exception {
        String markdown = "| Quelle | Was du dort findest |\n"
                + "|---|---|\n"
                + "| [Dokumentation](" + URL + ") | <ul><li>Chart-Definition</li><li>Templates</li></ul> |\n"
                + "| **Wiki** | Zeile eins<br>Zeile zwei mit `code` &amp; mehr |\n";
        final List<String> opened = new ArrayList<String>();
        DesktopLinkOpener opener = new DesktopLinkOpener() {
            @Override
            public void open(String url) {
                opened.add(url);
            }
        };
        MarkdownTheme theme = MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);
        Container rendered = new FlexmarkSwingRenderer(theme, opener, null).render(markdown, true);

        JTable table = find(rendered, JTable.class);
        assertNotNull(table);
        assertEquals(2, table.getRowCount());
        assertEquals("Quelle", table.getColumnName(0));

        for (int row = 0; row < table.getRowCount(); row++) {
            for (int column = 0; column < table.getColumnCount(); column++) {
                String text = visibleText(table, row, column);
                assertFalse(text.contains("<"), text);
                assertFalse(text.contains(">"), text);
                assertFalse(text.contains("&amp;"), text);
                assertFalse(text.contains("**"), text);
            }
        }
        assertEquals("Dokumentation", visibleText(table, 0, 0));
        assertEquals("• Chart-Definition\n• Templates", visibleText(table, 0, 1));
        assertEquals("Wiki", visibleText(table, 1, 0));
        assertEquals("Zeile eins\nZeile zwei mit code & mehr", visibleText(table, 1, 1));
        // Zwei Listenpunkte brauchen zwei Zeilen: die Zeile wächst über die Standardhöhe.
        int lineHeight = table.getFontMetrics(table.getFont()).getHeight();
        assertTrue(table.getRowHeight(0) >= 2 * lineHeight);

        table.setSize(table.getPreferredSize());
        table.doLayout();
        Rectangle cell = table.getCellRect(0, 0, false);
        MarkdownTablePanel.RichCell link = (MarkdownTablePanel.RichCell) table.getValueAt(0, 0);
        link.pane.setBounds(0, 0, cell.width, cell.height);
        Rectangle firstGlyph = link.pane.modelToView(1);
        Point onLink = new Point(cell.x + firstGlyph.x, cell.y + firstGlyph.y + firstGlyph.height / 2);
        assertEquals(URL, MarkdownTablePanel.linkAt(table, onLink));
        assertNull(MarkdownTablePanel.linkAt(table, new Point(cell.x + cell.width - 2, onLink.y)));

        table.dispatchEvent(new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, onLink.x, onLink.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
        assertEquals(1, opened.size());
        assertEquals(URL, opened.get(0));
    }

    @Test
    public void inlineHtmlInParagraphIsFormattedNotShownAsSource() throws BadLocationException {
        MarkdownTheme theme = MarkdownTheme.forBubble(Color.WHITE, Color.BLACK);
        Container rendered = new FlexmarkSwingRenderer(theme, DesktopLinkOpener.systemDefault(), null)
                .render("Eins<br/>Zwei <b>fett</b> <a href=\"" + URL + "\">Link</a> <span>x</span>", true);
        WrappingTextPane pane = find(rendered, WrappingTextPane.class);
        assertNotNull(pane);
        StyledDocument document = pane.getStyledDocument();
        String text = document.getText(0, document.getLength());
        assertEquals("Eins\nZwei fett Link x", text);
        int linkStart = text.indexOf("Link");
        assertEquals(URL, document.getCharacterElement(linkStart).getAttributes()
                .getAttribute(LinkMouseListener.HREF_ATTRIBUTE));
    }

    @Test
    public void longTablesShowEveryRowAndFitTheAvailableWidth() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            StringBuilder markdown = new StringBuilder("| Spalte A | Spalte B |\n|---|---|\n");
            for (int row = 0; row < 50; row++) {
                markdown.append("| Zeile ").append(row).append(" | ")
                        .append("Längerer Zellinhalt ".repeat(10)).append(" |\n");
            }
            MarkdownTheme theme = MarkdownTheme.forSurface(Color.WHITE);
            Container rendered = new FlexmarkSwingRenderer(theme, url -> { }, null).render(markdown.toString(), true);
            MarkdownTablePanel panel = find(rendered, MarkdownTablePanel.class);
            assertNotNull(panel);
            JTable table = (JTable) panel.getViewport().getView();
            assertEquals(50, table.getRowCount());
            for (int width : new int[] {260, 1000}) {
                int height = panel.preferredHeightForWidth(width);
                panel.setSize(width, height);
                panel.doLayout();
                panel.getViewport().doLayout();
                table.doLayout();
                assertTrue(height > 320);
                assertEquals(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_NEVER, panel.getVerticalScrollBarPolicy());
                assertFalse(panel.getVerticalScrollBar().isVisible());
                assertFalse(panel.getHorizontalScrollBar().isVisible(), "Fit the table into width " + width);
                Insets insets = panel.getInsets();
                assertEquals(width - insets.left - insets.right, table.getColumnModel().getTotalColumnWidth());
                Rectangle last = table.getCellRect(table.getRowCount() - 1, 0, true);
                assertTrue(panel.getViewport().getExtentSize().height >= last.y + last.height,
                        "Display the entire last row at width " + width);
            }
            int before = panel.preferredHeightForWidth(1000);
            table.getColumnModel().getColumn(1).setWidth(100);
            assertTrue(panel.preferredHeightForWidth(1000) > before,
                    "Grow the table after narrowing a column");
        });
    }

    private static String visibleText(JTable table, int row, int column) throws BadLocationException {
        Object value = table.getValueAt(row, column);
        assertTrue(value instanceof MarkdownTablePanel.RichCell);
        StyledDocument document = ((MarkdownTablePanel.RichCell) value).pane.getStyledDocument();
        return document.getText(0, document.getLength()).trim();
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
