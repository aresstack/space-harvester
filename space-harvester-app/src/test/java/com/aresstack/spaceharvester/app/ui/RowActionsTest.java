package com.aresstack.spaceharvester.app.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowActionsTest {

    /** JTable fragt den Tooltip am Renderer ohne gesetzte Größe ab; jedes Symbol muss trotzdem seinen Text zeigen. */
    @Test
    void everySlotShowsItsTooltipInTheTable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new DefaultTableModel(1, 2));
            List<RowActions.Action> actions = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                actions.add(new RowActions.Action(Glyph.TRASH, "Aktion " + i, true, () -> { }));
            }
            RowActions.install(table, 1, actions.size(), row -> actions);
            table.setSize(600, 100);
            table.doLayout();
            java.awt.Rectangle cell = table.getCellRect(0, 1, false);
            int start = cell.x + cell.width - 4 - actions.size() * RowActions.SLOT;
            assertThat(start).as("alle Symbole passen in die Spalte").isGreaterThanOrEqualTo(cell.x);
            for (int i = 0; i < actions.size(); i++) {
                int x = start + i * RowActions.SLOT + RowActions.SLOT / 2;
                MouseEvent event = new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, x, cell.y + 5, 0, false);
                assertThat(table.getToolTipText(event)).isEqualTo("Aktion " + i);
            }
        });
    }
}
