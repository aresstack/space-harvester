package com.aresstack.spaceharvester.app.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.RelationConflictException;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Der Bereich „Begriffe“ (#339): die quellenübergreifende Sicht auf die je Seite ermittelten Begriffe, mit den
 * Reitern Akronyme, Bezeichner, Beziehungen und Fachglossar in dieser Reihenfolge. Akronyme, Bezeichner und
 * Beziehungen zeigen je ein Verzeichnis über alle berechtigten, aktuell indexierten Seiten mit Suchfeld. Akronyme und
 * Bezeichner liefert der Begriffsindex des Core ({@code Harvester.terms()}, #119), außerhalb des EDT abgefragt, sobald
 * der Bereich sichtbar wird und bei jeder Eingabe ins Suchfeld, ebenso die Beziehungen (#120). Für das Fachglossar gibt
 * es noch keinen Unterbau. Nur auf dem EDT.
 */
public final class TermsView implements View {

    static final String ACRONYMS = "Akronyme";
    static final String IDENTIFIERS = "Bezeichner";
    static final String RELATIONS = "Beziehungen";
    static final String GLOSSARY = "Fachglossar";
    static final String NOT_AVAILABLE = "Das Fachglossar ist noch nicht verfügbar.";
    static final String PENDING = "Das Verzeichnis füllt der Begriffsindex des Core; bis dahin stehen die Akronyme "
            + "einer Seite unter Inhalte › Begriffe.";
    static final String EMPTY = "Noch keine Begriffe im Index. Sie entstehen, wenn Seiten in den Cache geladen werden.";
    static final String NO_MATCH = "Kein Begriff passt zur Suche.";
    static final String NO_RUNTIME = "Die Laufzeit ist noch nicht gestartet.";
    /** Einträge je Abfrage; mehr zeigt die Suche. */
    static final int LIMIT = TermQueries.MAX_LIMIT;
    /** Höchstens so viele Beziehungen je Status lädt die Liste ({@code TermRelations.relations}). */
    static final int RELATION_LIMIT = TermRelations.MAX_LIMIT;
    static final String MORE_RELATIONS = "Es gibt weitere Beziehungen; gezeigt werden die ersten " + RELATION_LIMIT
            + " je Status. Ein Suchbegriff grenzt die Liste ein.";

    private final JPanel root = new JPanel(new BorderLayout());
    private final PillTabs tabs = new PillTabs();
    private final Directory acronyms = new Directory("Globales Akronymverzeichnis",
            new String[]{"Akronym", "Bedeutung", "Fundstellen"});
    private final Directory identifiers = new Directory("Globales Bezeichnerverzeichnis",
            new String[]{"Bezeichner", "Schreibweisen", "Fundstellen"});
    private final Directory relations = new Directory("Beziehungen zwischen Begriffen",
            new String[]{"Begriff", "Beziehung", "Zu Begriff", "Status", "Herkunft"});
    static final String CONFIRM = "Bestätigen";
    static final String REJECT = "Ablehnen";
    static final String REMOVE = "Entfernen";
    private final ComposerButton confirm = Comic.button(CONFIRM, "Die gewählte Beziehung gilt");
    private final ComposerButton reject = Comic.button(REJECT, "Die gewählte Beziehung gilt nicht; sie bleibt als "
            + "abgelehnt sichtbar und kommt nicht wieder");
    private final ComposerButton remove = Comic.button(REMOVE, "Eine manuelle Beziehung ganz löschen, jede andere "
            + "ablehnen");
    private List<Relation> shownRelations = List.of();
    private java.util.function.BiConsumer<String, Relation> onReview = (action, relation) -> { };

    private final AppContext context;
    private int acronymRequest;
    private int identifierRequest;
    private int relationRequest;

    /** @param context Laufzeit für den Begriffsindex; {@code null} zeigt nur das Gerüst (Tests) */
    public TermsView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JLabel glossary = Comic.muted(NOT_AVAILABLE);
        glossary.setHorizontalAlignment(SwingConstants.CENTER);
        glossary.setFont(Comic.regular(14f));
        tabs.tab(ACRONYMS, acronyms)
                .tab(IDENTIFIERS, identifiers)
                .tab(RELATIONS, relations)
                .tab(GLOSSARY, glossary);
        root.add(Comic.plate("Begriffe", Comic.PETROL, tabs), BorderLayout.CENTER);
        acronyms.show(List.of(), PENDING);
        identifiers.show(List.of(), PENDING);
        relations.show(List.of(), PENDING);
        JPanel review = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        review.setOpaque(false);
        for (ComposerButton button : List.of(confirm, reject, remove)) {
            button.setEnabled(false);
            review.add(button);
        }
        relations.actions(review);
        relations.table().getSelectionModel().addListSelectionListener(e -> selectionChanged());
        confirm.addActionListener(e -> review(CONFIRM));
        reject.addActionListener(e -> review(REJECT));
        remove.addActionListener(e -> review(REMOVE));
        if (context != null) {
            acronyms.onSearch(q -> loadAcronyms());
            identifiers.onSearch(q -> loadIdentifiers());
            relations.onSearch(q -> loadRelations(null));
            onReview = this::reviewInCore;
        }
    }

    @Override
    public void shown() {
        if (context != null) {
            loadAcronyms();
            loadIdentifiers();
            loadRelations(null);
        }
    }

    static final String NO_RELATIONS = "Noch keine Beziehungen. Vorschläge entstehen aus den Akronymen der Seiten; "
            + "bestätigte gelten für die Suche.";
    static final String CONFLICT = "Die Beziehung wurde inzwischen geändert; die Liste ist neu geladen.";

    /**
     * Vorgeschlagene und bestätigte Beziehungen, danach die abgelehnten (sie bleiben sichtbar und kommen nicht wieder),
     * je nach Begriff gefiltert wie im Dienst.
     */
    private void loadRelations(String notice) {
        if (context.runtime() == null) {
            relations.show(List.of(), NO_RUNTIME);
            return;
        }
        int request = ++relationRequest;
        String q = relations.query();
        String term = q.isEmpty() ? null : q;
        AppRuntime runtime = context.runtime();
        Background.run(() -> {
            List<Relation> all = new ArrayList<>();
            TermRelations.RelationList open = runtime.workspace().termRelations(term, null, RELATION_LIMIT);
            TermRelations.RelationList rejected = runtime.workspace().termRelations(term, ReviewStatus.REJECTED,
                    RELATION_LIMIT);
            open.relations().forEach(r -> all.add(Relation.of(r)));
            rejected.relations().forEach(r -> all.add(Relation.of(r)));
            return new LoadedRelations(all, open.nextCursor() != null || rejected.nextCursor() != null);
        }, found -> {
            if (request == relationRequest) {
                showRelations(found.list(), q.isEmpty() ? NO_RELATIONS : NO_MATCH);
                // Der Core liefert je Status höchstens RELATION_LIMIT; mehr nur mit Hinweis, nicht still gekürzt.
                String message = relationsMessage(notice, found.more());
                if (message != null) {
                    relations.message(message);
                }
            }
        }, failure -> {
            if (request == relationRequest) {
                showRelations(List.of(), "Beziehungen nicht lesbar: " + failure.getMessage());
            }
        });
    }

    /** Hinweis unter der Liste: {@code notice} und, wenn der Core mehr hat, {@link #MORE_RELATIONS}. */
    static String relationsMessage(String notice, boolean more) {
        return notice == null ? more ? MORE_RELATIONS : null : more ? notice + " " + MORE_RELATIONS : notice;
    }

    private record LoadedRelations(List<Relation> list, boolean more) {
    }

    /** Bestätigen, Ablehnen oder Entfernen mit dem gezeigten Stand; der Core prüft Rechte und Revision. */
    private void reviewInCore(String action, Relation relation) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        Background.run(() -> {
            switch (action) {
                case CONFIRM -> runtime.workspace().confirmRelation(relation.id(), relation.revision());
                case REJECT -> runtime.workspace().rejectRelation(relation.id(), relation.revision());
                default -> runtime.workspace().removeRelation(relation.id(), relation.revision());
            }
            return relation;
        }, done -> loadRelations(null), failure -> loadRelations(failure instanceof RelationConflictException
                ? CONFLICT : action + " nicht möglich: " + failure.getMessage()));
    }

    /** Zeigt Beziehungen; {@code hint} steht da, solange es keine gibt. */
    void showRelations(List<Relation> list, String hint) {
        shownRelations = List.copyOf(list);
        List<List<String>> rows = new ArrayList<>(list.size());
        for (Relation relation : list) {
            rows.add(List.of(relation.fromTerm(), Relation.typeText(relation.type()), relation.toTerm(),
                    Relation.statusText(relation.status()), Relation.originText(relation.origin())));
        }
        relations.show(rows, hint);
        selectionChanged();
    }

    /** {@code listener} bekommt Aktion ({@link #CONFIRM}, {@link #REJECT}, {@link #REMOVE}) und Beziehung. */
    void onReview(java.util.function.BiConsumer<String, Relation> listener) {
        onReview = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Die Schaltflächen passen zum Stand der gewählten Beziehung; Rechte prüft der Core. */
    private void selectionChanged() {
        Relation selected = selectedRelation();
        confirm.setEnabled(selected != null && !"CONFIRMED".equals(selected.status()));
        reject.setEnabled(selected != null && !"REJECTED".equals(selected.status()));
        remove.setEnabled(selected != null);
    }

    private Relation selectedRelation() {
        int row = relations.table().getSelectedRow();
        if (row < 0) {
            return null;
        }
        int model = relations.table().convertRowIndexToModel(row);
        List<String> cells = relations.visibleRow(model);
        // Die Tabelle filtert nur ohne Core-Suche; die Zuordnung geht über die gezeigten Zellen.
        for (Relation relation : shownRelations) {
            // Stand und Herkunft gehören dazu: dasselbe Paar kann abgelehnt und neu vorgeschlagen dastehen.
            if (cells.get(0).equals(relation.fromTerm()) && cells.get(2).equals(relation.toTerm())
                    && cells.get(1).equals(Relation.typeText(relation.type()))
                    && cells.get(3).equals(Relation.statusText(relation.status()))
                    && cells.get(4).equals(Relation.originText(relation.origin()))) {
                return relation;
            }
        }
        return null;
    }

    private void review(String action) {
        Relation selected = selectedRelation();
        if (selected != null) {
            onReview.accept(action, selected);
        }
    }

    /** Akronym, Bedeutung (Ausschreibungen neben dem Akronym), Fundstellen. */
    private void loadAcronyms() {
        int request = ++acronymRequest;
        String q = acronyms.query();
        load(acronyms, () -> query(TermKind.ACRONYM, q), rows -> {
            if (request == acronymRequest) {
                acronyms.show(rows, q.isEmpty() ? EMPTY : NO_MATCH);
            }
        });
    }

    /** Bezeichner (Nomen, Nominalgruppen, Kennungen), Schreibweisen, Fundstellen. */
    private void loadIdentifiers() {
        int request = ++identifierRequest;
        String q = identifiers.query();
        load(identifiers, () -> {
            List<TermQueries.TermSummary> all = new ArrayList<>(query(TermKind.NOUN, q));
            all.addAll(query(TermKind.IDENTIFIER, q));
            all.sort(Comparator.comparing(TermQueries.TermSummary::normalized));
            return all.size() > LIMIT ? all.subList(0, LIMIT) : all;
        }, rows -> {
            if (request == identifierRequest) {
                identifiers.show(rows, q.isEmpty() ? EMPTY : NO_MATCH);
            }
        });
    }

    private List<TermQueries.TermSummary> query(TermKind kind, String q) {
        AppRuntime runtime = context.runtime();
        return runtime.workspace().terms(new TermQueries.TermSearch(null, null, kind, q.isEmpty() ? null : q, false,
                LIMIT, null)).terms();
    }

    private void load(Directory directory, java.util.concurrent.Callable<List<TermQueries.TermSummary>> work,
                      Consumer<List<List<String>>> onRows) {
        if (context.runtime() == null) {
            directory.show(List.of(), NO_RUNTIME);
            return;
        }
        Background.run(work, terms -> onRows.accept(rows(terms)),
                failure -> directory.show(List.of(), "Begriffe nicht lesbar: " + failure.getMessage()));
    }

    /** Je Begriff Wortlaut, Ausschreibungen bzw. Schreibweisen, Zahl der Fundstellen. */
    static List<List<String>> rows(List<TermQueries.TermSummary> terms) {
        List<List<String>> rows = new ArrayList<>(terms.size());
        for (TermQueries.TermSummary term : terms) {
            List<String> second = term.kind() == TermKind.ACRONYM ? term.expansions() : term.spellings();
            rows.add(List.of(term.term(), second.isEmpty() ? "–" : String.join(" · ", second),
                    String.valueOf(term.occurrences())));
        }
        return rows;
    }

    @Override
    public JComponent component() {
        return root;
    }

    // ---- für Tests

    PillTabs tabs() {
        return tabs;
    }

    Directory acronyms() {
        return acronyms;
    }

    Directory identifiers() {
        return identifiers;
    }

    Directory relations() {
        return relations;
    }

    List<ComposerButton> reviewButtons() {
        return List.of(confirm, reject, remove);
    }

    /**
     * Ein Verzeichnis: Überschrift, „Alle berechtigten, aktuell indexierten Seiten“, Suchfeld und Tabelle; die letzte
     * Spalte ist rechtsbündig. Das Suchfeld filtert über alle Spalten.
     */
    static final class Directory extends JPanel {

        private final Model model;
        private final JTable table;
        private final PlaceholderField search = new PlaceholderField(16, "Suchen …");
        private final JLabel hint = Comic.muted(" ");
        private Consumer<String> onSearch;
        private final JPanel south = new JPanel(new BorderLayout(0, 6));

        Directory(String title, String[] columns) {
            super(new BorderLayout(0, 8));
            setOpaque(false);
            model = new Model(columns);
            table = Comic.table(new JTable(model));
            DefaultTableCellRenderer right = new DefaultTableCellRenderer();
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            table.getColumnModel().getColumn(columns.length - 1).setCellRenderer(right);

            JLabel heading = Comic.text(title);
            heading.setFont(Comic.semiBold(15f));
            JPanel lines = new JPanel(new BorderLayout(0, 2));
            lines.setOpaque(false);
            lines.add(heading, BorderLayout.NORTH);
            lines.add(Comic.muted("Alle berechtigten, aktuell indexierten Seiten"), BorderLayout.SOUTH);
            JPanel head = new JPanel(new BorderLayout(12, 0));
            head.setOpaque(false);
            head.add(lines, BorderLayout.CENTER);
            head.add(search, BorderLayout.EAST);

            add(head, BorderLayout.NORTH);
            add(Comic.scroll(table), BorderLayout.CENTER);
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
    }

    private static final class Model extends AbstractTableModel {

        private final String[] columns;
        private List<List<String>> all = List.of();
        private List<List<String>> visible = List.of();
        private String query = "";

        Model(String[] columns) {
            this.columns = columns.clone();
        }

        void set(List<List<String>> rows) {
            all = List.copyOf(rows);
            filter(query);
        }

        void filter(String text) {
            query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            List<List<String>> matching = new ArrayList<>();
            for (List<String> row : all) {
                if (query.isEmpty() || row.stream()
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
