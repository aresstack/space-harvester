package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #117: text in Markdown table cells is selectable and quotable like a paragraph. */
public class MarkdownTableSelectionTest {

    private static final String URL = "https://example.org/docs";
    private static final String MARKDOWN = "| System | Beschreibung |\n"
            + "|---|---|\n"
            + "| Alpha | Import- und **Freigabe**plattform |\n"
            + "| [Beta](" + URL + ") | Zeile eins<br>Zeile zwei |\n";

    @Test
    public void dragSelectionInCellIsReportedAsQuoteSelection() throws Exception {
        final List<QuoteSelection> made = new ArrayList<QuoteSelection>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    MarkdownMessageView view = new MarkdownMessageView(theme(), DesktopLinkOpener.systemDefault(),
                            CachingMermaidImageRenderer.forChat());
                    view.setQuoteSelectionListener(new QuoteSelectionListener() {
                        @Override
                        public void selectionMade(QuoteSelection selection) {
                            made.add(selection);
                        }

                        @Override
                        public void selectionCleared() {
                        }
                    });
                    view.setMarkdown(MARKDOWN);
                    JTable table = find(view, JTable.class);
                    assertNotNull(table);
                    assertFalse(table.getRowSelectionAllowed(), "rows must not be selected instead of text");

                    assertTrue(table.editCellAt(0, 1));
                    Component editor = table.getEditorComponent();
                    assertTrue(editor instanceof WrappingTextPane);
                    WrappingTextPane pane = (WrappingTextPane) editor;
                    assertSame(table, pane.getParent());
                    assertFalse(pane.isEditable(), "cells stay read-only");

                    String text = pane.getText();
                    int start = text.indexOf("und");
                    pane.select(start, start + "und Freigabe".length());
                    pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                            0, 5, 5, 1, false, MouseEvent.BUTTON1));

                    assertEquals(1, made.size());
                    assertEquals("und Freigabe", made.get(0).selectedText());
                    assertSame(pane, made.get(0).anchor());
                    // Inline markup sits between the words, so the source position is unknown, not wrong.
                    assertFalse(made.get(0).hasSourceOffsets());

                    table.getCellEditor().stopCellEditing();
                    assertEquals("Import- und Freigabeplattform", table.getValueAt(0, 1).toString());
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    public void keystrokesNeverStartCellSelection() {
        JTable table = table(DesktopLinkOpener.systemDefault());
        KeyEvent typed = new KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                KeyEvent.VK_A, 'a');
        assertFalse(table.getCellEditor(0, 1).isCellEditable(typed));
    }

    @Test
    public void tableExposesEveryCellTextForSelectionWatching() {
        MarkdownTablePanel panel = panel(DesktopLinkOpener.systemDefault());
        List<JTextComponent> texts = panel.selectableTexts();
        assertEquals(4, texts.size());
        for (JTextComponent text : texts) {
            assertFalse(text.isEditable());
        }
    }

    @Test
    public void linkInSelectedCellOpensOnClickButNotAfterDragSelection() throws Exception {
        final List<String> opened = new ArrayList<String>();
        DesktopLinkOpener opener = new DesktopLinkOpener() {
            @Override
            public void open(String url) {
                opened.add(url);
            }
        };
        JTable table = table(opener);
        table.setSize(table.getPreferredSize());
        table.doLayout();
        assertTrue(table.editCellAt(1, 0));
        WrappingTextPane pane = (WrappingTextPane) table.getEditorComponent();
        Rectangle cell = table.getCellRect(1, 0, false);
        pane.setBounds(cell);
        Rectangle glyph = pane.modelToView(1);
        Point onLink = new Point(glyph.x, glyph.y + glyph.height / 2);

        pane.select(0, 2);
        pane.dispatchEvent(click(pane, onLink));
        assertTrue(opened.isEmpty(), "a click ending a drag selection must not open the link");

        pane.select(0, 0);
        pane.dispatchEvent(click(pane, onLink));
        assertEquals(1, opened.size());
        assertEquals(URL, opened.get(0));
    }

    private static MouseEvent click(Component source, Point at) {
        return new MouseEvent(source, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, at.x, at.y, 1,
                false, MouseEvent.BUTTON1);
    }

    private static JTable table(DesktopLinkOpener opener) {
        return (JTable) panel(opener).getViewport().getView();
    }

    private static MarkdownTablePanel panel(DesktopLinkOpener opener) {
        Container rendered = new FlexmarkSwingRenderer(theme(), opener, null).render(MARKDOWN, true);
        MarkdownTablePanel panel = find(rendered, MarkdownTablePanel.class);
        assertNotNull(panel);
        return panel;
    }

    private static MarkdownTheme theme() {
        return MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);
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
