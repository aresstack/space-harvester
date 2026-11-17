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
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import javax.swing.SwingUtilities;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.RelationType;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Der Bereich „Begriffe“ (#339): die quellenübergreifende Sicht auf die je Seite ermittelten Begriffe, mit den
 * Reitern Akronyme, Bezeichner, Beziehungen, Fachglossar und Graph in dieser Reihenfolge. Akronyme, Bezeichner und
 * Beziehungen zeigen je ein Verzeichnis über alle berechtigten, aktuell indexierten Seiten mit
 * Suchfeld. Akronyme und Bezeichner liefert der Begriffsindex des Core ({@code Harvester.terms()}, #119), außerhalb des
 * EDT abgefragt, sobald der Bereich sichtbar wird und bei jeder Eingabe ins Suchfeld, ebenso die Beziehungen (#120);
 * Synonyme und Schreibvarianten sind Beziehungen; die Liste filtert nach Art ({@link RelationTab}), ihre Aktionen
 * „Neue Beziehung …“ und „Synonyme vorschlagen“ stehen oben rechts in der Reiterzeile. Der Graph zeigt einen
 * Begriff mit seinen Nachbarn ({@link TermGraphView}). Das Fachglossar verwaltet die dauerhaft gepflegten
 * Glossareinträge ({@link GlossaryTab}); seine Knöpfe „Fachbegriffe ermitteln“ und „Fachbegriff hinzufügen …“ stehen
 * oben rechts in der Reiterzeile und sind nur auf diesem Reiter zu sehen. Nur auf dem EDT.
 */
public final class TermsView implements View {

    static final String ACRONYMS = "Akronyme";
    static final String IDENTIFIERS = "Bezeichner";
    static final String RELATIONS = "Beziehungen";
    static final String GLOSSARY = "Fachglossar";
    static final String GRAPH = "Graph";
    static final String NOT_AVAILABLE = "Das Fachglossar ist noch nicht verfügbar.";
    static final String PENDING = "Das Verzeichnis füllt der Begriffsindex des Core; bis dahin stehen die Akronyme "
            + "einer Seite unter Inhalte › Begriffe.";
    static final String EMPTY = "Noch keine Begriffe im Index. Sie entstehen, wenn Seiten in den Cache geladen werden.";
    static final String NO_MATCH = "Kein Begriff passt zur Suche.";
    static final String NO_RUNTIME = "Die Laufzeit ist noch nicht gestartet.";
    /** Einträge je Abfrage; mehr zeigt die Suche. */
    static final int LIMIT = TermQueries.MAX_LIMIT;
    /** Beziehungen je Seite ({@code TermRelations.relations}); die nächste Seite lädt beim Scrollen ans Ende. */
    static final int RELATION_LIMIT = TermRelations.MAX_LIMIT;

    private final JPanel root = new JPanel(new BorderLayout());
    private final PillTabs tabs = new PillTabs();
    private final Directory acronyms = new Directory("Globales Akronymverzeichnis",
            new String[]{"Akronym", "Bedeutung", "Fundstellen"});
    private final Directory identifiers = new Directory("Globales Bezeichnerverzeichnis",
            new String[]{"Bezeichner", "Fundstellen"});
    static final String SHOW_OCCURRENCES = "Fundstellen zeigen";
    private final ComposerButton occurrences = Comic.button("Fundstellen …", SHOW_OCCURRENCES
            + " (auch per Doppelklick auf eine Zeile)");
    private final RelationTab relationTab;
    private final TermGraphView graph;
    private final GlossaryTab glossaryTab;
    static final String CONFIRM = "Bestätigen";
    static final String REJECT = "Ablehnen";
    static final String REMOVE = "Entfernen";
    static final String UNDO = "Rückgängig";
    static final String NEW_RELATION = "Neue Beziehung …";

    private final AppContext context;
    /** Stand des Fachglossars (Anzahl, ausgewertete Seiten) in der Statuszeile, solange sein Reiter gewählt ist. */
    private final JLabel statusLabel = Comic.muted(" ");
    private String glossaryStatus = " ";
    private int acronymRequest;
    private int identifierRequest;
    private Pager<TermQueries.TermSummary> identifierPager;
    private boolean identifierLoading;

    /** @param context Laufzeit für den Begriffsindex; {@code null} zeigt nur das Gerüst (Tests) */
    public TermsView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        relationTab = RelationTab.all(context);
        graph = new TermGraphView(context);
        glossaryTab = new GlossaryTab(context, () -> context == null || context.runtime() == null
                ? java.util.Optional.empty() : context.runtime().workspace().glossary());
        tabs.tab(ACRONYMS, acronyms)
                .tab(IDENTIFIERS, identifiers)
                .tab(RELATIONS, relationTab.component())
                .tab(GLOSSARY, glossaryTab.component())
                .tab(GRAPH, graph.component())
                .actions(relationTab.proposeButton(), relationTab.createButton(), glossaryTab.tools(),
                        glossaryTab.addButton(), glossaryTab.detectButton());
        relationTab.createButton().setVisible(false);
        relationTab.proposeButton().setVisible(false);
        glossaryTab.tools().setVisible(false);
        glossaryTab.addButton().setVisible(false);
        glossaryTab.detectButton().setVisible(false);
        glossaryTab.onStatus(text -> {
            glossaryStatus = text;
            if (GLOSSARY.equals(tabs.selected())) {
                statusLabel.setText(text);
            }
        });
        root.add(Comic.plate("Begriffe", Comic.PETROL, tabs), BorderLayout.CENTER);
        acronyms.show(List.of(), PENDING);
        identifiers.show(List.of(), PENDING);
        JPanel identifierActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        identifierActions.setOpaque(false);
        identifierActions.add(occurrences);
        occurrences.setEnabled(false);
        identifiers.actions(identifierActions);
        identifiers.table().getSelectionModel().addListSelectionListener(e ->
                occurrences.setEnabled(context != null && identifiers.table().getSelectedRow() >= 0));
        occurrences.addActionListener(e -> showOccurrences());
        identifiers.table().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && identifiers.table().rowAtPoint(e.getPoint()) >= 0) {
                    showOccurrences();
                }
            }
        });
        tabs.onSelect(this::tabSelected);
        if (context != null) {
            acronyms.onSearch(q -> loadAcronyms());
            identifiers.onSearch(q -> loadIdentifiers());
            identifiers.letters(letter -> loadIdentifiers());
            identifiers.onScrolledToEnd(this::moreIdentifiers);
        }
    }

    /**
     * Die Auswahl links im Explorer (ADR 0029): das Fachglossar zeigt die Einträge und die Wichtigkeit dieser Auswahl;
     * die übrigen Reiter bleiben quellenübergreifend.
     */
    public void selection(com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection selection) {
        glossaryTab.selection(selection);
    }

    @Override
    public JComponent statusInfo() {
        return statusLabel;
    }

    @Override
    public void shown() {
        if (context != null) {
            loadAcronyms();
            loadIdentifiers();
            relationTab.shown();
            graph.shown();
            glossaryTab.shown();
        }
    }

    /**
     * Ein Wechsel des Reiters besiegelt das Entfernen; Beziehungen und Graph zeigen dieselben Beziehungen des Core,
     * also lädt der gewählte neu, was im anderen geändert wurde.
     */
    private void tabSelected(String name) {
        relationTab.forgetRemoved();
        // Die Knöpfe der Reiterzeile gelten nur auf ihrem Reiter; auf den anderen bleibt die Reiterzeile frei.
        relationTab.createButton().setVisible(RELATIONS.equals(name));
        relationTab.proposeButton().setVisible(RELATIONS.equals(name));
        glossaryTab.tools().setVisible(GLOSSARY.equals(name));
        glossaryTab.addButton().setVisible(GLOSSARY.equals(name));
        glossaryTab.detectButton().setVisible(GLOSSARY.equals(name));
        statusLabel.setText(GLOSSARY.equals(name) ? glossaryStatus : " ");
        if (context == null) {
            return;
        }
        switch (name) {
            case RELATIONS -> relationTab.load(null);
            case GLOSSARY -> glossaryTab.load();
            case GRAPH -> graph.shown();
            default -> { }
        }
    }

    static final String NO_RELATIONS = "Noch keine Beziehungen. Vorschläge entstehen aus den Akronymen der Seiten "
            + "und aus „Synonyme vorschlagen“; „Neue Beziehung …“ legt eine an; bestätigte gelten für die Suche.";
    static final String CONFLICT = "Die Beziehung wurde inzwischen geändert; die Liste ist neu geladen.";

    /** {@code null}, wenn die Eingaben taugen, sonst der Grund in Worten. */
    static String invalidNewRelation(String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return "Beide Begriffe angeben.";
        }
        if (from.trim().equalsIgnoreCase(to.trim())) {
            return "Die Begriffe müssen sich unterscheiden.";
        }
        return null;
    }

    /** Legt die Beziehung im Core an (manuell, bestätigt, global) und wählt sie unter Beziehungen. */
    void createRelation(String from, RelationType type, String to) {
        relationTab.createRelation(from, type, to);
    }

    static final java.awt.Color REJECTED_BACKGROUND = new java.awt.Color(0xECEDEF);

    /** Ob die Beziehung unter Beziehungen eben entfernt wurde und noch durchgestrichen dasteht. */
    boolean isRemoved(String id) {
        return relationTab.isRemoved(id);
    }

    ComposerButton createButton() {
        return relationTab.createButton();
    }

    /** Zeigt Beziehungen unter Beziehungen; {@code hint} steht da, solange es keine gibt. */
    void showRelations(List<Relation> list, String hint) {
        relationTab.show(list, hint);
    }

    /** {@code listener} bekommt Aktion ({@link #CONFIRM}, {@link #REJECT}, {@link #REMOVE}) und Beziehung. */
    void onReview(java.util.function.BiConsumer<String, Relation> listener) {
        relationTab.onReview(listener);
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

    /**
     * Bezeichner (Nomen, Nominalgruppen, Kennungen), Schreibweisen, Fundstellen: seitenweise je {@link #LIMIT}, die
     * nächste Seite lädt beim Scrollen ans Ende; ein gewählter Anfangsbuchstabe grenzt zusätzlich ein. Die Arten Nomen
     * und Kennung führt der {@link Pager} alphabetisch zusammen, jede mit eigenem Cursor des Core; den
     * Anfangsbuchstaben grenzt der Core ein ({@code TermSearch.startsWith}).
     */
    private void loadIdentifiers() {
        int request = ++identifierRequest;
        String q = identifiers.query();
        String letter = identifiers.letter();
        if (context.runtime() == null) {
            identifiers.show(List.of(), NO_RUNTIME);
            return;
        }
        Workspace workspace = context.runtime().workspace();
        identifierPager = new Pager<>(Comparator.comparing(TermQueries.TermSummary::normalized),
                List.of(List.of(identifierFetch(workspace, TermKind.NOUN, q, letter),
                        identifierFetch(workspace, TermKind.IDENTIFIER, q, letter))));
        identifierLoading = false;
        moreIdentifiers(request, true, q.isEmpty() && letter == null ? EMPTY : NO_MATCH);
    }

    /** Eine Seite Begriffe der Art {@code kind} zur Suche {@code q} und zum Anfangsbuchstaben {@code letter}. */
    static Pager.Fetch<TermQueries.TermSummary> identifierFetch(Workspace workspace, TermKind kind, String q,
                                                                String letter) {
        return cursor -> {
            TermQueries.TermList list = workspace.terms(new TermQueries.TermSearch(null, null, kind,
                    q.isEmpty() ? null : q, false, LIMIT, cursor, letter));
            return new Pager.Page<>(list.terms(), list.nextCursor());
        };
    }

    /** Die nächste Seite Bezeichner, wenn noch welche da sind und gerade keine lädt. */
    private void moreIdentifiers() {
        Pager<TermQueries.TermSummary> pager = identifierPager;
        if (pager != null && !identifierLoading && pager.hasMore()) {
            moreIdentifiers(identifierRequest, false, NO_MATCH);
        }
    }

    private void moreIdentifiers(int request, boolean first, String emptyHint) {
        Pager<TermQueries.TermSummary> pager = identifierPager;
        identifierLoading = true;
        Background.run(() -> pager.next(LIMIT), terms -> {
            if (request != identifierRequest) {
                return;
            }
            identifierLoading = false;
            if (first) {
                identifiers.show(identifierRows(terms), emptyHint);
            } else {
                identifiers.append(identifierRows(terms));
            }
            identifiers.more(pager.hasMore());
        }, failure -> {
            if (request == identifierRequest) {
                identifierLoading = false;
                identifiers.message("Begriffe nicht lesbar: " + failure.getMessage());
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

    /** Bezeichner und Zahl der Fundstellen; Suchwert und Art stehen unsichtbar dahinter, für die Fundstellen. */
    static List<List<String>> identifierRows(List<TermQueries.TermSummary> terms) {
        List<List<String>> rows = new ArrayList<>(terms.size());
        for (TermQueries.TermSummary term : terms) {
            rows.add(List.of(term.term(), String.valueOf(term.occurrences()), term.normalized(), term.kind().name()));
        }
        return rows;
    }

    /** Höchstens so viele Fundstellen zeigt der Dialog. */
    static final int OCCURRENCE_LIMIT = TermQueries.MAX_LIMIT;

    /** Fundstellen des gewählten Bezeichners im Dialog; ein Doppelklick dort öffnet die Seite im Leser. */
    private void showOccurrences() {
        int row = identifiers.table().getSelectedRow();
        AppRuntime runtime = context == null ? null : context.runtime();
        if (row < 0 || runtime == null) {
            return;
        }
        List<String> cells = identifiers.visibleRow(identifiers.table().convertRowIndexToModel(row));
        if (cells.size() < 4) {
            return;
        }
        String term = cells.get(0);
        TermKind kind = TermKind.valueOf(cells.get(3));
        Background.run(() -> runtime.workspace().occurrences(new TermQueries.OccurrenceSearch(cells.get(2), kind,
                null, null, OCCURRENCE_LIMIT, null, 80)), found -> {
            Directory list = new Directory("Fundstellen von „" + term + "“", new String[]{"Seite", "Quelle · Space",
                    "Kontext"});
            List<TermQueries.Occurrence> shown = found.occurrences();
            list.show(indexedOccurrenceRows(shown), "Keine sichtbare Fundstelle.");
            if (found.nextCursor() != null) {
                list.message("Gezeigt werden die ersten " + OCCURRENCE_LIMIT + " Fundstellen.");
            }
            javax.swing.JDialog dialog = Dialogs.dialog(root, "Fundstellen");
            list.table().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    int at = list.table().rowAtPoint(e.getPoint());
                    if (e.getClickCount() == 2 && at >= 0) {
                        TermQueries.Occurrence hit = shown.get(occurrenceIndex(
                                list.visibleRow(list.table().convertRowIndexToModel(at))));
                        dialog.dispose();
                        context.navigator().openPage(hit.sourceId(), hit.pageId());
                    }
                }
            });
            list.setPreferredSize(new java.awt.Dimension(720, 380));
            ComposerButton close = Comic.button("Schließen", null);
            close.addActionListener(e -> dialog.dispose());
            Dialogs.show(dialog, "Fundstellen – Doppelklick öffnet die Seite", list, close);
        }, failure -> identifiers.message("Fundstellen nicht lesbar: " + failure.getMessage()));
    }

    /** Wie {@link #occurrenceRows}, dazu unsichtbar die Stelle in der Liste, damit Filtern die Zuordnung hält. */
    static List<List<String>> indexedOccurrenceRows(List<TermQueries.Occurrence> occurrences) {
        List<List<String>> rows = new ArrayList<>();
        List<List<String>> plain = occurrenceRows(occurrences);
        for (int i = 0; i < plain.size(); i++) {
            List<String> row = new ArrayList<>(plain.get(i));
            row.add(String.valueOf(i));
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    /** Stelle der Fundstelle einer gezeigten Zeile in der ungefilterten Liste. */
    static int occurrenceIndex(List<String> cells) {
        return Integer.parseInt(cells.get(3));
    }

    /** Seite, Quelle · Space und Kontext je Fundstelle. */
    static List<List<String>> occurrenceRows(List<TermQueries.Occurrence> occurrences) {
        List<List<String>> rows = new ArrayList<>(occurrences.size());
        for (TermQueries.Occurrence o : occurrences) {
            String title = o.title() == null || o.title().isBlank() ? o.pageId() : o.title();
            String where = o.spaceKey() == null ? o.sourceId() : o.sourceId() + " · " + o.spaceKey();
            TermQueries.Snippet s = o.snippet();
            String context = s == null ? "–" : (s.truncatedBefore() ? "…" : "") + s.before() + s.match() + s.after()
                    + (s.truncatedAfter() ? "…" : "");
            rows.add(List.of(title, where, context.replaceAll("\\s+", " ").trim()));
        }
        return rows;
    }

    ComposerButton occurrencesButton() {
        return occurrences;
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
        return relationTab.directory();
    }

    List<ComposerButton> reviewButtons() {
        return relationTab.reviewButtons();
    }

    RelationTab relationTab() {
        return relationTab;
    }

    TermGraphView graph() {
        return graph;
    }

    GlossaryTab glossary() {
        return glossaryTab;
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

        Directory(String title, String[] columns) {
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
