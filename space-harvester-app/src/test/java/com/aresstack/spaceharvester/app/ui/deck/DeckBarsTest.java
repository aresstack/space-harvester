package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Leisten des Command Deck (Angelo, 23.11.2026): Kopf und Fuß so hoch wie im Space Harvester, die Fußleiste ist eine
 * Werkzeugleiste und keine Statuszeile; der Stand eines Bereichs steht in der Statuszeile der App.
 */
class DeckBarsTest {

    @Test
    void headAndFootKeepTheirHeightAndCentreWhatTheyCarry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton small = new JButton("a");
            small.setPreferredSize(new java.awt.Dimension(40, 21));
            JButton tall = new JButton("b");
            tall.setPreferredSize(new java.awt.Dimension(60, 90));
            JPanel head = DeckBars.head(small, tall);
            assertThat(head.getPreferredSize().height).isEqualTo(DeckBars.HEAD_HEIGHT);
            head.setSize(400, DeckBars.HEAD_HEIGHT);
            head.doLayout();
            int room = DeckBars.HEAD_HEIGHT - 1;
            // Das kleine Element sitzt mittig in seiner eigenen Höhe, das zu hohe wird auf die Leiste begrenzt.
            assertThat(small.getHeight()).isEqualTo(21);
            assertThat(small.getY()).isEqualTo((room - 21) / 2);
            assertThat(tall.getHeight()).isEqualTo(room);
            assertThat(tall.getX() + tall.getWidth()).isEqualTo(400 - head.getInsets().right);
            assertThat(small.getX() + small.getWidth()).isLessThanOrEqualTo(tall.getX());
            assertThat(DeckBars.foot(null, small).getPreferredSize().height).isEqualTo(DeckBars.FOOT_HEIGHT);
        });
    }

    @Test
    void filesShowTheirStateInTheStatusLineOfTheAppNotInTheFootBar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CommandDeck deck = new CommandDeck();
            try {
                FilesWorkspace files = deck.files();
                BorderLayout layout = (BorderLayout) files.getLayout();
                Component head = layout.getLayoutComponent(BorderLayout.NORTH);
                assertThat(head.getPreferredSize().height).isEqualTo(DeckBars.HEAD_HEIGHT);
                assertThat(layout.getLayoutComponent(BorderLayout.SOUTH)).as("keine eigene Fußleiste").isNull();
                // Die Aktionen stehen neben der Lupe in der Fußleiste des Command Deck, als Symbolknöpfe.
                deck.show(DeckArea.FILES);
                Container tools = files.footTools();
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(tools, deck.findBar())).isTrue();
                assertThat(deck.findBar().getPreferredSize().height).isEqualTo(DeckBars.FOOT_HEIGHT);
                assertThat(labels(tools)).as("kein Statustext in der Werkzeugleiste").isZero();
                assertThat(buttons(tools)).isNotEmpty().allSatisfy(button -> {
                    assertThat(button).isInstanceOf(com.aresstack.spaceharvester.app.ui.IconButton.class);
                    assertThat(button.getText()).as("Symbol statt Text").isNullOrEmpty();
                    assertThat(button.getToolTipText()).isNotBlank();
                });
                deck.show(DeckArea.TERMINAL);
                assertThat(javax.swing.SwingUtilities.isDescendingFrom(tools, deck.findBar()))
                        .as("jeder Bereich zeigt nur seine eigenen Bedienelemente").isFalse();
                assertThat(files.statusText()).isNotBlank();
                assertThat(((StatusLabel) files.statusInfo()).getAccessibleContext().getAccessibleName())
                        .isEqualTo(files.statusText());
            } finally {
                deck.shutdown();
            }
        });
    }

    private static java.util.List<javax.swing.AbstractButton> buttons(Container root) {
        java.util.List<javax.swing.AbstractButton> found = new java.util.ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.AbstractButton button) {
                found.add(button);
            } else if (child instanceof Container nested) {
                found.addAll(buttons(nested));
            }
        }
        return found;
    }

    /** Was allein in einer Leiste steht, beginnt links und nicht in der rechten Ecke (Angelo, 23.11.2026). */
    @Test
    void whatStandsAloneInABarStartsAtTheLeft() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JPanel actions = DeckBars.tools(new JLabel("x"));
            javax.swing.JPanel foot = DeckBars.foot(null, actions);
            foot.setSize(800, DeckBars.FOOT_HEIGHT);
            foot.doLayout();
            assertThat(actions.getX()).isEqualTo(foot.getInsets().left);
        });
    }

    private static int labels(Container root) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && !(child instanceof javax.swing.table.TableCellRenderer)
                    && label.getText() != null && !label.getText().isBlank() && label.isVisible()) {
                count++;
            } else if (child instanceof Container nested && !(child instanceof javax.swing.AbstractButton)) {
                count += labels(nested);
            }
        }
        return count;
    }
}
