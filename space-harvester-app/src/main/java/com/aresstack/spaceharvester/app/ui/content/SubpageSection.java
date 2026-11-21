package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.SectionHeader;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Der Abschnitt „Aus Unterseiten“ unter dem Eigenen eines Analyse-Reiters (Fahrplan P3, Form vom UX-Manager): dünne
 * Linie, {@link SectionHeader} „Aus Unterseiten“ mit {@link CountChip}, darunter eine Tabelle, deren letzte Spalte
 * „Unterseite“ die Herkunft als Link zeigt (gekürzt mit „…“, voller Titel im Tooltip). Ein Klick dort öffnet die
 * Unterseite, ein Klick auf eine andere Spalte das Ziel der Zeile. Während des Ladens liegt eine {@link BusyOverlay}
 * nur über dem Abschnitt. Nur auf dem EDT.
 */
final class SubpageSection extends JPanel {

    static final String ORIGIN = "Unterseite";

    /**
     * Eine Zeile.
     *
     * @param cells  die Werte der Spalten vor „Unterseite“
     * @param origin die Unterseite, von der der Eintrag stammt
     * @param more   auf wie vielen weiteren Unterseiten er außerdem vorkommt (0: nur hier)
     * @param target die Seite, die ein Klick auf die Zeile öffnet; {@code null}: die Unterseite
     */
    record Row(List<String> cells, PageNode origin, int more, String target) {

        Row {
            cells = List.copyOf(cells);
            Objects.requireNonNull(origin, "origin");
        }

        Row(List<String> cells, PageNode origin) {
            this(cells, origin, 0, null);
        }

        String originText() {
            return Breadcrumbs.title(origin) + (more > 0 ? " (+" + more + ")" : "");
        }
    }

    private final Model model;
    private final JTable table;
    private final JScrollPane scroll;
    private final SectionHeader header = new SectionHeader(TermsTab.BELOW, true, this::toggled);
    private final CountChip count;
    private final BusyOverlay busy;
    private final int maxRows;
    private Consumer<String> onOpen = pageId -> { };

    /**
     * @param word    Wort am Zählchip, etwa „Beziehungen“
     * @param maxRows so viele Zeilen hoch, darüber rollt die Tabelle
     * @param columns Spalten vor „Unterseite“
     */
    SubpageSection(String word, int maxRows, String... columns) {
        super(new BorderLayout(0, 6));
        this.maxRows = maxRows;
        setOpaque(false);
        model = new Model(columns);
        table = Comic.table(new JTable(model));
        scroll = Comic.scroll(table);
        count = new CountChip(word, Comic.MUTED);
        count.setFocusable(false);
        String tip = word + " der Unterseiten, die nicht schon oben stehen";
        count.tips(tip, tip);
        // Die Zahl ist nur eine Anzeige, kein Filter.
        count.addActionListener(e -> count.setSelected(false));
        JPanel title = new JPanel(new BorderLayout(8, 0));
        title.setOpaque(false);
        title.add(header, BorderLayout.CENTER);
        title.add(count, BorderLayout.EAST);
        title.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.MUTED),
                BorderFactory.createEmptyBorder(8, 0, 0, 0)));
        busy = new BusyOverlay(scroll);
        add(title, BorderLayout.NORTH);
        add(busy, BorderLayout.CENTER);
        table.getColumnModel().getColumn(columns.length).setCellRenderer(new OriginRenderer());
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    open(row, table.columnAtPoint(e.getPoint()));
                }
            }
        });
        setVisible(false);
        fit();
    }

    /** {@code listener} öffnet eine Seite über ihre Kennung. */
    void onOpen(Consumer<String> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /** Abschnitt an und leer, solange geladen wird; Teilergebnisse kommen über {@link #rows}. */
    void loading() {
        model.set(List.of());
        count.count(0);
        setVisible(true);
        busy.setBusy(true);
        fit();
    }

    /** Die Zeilen; {@code done} beendet die Überlagerung. */
    void rows(List<Row> rows, boolean done) {
        model.set(rows);
        count.count(rows.size());
        setVisible(true);
        busy.setBusy(!done);
        fit();
    }

    /** Abschnitt weg (Checkbox aus). */
    void off() {
        model.set(List.of());
        count.count(0);
        busy.setBusy(false);
        setVisible(false);
    }

    boolean shown() {
        return isVisible();
    }

    /** Öffnet aus einer Zeile die Unterseite (Spalte „Unterseite“) oder das Ziel der Zeile. */
    void open(int row, int column) {
        Row chosen = model.rows.get(row);
        boolean origin = column == model.columns.length || chosen.target() == null;
        onOpen.accept(origin ? chosen.origin().pageId() : chosen.target());
    }

    JTable table() {
        return table;
    }

    int count() {
        return count.count();
    }

    /** Die Zeilen als Text, Spalten durch Tabulator getrennt, mit der Überschrift davor; leer ohne Zeilen. */
    String copyText() {
        if (!isVisible() || model.rows.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(TermsTab.BELOW).append('\n');
        for (int i = 0; i < model.getRowCount(); i++) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < model.getColumnCount(); c++) {
                cells.add(String.valueOf(model.getValueAt(i, c)));
            }
            text.append(String.join("\t", cells)).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private void toggled() {
        busy.setVisible(header.isOpen());
        revalidate();
        repaint();
    }

    private void fit() {
        int rows = Math.max(1, Math.min(maxRows, model.getRowCount()));
        int height = table.getTableHeader().getPreferredSize().height + rows * table.getRowHeight() + 4;
        scroll.setPreferredSize(new Dimension(300, height));
        revalidate();
        repaint();
    }

    /** Die Herkunft als Link: gekürzt mit „…“, voller Titel im Tooltip. */
    private static final class OriginRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component cell = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (!selected) {
                cell.setForeground(ResearchUiPalette.ACCENT_BLUE);
            }
            setToolTipText(value == null ? null : value + " · Klick öffnet die Unterseite");
            return cell;
        }
    }

    private static final class Model extends AbstractTableModel {

        private final String[] columns;
        private List<Row> rows = List.of();

        Model(String[] columns) {
            this.columns = columns.clone();
        }

        void set(List<Row> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length + 1;
        }

        @Override
        public String getColumnName(int column) {
            return column < columns.length ? columns[column] : ORIGIN;
        }

        @Override
        public Object getValueAt(int index, int column) {
            Row row = rows.get(index);
            return column < columns.length ? row.cells().get(column) : row.originText();
        }
    }
}
