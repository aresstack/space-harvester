package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Ein Verzeichnis von Begriffen oder Beziehungen: Überschrift mit Unterzeile, Suchfeld und Tabelle; die letzte Spalte
 * ist rechtsbündig. Das Suchfeld filtert über alle Spalten. Genutzt von den Beziehungen ({@link RelationTab}) und vom
 * Fachglossar ({@link GlossaryTab}). Nur auf dem EDT.
 */
final class TermDirectory extends JPanel {

    /** Hintergrund abgelehnter Zeilen. */
    static final java.awt.Color REJECTED_BACKGROUND = AppColors.of("app.TermDirectory.rejectedBackground");
    static final String PENDING = "Das Verzeichnis füllt der Begriffsindex des Core.";
    static final String NO_MATCH = "Kein Begriff passt zur Suche.";
    static final String NO_RUNTIME = "Die Laufzeit ist noch nicht gestartet.";

    private final Model model;
    private final JTable table;
    private final PlaceholderField search = new PlaceholderField(16, "Suchen …");
    private final JLabel hint = Comic.muted(" ");
    private Consumer<String> onSearch;
    private final JPanel south = new JPanel(new BorderLayout(0, 6));
    private final JPanel head = new JPanel(new BorderLayout(12, 0));
    /** Kopfzeile, darunter (wenn die Breite nicht reicht) die Buchstabenleiste, darunter Filter-Chips. */
    private final JPanel top = new JPanel(new BorderLayout());
    private final JPanel lettersSlot = new JPanel(new BorderLayout());
    private final JLabel heading = Comic.text("");
    private final JPanel lines = new JPanel(new BorderLayout(0, 2));
    private JPanel lettersPanel;
    private JPanel lettersEast;
    private boolean lettersBelow;
    private javax.swing.JScrollPane scroll;
    private final java.util.Map<String, ComposerToggleButton> letterButtons = new java.util.LinkedHashMap<>();
    private String letter;
    private boolean more;
    private Runnable onScrolledToEnd;

    TermDirectory(String title, String[] columns) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        model = new Model(columns);
        table = Comic.table(new JTable(model));
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(columns.length - 1).setCellRenderer(right);

        heading.setFont(Comic.semiBold(15f));
        lines.setOpaque(false);
        heading(title, "Alle berechtigten, aktuell indexierten Seiten");
        head.setOpaque(false);
        head.add(lines, BorderLayout.CENTER);
        head.add(com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(search), BorderLayout.EAST);

        top.setOpaque(false);
        lettersSlot.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        top.add(lettersSlot, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        scroll = Comic.scroll(table);
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
                nearEnd();
            }
        });
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                nearEnd();
            }
        });
        add(scroll, BorderLayout.CENTER);
        south.setOpaque(false);
        south.add(hint, BorderLayout.NORTH);
        add(south, BorderLayout.SOUTH);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                searched();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                searched();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                searched();
            }
        });
    }

    /** Ab jetzt sucht der Core: jede Eingabe geht an {@code listener}, die Tabelle filtert nicht mehr selbst. */
    void onSearch(Consumer<String> listener) {
        onSearch = listener;
    }

    /**
     * Buchstabenleiste A–Z in zwei Reihen mittig zwischen Überschrift und Suchfeld; reicht die Breite dafür nicht
     * (960 px), rückt sie unter die Kopfzeile, damit nichts abgeschnitten wird. Ein zweiter Klick auf den
     * gewählten Buchstaben oder „Alle“ hebt die Auswahl auf. {@code listener} erhält den Buchstaben oder
     * {@code null}.
     */
    void letters(Consumer<String> listener) {
        JPanel rows = new JPanel(new java.awt.GridLayout(2, 14, 2, 2));
        rows.setOpaque(false);
        List<String> labels = new ArrayList<>();
        labels.add(ALL);
        for (char c = 'A'; c <= 'Z'; c++) {
            labels.add(String.valueOf(c));
        }
        for (String label : labels) {
            String tooltip = label.equals(ALL) ? "Alle Anfangsbuchstaben"
                    : "Nur Einträge mit „" + label + "“ am Anfang";
            ComposerToggleButton button = new ComposerToggleButton(null, label, tooltip);
            button.setSelected(label.equals(ALL));
            button.addActionListener(e -> {
                String chosen = label.equals(ALL) || label.equals(letter) ? null : label;
                selectLetter(chosen);
                listener.accept(chosen);
            });
            letterButtons.put(label, button);
            rows.add(button);
        }
        JPanel center = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        center.setOpaque(false);
        center.add(rows);
        JPanel east = new JPanel(new BorderLayout(12, 0));
        east.setOpaque(false);
        head.remove(search.getParent());
        east.add(center, BorderLayout.CENTER);
        east.add(com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(search), BorderLayout.EAST);
        head.add(east, BorderLayout.EAST);
        lettersPanel = center;
        lettersEast = east;
        placeLetters();
    }

    /** Jede neue Breite entscheidet, wo die Buchstabenleiste steht (auch ohne Bildschirm, für Bilder). */
    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        placeLetters();
    }

    /** Die Buchstabenleiste neben die Überschrift, wenn sie dort hinpasst, sonst darunter. */
    private void placeLetters() {
        if (lettersPanel == null || getWidth() == 0) {
            return;
        }
        int needed = lettersPanel.getPreferredSize().width + 2 * 12;
        for (java.awt.Component part : head.getComponents()) {
            if (part != lettersEast) {
                needed += part.getPreferredSize().width;
            }
        }
        needed += search.getParent().getPreferredSize().width;
        boolean below = needed > getWidth();
        if (below == lettersBelow) {
            return;
        }
        lettersBelow = below;
        if (below) {
            lettersEast.remove(lettersPanel);
            JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            left.setOpaque(false);
            left.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
            left.add(lettersPanel);
            lettersSlot.add(left, BorderLayout.CENTER);
        } else {
            lettersSlot.removeAll();
            lettersEast.add(lettersPanel, BorderLayout.CENTER);
        }
        top.revalidate();
        top.repaint();
    }

    /** Titel und Unterzeile des Kopfs; {@code null} lässt die jeweilige Zeile weg (Einbau in fremde Ansichten). */
    void heading(String title, String subtitle) {
        lines.removeAll();
        if (title != null) {
            heading.setText(title);
            lines.add(heading, BorderLayout.NORTH);
        }
        if (subtitle != null) {
            lines.add(Comic.muted(subtitle), BorderLayout.SOUTH);
        }
        lines.revalidate();
        lines.repaint();
    }

    /** Eine Zeile Filter (Chips) zwischen Kopf und Tabelle. */
    void filters(JComponent filters) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        row.add(filters, BorderLayout.WEST);
        top.add(row, BorderLayout.SOUTH);
    }

    /** Ob die Buchstabenleiste unter der Kopfzeile steht, weil sie daneben nicht passt (Tests). */
    boolean lettersBelow() {
        return lettersBelow;
    }

    static final String ALL = "Alle";

    private void selectLetter(String chosen) {
        letter = chosen;
        letterButtons.forEach((label, button) ->
                button.setSelected(chosen == null ? label.equals(ALL) : label.equals(chosen)));
    }

    /** Gewählter Anfangsbuchstabe oder {@code null} für alle. */
    String letter() {
        return letter;
    }

    ComposerToggleButton letterButton(String label) {
        return letterButtons.get(label);
    }

    /** Ruft {@code listener}, wenn die Tabelle bis ans Ende gescrollt ist und es weitere Einträge gibt. */
    void onScrolledToEnd(Runnable listener) {
        onScrolledToEnd = listener;
    }

    /** Ob weitere Einträge beim Scrollen nachladen. */
    void more(boolean more) {
        this.more = more;
        updateCount();
        SwingUtilities.invokeLater(this::nearEnd);
    }

    boolean hasMore() {
        return more;
    }

    /** Hängt Zeilen an, ohne die Auswahl zu verlieren. */
    void append(List<List<String>> rows) {
        model.append(rows);
        updateCount();
    }

    /** Ersetzt die Zeile, deren Zelle {@code column} gleich {@code key} ist, an Ort und Stelle (Auswahl bleibt). */
    void replace(int column, String key, List<String> row) {
        model.replace(column, key, row);
    }

    private void updateCount() {
        if (model.getRowCount() > 0) {
            hint.setText(Texts.count(model.getRowCount(), "Eintrag", "Einträge")
                    + (more ? " · weitere laden beim Scrollen" : ""));
        }
    }

    private void nearEnd() {
        if (!more || onScrolledToEnd == null || scroll == null) {
            return;
        }
        javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
        if (bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4 * table.getRowHeight()) {
            onScrolledToEnd.run();
        }
    }

    /** Lädt die nächste Seite, als wäre ans Ende gescrollt (Tests). */
    void loadMore() {
        if (more && onScrolledToEnd != null) {
            onScrolledToEnd.run();
        }
    }

    /** Spaltenköpfe sortieren: aufsteigend, absteigend, dann wieder die Reihenfolge der Liste. */
    void sortable() {
        javax.swing.table.TableRowSorter<Model> sorter = new javax.swing.table.TableRowSorter<>(model) {
            @Override
            public void toggleSortOrder(int column) {
                List<? extends javax.swing.RowSorter.SortKey> keys = getSortKeys();
                if (!keys.isEmpty() && keys.get(0).getColumn() == column
                        && keys.get(0).getSortOrder() == javax.swing.SortOrder.DESCENDING) {
                    setSortKeys(null);
                } else {
                    super.toggleSortOrder(column);
                }
            }
        };
        table.setRowSorter(sorter);
    }

    /** Ohne Kopfzeile: Suche und Filter stehen in der Reiterzeile (Fachglossar). */
    void withoutHead() {
        // Der Kopf steht in der oberen Zeile (über Buchstabenleiste und Chips), nicht direkt im Verzeichnis.
        top.remove(head);
    }

    /** Ob die Kopfzeile mit Überschrift und eigenem Suchfeld zu sehen ist (Tests). */
    boolean showsHead() {
        return head.getParent() != null;
    }

    /** Spaltenköpfe über der Tabelle, wenn sich die Werte nicht von selbst erklären. */
    void columnHeads() {
        scroll.setColumnHeaderView(table.getTableHeader());
    }

    /** Ein Element mittig zwischen Überschrift und Suchfeld. */
    void middle(JComponent component) {
        JPanel lines = (JPanel) ((BorderLayout) head.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        head.remove(lines);
        head.add(lines, BorderLayout.WEST);
        JPanel center = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        center.setOpaque(false);
        center.add(component);
        JPanel wrap = new JPanel(new java.awt.GridBagLayout());
        wrap.setOpaque(false);
        wrap.add(center);
        head.add(wrap, BorderLayout.CENTER);
    }

    /** Schaltflächen unter der Tabelle. */
    void actions(JComponent actions) {
        south.add(actions, BorderLayout.SOUTH);
    }

    List<String> visibleRow(int row) {
        return model.visible(row);
    }

    String query() {
        return search.getText() == null ? "" : search.getText().trim();
    }

    private void searched() {
        if (onSearch != null) {
            onSearch.accept(query());
        } else {
            model.filter(search.getText());
        }
    }

    /** Zeigt die Zeilen; {@code hint} steht unter der Tabelle, solange sie leer ist. */
    void show(List<List<String>> rows, String emptyHint) {
        model.set(rows);
        more = false;
        hint.setText(rows.isEmpty() ? emptyHint : Texts.count(rows.size(), "Eintrag", "Einträge"));
    }

    JTable table() {
        return table;
    }

    PlaceholderField search() {
        return search;
    }

    String hint() {
        return hint.getText();
    }

    /** Eine Meldung unter der Tabelle, etwa nach einer abgelehnten Änderung. */
    void message(String text) {
        hint.setText(text);
    }

    private static final class Model extends AbstractTableModel {

        private final String[] columns;
        private List<List<String>> all = List.of();
        private List<List<String>> visible = List.of();
        private String query = "";

        Model(String[] columns) {
            this.columns = columns.clone();
        }

        void append(List<List<String>> rows) {
            List<List<String>> joined = new ArrayList<>(all);
            joined.addAll(rows);
            all = List.copyOf(joined);
            int before = visible.size();
            List<List<String>> matching = new ArrayList<>(visible);
            for (List<String> row : rows) {
                if (query.isEmpty() || row.stream().limit(columns.length)
                        .anyMatch(cell -> cell != null && cell.toLowerCase(Locale.ROOT).contains(query))) {
                    matching.add(row);
                }
            }
            visible = matching;
            if (visible.size() > before) {
                fireTableRowsInserted(before, visible.size() - 1);
            }
        }

        void set(List<List<String>> rows) {
            all = List.copyOf(rows);
            filter(query);
        }

        void replace(int column, String key, List<String> row) {
            List<List<String>> joined = new ArrayList<>(all);
            joined.replaceAll(r -> column < r.size() && key.equals(r.get(column)) ? row : r);
            all = List.copyOf(joined);
            for (int i = 0; i < visible.size(); i++) {
                List<String> r = visible.get(i);
                if (column < r.size() && key.equals(r.get(column))) {
                    List<List<String>> changed = new ArrayList<>(visible);
                    changed.set(i, row);
                    visible = changed;
                    fireTableRowsUpdated(i, i);
                    return;
                }
            }
        }

        void filter(String text) {
            query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            List<List<String>> matching = new ArrayList<>();
            for (List<String> row : all) {
                if (query.isEmpty() || row.stream().limit(columns.length)
                        .anyMatch(cell -> cell != null && cell.toLowerCase(Locale.ROOT).contains(query))) {
                    matching.add(row);
                }
            }
            visible = matching;
            fireTableDataChanged();
        }

        List<String> visible(int row) {
            return visible.get(row);
        }

        @Override
        public int getRowCount() {
            return visible.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            List<String> cells = visible.get(row);
            return column < cells.size() ? cells.get(column) : null;
        }
    }
}
