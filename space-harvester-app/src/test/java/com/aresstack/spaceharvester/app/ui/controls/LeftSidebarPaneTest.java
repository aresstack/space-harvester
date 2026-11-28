package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Angelo 28.11.2026: Eine zugeklappte Seitenleiste zeigt keinen Trenner mehr. Vorher blieb links ein Griff, der sich
 * aufziehen ließ und die Leiste falsch stehen ließ.
 */
class LeftSidebarPaneTest {

    @Test
    void aCollapsedSidebarHasNoDividerAndComesBackWithIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LeftSidebarPane pane = new LeftSidebarPane(new JPanel(), new JPanel(), 280);
            assertThat(pane.getDividerSize()).isEqualTo(ComicDivider.THICKNESS);

            pane.toggle();
            assertThat(pane.isLeftCollapsed()).isTrue();
            assertThat(pane.hiddenByUser()).isTrue();
            assertThat(pane.getDividerSize()).as("kein Trenner, also nichts zum Aufziehen").isZero();
            assertThat(pane.getDividerLocation()).isZero();

            pane.toggle();
            assertThat(pane.isLeftCollapsed()).isFalse();
            assertThat(pane.hiddenByUser()).isFalse();
            assertThat(pane.getDividerSize()).isEqualTo(ComicDivider.THICKNESS);

            // Eine Perspektive ohne Leiste nimmt sie weg, ohne dass der Mensch sie zugeklappt hätte.
            pane.collapseLeft();
            assertThat(pane.hiddenByUser()).isFalse();
            assertThat(pane.getDividerSize()).isZero();
        });
    }
}
