package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der Abschnitt „Aus Unterseiten“ (Fahrplan P3): Herkunft ganz rechts, Klick öffnet Unterseite oder Ziel. */
class SubpageSectionTest {

    @Test
    void theOriginIsTheLastColumnAndOpensTheSubpage() throws Exception {
        List<String> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            SubpageSection section = new SubpageSection("Bilder", 5, "Bild", "Beschreibung");
            section.onOpen(opened::add);
            assertThat(section.shown()).as("Standard aus").isFalse();
            section.loading();
            assertThat(section.shown()).isTrue();
            PageNode child = new PageNode("11", "Zahlungen", "DEV", null);
            PageNode second = new PageNode("12", "Belege", "DEV", null);
            PageNode third = new PageNode("13", "Kasse", "DEV", null);
            section.rows(List.of(new SubpageSection.Row(List.of("Kasse.png", "Ein Kassenbon"), child, List.of(), "11"),
                    new SubpageSection.Row(List.of("ZV", "Zahlungsverkehr"), child, List.of(second, third), null)),
                    true);
            assertThat(section.count()).isEqualTo(2);
            assertThat(section.table().getColumnName(2)).isEqualTo(SubpageSection.ORIGIN);
            // UX: Der Link nennt nur die Unterseite; „+2“ steht dahinter als gedämpfter Chip, Klick zeigt die übrigen.
            assertThat(section.table().getValueAt(1, 2)).isEqualTo("Zahlungen");
            assertThat(section.others(1)).containsExactly("Belege", "Kasse");
            assertThat(section.othersMenu(1).getComponentCount()).isEqualTo(2);
            assertThat(SubpageSection.moreTip(1)).isEqualTo("auch auf 1 weiteren Unterseite");
            assertThat(SubpageSection.moreTip(2)).isEqualTo("auch auf 2 weiteren Unterseiten");
            assertThat(section.copyText()).isEqualTo(TermsTab.BELOW + "\nKasse.png\tEin Kassenbon\tZahlungen\n"
                    + "ZV\tZahlungsverkehr\tZahlungen (+2)");
            section.open(1, 0);
            section.open(0, 2);
            section.openOther(1, 1);
            section.off();
            assertThat(section.shown()).isFalse();
        });
        assertThat(opened).containsExactly("11", "11", "13");
    }

    @Test
    void aBadgeSectionNamesTheCountBehindEachSubpage() throws Exception {
        List<String> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            SubpageSection section = new SubpageSection("Unterseiten", 8).badge("Knoten");
            section.onOpen(opened::add);
            section.rows(List.of(new SubpageSection.Row(List.of(), new PageNode("11", "Zahlungen", "DEV", null),
                    List.of(), null, 12)), true);
            assertThat(section.table().getColumnCount()).isEqualTo(1);
            assertThat(section.table().getTableHeader()).as("kein Spaltenkopf über der Liste (UX)").isNull();
            assertThat(section.table().getValueAt(0, 0)).isEqualTo("Zahlungen");
            assertThat(section.copyText()).isEqualTo(TermsTab.BELOW + "\nZahlungen (12 Knoten)");
            section.open(0, 0);
        });
        assertThat(opened).containsExactly("11");
    }

    /**
     * Architektenvorgabe zu W4, Punkt 1: Sind alle Unterseiten gesperrt, rechnet der Graph wie ohne Unterseiten; der
     * Abschnitt unter dem Graph zählt nur nicht gesperrte Unterseiten, auch wenn eine gesperrte (aus einer Analyse von
     * früher) Knoten hätte.
     */
    @Test
    void theGraphSectionCountsOnlyUnlockedSubpagesAndAllLockedIsLikeNone() {
        PageKey plan = new PageKey("wiki", "11");
        PageKey locked = new PageKey("wiki", "12");
        PageKey lockedToo = new PageKey("wiki", "13");
        PageKey start = new PageKey("wiki", "14");
        Subpages.Below below = below(List.of(plan, locked, lockedToo, start), List.of(locked, lockedToo));
        GraphNeighbourhood.Stack stack = new GraphNeighbourhood.Stack(new GraphNeighbourhood.Subgraph("wiki/1@a",
                List.of(node("wiki/11@b", plan), node("wiki/11@b#s1.t1", plan), node("wiki/12@c", locked)), List.of(),
                false, 0), List.of(lockedToo, start), 0, 2);
        List<PageKey> asked = new ArrayList<>();

        PageDetailPanel.BelowRows rows = PageDetailPanel.belowGraphRows(keys -> {
            asked.addAll(keys);
            Map<PageKey, PageNode> nodes = new LinkedHashMap<>();
            keys.forEach(key -> nodes.put(key, new PageNode(key.pageId(), key.equals(plan) ? "Plan" : "Ziel", "DEV",
                    null)));
            return nodes;
        }, below, stack);

        assertThat(rows.rows()).singleElement().satisfies(row -> assertThat(row.origin().title()).isEqualTo("Plan"));
        assertThat(rows.rows().getFirst().badge()).isEqualTo(2);
        assertThat(rows.missing()).as("nur „Start“, nicht die gesperrte").isEqualTo(1);
        assertThat(asked).containsExactly(plan);

        assertThat(PageDetailPanel.graphSubpages(below)).isEqualTo(below.pages());
        assertThat(PageDetailPanel.graphSubpages(below(List.of(locked, lockedToo), List.of(locked, lockedToo))))
                .as("alle gesperrt: wie keine Unterseiten").isNull();
        assertThat(PageDetailPanel.graphSubpages(below(List.of(), List.of()))).isNull();
        assertThat(PageDetailPanel.graphSubpages(null)).isNull();
    }

    private static Subpages.Below below(List<PageKey> keys, List<PageKey> locked) {
        return new Subpages.Below("wiki", "DEV", "1", new PageSelection(PageSet.ofKeys(keys), HoldingCounts.ZERO,
                List.of(), true, PageSet.ofKeys(locked)));
    }

    private static GraphNeighbourhood.Node node(String id, PageKey key) {
        boolean document = !id.contains("#");
        return new GraphNeighbourhood.Node(id, document ? PageGraph.NodeKind.DOCUMENT : PageGraph.NodeKind.MENTION,
                "Nachtlauf", null, new KnowledgeGraph.PageRef(key, "DEV", "Plan", PageStatements.State.CURRENT, null),
                document ? -1 : 0, document ? -1 : 9, false);
    }
}
