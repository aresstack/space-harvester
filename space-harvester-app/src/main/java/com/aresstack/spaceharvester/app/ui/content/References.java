package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;
import com.aresstack.spaceharvester.application.links.PageLinks;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Der Reiter „Verweise“ (#177, Paket V2): Seiten, auf die diese Seite verweist, und Seiten, die auf sie verweisen. Die
 * Rückrichtung kommt von der Quelle, wenn sie sie kennt, sonst aus dem Verzeichnis der lokal gespeicherten Seiten; wie
 * vollständig sie dann ist („aus N von M Seiten“), steht in der Statuszeile, nicht in der Ansicht. Eine gewählte Zeile
 * öffnet die Seite ({@code onOpen}). Nur auf dem EDT.
 */
final class References extends JPanel {

    private final Model model = new Model();
    private final JTable table = Comic.table(new JTable(model));
    private final TabHeader head = new TabHeader("Verweise dieser Seite");
    private String hint = " ";
    private boolean loaded;
    private Consumer<String> onOpen = pageId -> { };
    private Consumer<String> onStatus = text -> { };

    References() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        head.copy(this::copyText);
        add(head, BorderLayout.NORTH);
        // Eigene Verweise und darunter abgetrennt die der Unterseiten (Fahrplan P3) in einer Spalte mit einem Balken.
        JPanel column = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout(0, 6));
        JPanel own = new JPanel(new BorderLayout());
        own.setOpaque(false);
        own.add(table.getTableHeader(), BorderLayout.NORTH);
        own.add(table, BorderLayout.CENTER);
        column.add(own, BorderLayout.NORTH);
        column.add(belowSection(), BorderLayout.CENTER);
        add(Comic.scroll(column), BorderLayout.CENTER);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                onOpen.accept(model.rows.get(table.getSelectedRow()).node().pageId());
            }
        });
    }

    // --- Aus Unterseiten (Fahrplan P3) ------------------------------------------------------------------------------

    private final BelowModel belowModel = new BelowModel();
    private final JTable belowTable = Comic.table(new JTable(belowModel));
    private final JPanel belowPanel = new JPanel(new BorderLayout(0, 6));
    private final com.aresstack.spaceharvester.app.ui.controls.SectionHeader belowHeader =
            new com.aresstack.spaceharvester.app.ui.controls.SectionHeader(TermsTab.BELOW, true, this::belowToggled);
    private final com.aresstack.spaceharvester.app.ui.controls.CountChip belowCount =
            new com.aresstack.spaceharvester.app.ui.controls.CountChip("Verweise", Comic.MUTED);
    private final JPanel belowBody = new JPanel(new BorderLayout());
    private com.aresstack.spaceharvester.app.ui.controls.BusyOverlay belowBusy;
    private boolean belowShown;
    private List<com.aresstack.spaceharvester.application.navigation.PageNavigation.SubpageReference> lastBelow;

    private JPanel belowSection() {
        belowPanel.setOpaque(false);
        belowBody.setOpaque(false);
        belowBody.add(belowTable.getTableHeader(), BorderLayout.NORTH);
        belowBody.add(belowTable, BorderLayout.CENTER);
        belowBusy = new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(belowBody);
        belowCount.setFocusable(false);
        belowCount.tips("Verweise der Unterseiten, die nicht schon oben stehen",
                "Verweise der Unterseiten, die nicht schon oben stehen");
        belowCount.addActionListener(e -> belowCount.setSelected(false));
        JPanel title = new JPanel(new BorderLayout(8, 0));
        title.setOpaque(false);
        title.add(belowHeader, BorderLayout.CENTER);
        title.add(belowCount, BorderLayout.EAST);
        title.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.MUTED),
                BorderFactory.createEmptyBorder(8, 0, 0, 0)));
        belowPanel.add(title, BorderLayout.NORTH);
        belowPanel.add(belowBusy, BorderLayout.CENTER);
        belowPanel.setVisible(false);
        // Herkunft als Link ganz rechts: ein Klick dort öffnet die Unterseite, sonst das Ziel des Verweises.
        belowTable.getColumnModel().getColumn(BelowModel.ORIGIN).setCellRenderer(new OriginRenderer());
        Cursors.set(belowTable, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        belowTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = belowTable.rowAtPoint(e.getPoint());
                int column = belowTable.columnAtPoint(e.getPoint());
                if (row >= 0) {
                    openBelow(row, column);
                }
            }
        });
        return belowPanel;
    }

    private void belowToggled() {
        belowBusy.setVisible(belowHeader.isOpen());
        belowPanel.revalidate();
        belowPanel.repaint();
    }

    /** Öffnet aus einer Zeile des Abschnitts die Unterseite (Spalte „Unterseite“) oder das Ziel des Verweises. */
    void openBelow(int row, int column) {
        BelowRow chosen = belowModel.rows.get(row);
        onOpen.accept(column == BelowModel.ORIGIN ? chosen.origin().pageId() : chosen.target().pageId());
    }

    /**
     * Abschnitt „Aus Unterseiten“ an oder aus: an mit {@code pages} Unterseiten, deren Verweise noch geladen werden
     * (Überlagerung nur über dem Abschnitt); aus ({@code pages < 0}) blendet ihn aus.
     */
    void belowLoading(int pages) {
        belowShown = pages >= 0;
        lastBelow = null;
        belowModel.set(List.of());
        belowCount.count(0);
        belowPanel.setVisible(belowShown);
        belowBusy.setBusy(belowShown);
        onStatus.accept(status());
    }

    /**
     * Zeigt die Verweise der Unterseiten; was oben schon steht (gleiche Richtung, gleiches Ziel), steht unten nicht
     * noch einmal.
     */
    void below(List<com.aresstack.spaceharvester.application.navigation.PageNavigation.SubpageReference> found) {
        lastBelow = List.copyOf(found);
        java.util.Set<String> own = new java.util.HashSet<>();
        for (Row row : model.rows) {
            own.add(row.direction() + "\u0000" + row.node().pageId());
        }
        List<BelowRow> rows = new ArrayList<>();
        for (var reference : found) {
            String direction = reference.outgoing() ? "verweist auf" : "verwiesen von";
            if (!own.contains(direction + "\u0000" + reference.target().pageId())) {
                rows.add(new BelowRow(direction, reference.target(), reference.origin()));
            }
        }
        belowModel.set(rows);
        belowCount.count(rows.size());
        belowBusy.setBusy(false);
        belowShown = true;
        belowPanel.setVisible(true);
        onStatus.accept(status());
    }

    /** Die Fußzeile bleibt der Hinweis zu den eigenen Verweisen; die Zahl der Unterseiten-Verweise zeigt der Chip (UX). */
    private String status() {
        return hint;
    }

    /** Für Tests: der Abschnitt „Aus Unterseiten“. */
    JTable belowTable() {
        return belowTable;
    }

    boolean showsBelow() {
        return belowShown && belowPanel.isVisible();
    }

    int belowCount() {
        return belowCount.count();
    }

    /** Die Herkunft als Link: gekürzt mit „…“, voller Titel im Tooltip. */
    private static final class OriginRenderer extends javax.swing.table.DefaultTableCellRenderer {

        OriginRenderer() {
            // Titel kommen aus der Quelle: nie als HTML deuten (wie Comic.table).
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                boolean focused, int row, int column) {
            java.awt.Component cell = super.getTableCellRendererComponent(table, value, selected, focused, row,
                    column);
            if (!selected) {
                cell.setForeground(com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette.ACCENT_BLUE);
            }
            setToolTipText(value == null ? null : value + " · Klick öffnet die Unterseite");
            return cell;
        }
    }

    private record BelowRow(String direction, PageNode target, PageNode origin) {
    }

    private static final class BelowModel extends AbstractTableModel {

        static final int ORIGIN = 3;
        private static final String[] COLUMNS = {"Richtung", "Seite", "Space", "Unterseite"};
        private List<BelowRow> rows = List.of();

        void set(List<BelowRow> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int index, int column) {
            BelowRow row = rows.get(index);
            return switch (column) {
                case 0 -> row.direction();
                case 1 -> Breadcrumbs.title(row.target());
                case 2 -> row.target().partitionKey() == null || row.target().partitionKey().isBlank() ? "–"
                        : row.target().partitionKey();
                default -> Breadcrumbs.title(row.origin());
            };
        }
    }

    void onOpen(Consumer<String> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} bekommt die Zusammenfassung („verweist auf 3 Seiten …“) für die Statuszeile. */
    void onStatus(Consumer<String> listener) {
        onStatus = Objects.requireNonNull(listener, "listener");
    }

    /** Ob die Tabelle einen geladenen Stand zeigt; dann lädt eine Änderung still nach, ohne sie zu leeren. */
    boolean showing() {
        return loaded;
    }

    void message(String text) {
        loaded = false;
        model.set(List.of());
        hint = text;
        onStatus.accept(text);
    }

    /** Die Tabelle als Zeilen: Richtung, Seite, Space, durch Tabulator getrennt. */
    String copyText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < model.getRowCount(); i++) {
            text.append(model.getValueAt(i, 0)).append('\t').append(model.getValueAt(i, 1)).append('\t')
                    .append(model.getValueAt(i, 2)).append('\n');
        }
        if (showsBelow() && belowModel.getRowCount() > 0) {
            text.append(TermsTab.BELOW).append('\n');
            for (int i = 0; i < belowModel.getRowCount(); i++) {
                text.append(belowModel.getValueAt(i, 0)).append('\t').append(belowModel.getValueAt(i, 1))
                        .append('\t').append(belowModel.getValueAt(i, 2)).append('\t')
                        .append(belowModel.getValueAt(i, 3)).append('\n');
            }
        }
        return text.toString().stripTrailing();
    }

    void show(PageNavigation.References references) {
        List<Row> rows = new ArrayList<>();
        references.outgoing().forEach(node -> rows.add(new Row("verweist auf", node)));
        if (references.incoming() != null) {
            references.incoming().forEach(node -> rows.add(new Row("verwiesen von", node)));
        }
        model.set(rows);
        loaded = true;
        hint = hint(references);
        onStatus.accept(status());
        if (belowShown && lastBelow != null) {
            // Die eigenen Verweise kamen nach denen der Unterseiten: Doppelte unten wieder herausnehmen.
            below(lastBelow);
        }
    }

    static String hint(PageNavigation.References references) {
        int out = references.outgoing().size();
        String text = out == 0 ? "Diese Seite verweist auf keine andere Seite der Quelle"
                : "Diese Seite verweist auf " + (out == 1 ? "1 Seite" : out + " Seiten");
        if (references.incoming() == null) {
            return text + "; eingehende Verweise kennt die Quelle nicht vollständig.";
        }
        PageLinks.Coverage coverage = references.coverage();
        if (coverage != null && coverage.shown() && coverage.reconciling()) {
            // Zahlen, die vollständig wirken, kämen hier zu früh.
            return text + "; Verzeichnis der Verweise wird noch aufgebaut.";
        }
        int in = references.incoming().size();
        String incoming = in == 0 ? "; keine Seite verweist auf sie" : "; " + (in == 1 ? "1 Seite verweist"
                : in + " Seiten verweisen") + " auf sie";
        return text + incoming + coverage(coverage) + ".";
    }

    /**
     * „ (geprüft: 120 von 340 Seiten sind lokal gespeichert)“, wenn die Rückrichtung aus den lokal gespeicherten Seiten
     * stammt.
     */
    private static String coverage(PageLinks.Coverage coverage) {
        if (coverage == null || !coverage.shown()) {
            return "";
        }
        return " (geprüft: " + coverage.indexed() + " von " + coverage.known()
                + (coverage.known() == 1 ? " Seite ist" : " Seiten sind") + " lokal gespeichert)";
    }

    // ---- für Tests

    JTable table() {
        return table;
    }

    String hint() {
        return hint;
    }

    private record Row(String direction, PageNode node) {
    }

    private static final class Model extends AbstractTableModel {

        private static final String[] COLUMNS = {"Richtung", "Seite", "Space"};
        private List<Row> rows = List.of();

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
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int index, int column) {
            Row row = rows.get(index);
            return switch (column) {
                case 0 -> row.direction();
                case 1 -> Breadcrumbs.title(row.node());
                // Verweisziele kennen ihren Space oft nicht (nur der Bestand kennt ihn): dann „–“ statt leer.
                default -> row.node().partitionKey() == null || row.node().partitionKey().isBlank() ? "–"
                        : row.node().partitionKey();
            };
        }
    }
}
