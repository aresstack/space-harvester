package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Bereich des Chats für Wurzeln, die der Katalog nicht kennt (Angelo 26.11.2026: bei SharePoint antwortete der
 * Chat trotz Auswahl im Baum, ihm lägen keine Wissensquellen vor). Dieselbe Regel für jede Quellenart, gegen eine
 * erfundene Navigation in der Form der jeweiligen Quelle: Kennungen, Partitionen, Ordner und Fortsetzungen wie dort.
 */
class LiveAreaTest {

    /**
     * Eine Quellenart in ihrer Form: Wurzel (der gewählte Knoten), ein Kind, ein Enkel darunter, eine Seite daneben
     * (gleiche Quelle, nicht im Bereich) und die Partition, die die Quelle für ihre Seiten meldet.
     */
    record Shape(String name, String sourceId, String partition, String root, String child, String grandchild,
                 String sibling) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Shape> shapes() {
        return Stream.of(
                // SharePoint: gruppenbasiert, Partition = Quelle, Ordner als Pfad (ADR 0040/0042).
                new Shape("SharePoint", "sp", "sp", "folder:/sites/team/Dokumente/Projekt",
                        "folder:/sites/team/Dokumente/Projekt/Protokolle",
                        "file:/sites/team/Dokumente/Projekt/Protokolle/2026-11.docx",
                        "file:/sites/team/Dokumente/Anderes/Liste.xlsx"),
                // BSCW: Wurzel ist eine Seite, Kennungen sind unabhängige Zahlen.
                new Shape("BSCW", "bscw", "ablage", "4711", "4712", "4713", "9001"),
                // Lokale Dateien: Ordner und Dateien als relative Pfade.
                new Shape("Lokale Dateien", "dateien", "handbuch", "d:betrieb", "d:betrieb/nacht",
                        "f:betrieb/nacht/abbruch.md", "f:entwicklung/regeln.md"),
                // Confluence ohne Abgleich: Space-Schlüssel als Partition, Seiten-IDs als Zahlen.
                new Shape("Confluence", "wiki", "TEAM", "1001", "1002", "1003", "2001"),
                // FTP: HLQ als Partition, Datasets und Member.
                new Shape("FTP", "ftp", "PROD", "PROD.JCL", "PROD.JCL(NACHT)", "PROD.JCL(NACHT).STEP1",
                        "PROD.COBOL(BUCHUNG)"),
                // NDV: Bibliothek als Partition, Objekte darin.
                new Shape("NDV", "ndv", "LIBA", "LIBA/Programme", "LIBA/Programme/PGM01",
                        "LIBA/Programme/PGM01/Copycode", "LIBA/Maps/MAP01"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void aSelectedNodeOutsideTheCatalogIsAnAreaWithItsDescendants(Shape shape) {
        FakeNavigation navigation = new FakeNavigation(shape);
        Selection.Root root = Selection.Root.subtree(shape.sourceId(), shape.partition(), shape.root());
        LiveArea area = new LiveArea(navigation, LiveArea.uncovered(emptyCatalog(), List.of(root)));

        assertThat(area.isEmpty()).isFalse();
        // Die oberste Liste sind die Kinder der gewählten Seite, gefolgt über alle Fortsetzungen der Quelle.
        List<LiveArea.Node> top = area.children(null, () -> false);
        assertThat(top).extracting(node -> node.key().pageId()).containsExactly(shape.child(), shape.child() + "#2");
        assertThat(top.get(0).folder()).isTrue();
        assertThat(navigation.cursors).contains("weiter");

        PageKey child = new PageKey(shape.sourceId(), shape.child());
        PageKey grandchild = new PageKey(shape.sourceId(), shape.grandchild());
        PageKey sibling = new PageKey(shape.sourceId(), shape.sibling());
        // Gelistetes gehört ohne weitere Frage dazu, mit der Partition der Quelle.
        int before = navigation.positions.get();
        assertThat(area.contains(child)).isTrue();
        assertThat(area.spaceOf(child)).isEqualTo(shape.partition());
        assertThat(area.isFolder(child)).isTrue();
        assertThat(navigation.positions.get()).isEqualTo(before);
        // Ein Enkel (etwa ein Suchtreffer) gehört nach seinen Vorfahren dazu, die Nachbarseite nicht.
        assertThat(area.contains(grandchild)).isTrue();
        assertThat(area.spaceOf(grandchild)).isEqualTo(shape.partition());
        assertThat(area.contains(sibling)).isFalse();
        // Die Lage wird je Seite nur einmal gefragt.
        int asked = navigation.positions.get();
        assertThat(area.contains(grandchild)).isTrue();
        assertThat(area.contains(sibling)).isFalse();
        assertThat(navigation.positions.get()).isEqualTo(asked);
        // Eine andere Quelle, eine unbekannte Seite: nicht im Bereich, ohne Fehler.
        assertThat(area.contains(new PageKey("fremd", shape.grandchild()))).isFalse();
        assertThat(area.contains(new PageKey(shape.sourceId(), "gibt-es-nicht"))).isFalse();
        // Die Liste unter einem Kind kommt von der Quelle; außerhalb des Bereichs gibt es keine.
        assertThat(area.children(child, () -> false)).extracting(node -> node.key().pageId())
                .containsExactly(shape.grandchild());
        assertThat(area.children(sibling, () -> false)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void aSelectedSpaceOutsideTheCatalogListsItsStartPagesAndTakesItsPages(Shape shape) {
        FakeNavigation navigation = new FakeNavigation(shape);
        LiveArea area = new LiveArea(navigation, LiveArea.uncovered(emptyCatalog(),
                List.of(Selection.Root.space(shape.sourceId(), shape.partition()))));

        assertThat(area.children(null, () -> false)).extracting(node -> node.key().pageId())
                .containsExactly(shape.root());
        assertThat(area.contains(new PageKey(shape.sourceId(), shape.sibling()))).isTrue();
        assertThat(area.contains(new PageKey(shape.sourceId(), "anderer-space"))).isFalse();
    }

    @Test
    void rootsTheCatalogCoversStayWithTheCatalog() {
        PageSelection catalog = new PageSelection(PageSet.of(Map.of("wiki", List.of("1001", "1002"))),
                HoldingCounts.ZERO, List.of(new SpaceSummary("wiki", "TEAM", null, HoldingCounts.ZERO)), true);
        Selection.Root covered = Selection.Root.subtree("wiki", "TEAM", "1001");
        Selection.Root folder = Selection.Root.subtree("sp", "sp", "folder:/sites/team/Dokumente");
        Selection.Root emptySpace = Selection.Root.space("wiki", "LEER");
        Selection.Root wholeSource = Selection.Root.source("bscw");

        assertThat(LiveArea.uncovered(catalog, List.of(covered, folder, emptySpace, wholeSource)))
                .containsExactly(folder, emptySpace, wholeSource);
        // Ohne aufgelösten Bereich (die Auflösung scheiterte) gilt jede Wurzel über die Navigation.
        assertThat(LiveArea.uncovered(null, List.of(covered))).containsExactly(covered);
        assertThat(LiveArea.uncovered(catalog, List.of())).isEmpty();
        assertThat(LiveArea.NONE.isEmpty()).isTrue();
        assertThat(LiveArea.NONE.contains(new PageKey("wiki", "1001"))).isFalse();
    }

    @Test
    void aWholeSourceTakesEveryPageOfItWithoutAskingTheSource() {
        FakeNavigation navigation = new FakeNavigation(shapes()
                .filter(shape -> shape.name().equals("BSCW")).findFirst().orElseThrow());
        LiveArea area = new LiveArea(navigation, List.of(Selection.Root.source("bscw")));

        assertThat(area.contains(new PageKey("bscw", "9001"))).isTrue();
        assertThat(area.contains(new PageKey("sp", "9001"))).isFalse();
        assertThat(navigation.positions.get()).isZero();
        assertThat(area.children(null, () -> false)).isEmpty();
    }

    @Test
    void aRefusedPositionMeansNotInTheArea() {
        LiveArea area = new LiveArea(new LiveArea.Navigation() {
            @Override
            public PageNavigation.Children children(String sourceId, String spaceKey, String parentPageId,
                                                    String cursor) {
                throw new IllegalStateException("Quelle nicht erreichbar");
            }

            @Override
            public PageNavigation.Position position(String sourceId, String pageId) {
                throw new IllegalStateException("Quelle nicht erreichbar");
            }
        }, List.of(Selection.Root.subtree("sp", "sp", "folder:/a")));

        assertThat(area.contains(new PageKey("sp", "file:/a/b.docx"))).isFalse();
        assertThat(area.contains(new PageKey("sp", "folder:/a"))).isTrue();
    }

    private static PageSelection emptyCatalog() {
        return new PageSelection(PageSet.EMPTY, HoldingCounts.ZERO, List.of(), false);
    }

    /**
     * Eine erfundene Navigation in der Form der Quelle: die Wurzel unter dem Space, darunter zwei Kinder in zwei
     * Stapeln (Fortsetzung „weiter“), unter dem ersten Kind ein Enkel; die Nachbarseite liegt direkt im Space.
     */
    static final class FakeNavigation implements LiveArea.Navigation {

        private final Shape shape;
        private final Map<String, String> parents = new HashMap<>();
        final List<String> cursors = new ArrayList<>();
        final AtomicInteger positions = new AtomicInteger();

        FakeNavigation(Shape shape) {
            this.shape = shape;
            parents.put(shape.root(), null);
            parents.put(shape.child(), shape.root());
            parents.put(shape.child() + "#2", shape.root());
            parents.put(shape.grandchild(), shape.child());
            parents.put(shape.sibling(), null);
        }

        @Override
        public PageNavigation.Children children(String sourceId, String spaceKey, String parentPageId,
                                                String cursor) {
            cursors.add(String.valueOf(cursor));
            if (!shape.sourceId().equals(sourceId)) {
                throw new IllegalArgumentException("fremde Quelle");
            }
            if (parentPageId == null) {
                return new PageNavigation.Children(sourceId, shape.partition(), null,
                        List.of(node(shape.root(), PageNode.Kind.FOLDER)), null);
            }
            if (parentPageId.equals(shape.root())) {
                return cursor == null
                        ? new PageNavigation.Children(sourceId, shape.partition(), parentPageId,
                        List.of(node(shape.child(), PageNode.Kind.FOLDER)), "weiter")
                        : new PageNavigation.Children(sourceId, shape.partition(), parentPageId,
                        List.of(node(shape.child(), PageNode.Kind.FOLDER), node(shape.child() + "#2",
                                PageNode.Kind.FILE)), null);
            }
            if (parentPageId.equals(shape.child())) {
                return new PageNavigation.Children(sourceId, shape.partition(), parentPageId,
                        List.of(node(shape.grandchild(), PageNode.Kind.FILE)), null);
            }
            return new PageNavigation.Children(sourceId, shape.partition(), parentPageId, List.of(), null);
        }

        @Override
        public PageNavigation.Position position(String sourceId, String pageId) {
            positions.incrementAndGet();
            if (!shape.sourceId().equals(sourceId) || !parents.containsKey(pageId)) {
                throw new IllegalArgumentException("unbekannte Seite");
            }
            List<PageNode> ancestors = new ArrayList<>();
            for (String parent = parents.get(pageId); parent != null; parent = parents.get(parent)) {
                ancestors.add(0, node(parent, PageNode.Kind.FOLDER));
            }
            return new PageNavigation.Position(sourceId, shape.partition(), shape.partition(), ancestors,
                    node(pageId, PageNode.Kind.FILE));
        }

        private PageNode node(String pageId, PageNode.Kind kind) {
            return new PageNode(pageId, "Titel " + pageId, shape.partition(), kind != PageNode.Kind.FILE, kind);
        }
    }
}
