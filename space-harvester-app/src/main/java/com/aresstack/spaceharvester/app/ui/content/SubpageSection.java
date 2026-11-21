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
     * @param others die weiteren Unterseiten, auf denen er außerdem vorkommt (leer: nur hier)
     * @param target die Seite, die ein Klick auf die Zeile öffnet; {@code null}: die Unterseite
     * @param badge  die Zahl am Chip hinter der Unterseite, wenn der Abschnitt einen hat ({@link #badge(String)})
     */
    record Row(List<String> cells, PageNode origin, List<PageNode> others, String target, int badge) {

        Row {
            cells = List.copyOf(cells);
            Objects.requireNonNull(origin, "origin");
            others = List.copyOf(others);
        }

        Row(List<String> cells, PageNode origin, List<PageNode> others, String target) {
            this(cells, origin, others, target, 0);
        }

        Row(List<String> cells, PageNode origin) {
            this(cells, origin, List.of(), null);
        }

        /** Der Titel der Unterseite, so wie der Link ihn zeigt. */
        String originText() {
            return Breadcrumbs.title(origin);
        }

        int more() {
            return others.size();
        }
    }

    /** Tooltip des gedämpften „+N“ hinter dem Link. */
    static String moreTip(int more) {
        return "auch auf " + (more == 1 ? "1 weiteren Unterseite" : more + " weiteren Unterseiten");
    }

    private final Model model;
    private final JTable table;
    private final JScrollPane scroll;
    private final SectionHeader header = new SectionHeader(TermsTab.BELOW, true, this::toggled);
    private final CountChip count;
    private final BusyOverlay busy;
    private final int maxRows;
    private Consumer<String> onOpen = pageId -> { };
    private final OriginRenderer origins = new OriginRenderer();
    /** Wort am Chip hinter jeder Unterseite, etwa „Knoten“; {@code null}: nur „+N“ bei weiteren Unterseiten. */
    private String badge;

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
        table = Comic.table(new JTable(model) {
            @Override
            public String getToolTipText(MouseEvent event) {
                return tip(event.getPoint());
            }
        });
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
        table.getColumnModel().getColumn(columns.length).setCellRenderer(origins);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(table);
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                if (onMore(row, e.getPoint())) {
                    showOthers(row, e.getX(), e.getY());
                } else {
                    open(row, table.columnAtPoint(e.getPoint()));
                }
            }
        });
        if (columns.length == 0) {
            // Einspaltige Liste: Der Abschnittskopf sagt schon „Aus Unterseiten“ (UX).
            table.setTableHeader(null);
            scroll.setColumnHeaderView(null);
        }
        setVisible(false);
        fit();
    }

    /**
     * Statt „+N“ steht hinter jeder Unterseite ein gedämpfter Chip „{@code word} N“ mit {@link Row#badge()}, etwa die
     * Knotenzahl im Graph (UX-Manager); ein Klick darauf öffnet die Unterseite.
     */
    SubpageSection badge(String word) {
        badge = Objects.requireNonNull(word, "word");
        return this;
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

    /** Die Titel der weiteren Unterseiten einer Zeile (das „+N“). */
    List<String> others(int row) {
        return model.rows.get(row).others().stream().map(Breadcrumbs::title).toList();
    }

    /** Öffnet die {@code index}-te weitere Unterseite einer Zeile. */
    void openOther(int row, int index) {
        onOpen.accept(model.rows.get(row).others().get(index).pageId());
    }

    /** Das Menü hinter „+N“: je weitere Unterseite ein Eintrag, Klick öffnet sie. */
    javax.swing.JPopupMenu othersMenu(int row) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        List<String> titles = others(row);
        for (int i = 0; i < titles.size(); i++) {
            int index = i;
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(titles.get(i));
            item.setFont(Comic.regular(13f));
            item.addActionListener(e -> openOther(row, index));
            menu.add(item);
        }
        return menu;
    }

    private void showOthers(int row, int x, int y) {
        othersMenu(row).show(table, x, y);
    }

    /** Ob {@code point} auf dem „+N“ der Zeile liegt. */
    private boolean onMore(int row, java.awt.Point point) {
        Row chosen = model.rows.get(row);
        if (badge != null || chosen.more() == 0 || table.columnAtPoint(point) != model.columns.length) {
            return false;
        }
        java.awt.Rectangle cell = table.getCellRect(row, model.columns.length, false);
        int start = cell.x + titleWidth(row, textWidth(chosen.originText())) + 2;
        return point.x >= start && point.x <= start + moreChip(chosen.more()).getPreferredSize().width;
    }

    private String tip(java.awt.Point point) {
        int row = table.rowAtPoint(point);
        if (row < 0) {
            return null;
        }
        Row chosen = model.rows.get(row);
        if (onMore(row, point)) {
            return moreTip(chosen.more());
        }
        if (table.columnAtPoint(point) == model.columns.length) {
            return chosen.originText() + " · Klick öffnet die Unterseite";
        }
        Object value = table.getValueAt(row, table.columnAtPoint(point));
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /** Breite eines Titels in der Schrift der Tabelle samt Rand der Zelle. */
    private int textWidth(String text) {
        // Der Link malt in der Schrift der Tabelle (OriginRenderer).
        return table.getFontMetrics(table.getFont()).stringWidth(text) + 4;
    }

    /** So breit steht der Titel der Unterseite vor dem „+N“: ganz, oder gekürzt, wenn beides nicht passt. */
    private int titleWidth(int row, int preferred) {
        CountChip chip = chip(model.rows.get(row));
        int room = table.getColumnModel().getColumn(model.columns.length).getWidth()
                - (chip == null ? 0 : chip.getPreferredSize().width) - 4;
        return Math.max(0, Math.min(preferred, room));
    }

    /** Der Chip hinter der Unterseite einer Zeile oder {@code null}. */
    private CountChip chip(Row row) {
        if (badge != null) {
            CountChip chip = new CountChip(badge, Comic.MUTED);
            chip.count(row.badge());
            String tip = row.badge() + " " + badge + " · Klick öffnet die Unterseite";
            return chip.tips(tip, tip);
        }
        return row.more() > 0 ? moreChip(row.more()) : null;
    }

    private static CountChip moreChip(int more) {
        CountChip chip = CountChip.choice("+" + more, Comic.MUTED);
        String tip = moreTip(more);
        return chip.tips(tip, tip);
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
            int more = model.rows.get(i).more();
            if (badge != null) {
                cells.set(cells.size() - 1, cells.get(cells.size() - 1) + " (" + model.rows.get(i).badge() + " "
                        + badge + ")");
            } else if (more > 0) {
                cells.set(cells.size() - 1, cells.get(cells.size() - 1) + " (+" + more + ")");
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
        int header = table.getTableHeader() == null ? 0 : table.getTableHeader().getPreferredSize().height;
        int height = header + rows * table.getRowHeight() + 4;
        scroll.setPreferredSize(new Dimension(300, height));
        revalidate();
        repaint();
    }

    /**
     * Die Herkunft als Link (gekürzt mit „…“, voller Titel im Tooltip der Tabelle); kommt der Eintrag auf weiteren
     * Unterseiten vor, steht dahinter ein gedämpftes „+N“ wie bei den Begriffen (UX-Manager).
     */
    private final class OriginRenderer implements javax.swing.table.TableCellRenderer {

        private final DefaultTableCellRenderer link = new DefaultTableCellRenderer();
        private final JPanel cell = new JPanel();

        OriginRenderer() {
            cell.setLayout(new javax.swing.BoxLayout(cell, javax.swing.BoxLayout.X_AXIS));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component title = link.getTableCellRendererComponent(table, value, selected, focused, row, column);
            link.setFont(table.getFont());
            if (!selected) {
                title.setForeground(ResearchUiPalette.ACCENT_BLUE);
            }
            link.setToolTipText(null);
            link.setPreferredSize(null);
            link.setMinimumSize(null);
            link.setMaximumSize(null);
            CountChip chip = chip(model.rows.get(row));
            if (chip == null) {
                return title;
            }
            cell.removeAll();
            cell.setOpaque(true);
            cell.setBackground(title.getBackground());
            // Das „+N“ dicht hinter dem Titel (2 px), der Titel kürzt sich, wenn beides nicht passt.
            int width = titleWidth(row, textWidth(String.valueOf(value)));
            Dimension size = new Dimension(width, table.getRowHeight(row));
            title.setMinimumSize(size);
            title.setMaximumSize(size);
            ((javax.swing.JComponent) title).setPreferredSize(size);
            cell.add(title);
            cell.add(javax.swing.Box.createHorizontalStrut(2));
            JPanel holder = new JPanel(new java.awt.GridBagLayout());
            holder.setOpaque(false);
            holder.add(chip);
            holder.setMaximumSize(new Dimension(chip.getPreferredSize().width, table.getRowHeight(row)));
            cell.add(holder);
            cell.add(javax.swing.Box.createHorizontalGlue());
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
