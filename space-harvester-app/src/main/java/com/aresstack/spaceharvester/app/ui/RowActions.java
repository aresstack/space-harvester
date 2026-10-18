package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Symbolaktionen rechtsbündig in einer Tabellenspalte, im Stil der Space-Aktionen ({@link RoundHover}): je Aktion ein
 * schlichtes Symbol, beim Darüberfahren der Kreis, Tooltip mit Name oder, wenn gesperrt, dem Grund. Ein Klick löst die
 * Aktion aus, ohne die Zeile erst wählen zu müssen; gesperrte Aktionen sind blass und tun nichts.
 */
final class RowActions extends JComponent implements TableCellRenderer {

    /** Eine Aktion einer Zeile. */
    record Action(Icon icon, String tooltip, boolean enabled, Runnable run) {
    }

    static final int SLOT = 28;

    private final IntFunction<List<Action>> actions;
    private List<Action> shown = List.of();
    private int row = -1;
    private int hoverRow = -1;
    private int hoverSlot = -1;

    private RowActions(IntFunction<List<Action>> actions) {
        this.actions = actions;
        setOpaque(true);
    }

    /**
     * Legt die Aktionen in die Spalte {@code column} der Tabelle.
     *
     * @param actions Aktionen je Zeile des Modells, von links nach rechts
     */
    static RowActions install(JTable table, int column, int count, IntFunction<List<Action>> actions) {
        RowActions renderer = new RowActions(actions);
        TableColumn col = table.getColumnModel().getColumn(column);
        int width = count * SLOT + 8;
        col.setCellRenderer(renderer);
        col.setMinWidth(width);
        col.setMaxWidth(width);
        col.setPreferredWidth(width);
        if (table.getRowHeight() < SLOT) {
            table.setRowHeight(SLOT);
        }
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                int viewRow = table.rowAtPoint(event.getPoint());
                int slot = table.columnAtPoint(event.getPoint()) == table.convertColumnIndexToView(column)
                        && viewRow >= 0 ? renderer.slotAt(table, viewRow, column, event.getX()) : -1;
                int hover = slot < 0 ? -1 : viewRow;
                if (hover != renderer.hoverRow || slot != renderer.hoverSlot) {
                    renderer.hoverRow = hover;
                    renderer.hoverSlot = slot;
                    table.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                renderer.hoverRow = -1;
                renderer.hoverSlot = -1;
                table.repaint();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                int viewRow = table.rowAtPoint(event.getPoint());
                if (viewRow < 0 || table.columnAtPoint(event.getPoint()) != table.convertColumnIndexToView(column)
                        || !javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int slot = renderer.slotAt(table, viewRow, column, event.getX());
                List<Action> row = actions.apply(table.convertRowIndexToModel(viewRow));
                if (slot >= 0 && slot < row.size() && row.get(slot).enabled()) {
                    row.get(slot).run().run();
                }
            }
        };
        table.addMouseListener(mouse);
        table.addMouseMotionListener(mouse);
        return renderer;
    }

    /** Welche Aktion unter {@code x} liegt, oder -1. */
    private int slotAt(JTable table, int viewRow, int column, int x) {
        java.awt.Rectangle cell = table.getCellRect(viewRow, table.convertColumnIndexToView(column), false);
        List<Action> row = actions.apply(table.convertRowIndexToModel(viewRow));
        int start = cell.x + cell.width - 4 - row.size() * SLOT;
        int slot = Math.floorDiv(x - start, SLOT);
        return x < start || slot >= row.size() ? -1 : slot;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                   int viewRow, int column) {
        shown = actions.apply(table.convertRowIndexToModel(viewRow));
        row = viewRow;
        setBackground(selected ? table.getSelectionBackground() : table.getBackground());
        return this;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int start = getWidth() - 4 - shown.size() * SLOT;
        int slot = Math.floorDiv(event.getX() - start, SLOT);
        return event.getX() < start || slot >= shown.size() ? null : shown.get(slot).tooltip();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        graphics.setColor(getBackground());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        int start = getWidth() - 4 - shown.size() * SLOT;
        for (int i = 0; i < shown.size(); i++) {
            Action action = shown.get(i);
            int x = start + i * SLOT;
            if (action.enabled() && row == hoverRow && i == hoverSlot) {
                RoundHover.paint(graphics, x, 0, SLOT, getHeight(), false);
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (!action.enabled()) {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
                }
                g.setColor(Comic.INK);
                Icon icon = action.icon();
                icon.paintIcon(this, g, x + (SLOT - icon.getIconWidth()) / 2, (getHeight() - icon.getIconHeight()) / 2);
            } finally {
                g.dispose();
            }
        }
    }
}
