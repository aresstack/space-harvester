package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseWheelEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;

/**
 * Eine Tabelle in einer scrollenden Seite: ohne eigenen Scrollbedarf geht das Rad an die Seite, mit Scrollbedarf
 * scrollt erst die Tabelle und erst am Ende die Seite.
 */
class WheelForwardingTest {

    @Test
    void wheelOverATableWithoutScrollNeedScrollsThePage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture fixture = new Fixture(2);
            assertThat(fixture.inner.getVerticalScrollBar().isVisible()).isFalse();
            fixture.wheel(3);
            assertThat(fixture.outer.getVerticalScrollBar().getValue()).as("Seite scrollt").isPositive();
        });
    }

    @Test
    void wheelOverAScrollableTableScrollsTheTableFirstAndThePageAtItsEnd() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture fixture = new Fixture(40);
            assertThat(fixture.inner.getVerticalScrollBar().isVisible()).isTrue();
            fixture.wheel(1);
            assertThat(fixture.inner.getVerticalScrollBar().getValue()).as("Tabelle scrollt").isPositive();
            assertThat(fixture.outer.getVerticalScrollBar().getValue()).as("Seite bleibt").isZero();

            fixture.inner.getVerticalScrollBar().setValue(fixture.inner.getVerticalScrollBar().getMaximum());
            fixture.wheel(1);
            assertThat(fixture.outer.getVerticalScrollBar().getValue()).as("am Ende geht es an die Seite").isPositive();

            // Zurück nach oben: die Tabelle ist unten, also scrollt sie selbst wieder.
            int page = fixture.outer.getVerticalScrollBar().getValue();
            int table = fixture.inner.getVerticalScrollBar().getValue();
            fixture.wheel(-1);
            assertThat(fixture.inner.getVerticalScrollBar().getValue()).isLessThan(table);
            assertThat(fixture.outer.getVerticalScrollBar().getValue()).isEqualTo(page);
        });
    }

    @Test
    void installingTwiceKeepsOneForwarder() {
        JScrollPane pane = new JScrollPane(new JTable());
        WheelForwarding.install(pane);
        WheelForwarding.install(pane);
        assertThat(pane.getMouseWheelListeners()).hasSize(1);
    }

    private static final class Fixture {
        final JScrollPane inner;
        final JScrollPane outer;

        Fixture(int rows) {
            DefaultTableModel model = new DefaultTableModel(new Object[] {"Ordner"}, 0);
            for (int i = 0; i < rows; i++) {
                model.addRow(new Object[] {"/ordner/" + i});
            }
            JTable table = new JTable(model);
            table.setRowHeight(20);
            inner = WheelForwarding.install(new JScrollPane(table));
            inner.setPreferredSize(new Dimension(300, 120));
            inner.setMaximumSize(new Dimension(300, 120));
            JPanel page = new JPanel();
            page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
            page.add(inner);
            page.add(Box.createRigidArea(new Dimension(300, 2000)));
            outer = new JScrollPane(page);
            JPanel window = new JPanel(new BorderLayout());
            window.add(outer);
            window.setSize(320, 400);
            layout(window);
        }

        void wheel(int rotation) {
            MouseWheelEvent event = new MouseWheelEvent(inner, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
            inner.dispatchEvent(event);
        }

        private static void layout(java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                if (child instanceof java.awt.Container nested) {
                    layout(nested);
                }
            }
        }
    }
}
