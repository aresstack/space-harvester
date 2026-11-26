package com.aresstack.spaceharvester.app.ui.deck.editor;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.mainframe.dependency.CallNode;
import com.aresstack.spaceharvester.mainframe.dependency.NaturalRelations;
import com.aresstack.spaceharvester.mainframe.dependency.SourceRelations;
import com.aresstack.spaceharvester.mainframe.diagram.SystemFunctions;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Callees/Caller mit Filtern wie im LeftDrawer von MainframeMate (erfundene Bibliothek und Objekte). */
class RelationsPanelTest {

    private static final String SOURCE = """
            DEFINE DATA
            LOCAL USING TESTLDA
            END-DEFINE
            CALLNAT 'UNTERPRG' #A
            PERFORM AUSGABE
            END
            """;

    @Test
    void filtersGroupsAndOpensLikeTheLeftDrawer() throws Exception {
        NaturalRelations graph = new NaturalRelations() {
            @Override
            public Optional<CallNode> callees(String library, String object, int depth) {
                return Optional.of(CallNode.root(object, List.of(
                        new CallNode("UNTERPRG", "ndv://TESTLIB/UNTERPRG", false, 4, "ndv://TESTLIB/UMSATZ",
                                "CALLNAT", List.of(new CallNode("HILFE", "ndv://TESTLIB/HILFE", false, 7,
                                        "ndv://TESTLIB/UNTERPRG", "CALLNAT", List.of()))),
                        new CallNode("AUSGABE", "ndv://TESTLIB/AUSGABE", false, 5, "ndv://TESTLIB/UMSATZ",
                                "PERFORM", List.of()))));
            }

            @Override
            public Optional<CallNode> callers(String library, String object, int depth) {
                return Optional.of(CallNode.root(object, List.of(new CallNode("HAUPT", "ndv://TESTLIB/HAUPT", false,
                        12, "ndv://TESTLIB/HAUPT", "CALLNAT", List.of()))));
            }
        };
        SourceRelations.Analysis analysis = new SourceRelations(graph, (c, n) -> List.of(), SystemFunctions.DEFAULT_NAMES::contains)
                .analyze(Optional.of(SourceLanguage.NATURAL), SOURCE, "TESTLIB/UMSATZ.NSP", "TESTLIB");
        List<String> lines = new CopyOnWriteArrayList<>();
        List<String> opens = new CopyOnWriteArrayList<>();
        AtomicReference<RelationsPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new RelationsPanel(RelationsPanel.actions(lines::add, opens::add)));
            panel.get().group(RelationsPanel.CALLEES, false);
            panel.get().show(analysis);
        });
        RelationsPanel p = panel.get();

        assertThat(on(p::resourceLabels)).anyMatch(l -> l.contains("USING"));
        assertThat(on(() -> p.sideTitle(RelationsPanel.CALLEES))).isEqualTo("Callees (3)");
        assertThat(on(() -> p.sideTitle(RelationsPanel.CALLERS))).isEqualTo("Caller (1)");
        SwingUtilities.invokeAndWait(() -> {
            assertRenderedIcons(p.resourceTree());
            assertRenderedIcons(p.callTree(RelationsPanel.CALLEES));
            assertRenderedIcons(p.callTree(RelationsPanel.CALLERS));
        });

        SwingUtilities.invokeAndWait(() -> p.hideKind(RelationsPanel.CALLEES, "PERFORM", true));
        assertThat(on(() -> p.sideTitle(RelationsPanel.CALLEES))).isEqualTo("Callees (2/3)");
        assertThat(on(() -> p.labels(RelationsPanel.CALLEES))).containsExactly("UNTERPRG", "HILFE");

        SwingUtilities.invokeAndWait(() -> {
            p.hideKind(RelationsPanel.CALLEES, "PERFORM", false);
            p.group(RelationsPanel.CALLEES, true);
        });
        assertThat(on(() -> p.labels(RelationsPanel.CALLEES))).containsExactly("CALLNAT (1)", "UNTERPRG",
                "HILFE", "PERFORM (1)", "AUSGABE");

        SwingUtilities.invokeAndWait(() -> {
            p.group(RelationsPanel.CALLEES, false);
            p.nameFilter(RelationsPanel.CALLEES, "^UMSATZ$");
        });
        assertThat(on(() -> p.labels(RelationsPanel.CALLEES))).containsExactly("UNTERPRG", "AUSGABE");

        // Passt nur ein Nachfahre, bleibt der Pfad dorthin stehen (Codex #1204).
        SwingUtilities.invokeAndWait(() -> p.nameFilter(RelationsPanel.CALLEES, "^UNTERPRG$"));
        assertThat(on(() -> p.labels(RelationsPanel.CALLEES))).containsExactly("UNTERPRG", "HILFE");
        SwingUtilities.invokeAndWait(() -> p.group(RelationsPanel.CALLEES, true));
        assertThat(on(() -> p.labels(RelationsPanel.CALLEES))).containsExactly("CALLNAT (1)", "UNTERPRG",
                "HILFE");
        SwingUtilities.invokeAndWait(() -> {
            p.group(RelationsPanel.CALLEES, false);
            p.nameFilter(RelationsPanel.CALLEES, "^UMSATZ$");
        });

        // Einfachklick: Zeile in der Quelle; Doppelklick: Ziel öffnen.
        SwingUtilities.invokeAndWait(() -> p.clickFirst(RelationsPanel.CALLEES, 1));
        SwingUtilities.invokeAndWait(() -> p.clickFirst(RelationsPanel.CALLEES, 2));
        assertThat(lines).containsExactly("ndv://TESTLIB/UMSATZ:4");
        assertThat(opens).containsExactly("ndv://TESTLIB/UNTERPRG");
    }

    @Test
    void systemFunctionsLinkToWikipediaAndCobolUsesCodeAnalytics() {
        assertThat(DocumentEditors.wikipedia("idcams").toString())
                .isEqualTo("https://de.wikipedia.org/wiki/Access_Method_Services");
        assertThat(DocumentEditors.wikipedia("UNBEKANNT").toString())
                .isEqualTo("https://de.wikipedia.org/w/index.php?search=UNBEKANNT");

        String cobol = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID. BEISPIEL.
                       PROCEDURE DIVISION.
                           CALL 'UNTERPRG' USING WS-DATEN.
                           STOP RUN.
                """;
        SourceRelations.Analysis analysis = DocumentEditors.relations()
                .analyze(Optional.of(SourceLanguage.COBOL), cobol, "BEISPIEL.cbl", null);
        assertThat(analysis.callees().root()).isPresent();
        assertThat(analysis.callees().root().get().children()).extracting(CallNode::label)
                .anyMatch(label -> label.contains("UNTERPRG"));
    }

    private static void assertRenderedIcons(javax.swing.JTree tree) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            Object node = tree.getPathForRow(row).getLastPathComponent();
            var label = (javax.swing.JLabel) tree.getCellRenderer().getTreeCellRendererComponent(
                    tree, node, false, true, tree.getModel().isLeaf(node), row, false);
            assertThat(label.getIcon()).isNotNull();
            assertThat(label.getText()).doesNotContain("📞", "📦", "🔄", "🌿", "📎");
        }
    }

    private static <T> T on(java.util.function.Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> value.set(read.get()));
        return value.get();
    }
}
