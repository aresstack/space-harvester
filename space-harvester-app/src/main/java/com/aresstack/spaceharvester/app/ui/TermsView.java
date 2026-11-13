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
import com.aresstack.spaceharvester.application.terms.SynonymDiscovery;
import com.aresstack.spaceharvester.application.terms.SynonymScope;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Der Bereich „Begriffe“ (#339): die quellenübergreifende Sicht auf die je Seite ermittelten Begriffe, mit den
 * Reitern Akronyme, Bezeichner, Beziehungen, Synonyme, Fachglossar und Graph in dieser Reihenfolge. Akronyme,
 * Bezeichner, Beziehungen und Synonyme zeigen je ein Verzeichnis über alle berechtigten, aktuell indexierten Seiten mit
 * Suchfeld. Akronyme und Bezeichner liefert der Begriffsindex des Core ({@code Harvester.terms()}, #119), außerhalb des
 * EDT abgefragt, sobald der Bereich sichtbar wird und bei jeder Eingabe ins Suchfeld, ebenso die Beziehungen (#120);
 * Synonyme sind die Beziehungen der Arten Synonym und Schreibvariante ({@link RelationTab}). Der Graph zeigt einen
 * Begriff mit seinen Nachbarn ({@link TermGraphView}). Das Fachglossar verwaltet die dauerhaft gepflegten
 * Glossareinträge ({@link GlossaryTab}); seine Knöpfe „Fachbegriffe ermitteln“ und „Fachbegriff hinzufügen …“ stehen
 * oben rechts in der Reiterzeile und sind nur auf diesem Reiter zu sehen. Nur auf dem EDT.
 */
public final class TermsView implements View {

    static final String ACRONYMS = "Akronyme";
    static final String IDENTIFIERS = "Bezeichner";
    static final String RELATIONS = "Beziehungen";
    static final String SYNONYMS = "Synonyme";
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
    /** Höchstens so viele Beziehungen je Status lädt die Liste ({@code TermRelations.relations}). */
    static final int RELATION_LIMIT = TermRelations.MAX_LIMIT;
    static final String MORE_RELATIONS = "Es gibt weitere Beziehungen; gezeigt werden die ersten " + RELATION_LIMIT
            + " je Status. Ein Suchbegriff grenzt die Liste ein.";
    /** „Synonyme vorschlagen“ (#336): nur auf Klick, Umfang und Budget im Dialog, Lauf mit Fortschritt und Abbrechen. */
    static final String PROPOSE_SYNONYMS = "Synonyme vorschlagen";
    static final String PROPOSING = "Synonyme werden vorgeschlagen …";
    /** Meldungen in der Statuszeile des Hauptfensters; kein Meldungsband unter dem Reiter. */
    static final String RULES_ONLY = "Ohne Embedding-Modell nur Regelvorschläge";
    static final String CANCELLED = "Synonymerkennung abgebrochen; bisherige Vorschläge bleiben";
    private static final int NOTICE_MS = 8000;

    private final JPanel root = new JPanel(new BorderLayout());
    private final PillTabs tabs = new PillTabs();
    private final Directory acronyms = new Directory("Globales Akronymverzeichnis",
            new String[]{"Akronym", "Bedeutung", "Fundstellen"});
    private final Directory identifiers = new Directory("Globales Bezeichnerverzeichnis",
            new String[]{"Bezeichner", "Fundstellen"});
    static final String SHOW_OCCURRENCES = "Fundstellen zeigen";
    private final ComposerButton proposeSynonyms = Comic.button(PROPOSE_SYNONYMS, "Schreibvarianten und "
            + "Formulierungen im Text als Regelvorschläge; mit Chat-Modell werden Kandidatenpaare anhand kurzer "
            + "Fundstellen aus freigegebenen Quellen geprüft. Nur Vorschläge, nichts wird bestätigt");
    private BusyOverlay synonymOverlay;
    /** Anmeldung bei den Aufträgen, solange die Synonymerkennung läuft (ADR 0027, kein Zeitgeber). */
    private com.aresstack.spaceharvester.application.observe.Subscription synonymSubscription;
    private String synonymJob;
    private boolean synonymRulesOnly;
    private final ComposerButton occurrences = Comic.button("Fundstellen …", SHOW_OCCURRENCES
            + " (auch per Doppelklick auf eine Zeile)");
    private final RelationTab relationTab;
    private final RelationTab synonymTab;
    private final TermGraphView graph;
    private final GlossaryTab glossaryTab;
    static final String CONFIRM = "Bestätigen";
    static final String REJECT = "Ablehnen";
    static final String REMOVE = "Entfernen";
    static final String UNDO = "Rückgängig";
    static final String NEW_RELATION = "Neue Beziehung …";

    private final AppContext context;
    private int acronymRequest;
    private int identifierRequest;
    private IdentifierPager identifierPager;
    private boolean identifierLoading;

    /** @param context Laufzeit für den Begriffsindex; {@code null} zeigt nur das Gerüst (Tests) */
    public TermsView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        relationTab = new RelationTab(context, "Beziehungen zwischen Begriffen",
                new String[]{"Begriff", "Beziehung", "Zu Begriff", "Status", "Herkunft"},
                r -> List.of(r.fromTerm(), Relation.typeText(r.type()), r.toTerm(), Relation.statusText(r.status()),
                        Relation.originText(r.origin())),
                List.of(), RelationType.ABBREVIATION, NO_RELATIONS);
        synonymTab = new RelationTab(context, "Synonyme und Schreibvarianten",
                new String[]{"Begriff", "Synonym", "Art", "Herkunft", "Status"},
                r -> List.of(r.fromTerm(), r.toTerm(), Relation.typeText(r.type()), Relation.originText(r.origin()),
                        Relation.statusText(r.status())),
                SYNONYM_TYPES, RelationType.SYNONYM, NO_SYNONYMS);
        graph = new TermGraphView(context);
        glossaryTab = new GlossaryTab(context, () -> context == null || context.runtime() == null
                ? java.util.Optional.empty() : context.runtime().workspace().glossary());
        tabs.tab(ACRONYMS, acronyms)
                .tab(IDENTIFIERS, identifiers)
                .tab(RELATIONS, relationTab.directory())
                .tab(SYNONYMS, createSynonymOverlay())
                .tab(GLOSSARY, glossaryTab.component())
                .tab(GRAPH, graph.component())
                .actions(proposeSynonyms, glossaryTab.addButton(), glossaryTab.detectButton());
        proposeSynonyms.setVisible(false);
        proposeSynonyms.setEnabled(context != null);
        proposeSynonyms.addActionListener(e -> askSynonymRun());
        glossaryTab.addButton().setVisible(false);
        glossaryTab.detectButton().setVisible(false);
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

    @Override
    public void shown() {
        if (context != null) {
            loadAcronyms();
            loadIdentifiers();
            relationTab.shown();
            synonymTab.shown();
            graph.shown();
            glossaryTab.shown();
        }
    }

    /**
     * Ein Wechsel des Reiters besiegelt das Entfernen; Beziehungen, Synonyme und Graph zeigen dieselben Beziehungen
     * des Core, also lädt der gewählte neu, was im anderen geändert wurde.
     */
    private void tabSelected(String name) {
        relationTab.forgetRemoved();
        synonymTab.forgetRemoved();
        // Die Knöpfe der Reiterzeile gelten nur auf ihrem Reiter; auf den anderen bleibt die Reiterzeile frei.
        proposeSynonyms.setVisible(SYNONYMS.equals(name));
        glossaryTab.addButton().setVisible(GLOSSARY.equals(name));
        glossaryTab.detectButton().setVisible(GLOSSARY.equals(name));
        if (context == null) {
            return;
        }
        switch (name) {
            case RELATIONS -> relationTab.load(null);
            case SYNONYMS -> synonymTab.load(null);
            case GLOSSARY -> glossaryTab.load();
            case GRAPH -> graph.shown();
            default -> { }
        }
    }

    /** Der Synonym-Reiter unter dem Wartekreis des Laufs (eine Größe überall, Abbrechen in der Mitte, Esc). */
    private BusyOverlay createSynonymOverlay() {
        synonymOverlay = new BusyOverlay(synonymTab.directory());
        synonymOverlay.setHighlight(Comic.BLUE);
        synonymOverlay.setVeilColor(Comic.SURFACE);
        synonymOverlay.onCancel(this::cancelSynonymRun);
        return synonymOverlay;
    }

    /** Dialog „Synonyme vorschlagen“: Umfang, Budget, was der Lauf kann; „Starten“ reiht den Auftrag ein. */
    private void askSynonymRun() {
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == null) {
            synonymTab.directory().message(NO_RUNTIME);
            return;
        }
        Workspace workspace = runtime.workspace();
        SynonymRunForm form;
        try {
            form = new SynonymRunForm(workspace.sources(), workspace::spaces, workspace::synonymAvailability,
                    workspace.synonymAvailability(SynonymScope.ALL).budget().maxJudgedPairs());
        } catch (RuntimeException e) {
            synonymTab.directory().message("Nicht möglich: " + e.getMessage());
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(root, PROPOSE_SYNONYMS);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton start = Comic.primary("Starten", "Den Lauf im Hintergrund einreihen; der Kreis zeigt den "
                + "Fortschritt, Abbrechen lässt bisherige Vorschläge stehen");
        cancel.addActionListener(e -> dialog.dispose());
        start.addActionListener(e -> {
            SynonymDiscovery.Request request = form.request();
            dialog.dispose();
            startSynonymRun(request);
        });
        Dialogs.show(dialog, PROPOSE_SYNONYMS, form, cancel, start);
    }

    /** Startet den Lauf und zeigt ihn über dem Reiter; jeden neuen Stand meldet der Core (ADR 0027). */
    void startSynonymRun(SynonymDiscovery.Request request) {
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == null || synonymJob != null) {
            return;
        }
        Workspace workspace = runtime.workspace();
        synonymOverlay.setText(PROPOSING);
        synonymOverlay.setBusy(true);
        proposeSynonyms.setEnabled(false);
        Background.run(() -> {
            synonymRulesOnly = !workspace.synonymAvailability(request.scope()).embeddingModel();
            return workspace.startSynonymDiscovery(request.scope(), request.maxJudgedPairs());
        }, status -> {
            synonymJob = status.id();
            // Erst anmelden, dann einmal lesen: was zwischen Start und Anmeldung geschah, bringt das Lesen.
            synonymSubscription = workspace.subscribeJobs(changed -> {
                if (changed.id().equals(status.id())) {
                    SwingUtilities.invokeLater(() -> showSynonymRun(changed));
                }
            });
            pollSynonymRun(workspace);
        }, failure -> {
            synonymOverlay.setBusy(false);
            proposeSynonyms.setEnabled(true);
            notice("Synonymerkennung nicht gestartet: " + failure.getMessage(), Severity.WARN);
        });
    }

    private void pollSynonymRun(Workspace workspace) {
        String jobId = synonymJob;
        if (jobId == null) {
            return;
        }
        Background.run(() -> workspace.synonymJob(jobId), this::showSynonymRun, failure -> finishSynonymRun(null));
    }

    /** Ein Stand des laufenden Auftrags, auf dem EDT; ein Stand eines anderen Auftrags zählt nicht. */
    private void showSynonymRun(JobStatus status) {
        if (!status.id().equals(synonymJob)) {
            return;
        }
        synonymOverlay.setText(progressText(status));
        if (status.state().terminal()) {
            finishSynonymRun(status);
        }
    }

    static String progressText(JobStatus status) {
        if (status.progress().discovered() == 0) {
            return PROPOSING;
        }
        return PROPOSING + " " + status.progress().processed() + " von " + status.progress().discovered()
                + " Paaren geprüft";
    }

    private void finishSynonymRun(JobStatus status) {
        if (synonymSubscription != null) {
            synonymSubscription.close();
            synonymSubscription = null;
        }
        synonymJob = null;
        synonymOverlay.setBusy(false);
        proposeSynonyms.setEnabled(true);
        if (status != null) {
            String text = outcomeText(status, synonymRulesOnly);
            notice(text, status.state() == JobState.FAILED ? Severity.WARN : Severity.INFO);
        }
        synonymTab.load(null);
    }

    /** Die Statuszeile nach dem Lauf: Zahl der neuen oder geänderten Vorschläge, Fehler oder Abbruch. */
    static String outcomeText(JobStatus status, boolean rulesOnly) {
        return switch (status.state()) {
            case COMPLETED -> "Synonymerkennung abgeschlossen: " + Texts.count(status.progress().written(),
                    "Vorschlag", "Vorschläge") + " neu oder geändert" + (rulesOnly ? "; " + RULES_ONLY : "");
            case CANCELLED -> CANCELLED;
            case FAILED -> "Synonymerkennung gescheitert: " + (status.error() == null ? "unbekannter Fehler"
                    : status.error().message()) + "; bisherige Vorschläge bleiben";
            default -> PROPOSING;
        };
    }

    private void cancelSynonymRun() {
        String jobId = synonymJob;
        AppRuntime runtime = context == null ? null : context.runtime();
        if (jobId == null || runtime == null) {
            return;
        }
        Background.run(() -> runtime.workspace().cancelSynonymJob(jobId), ignored -> { }, failure -> { });
    }

    private void notice(String text, Severity severity) {
        if (context != null) {
            context.events().publish(new StatusMessageEvent(text, NOTICE_MS, severity));
        }
    }

    ComposerButton proposeSynonymsButton() {
        return proposeSynonyms;
    }

    BusyOverlay synonymOverlay() {
        return synonymOverlay;
    }

    /** Die Arten des Reiters „Synonyme“. */
    static final List<RelationType> SYNONYM_TYPES = List.of(RelationType.SYNONYM, RelationType.VARIANT);

    static final String NO_RELATIONS = "Noch keine Beziehungen. Vorschläge entstehen aus den Akronymen der Seiten; "
            + "bestätigte gelten für die Suche.";
    static final String NO_SYNONYMS = "Noch keine Synonyme oder Schreibvarianten. „Neue Beziehung …“ legt eine an; "
            + "bestätigte gelten für die Suche.";
    static final String CONFLICT = "Die Beziehung wurde inzwischen geändert; die Liste ist neu geladen.";

    /** Hinweis unter der Liste: {@code notice} und, wenn der Core mehr hat, {@link #MORE_RELATIONS}. */
    static String relationsMessage(String notice, boolean more) {
        return notice == null ? more ? MORE_RELATIONS : null : more ? notice + " " + MORE_RELATIONS : notice;
    }

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
     * nächste Seite lädt beim Scrollen ans Ende; ein gewählter Anfangsbuchstabe grenzt zusätzlich ein.
     */
    private void loadIdentifiers() {
        int request = ++identifierRequest;
        String q = identifiers.query();
        String letter = identifiers.letter();
        if (context.runtime() == null) {
            identifiers.show(List.of(), NO_RUNTIME);
            return;
        }
        identifierPager = new IdentifierPager((kind, cursor) -> context.runtime().workspace().terms(
                new TermQueries.TermSearch(null, null, kind, q.isEmpty() ? null : q, false, LIMIT, cursor,
                        letter)));
        identifierLoading = false;
        moreIdentifiers(request, true, q.isEmpty() && letter == null ? EMPTY : NO_MATCH);
    }

    /** Die nächste Seite Bezeichner, wenn noch welche da sind und gerade keine lädt. */
    private void moreIdentifiers() {
        IdentifierPager pager = identifierPager;
        if (pager != null && !identifierLoading && pager.hasMore()) {
            moreIdentifiers(identifierRequest, false, NO_MATCH);
        }
    }

    private void moreIdentifiers(int request, boolean first, String emptyHint) {
        IdentifierPager pager = identifierPager;
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

    /**
     * Führt die Seiten der Arten Nomen und Kennung alphabetisch zusammen, jede mit eigenem Cursor des Core.
     * Den Anfangsbuchstaben grenzt der Core ein ({@code TermSearch.startsWith}).
     */
    static final class IdentifierPager {

        interface Fetch {
            TermQueries.TermList fetch(TermKind kind, String cursor) throws Exception;
        }

        private final Fetch fetch;
        private final List<Stream> streams = List.of(new Stream(TermKind.NOUN), new Stream(TermKind.IDENTIFIER));

        IdentifierPager(Fetch fetch) {
            this.fetch = fetch;
        }

        synchronized List<TermQueries.TermSummary> next(int count) throws Exception {
            List<TermQueries.TermSummary> page = new ArrayList<>(count);
            while (page.size() < count) {
                Stream smallest = null;
                for (Stream stream : streams) {
                    stream.fill();
                    if (!stream.buffer.isEmpty() && (smallest == null || stream.buffer.peekFirst().normalized()
                            .compareTo(smallest.buffer.peekFirst().normalized()) < 0)) {
                        smallest = stream;
                    }
                }
                if (smallest == null) {
                    break;
                }
                page.add(smallest.buffer.pollFirst());
            }
            return page;
        }

        synchronized boolean hasMore() {
            return streams.stream().anyMatch(stream -> !stream.buffer.isEmpty() || !stream.done);
        }

        private final class Stream {
            private final TermKind kind;
            private final java.util.ArrayDeque<TermQueries.TermSummary> buffer = new java.util.ArrayDeque<>();
            private String cursor;
            private boolean done;

            Stream(TermKind kind) {
                this.kind = kind;
            }

            void fill() throws Exception {
                while (buffer.isEmpty() && !done) {
                    TermQueries.TermList list = fetch.fetch(kind, cursor);
                    for (TermQueries.TermSummary term : list.terms()) {
                        buffer.addLast(term);
                    }
                    cursor = list.nextCursor();
                    done = cursor == null;
                }
            }
        }
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

    RelationTab synonyms() {
        return synonymTab;
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

            JLabel heading = Comic.text(title);
            heading.setFont(Comic.semiBold(15f));
            JPanel lines = new JPanel(new BorderLayout(0, 2));
            lines.setOpaque(false);
            lines.add(heading, BorderLayout.NORTH);
            lines.add(Comic.muted("Alle berechtigten, aktuell indexierten Seiten"), BorderLayout.SOUTH);
            head.setOpaque(false);
            head.add(lines, BorderLayout.CENTER);
            head.add(com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(search), BorderLayout.EAST);

            add(head, BorderLayout.NORTH);
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
         * Buchstabenleiste A–Z in zwei Reihen mittig zwischen Überschrift und Suchfeld; ein zweiter Klick auf den
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
