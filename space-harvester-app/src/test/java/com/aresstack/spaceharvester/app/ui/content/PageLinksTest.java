package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pfad und Querverweise der Leseansicht (#177). */
class PageLinksTest {

    @Test
    void theBreadcrumbsNameTheSpaceAndOpenEachAncestor() throws Exception {
        List<String> opened = new ArrayList<>();
        List<String> text = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            Breadcrumbs crumbs = new Breadcrumbs();
            crumbs.onOpen(opened::add);
            crumbs.show(new PageNavigation.Position("wiki", "DEV", "Entwicklung",
                    List.of(new PageNode("1", "Start", "DEV", true), new PageNode("2", "Bereich", "DEV", true)),
                    new PageNode("3", "Hier", "DEV", false)));
            text.add(crumbs.text());
            text.add(String.valueOf(crumbs.isVisible()));
            java.awt.event.MouseListener[] listeners = crumbs.getComponent(2).getMouseListeners();
            listeners[listeners.length - 1].mouseClicked(new java.awt.event.MouseEvent(crumbs, 0, 0, 0, 0, 0, 1, false));
            crumbs.clear();
            text.add(String.valueOf(crumbs.isVisible()));
        });
        assertThat(text).containsExactly("Entwicklung (DEV) › Start › Bereich", "true", "false");
        assertThat(opened).containsExactly("1");
    }

    @Test
    void theReferencesListBothDirectionsAndSayWhenIncomingIsUnknown() throws Exception {
        List<String> seen = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            References references = new References();
            references.onOpen(opened::add);
            references.show(new PageNavigation.References("wiki", "3",
                    List.of(new PageNode("7", "Ziel", "DEV", null)), List.of(new PageNode("8", "Quelle", "OPS", null))));
            seen.add(references.hint());
            seen.add(references.table().getRowCount() + " " + references.table().getValueAt(1, 0) + " "
                    + references.table().getValueAt(1, 1) + " " + references.table().getValueAt(1, 2));
            references.table().setRowSelectionInterval(0, 0);
            references.show(new PageNavigation.References("wiki", "3", List.of(), null));
            seen.add(references.hint());
        });
        assertThat(seen).containsExactly("Diese Seite verweist auf 1 Seite; 1 Seite verweist auf sie.",
                "2 verwiesen von Quelle OPS",
                "Diese Seite verweist auf keine andere Seite der Quelle; eingehende Verweise kennt die Quelle nicht "
                        + "vollständig.");
        assertThat(opened).containsExactly("7");
    }
}
