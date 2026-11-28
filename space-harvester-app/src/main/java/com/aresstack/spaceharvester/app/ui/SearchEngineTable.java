package com.aresstack.spaceharvester.app.ui;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.askai.browser.search.engine.SearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.SearchEngineCatalogEntry;
import com.aresstack.askai.browser.search.engine.SearchEngineImplementationStatus;
import com.aresstack.askai.browser.search.engine.SearchLanguages;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Die Suchmaschinenliste eines Browserprofils, übernommen aus {@code SearchEngineTableEditor} von Ask AI
 * (Suche › Suchmaschinen): eine Zeile je Suchmaschinen-Einstellung, dieselbe Suchmaschine beliebig oft. Spalten: Aktiv
 * · Suchmaschine · Priorität · Pause (s) · Seiten · Sprachen · Weitere Sprachen. Priorität 0 sucht zuerst; Zeilen
 * gleicher Priorität bilden eine Gruppe und werden zusammen durchsucht. Neu gegenüber Ask AI: Suchseiten des Hauses
 * (nur die Adresse der Seite, etwa das Unternehmensportal; gesucht wird über ihr Suchfeld). Im Profil Intern bietet „Hinzufügen“ nur Suchseiten an,
 * damit Suchbegriffe aus dem Intranet nicht an öffentliche Suchmaschinen gehen. Jede Änderung meldet die Liste sofort.
 * Nur auf dem EDT.
 */
final class SearchEngineTable extends JPanel {

    static final int COL_ACTIVE = 0;
    static final int COL_ENGINE = 1;
    static final int COL_PRIORITY = 2;
    static final int COL_DELAY = 3;
    static final int COL_PAGES = 4;
    static final int COL_LANGUAGES = 5;
    static final int COL_FURTHER = 6;

    private static final String[] COLUMNS = {
            "Aktiv", "Suchmaschine", "Priorität", "Pause (s)", "Seiten", "Sprachen", "Weitere Sprachen"};

    /** Bevorzugte Spaltenbreiten; die Tabelle schrumpft auf die Breite des Dialogs und rollt nie seitwärts. */
    private static final int[] WIDTHS = {44, 150, 56, 60, 48, 100, 110};

    static final String ADD_SEARCH_PAGE = "Suchseite des Hauses …";
    static final String SEARCH_PAGE_TITLE = "Suchseite hinzufügen";
    static final String SEARCH_PAGE_TEXT = "Auf welcher Seite soll gesucht werden?";

    /** Eine Zeile, wie die Tabelle sie bearbeitet. */
    static final class Row {
        final String engineId;
        boolean enabled;
        int priority;
        int delayMillis;
        int resultPages;
        List<String> languages;
        List<String> further;

        Row(BrowserSearchEngineSelection.Entry entry) {
            this.engineId = entry.getEngineId();
            this.enabled = entry.isEnabled();
            this.priority = Math.max(0, entry.getPriority());
            this.delayMillis = entry.getDelayMillis();
            this.resultPages = entry.getResultPages();
            this.languages = new ArrayList<>(entry.getLanguages());
            this.further = new ArrayList<>(entry.getFurtherLanguages());
        }

        BrowserSearchEngineSelection.Entry toEntry() {
            return new BrowserSearchEngineSelection.Entry(engineId, enabled, resultPages, delayMillis, priority,
                    languages, further);
        }
    }

    /** Fragt nach der Such-URL einer Suchseite; {@code null} = abgebrochen. */
    interface SearchPageQuestion {
        String ask(String current);
    }

    private final boolean searchPagesOnly;
    private final List<Row> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JPopupMenu addMenu = new JPopupMenu();
    private Consumer<BrowserSearchEngineSelection> changed = selection -> { };
    SearchPageQuestion searchPageQuestion = current -> Dialogs.askText(this, SEARCH_PAGE_TITLE, SEARCH_PAGE_TEXT,
            current);

    /**
     * @param name            Name für Bildschirmleser
     * @param searchPagesOnly nur Suchseiten des Hauses hinzufügen (Profil Intern)
     */
    SearchEngineTable(String name, boolean searchPagesOnly) {
        super(new BorderLayout(0, 4));
        this.searchPagesOnly = searchPagesOnly;
        setOpaque(false);
        table.getAccessibleContext().setAccessibleName(name);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 22));
        table.getTableHeader().setReorderingAllowed(false);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); // Verlassen der Zelle übernimmt sie
        table.setFont(Comic.regular(12.5f));
        for (int i = 0; i < WIDTHS.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(WIDTHS[i]);
        }
        table.getColumnModel().getColumn(COL_ENGINE).setMinWidth(90);
        table.setToolTipText("Priorität 0 sucht zuerst; Zeilen gleicher Priorität werden zusammen durchsucht. "
                + "Sprachen per Klick wählen, weitere Sprachcodes in der letzten Spalte eintippen.");
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row >= 0 && column == COL_LANGUAGES) {
                    showLanguageMenu(row, event.getX(), event.getY());
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                describeSelection();
            }
        });

        JScrollPane scroll = Comic.comicBars(new JScrollPane(table));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(420, 130));
        scroll.setBorder(BorderFactory.createLineBorder(Comic.MUTED));
        add(scroll, BorderLayout.CENTER);

        buildAddMenu();
        JButton addButton = Comic.button("Hinzufügen …", searchPagesOnly ? "Eine Suchseite des Hauses hinzufügen"
                : "Eine Suchmaschine oder Suchseite hinzufügen (dieselbe darf mehrere Zeilen haben)");
        addButton.addActionListener(e -> {
            if (searchPagesOnly) {
                addSearchPage();
            } else {
                addMenu.show(addButton, 0, addButton.getHeight());
            }
        });
        JButton remove = Comic.button("Entfernen", "Die gewählte Zeile entfernen");
        remove.addActionListener(e -> removeRow(table.getSelectedRow()));
        status.setForeground(Comic.MUTED);
        status.setFont(Comic.regular(12f));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(addButton);
        buttons.add(remove);
        buttons.add(status);
        add(buttons, BorderLayout.SOUTH);
    }

    /** Wer von jeder Änderung erfährt. */
    void onChange(Consumer<BrowserSearchEngineSelection> listener) {
        changed = listener == null ? selection -> { } : listener;
    }

    /** Die Liste in Tabellenreihenfolge; eine offene Zelle wird vorher übernommen. */
    BrowserSearchEngineSelection get() {
        stopEditing();
        return snapshot();
    }

    /**
     * Die Zeilen, wie sie sind, ohne den Zelleditor anzufassen: Eine Änderung wird gemeldet, während JTable die Zelle
     * gerade übernimmt; ein weiteres Übernehmen dort liefe endlos.
     */
    private BrowserSearchEngineSelection snapshot() {
        List<BrowserSearchEngineSelection.Entry> entries = new ArrayList<>();
        for (Row row : rows) {
            entries.add(row.toEntry());
        }
        return new BrowserSearchEngineSelection(entries, EngineAcquisitionMode.FIRST_USABLE);
    }

    /** Zeigt {@code selection}, ohne eine Änderung zu melden. */
    void set(BrowserSearchEngineSelection selection) {
        stopEditing();
        rows.clear();
        for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
            rows.add(new Row(entry));
        }
        model.fireTableDataChanged();
        status.setText(" ");
    }

    // ------------------------------------------------------------------ Aktionen (paketweit für Tests)

    /** Eine Zeile für eine Suchmaschine des Katalogs: aktiv, hinter der letzten Priorität, Sprachen des Katalogs. */
    void addEngine(String engineId) {
        stopEditing();
        int priority = 0;
        for (Row row : rows) {
            priority = Math.max(priority, row.priority + 1);
        }
        Row row = new Row(new BrowserSearchEngineSelection.Entry(engineId, true,
                BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                SearchEngineCatalog.defaultDelayMillis(engineId), priority,
                SearchEngineCatalog.defaultLanguages(engineId), Collections.emptyList()));
        rows.add(row);
        resortKeeping(row);
        fireChanged();
    }

    /**
     * Fragt nach einer Suchseite und nimmt sie mit Priorität 0 auf, wie die übrigen Suchseiten: Weiß man nicht, wo
     * etwas steht, werden alle zusammen durchsucht.
     */
    void addSearchPage() {
        String url = com.aresstack.spaceharvester.app.chat.browser.BrowserSettings.searchPageAddress(
                searchPageQuestion.ask(""));
        if (url == null) {
            return;
        }
        stopEditing();
        for (Row row : rows) {
            if (row.engineId.equals(url)) {
                status.setText("Diese Suchseite steht schon in der Liste.");
                return;
            }
        }
        Row row = new Row(new BrowserSearchEngineSelection.Entry(url, true,
                BrowserSearchEngineSelection.Entry.DEFAULT_RESULT_PAGES,
                BrowserSearchEngineSelection.Entry.DEFAULT_DELAY_MILLIS, 0, Collections.emptyList(),
                Collections.emptyList()));
        rows.add(row);
        resortKeeping(row);
        status.setText(" ");
        fireChanged();
    }

    void removeRow(int index) {
        stopEditing();
        if (index < 0 || index >= rows.size()) {
            return;
        }
        rows.remove(index);
        model.fireTableDataChanged();
        fireChanged();
    }

    /** Schaltet eine gewählte Sprache einer Zeile um (Aktion des Sprachmenüs). */
    void toggleLanguage(int index, String code) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        Row row = rows.get(index);
        List<String> languages = new ArrayList<>(row.languages);
        if (!languages.remove(code)) {
            languages.add(code);
        }
        row.languages = orderedLikePicker(languages);
        model.fireTableRowsUpdated(index, index);
        fireChanged();
    }

    JTable table() {
        return table;
    }

    String statusText() {
        return status.getText();
    }

    // ------------------------------------------------------------------ intern

    private void fireChanged() {
        changed.accept(snapshot());
    }

    private void buildAddMenu() {
        JMenuItem page = new JMenuItem(ADD_SEARCH_PAGE);
        page.setToolTipText("Eine eigene Suchseite, etwa das Unternehmensportal (Such-URL mit {query})");
        page.addActionListener(e -> addSearchPage());
        addMenu.add(page);
        addMenu.addSeparator();
        for (SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            JMenuItem item = new JMenuItem(entry.getDisplayName() + statusSuffix(entry.getStatus()));
            item.setEnabled(entry.isSearchable());
            item.setToolTipText(entry.getOrigin() + " · " + entry.getIndexSource()
                    + (entry.getStatusNote().isEmpty() ? "" : " — " + entry.getStatusNote()));
            item.addActionListener(e -> addEngine(entry.getId()));
            addMenu.add(item);
        }
    }

    private static String statusSuffix(SearchEngineImplementationStatus status) {
        switch (status) {
            case EXPERIMENTAL:
                return "  (experimentell)";
            case NOT_IMPLEMENTED:
                return "  (nicht umgesetzt)";
            case UNAVAILABLE:
                return "  (nicht verfügbar)";
            default:
                return "";
        }
    }

    private void showLanguageMenu(int index, int x, int y) {
        stopEditing();
        JPopupMenu menu = new JPopupMenu();
        Row row = rows.get(index);
        for (String code : SearchLanguages.pickerCodes()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(code + "  " + SearchLanguages.displayName(code),
                    row.languages.contains(code));
            item.addActionListener(e -> toggleLanguage(index, code));
            menu.add(item);
        }
        menu.show(table, x, y);
    }

    private void describeSelection() {
        int index = table.getSelectedRow();
        if (index < 0 || index >= rows.size()) {
            return;
        }
        String id = rows.get(index).engineId;
        if (BrowserSearchEngineCatalog.isSearchPage(id)) {
            status.setText("Suchseite: " + id);
            return;
        }
        SearchEngineCatalogEntry entry = SearchEngineCatalog.byId(id);
        if (entry == null) {
            status.setText("Unbekannte Suchmaschine — bleibt erhalten, wird nie durchsucht.");
        } else if (!entry.isSearchable()) {
            status.setText(entry.getDisplayName() + ": "
                    + entry.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " — wird nie durchsucht.");
        } else if (!entry.hasLanguageFilter()) {
            status.setText(entry.getDisplayName() + ": Sprache über die Suchbegriffe.");
        } else {
            status.setText(" ");
        }
    }

    private void resortKeeping(Row keep) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> a.priority != b.priority ? Integer.compare(a.priority, b.priority)
                : engineLabel(a.engineId).compareToIgnoreCase(engineLabel(b.engineId)));
        rows.clear();
        rows.addAll(sorted);
        model.fireTableDataChanged();
        int at = rows.indexOf(keep);
        if (at >= 0) {
            table.getSelectionModel().setSelectionInterval(at, at);
        }
    }

    private void stopEditing() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static List<String> orderedLikePicker(List<String> codes) {
        List<String> ordered = new ArrayList<>();
        for (String code : SearchLanguages.pickerCodes()) {
            if (codes.contains(code)) {
                ordered.add(code);
            }
        }
        for (String code : codes) {
            if (!ordered.contains(code)) {
                ordered.add(code);
            }
        }
        return ordered;
    }

    /** Der Name einer Zeile: die Suchmaschine des Katalogs oder der Host einer Suchseite. */
    static String engineLabel(String engineId) {
        if (BrowserSearchEngineCatalog.isSearchPage(engineId)) {
            return "Suchseite " + BrowserSearchEngineCatalog.byId(engineId).getDisplayName();
        }
        SearchEngineCatalogEntry entry = SearchEngineCatalog.byId(engineId);
        if (entry == null) {
            return engineId + " (unbekannt)";
        }
        return entry.getDisplayName() + statusSuffix(entry.getStatus());
    }

    private static Integer parseNonNegative(Object value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private final class Model extends AbstractTableModel {

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
        public Class<?> getColumnClass(int column) {
            return column == COL_ACTIVE ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column != COL_ENGINE && column != COL_LANGUAGES;
        }

        @Override
        public Object getValueAt(int index, int column) {
            Row row = rows.get(index);
            switch (column) {
                case COL_ACTIVE:
                    return row.enabled;
                case COL_ENGINE:
                    return engineLabel(row.engineId);
                case COL_PRIORITY:
                    return String.valueOf(row.priority);
                case COL_DELAY:
                    return BrowserSearchEngineSelection.formatDelaySeconds(row.delayMillis);
                case COL_PAGES:
                    return String.valueOf(row.resultPages);
                case COL_LANGUAGES:
                    return row.languages.isEmpty() ? "—" : SearchLanguages.join(row.languages);
                default:
                    return SearchLanguages.join(row.further);
            }
        }

        @Override
        public void setValueAt(Object value, int index, int column) {
            if (index < 0 || index >= rows.size()) {
                return;
            }
            Row row = rows.get(index);
            switch (column) {
                case COL_ACTIVE:
                    row.enabled = Boolean.TRUE.equals(value);
                    break;
                case COL_PRIORITY:
                    Integer priority = parseNonNegative(value);
                    if (priority == null) {
                        status.setText("Die Priorität ist eine ganze Zahl ab 0.");
                        return;
                    }
                    row.priority = priority;
                    resortKeeping(row); // übernommen: die Zeile wandert in ihre Gruppe
                    fireChanged();
                    return;
                case COL_DELAY:
                    String text = String.valueOf(value).trim().replace(',', '.');
                    try {
                        if (Double.parseDouble(text) < 0) {
                            throw new NumberFormatException(text);
                        }
                    } catch (NumberFormatException invalid) {
                        status.setText("Die Pause sind Sekunden ab 0, zum Beispiel 1,5.");
                        return;
                    }
                    row.delayMillis = BrowserSearchEngineSelection.parseDelayMillis(text);
                    break;
                case COL_PAGES:
                    Integer pages = parseNonNegative(value);
                    if (pages == null || pages < 1) {
                        status.setText("Seiten sind eine ganze Zahl ab 1.");
                        return;
                    }
                    row.resultPages = pages;
                    break;
                case COL_FURTHER:
                    SearchLanguages.Parsed parsed = SearchLanguages.parse(String.valueOf(value));
                    row.further = new ArrayList<>(parsed.getCodes());
                    status.setText(parsed.getRejected().isEmpty() ? " "
                            : "Kein Sprachcode, nicht gespeichert: " + SearchLanguages.join(parsed.getRejected()));
                    break;
                default:
                    return;
            }
            fireTableRowsUpdated(index, index);
            fireChanged();
        }
    }
}
