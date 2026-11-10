package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;
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
 * Der Reiter „Verweise“ (#177): Seiten, auf die diese Seite verweist, und, wenn die Quelle sie vollständig kennt, Seiten,
 * die auf sie verweisen. Eine gewählte Zeile öffnet die Seite ({@code onOpen}). Nur auf dem EDT.
 */
final class References extends JPanel {

    private final Model model = new Model();
    private final JTable table = Comic.table(new JTable(model));
    private final TabHeader head = new TabHeader("Verweise dieser Seite");
    private String hint = " ";
    private Consumer<String> onOpen = pageId -> { };
    private Consumer<String> onStatus = text -> { };

    References() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));
        head.copy(this::copyText);
        add(head, BorderLayout.NORTH);
        add(Comic.scroll(table), BorderLayout.CENTER);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                onOpen.accept(model.rows.get(table.getSelectedRow()).node().pageId());
            }
        });
    }

    void onOpen(Consumer<String> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} bekommt die Zusammenfassung („verweist auf 3 Seiten …“) für die Statuszeile. */
    void onStatus(Consumer<String> listener) {
        onStatus = Objects.requireNonNull(listener, "listener");
    }

    void message(String text) {
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
        return text.toString().stripTrailing();
    }

    void show(PageNavigation.References references) {
        List<Row> rows = new ArrayList<>();
        references.outgoing().forEach(node -> rows.add(new Row("verweist auf", node)));
        if (references.incoming() != null) {
            references.incoming().forEach(node -> rows.add(new Row("verwiesen von", node)));
        }
        model.set(rows);
        hint = hint(references);
        onStatus.accept(hint);
    }

    static String hint(PageNavigation.References references) {
        int out = references.outgoing().size();
        String text = out == 0 ? "Diese Seite verweist auf keine andere Seite der Quelle"
                : "Diese Seite verweist auf " + (out == 1 ? "1 Seite" : out + " Seiten");
        if (references.incoming() == null) {
            return text + "; eingehende Verweise kennt die Quelle nicht vollständig.";
        }
        int in = references.incoming().size();
        return text + (in == 0 ? "; keine Seite verweist auf sie." : "; " + (in == 1 ? "1 Seite verweist"
                : in + " Seiten verweisen") + " auf sie.");
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
