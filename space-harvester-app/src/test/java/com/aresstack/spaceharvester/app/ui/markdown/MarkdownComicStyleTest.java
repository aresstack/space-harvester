package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.plaf.basic.BasicTableHeaderUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Angelos Fehlerbild: Tabellen in Antworten hatten Standard-Scrollbalken mit Pfeilknöpfen und einen Spaltenkopf im
 * Look-and-Feel; Trennlinien erschienen als weiße Doppellinie. Alles trägt jetzt den Comic-Stil der Blase.
 */
public class MarkdownComicStyleTest {

    private final MarkdownTheme theme = MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);

    @Test
    public void tablesUseComicScrollBarsAndAFlatBubbleColoredHeader() {
        Container rendered = render("| Kapitel | Wo |\n|---|---|\n| 2 Zweck | S. 3-4 |\n");
        MarkdownTablePanel panel = find(rendered, MarkdownTablePanel.class);
        assertNotNull(panel);
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_NEVER, panel.getVerticalScrollBarPolicy());
        assertTrue(panel.getHorizontalScrollBar().getUI() instanceof ComicScrollBarUI);
        assertEquals(ComicScrollBarUI.BAR_THICKNESS, panel.getHorizontalScrollBar().getPreferredSize().height);

        JTable table = (JTable) panel.getViewport().getView();
        assertTrue(table.getTableHeader().getUI().getClass() == BasicTableHeaderUI.class);
        assertFalse(table.getTableHeader().getReorderingAllowed());
        Component title = table.getTableHeader().getDefaultRenderer()
                .getTableCellRendererComponent(table, "Kapitel", false, false, -1, 0);
        assertTrue(title instanceof MarkdownTablePanel.HeaderRenderer);
        assertEquals(theme.getTableHeaderBackground(), title.getBackground());
        assertEquals(theme.getForeground(), title.getForeground());
        assertTrue(title.getFont().isBold());
        assertNotNull(panel.getCorner(JScrollPane.UPPER_RIGHT_CORNER));
    }

    @Test
    public void codeBlocksUseComicScrollBars() {
        Container rendered = render("```java\nint a = 1;\n```\n");
        MarkdownScrollPane scroll = find(rendered, MarkdownScrollPane.class);
        assertNotNull(scroll);
        assertEquals(JScrollPane.VERTICAL_SCROLLBAR_NEVER, scroll.getVerticalScrollBarPolicy());
        assertTrue(scroll.getHorizontalScrollBar().getUI() instanceof ComicScrollBarUI);
    }

    @Test
    public void thematicBreakIsAComicRuleInsteadOfASwingSeparator() {
        Container rendered = render("oben\n\n---\n\nunten\n");
        assertNotNull(find(rendered, MarkdownRule.class));
        assertEquals(null, find(rendered, JSeparator.class));
    }

    private Container render(String markdown) {
        return new FlexmarkSwingRenderer(theme, url -> { }, null).render(markdown, true);
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
