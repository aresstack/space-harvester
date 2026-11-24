package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.deck.betaview.Bookmark;
import com.aresstack.spaceharvester.deck.betaview.FilterPatterns;
import com.aresstack.spaceharvester.deck.betaview.ResultTable;
import com.aresstack.spaceharvester.deck.betaview.SavedSearch;
import com.aresstack.spaceharvester.deck.betaview.SearchFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

/**
 * Linke Hälfte einer BetaView-Sitzung: oben die Auswahl in Reitern wie in MainframeMate (Zeit, Dokument, Status,
 * Gespeichert, Gemerkt), rechts in der Reiterzeile „Suchen“ und „Suche speichern“; darunter die Trefferliste mit
 * Filterfeld. Ein Klick auf einen Treffer öffnet das Dokument (wie in MainframeMate). Die Felder der Auswahl stehen beim
 * nächsten Mal wieder da (app-state), vorbelegt ist nichts außer den Platzhaltern des Servers.
 */
final class SearchPane extends JPanel {

    static final String KEY = "deck.betaview.filter.";
    static final String TIME = "Zeit";
    static final String DOCUMENT = "Dokument";
    static final String STATUS = "Status";
    static final String SAVED = "Gespeichert";
    static final String MARKED = "Gemerkt";

    /** Was die Suchseite auslöst; die Sitzung führt es aus. */
    interface Actions {
        void search(SearchFilter filter);

        void saveSearch();

        void loadSaved();

        void runSaved(SavedSearch search);

        void loadBookmarks();

        void openBookmark(Bookmark bookmark);

        /**
         * Öffnet einen Treffer. Treffer gehen beim Server über ihre Nummer in der zuletzt gesuchten Liste; {@code origin}
         * sagt, aus welcher Suche (Auswahl oder gespeicherte Suche) die Liste stammt, damit die Sitzung eine ältere Liste
         * vorher neu sucht.
         */
        void open(ResultTable.Row row, Object origin);

        /** Eine Eingabe passt nicht; der Text sagt, was fehlt. */
        void problem(String message);
    }

    final PlaceholderField favorite = new PlaceholderField(10, "Favorit, z. B. aus select.action?favoriteID=…");
    final PlaceholderField locale = new PlaceholderField(4, "de");
    final JComboBox<SearchFilter.Period.Mode> period = Comic.combo();
    final PlaceholderField amount = new PlaceholderField(5, String.valueOf(SearchFilter.Period.DEFAULT_AMOUNT));
    final JComboBox<SearchFilter.Period.Unit> unit = Comic.combo();
    final PlaceholderField from = new PlaceholderField(9, "TT.MM.JJJJ");
    final PlaceholderField to = new PlaceholderField(9, "TT.MM.JJJJ");
    final PatternFields.ChoiceField form = new PatternFields.ChoiceField(FilterPatterns.FORMS, true);
    final PatternFields.ExtensionField extension = new PatternFields.ExtensionField();
    final PatternFields.ReportField report = new PatternFields.ReportField();
    final PatternFields.ChoiceField jobName = new PatternFields.ChoiceField(FilterPatterns.JOB_NAMES, false);
    final PatternFields.ChoiceField folder = new PatternFields.ChoiceField(FilterPatterns.WILDCARD, true);
    final PatternFields.ChoiceField tab = new PatternFields.ChoiceField(FilterPatterns.WILDCARD, true);
    final PlaceholderField title = new PlaceholderField(16, "leer: alle");
    final JCheckBox exactTitle = Comic.checkBox("Exakter Titel");
    final PatternFields.ChoiceField recipient = new PatternFields.ChoiceField(FilterPatterns.WILDCARD, true);
    final JComboBox<Choice> online = yesNo();
    final JComboBox<Choice> note = yesNo();
    final JComboBox<Choice> read = yesNo();
    final JComboBox<Choice> archive = yesNo();
    final JComboBox<Choice> process = choices(new Choice("", "(Alle)"), new Choice("LIST", "LIST"),
            new Choice("REPORT", "REPORT"));
    final JComboBox<Choice> delete = yesNo();
    final JComboBox<Choice> listState = choices(new Choice("", "(Alle)"), new Choice("H", "H – Halten"),
            new Choice("C", "C – Abgeschlossen"), new Choice("N", "N – Neu"), new Choice("T", "T – Temporär"));
    final JComboBox<Choice> reload = yesNo();

    final PillTabs tabs = new PillTabs();
    final ResultModel model = new ResultModel();
    final JTable table = Comic.table(new JTable(model));
    final PlaceholderField filter = new PlaceholderField(14, "Treffer filtern");
    final DefaultListModel<SavedSearch> saved = new DefaultListModel<>();
    final DefaultListModel<Bookmark> bookmarks = new DefaultListModel<>();
    final ComposerButton searchButton;
    final IconButton saveSearchButton;
    private final JLabel count = Comic.muted(" ");
    private final BusyOverlay busy;
    private final TableRowSorter<ResultModel> sorter = new TableRowSorter<>(model);
    private final JPanel periodDetail = new JPanel(new CardLayout());
    /** Mitte: die Suchmaske oder eine Trefferliste, umgeschaltet in der Fußleiste der Seitenleiste. */
    static final String MASK = "mask";
    static final String HITS = "hits";
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    /** Fußleiste der Seitenleiste: „Suchmaske“ und je Suche eine schließbare Trefferliste (Angelo, 24.11.2026). */
    private final JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private final javax.swing.ButtonGroup switchGroup = new javax.swing.ButtonGroup();
    final com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton maskTab =
            new com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton(null, "Suchmaske",
                    "Suchmaske zeigen (Auswahl, gespeicherte Suchen, gemerkte Dokumente)");
    final List<HitList> hitLists = new java.util.ArrayList<>();
    private HitList shown;
    private int searchCount;
    private final Actions actions;
    private boolean savedLoaded;
    private boolean bookmarksLoaded;
    private boolean searching;
    private boolean filling;

    SearchPane(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;
        setOpaque(false);
        for (SearchFilter.Period.Mode mode : SearchFilter.Period.Mode.values()) {
            period.addItem(mode);
        }
        for (SearchFilter.Period.Unit value : SearchFilter.Period.Unit.values()) {
            unit.addItem(value);
        }

        searchButton = Comic.primary("Suchen", "Mit dieser Auswahl suchen (Eingabe)");
        searchButton.addActionListener(e -> search());
        saveSearchButton = new IconButton(BetaViewIcons.save(), "Suche auf dem Server speichern");
        saveSearchButton.addActionListener(e -> actions.saveSearch());
        tabs.tab(TIME, timeTab()).tab(DOCUMENT, documentTab()).tab(STATUS, statusTab())
                .tab(SAVED, listTab(saved, "Gespeicherte Suchen neu laden", actions::loadSaved,
                        actions::runSaved))
                .tab(MARKED, listTab(bookmarks, "Gemerkte Dokumente neu laden", actions::loadBookmarks,
                        actions::openBookmark))
                .actions(saveSearchButton, searchButton);

        tabs.onSelect(name -> {
            if (SAVED.equals(name) && !savedLoaded) {
                savedLoaded = true;
                actions.loadSaved();
            } else if (MARKED.equals(name) && !bookmarksLoaded) {
                bookmarksLoaded = true;
                actions.loadBookmarks();
            }
        });
        tabs.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> {
            // Während einer Suche gehört die alte Trefferliste nicht mehr zum Server (Treffer gehen über ihre Nummer).
            if (!event.getValueIsAdjusting() && !filling && !searching) {
                int view = table.getSelectedRow();
                if (view >= 0) {
                    ResultTable.Row row = model.row(table.convertRowIndexToModel(view));
                    if (row.openable()) {
                        actions.open(row, shown == null ? null : shown.origin);
                    }
                }
            }
        });
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyFilter();
            }
        });
        // Kopf wie die Seitenleiste des Space Harvesters: Überschrift, rechts die Anzahl, darunter der Filter.
        JPanel resultHead = new JPanel(new BorderLayout());
        resultHead.setOpaque(false);
        resultHead.setBorder(BorderFactory.createEmptyBorder(8, 8, 2, 8));
        resultHead.add(new com.aresstack.spaceharvester.app.ui.controls.SideHead("Treffer", filter).actions(count),
                BorderLayout.CENTER);
        JPanel results = new JPanel(new BorderLayout());
        results.setOpaque(false);
        results.add(resultHead, BorderLayout.NORTH);
        results.add(Comic.scroll(table), BorderLayout.CENTER);
        // Oben bleiben Reiterzeile und Filter wie gehabt; die Trefferlisten liegen in derselben Fläche und werden in
        // der Fußleiste umgeschaltet, wie die Seitenleiste von NDV (Angelo, 24.11.2026).
        center.setOpaque(false);
        center.add(tabs, MASK);
        center.add(results, HITS);
        busy = new BusyOverlay(center);
        add(busy, BorderLayout.CENTER);
        switchRow.setOpaque(false);
        maskTab.setAccent(com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette.SECONDARY_SURFACE);
        maskTab.addActionListener(e -> showMask());
        switchGroup.add(maskTab);
        switchRow.add(maskTab);
        maskTab.setSelected(true);
        showCount();
        recall();
        for (PlaceholderField field : List.of(favorite, locale, amount, from, to, title, form.custom, extension.bkNumber,
                extension.bkSuffix, extension.bankNumber, extension.bankSuffix, extension.bewFb, extension.bewLb,
                extension.custom, report.kDigits, report.bewirt, report.custom, jobName.custom, folder.custom,
                tab.custom, recipient.custom)) {
            field.addActionListener(e -> search());
        }
    }

    private JComponent timeTab() {
        JPanel panel = Prompts.form();
        Prompts.row(panel, 0, "Favorit", favorite);
        Prompts.row(panel, 1, "Sprache", locale);
        Prompts.row(panel, 2, "Zeitraum", period);
        JPanel individual = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        individual.setOpaque(false);
        individual.add(amount);
        individual.add(unit);
        JPanel range = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        range.setOpaque(false);
        range.add(Comic.text("von"));
        range.add(from);
        range.add(Comic.text("bis"));
        range.add(to);
        JPanel none = new JPanel();
        none.setOpaque(false);
        periodDetail.setOpaque(false);
        periodDetail.add(none, "none");
        periodDetail.add(individual, SearchFilter.Period.Mode.INDIVIDUAL.name());
        periodDetail.add(range, SearchFilter.Period.Mode.DATE_RANGE.name());
        Prompts.row(panel, 3, "", periodDetail);
        period.addActionListener(e -> showPeriodDetail());
        return top(panel);
    }

    private JComponent documentTab() {
        JPanel panel = Prompts.form();
        Prompts.row(panel, 0, "Form", form);
        Prompts.row(panel, 1, "Extension", extension);
        Prompts.row(panel, 2, "Report", report);
        Prompts.row(panel, 3, "Jobname", jobName);
        Prompts.row(panel, 4, "Ordner", folder);
        Prompts.row(panel, 5, "Fach", tab);
        Prompts.row(panel, 6, "Titel", title);
        Prompts.row(panel, 7, "", exactTitle);
        Prompts.row(panel, 8, "Empfänger", recipient);
        return top(panel);
    }

    private JComponent statusTab() {
        JPanel panel = Prompts.form();
        Prompts.row(panel, 0, "Online", online);
        Prompts.row(panel, 1, "Notiz", note);
        Prompts.row(panel, 2, "Gelesen", read);
        Prompts.row(panel, 3, "Archiv", archive);
        Prompts.row(panel, 4, "Verarbeitung", process);
        Prompts.row(panel, 5, "Löschen", delete);
        Prompts.row(panel, 6, "Listenstatus", listState);
        Prompts.row(panel, 7, "Neu laden", reload);
        return top(panel);
    }

    private static <T> JComponent listTab(DefaultListModel<T> items, String refreshTip, Runnable refresh,
                                          Consumer<T> chosen) {
        JList<T> list = new JList<>(items);
        list.setFont(Comic.regular(13f));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectionBackground(Comic.YELLOW);
        list.setSelectionForeground(Comic.INK);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && list.getSelectedValue() != null) {
                chosen.accept(list.getSelectedValue());
            }
        });
        IconButton reload = new IconButton(BetaViewIcons.refresh(), refreshTip);
        reload.addActionListener(e -> refresh.run());
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(reload, BorderLayout.EAST);
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        panel.add(head, BorderLayout.NORTH);
        panel.add(Comic.scroll(list), BorderLayout.CENTER);
        panel.setPreferredSize(new java.awt.Dimension(200, 150));
        return panel;
    }

    /**
     * Hält ein Formular oben; reicht der Platz nicht, scrollt es mit der gestylten Leiste der App (Angelo, 24.11.2026:
     * Dokument und Status haben viele Felder).
     */
    private static JComponent top(JComponent content) {
        return scrolling(content);
    }

    private void showPeriodDetail() {
        SearchFilter.Period.Mode mode = (SearchFilter.Period.Mode) period.getSelectedItem();
        String card = mode == SearchFilter.Period.Mode.INDIVIDUAL || mode == SearchFilter.Period.Mode.DATE_RANGE
                ? mode.name() : "none";
        ((CardLayout) periodDetail.getLayout()).show(periodDetail, card);
    }

    /** Die Auswahl aus den Feldern; wirft mit lesbarer Meldung, wenn etwas fehlt. */
    /**
     * Breite, ab der die Unterreiter und die Aktionen in eine Zeile passen. Die Sitzung gibt der Suche mindestens so
     * viel Platz, damit „Gemerkt“ bei 960 px nicht in eine zweite Zeile rutscht.
     */
    int headerWidth() {
        int width = 0;
        for (String name : tabs.names()) {
            width += tabs.button(name).getPreferredSize().width + 4;
        }
        width += saveSearchButton.getPreferredSize().width + searchButton.getPreferredSize().width + 3 * 6;
        java.awt.Insets insets = tabs.getInsets();
        return width + insets.left + insets.right + 8;
    }

    /**
     * Der Umschalter für die Fußleiste der Seitenleiste: „Suchmaske“ und die Trefferlisten, jede mit Kreuz (Angelo
     * 24.11.2026). Reiterzeile, Speichern und „Suchen“ bleiben oben.
     */
    javax.swing.JComponent footTabs() {
        return switchRow;
    }

    /** Eine Trefferliste: woher sie stammt (für das erneute Suchen beim Öffnen) und was sie zeigt. */
    static final class HitList {
        final String label;
        final Object origin;
        final ResultTable result;
        com.aresstack.spaceharvester.app.ui.controls.ClosableTab tab;

        HitList(String label, Object origin, ResultTable result) {
            this.label = label;
            this.origin = origin;
            this.result = result;
        }
    }

    /** Die gerade gezeigte Trefferliste, sonst {@code null} (Suchmaske). */
    HitList shownList() {
        return shown;
    }

    /** Zeigt die Suchmaske. */
    void showMask() {
        shown = null;
        maskTab.setSelected(true);
        centerCards.show(center, MASK);
    }

    /** Zeigt eine Trefferliste in der Tabelle. */
    void showList(HitList list) {
        shown = list;
        list.tab.setSelected(true);
        filling = true;
        try {
            filter.setText("");
            model.fill(list.result);
            table.clearSelection();
        } finally {
            filling = false;
        }
        showCount();
        centerCards.show(center, HITS);
    }

    /** Schließt eine Trefferliste; war sie zu sehen, kommt die vorige oder die Suchmaske. */
    void closeList(HitList list) {
        int index = hitLists.indexOf(list);
        if (index < 0) {
            return;
        }
        hitLists.remove(index);
        switchGroup.remove(list.tab);
        switchRow.remove(list.tab);
        switchRow.revalidate();
        switchRow.repaint();
        if (shown == list) {
            if (hitLists.isEmpty()) {
                showMask();
            } else {
                showList(hitLists.get(Math.max(0, index - 1)));
            }
        }
    }

    /** Hält den Inhalt oben und lässt ihn mit der gestylten Leiste der App senkrecht scrollen. */
    private static JComponent scrolling(JComponent content) {
        javax.swing.JScrollPane scroll = Comic.column(content);
        scroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    SearchFilter filter() {
        SearchFilter.Period.Mode mode = (SearchFilter.Period.Mode) period.getSelectedItem();
        int number = 0;
        if (!amount.getText().isBlank()) {
            try {
                number = Integer.parseInt(amount.getText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Der Zeitraum braucht eine Zahl, nicht „" + amount.getText().trim()
                        + "“");
            }
        }
        SearchFilter.Period chosen = new SearchFilter.Period(mode, number, (SearchFilter.Period.Unit)
                unit.getSelectedItem(), from.getText(), to.getText());
        return new SearchFilter(favorite.getText(), locale.getText().isBlank() ? "de" : locale.getText(), chosen,
                form.value(), extension.value(), report.value(), jobName.value(), folder.value(),
                tab.value(), title.getText(), exactTitle.isSelected(), recipient.value(),
                new SearchFilter.Status(value(online), value(note), value(read), value(archive), value(process),
                        value(delete), value(listState), value(reload)));
    }

    void search() {
        SearchFilter chosen;
        try {
            chosen = filter();
        } catch (IllegalArgumentException e) {
            tabs.select(TIME);
            actions.problem(e.getMessage());
            return;
        }
        remember();
        actions.search(chosen);
    }

    void busy(boolean on, String text) {
        searching = on;
        busy.setText(text);
        busy.setBusy(on);
        searchButton.setEnabled(!on);
        table.setEnabled(!on);
    }

    void show(ResultTable result) {
        show(result, "Treffer", null);
    }

    /**
     * Legt für das Ergebnis einer Suche eine eigene Trefferliste an und zeigt sie; ältere Listen bleiben in der
     * Fußleiste stehen, bis man sie schließt.
     */
    HitList show(ResultTable result, String label, Object origin) {
        searchCount++;
        String name = label == null || label.isBlank() ? "Treffer " + searchCount : label.strip();
        HitList list = new HitList(name, origin, result);
        int rows = result.rows().size();
        list.tab = new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(null, name.length() > DocumentPane.MAX_PILL
                ? name.substring(0, DocumentPane.MAX_PILL - 1).strip() + "…" : name,
                name + " · " + rows + (rows == 1 ? " Treffer" : " Treffer"),
                "Trefferliste schließen (auch Mittelklick)", () -> closeList(list));
        list.tab.addActionListener(e -> showList(list));
        switchGroup.add(list.tab);
        switchRow.add(list.tab);
        switchRow.revalidate();
        switchRow.repaint();
        hitLists.add(list);
        showList(list);
        return list;
    }

    void showSaved(List<SavedSearch> items) {
        savedLoaded = true;
        saved.clear();
        items.forEach(saved::addElement);
    }

    void showBookmarks(List<Bookmark> items) {
        bookmarksLoaded = true;
        bookmarks.clear();
        items.forEach(bookmarks::addElement);
    }

    boolean bookmarksLoaded() {
        return bookmarksLoaded;
    }

    boolean savedLoaded() {
        return savedLoaded;
    }

    private void applyFilter() {
        String text = filter.getText().strip();
        sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
        showCount();
    }

    private void showCount() {
        int all = model.getRowCount();
        int shown = table.getRowCount();
        count.setText(all == 0 ? "Keine Treffer – Auswahl oben, dann „Suchen“"
                : shown == all ? all + (all == 1 ? " Treffer" : " Treffer") : shown + " von " + all + " Treffern");
    }

    private void remember() {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        fields().forEach((name, field) -> values.put(KEY + name, field.getText().strip()));
        patterns().forEach((name, field) -> values.put(KEY + name, field.value()));
        values.put(KEY + "period", ((SearchFilter.Period.Mode) period.getSelectedItem()).name());
        values.put(KEY + "unit", ((SearchFilter.Period.Unit) unit.getSelectedItem()).name());
        values.put(KEY + "exact-title", String.valueOf(exactTitle.isSelected()));
        AppState.shared().putAll(values);
    }

    private void recall() {
        AppState state = AppState.shared();
        // Ohne gespeicherte Auswahl der Standard-Suchfilter (Einstellungen › BetaView, wie in MainframeMate).
        BetaViewDefaults standard = BetaViewDefaults.load();
        Map<String, String> defaults = Map.of("favorite", standard.favoriteId(),
                "locale", standard.locale(), "amount", String.valueOf(standard.daysBack()));
        fields().forEach((name, field) -> {
            String value = state.get(KEY + name);
            field.setText(value == null ? defaults.getOrDefault(name, "") : value);
        });
        Map<String, String> patternDefaults = Map.of("form", standard.form(),
                "extension", standard.extension(), "report", FilterPatterns.DEFAULT_REPORT,
                "job-name", FilterPatterns.DEFAULT_JOB_NAME);
        patterns().forEach((name, field) -> {
            String value = state.get(KEY + name);
            field.apply(value == null ? patternDefaults.getOrDefault(name, "*") : value);
        });
        for (SearchFilter.Period.Mode mode : SearchFilter.Period.Mode.values()) {
            if (mode.name().equals(state.get(KEY + "period"))) {
                period.setSelectedItem(mode);
            }
        }
        for (SearchFilter.Period.Unit value : SearchFilter.Period.Unit.values()) {
            if (value.name().equals(state.get(KEY + "unit"))) {
                unit.setSelectedItem(value);
            }
        }
        exactTitle.setSelected(state.getBoolean(KEY + "exact-title", false));
        showPeriodDetail();
    }

    private Map<String, PlaceholderField> fields() {
        Map<String, PlaceholderField> fields = new java.util.LinkedHashMap<>();
        fields.put("favorite", favorite);
        fields.put("locale", locale);
        fields.put("amount", amount);
        fields.put("from", from);
        fields.put("to", to);
        fields.put("title", title);
        return fields;
    }

    private Map<String, PatternFields.Field> patterns() {
        Map<String, PatternFields.Field> fields = new java.util.LinkedHashMap<>();
        fields.put("form", form);
        fields.put("extension", extension);
        fields.put("report", report);
        fields.put("job-name", jobName);
        fields.put("folder", folder);
        fields.put("tab", tab);
        fields.put("recipient", recipient);
        return fields;
    }

    /** Ein Eintrag einer Auswahlliste: Wert für den Server, Text für die Anzeige. */
    record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static JComboBox<Choice> yesNo() {
        return choices(new Choice("", "(Alle)"), new Choice("JA", "Ja"), new Choice("NEIN", "Nein"));
    }

    private static JComboBox<Choice> choices(Choice... items) {
        JComboBox<Choice> box = Comic.combo();
        for (Choice item : items) {
            box.addItem(item);
        }
        return box;
    }

    private static String value(JComboBox<Choice> box) {
        Object selected = box.getSelectedItem();
        return selected instanceof Choice choice ? choice.value() : "";
    }

    /** Die Trefferliste als Tabelle, Spalten wie vom Server. */
    static final class ResultModel extends AbstractTableModel {

        private ResultTable result = ResultTable.empty();

        void fill(ResultTable next) {
            result = next;
            fireTableStructureChanged();
        }

        ResultTable.Row row(int index) {
            return result.rows().get(index);
        }

        @Override
        public int getRowCount() {
            return result.rows().size();
        }

        @Override
        public int getColumnCount() {
            return result.columns().size();
        }

        @Override
        public String getColumnName(int column) {
            return result.columns().get(column);
        }

        @Override
        public Object getValueAt(int row, int column) {
            return result.rows().get(row).cell(column);
        }
    }
}
