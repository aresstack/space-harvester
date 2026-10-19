package com.aresstack.spaceharvester.application.tree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link PageTreeBuilder}: mehrere Wurzeln, fehlende Eltern, Zyklen, jede Seite genau einmal. */
class PageTreeBuilderTest {

    @Test
    void emptyInputIsAnEmptyTree() {
        PageTree tree = PageTreeBuilder.build(List.of());

        assertThat(tree.roots()).isEmpty();
        assertThat(tree.nodeCount()).isZero();
        assertThat(tree.depth()).isZero();
    }

    @Test
    void buildsSeveralRootsWithSortedChildren() {
        PageTree tree = PageTreeBuilder.build(List.of(
                entry("20", null), entry("10", null), entry("13", "10"), entry("11", "10"), entry("12", "11")));

        assertThat(render(tree)).isEqualTo("10(11(12),13) 20");
        assertThat(tree.anomalies()).isEmpty();
        assertThat(tree.nodeCount()).isEqualTo(5);
        assertThat(tree.depth()).isEqualTo(3);
    }

    /** Wie Confluence /child/page: erst Position, dann Titel (ohne Groß-/Kleinschreibung), dann Seiten-ID. */
    @Test
    void siblingsFollowPositionThenTitleThenPageId() {
        PageTree tree = PageTreeBuilder.build(List.of(entry("1", null),
                new TreeEntry("9", "1", null, "alpha"), new TreeEntry("2", "1", null, "Zulu"),
                new TreeEntry("8", "1", 1L, "Yankee"), new TreeEntry("3", "1", 0L, "X-Ray"),
                new TreeEntry("5", "1", null, "Beta"), new TreeEntry("4", "1", null, "Beta"),
                new TreeEntry("7", "1", null, null)));

        assertThat(render(tree)).isEqualTo("1(3,8,9,4,5,2,7)");
    }

    @Test
    void pageWithMissingParentBecomesARootWithItsSubtree() {
        PageTree tree = PageTreeBuilder.build(List.of(
                entry("1", null), entry("5", "4"), entry("6", "5")));

        assertThat(render(tree)).isEqualTo("1 5(6)");
        assertThat(tree.anomalies()).containsExactly(new PageTree.Anomaly("5", PageTree.Kind.MISSING_PARENT));
    }

    @Test
    void selfParentIsACycle() {
        PageTree tree = PageTreeBuilder.build(List.of(entry("7", "7"), entry("8", "7")));

        assertThat(render(tree)).isEqualTo("7(8)");
        assertThat(tree.anomalies()).containsExactly(new PageTree.Anomaly("7", PageTree.Kind.CYCLE));
    }

    @Test
    void cycleIsBrokenAtItsSmallestPageIdAndHangingPagesStay() {
        // 3 -> 4 -> 5 -> 3 ist ein Zyklus; 6 hängt an 5, 9 an 6.
        PageTree tree = PageTreeBuilder.build(List.of(
                entry("4", "3"), entry("5", "4"), entry("3", "5"), entry("6", "5"), entry("9", "6"),
                entry("1", null)));

        assertThat(render(tree)).isEqualTo("1 3(4(5(6(9))))");
        assertThat(tree.anomalies()).containsExactly(new PageTree.Anomaly("3", PageTree.Kind.CYCLE));
        assertThat(tree.nodeCount()).isEqualTo(6);
    }

    @Test
    void resultDoesNotDependOnInputOrder() {
        List<TreeEntry> entries = new ArrayList<>(List.of(
                entry("1", null), entry("2", "1"), entry("3", "1"), entry("4", "9"), entry("5", "6"),
                entry("6", "5"), entry("7", "2")));
        String expected = render(PageTreeBuilder.build(entries));
        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(entries, random);
            assertThat(render(PageTreeBuilder.build(entries))).isEqualTo(expected);
        }
    }

    @Test
    void veryDeepChainNeedsNoRecursion() {
        List<TreeEntry> entries = new ArrayList<>();
        entries.add(entry("p0", null));
        for (int i = 1; i < 100_000; i++) {
            entries.add(entry("p" + i, "p" + (i - 1)));
        }

        PageTree tree = PageTreeBuilder.build(entries);

        assertThat(tree.depth()).isEqualTo(100_000);
        assertThat(tree.nodeCount()).isEqualTo(100_000);
        assertThat(tree.roots()).hasSize(1);
    }

    @Test
    void duplicatePageIdIsRejected() {
        assertThatThrownBy(() -> PageTreeBuilder.build(List.of(entry("1", null), entry("1", "2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TreeEntry entry(String pageId, String parentPageId) {
        return new TreeEntry(pageId, parentPageId);
    }

    /** Kompakte Textform: Wurzeln durch Leerzeichen getrennt, Kinder in Klammern. Nur für flache Testbäume. */
    static String render(PageTree tree) {
        StringBuilder text = new StringBuilder();
        for (PageTree.Node root : tree.roots()) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            render(root, text);
        }
        return text.toString();
    }

    private static void render(PageTree.Node node, StringBuilder text) {
        text.append(node.pageId());
        if (!node.children().isEmpty()) {
            text.append('(');
            for (int i = 0; i < node.children().size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                render(node.children().get(i), text);
            }
            text.append(')');
        }
    }
}
