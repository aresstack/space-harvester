package com.aresstack.spaceharvester.application.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExportTreeBuilderTest {

    private static ExportTree.Node leaf(String id) {
        return new ExportTree.Node(id, List.of());
    }

    @Test
    void childrenMayArriveBeforeTheirParent() {
        ExportTreeBuilder builder = new ExportTreeBuilder();
        builder.add("2", "1");
        builder.add("1", null);
        builder.add("3", "1");

        ExportTree tree = builder.build();

        assertThat(tree.roots()).containsExactly(new ExportTree.Node("1", List.of(leaf("2"), leaf("3"))));
        assertThat(tree.warnings()).isEmpty();
    }

    @Test
    void severalRoots() {
        ExportTreeBuilder builder = new ExportTreeBuilder();
        builder.add("a", null);
        builder.add("b", null);

        assertThat(builder.build().roots()).containsExactly(leaf("a"), leaf("b"));
    }

    @Test
    void cycleIsBrokenWithWarningAndEveryPageAppearsOnce() {
        ExportTreeBuilder builder = new ExportTreeBuilder();
        builder.add("r", null);
        builder.add("x", "y");
        builder.add("y", "x");

        ExportTree tree = builder.build();

        assertThat(tree.roots()).containsExactly(leaf("r"), new ExportTree.Node("x", List.of(leaf("y"))));
        assertThat(tree.warnings()).singleElement().asString().contains("Zyklus", "x");
    }

    @Test
    void selfParentIsACycle() {
        ExportTreeBuilder builder = new ExportTreeBuilder();
        builder.add("x", "x");

        ExportTree tree = builder.build();

        assertThat(tree.roots()).containsExactly(leaf("x"));
        assertThat(tree.warnings()).hasSize(1);
    }

    @Test
    void duplicateIsRejected() {
        ExportTreeBuilder builder = new ExportTreeBuilder();

        assertThat(builder.add("x", null)).isTrue();
        assertThat(builder.add("x", "y")).isFalse();
    }
}
