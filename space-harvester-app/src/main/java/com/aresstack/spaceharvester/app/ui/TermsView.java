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
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.RelationConflictException;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationType;

import java.awt.BorderLayout;
import java.util.ArrayList;
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
    static final String UNDO = "Rückgängig";
    static final String NEW_RELATION = "Neue Beziehung …";
    private final ComposerButton create = Comic.button(NEW_RELATION, "Eine eigene Beziehung zwischen zwei Begriffen "
            + "anlegen; sie gilt sofort als bestätigt");
    private List<Relation> shownRelations = List.of();
    /** Beziehungen der Liste nach Kennung, für Auswahl und Darstellung. */
    private final java.util.Map<String, Relation> byId = new java.util.HashMap<>();
    /** Stand im Core je Kennung, damit Rückgängig eine entfernte Beziehung wiederherstellen kann. */
    private final java.util.Map<String, TermRelation> loaded = new java.util.HashMap<>();
    /** Eben entfernte Beziehungen: bleiben durchgestrichen stehen, bis Reiter oder Bereich wechseln. */
    private final java.util.Map<String, Relation> removed = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, TermRelation> removedCore = new java.util.HashMap<>();
    /** Nach dem Neuladen wieder zu wählende Beziehung. */
    private String keepSelected;
    private java.util.function.BiConsumer<String, Relation> onReview = (action, relation) -> { };

    private final AppContext context;
    private int acronymRequest;
    private int identifierRequest;
    private IdentifierPager identifierPager;
    private boolean identifierLoading;
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
        relations.sortable();
        RelationRenderer renderer = new RelationRenderer();
        for (int c = 0; c < relations.table().getColumnCount(); c++) {
            relations.table().getColumnModel().getColumn(c).setCellRenderer(renderer);
        }
        relations.middle(create);
        create.addActionListener(e -> askNewRelation());
        create.setEnabled(context != null);
        tabs.onSelect(name -> forgetRemoved());
        relations.table().getSelectionModel().addListSelectionListener(e -> selectionChanged());
        confirm.addActionListener(e -> review(CONFIRM));
        reject.addActionListener(e -> review(REJECT));
        remove.addActionListener(e -> review(REMOVE));
        if (context != null) {
            acronyms.onSearch(q -> loadAcronyms());
            identifiers.onSearch(q -> loadIdentifiers());
            identifiers.letters(letter -> loadIdentifiers());
            identifiers.onScrolledToEnd(this::moreIdentifiers);
            relations.onSearch(q -> loadRelations(null));
            onReview = this::reviewInCore;
        }
    }

    @Override
    public void shown() {
        if (context != null) {
            loadAcronyms();
            loadIdentifiers();
            removed.clear();
            removedCore.clear();
            loadRelations(null);
        }
    }

    /** Ein Wechsel von Reiter oder Bereich besiegelt das Entfernen: die durchgestrichenen Zeilen verschwinden. */
    private void forgetRemoved() {
        if (!removed.isEmpty()) {
            removed.clear();
            removedCore.clear();
            showRelations(shownRelations.stream().filter(r -> byId.containsKey(r.id()) && loaded.containsKey(r.id()))
                    .toList(), NO_RELATIONS);
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
            java.util.Map<String, TermRelation> core = new java.util.HashMap<>();
            for (TermRelation r : open.relations()) {
                all.add(Relation.of(r));
                core.put(r.id(), r);
            }
            for (TermRelation r : rejected.relations()) {
                all.add(Relation.of(r));
                core.put(r.id(), r);
            }
            return new LoadedRelations(all, core, open.nextCursor() != null || rejected.nextCursor() != null);
        }, found -> {
            if (request == relationRequest) {
                loaded.clear();
                loaded.putAll(found.core());
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

    private record LoadedRelations(List<Relation> list, java.util.Map<String, TermRelation> core, boolean more) {
    }

    /** Bestätigen, Ablehnen oder Entfernen mit dem gezeigten Stand; der Core prüft Rechte und Revision. */
    private void reviewInCore(String action, Relation relation) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        TermRelation before = removed.containsKey(relation.id()) ? removedCore.get(relation.id())
                : loaded.get(relation.id());
        keepSelected = relation.id();
        Background.run(() -> {
            switch (action) {
                case CONFIRM -> runtime.workspace().confirmRelation(relation.id(), relation.revision());
                case REJECT -> runtime.workspace().rejectRelation(relation.id(), relation.revision());
                case UNDO -> undoRemove(runtime.workspace(), relation, before);
                default -> runtime.workspace().removeRelation(relation.id(), relation.revision());
            }
            return relation;
        }, done -> {
            if (REMOVE.equals(action)) {
                removed.put(relation.id(), relation);
                if (before != null) {
                    removedCore.put(relation.id(), before);
                }
            } else if (UNDO.equals(action)) {
                removed.remove(relation.id());
                removedCore.remove(relation.id());
            }
            loadRelations(null);
        }, failure -> loadRelations(failure instanceof RelationConflictException
                ? CONFLICT : action + " nicht möglich: " + failure.getMessage()));
    }

    /**
     * Stellt den Stand vor dem Entfernen wieder her: Eine manuelle Beziehung hat der Core gelöscht, sie entsteht neu
     * (gleicher Schlüssel, gleiche Kennung); eine automatische hat er abgelehnt, die Ablehnung wird aufgehoben.
     */
    private static void undoRemove(com.aresstack.spaceharvester.app.runtime.Workspace workspace, Relation relation,
                                   TermRelation before) {
        if (before == null) {
            throw new IllegalStateException("der frühere Stand ist nicht bekannt");
        }
        if (before.origin() == RelationOrigin.MANUAL) {
            TermRelation again = workspace.addRelation(new TermRelations.NewRelation(before.fromTerm(),
                    before.toTerm(), before.type(), before.scope(), before.note(), before.evidence()));
            if (before.status() == ReviewStatus.REJECTED) {
                workspace.rejectRelation(again.id(), again.revision());
            }
        } else if (before.status() != ReviewStatus.REJECTED) {
            workspace.restoreRelation(relation.id());
            if (before.status() == ReviewStatus.CONFIRMED) {
                workspace.confirmRelation(relation.id());
            }
        }
    }

    /** Dialog „Neue Beziehung“: Begriff, Art, Zu Begriff. */
    private void askNewRelation() {
        javax.swing.JDialog dialog = Dialogs.dialog(root, "Neue Beziehung");
        javax.swing.JTextField from = Comic.field(20);
        javax.swing.JTextField to = Comic.field(20);
        javax.swing.JComboBox<String> type = Comic.combo();
        for (RelationType t : RelationType.values()) {
            type.addItem(Relation.typeText(t.name()));
        }
        JLabel problem = Comic.muted(" ");
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.caption("Begriff"));
        form.add(from);
        form.add(Comic.caption("Beziehung"));
        form.add(type);
        form.add(Comic.caption("Zu Begriff"));
        form.add(to);
        form.add(problem);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton ok = Comic.primary("Anlegen", "Die Beziehung anlegen und bestätigen");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            String invalid = invalidNewRelation(from.getText(), to.getText());
            if (invalid != null) {
                problem.setText(invalid);
                return;
            }
            dialog.dispose();
            createRelation(from.getText(), RelationType.values()[type.getSelectedIndex()], to.getText());
        });
        Dialogs.show(dialog, "Neue Beziehung", form, cancel, ok);
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

    /** Legt die Beziehung im Core an (manuell, bestätigt, global) und wählt sie danach in der Liste. */
    void createRelation(String from, RelationType type, String to) {
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == null) {
            return;
        }
        Background.run(() -> runtime.workspace().addRelation(new TermRelations.NewRelation(from.trim(), to.trim(),
                type, null, null, null)), created -> {
            keepSelected = created.id();
            loadRelations(null);
        }, failure -> relations.message("Anlegen nicht möglich: " + failure.getMessage()));
    }

    /** Grau hinterlegt: abgelehnte; zusätzlich durchgestrichen: eben entfernte. Alle Spalten linksbündig. */
    private final class RelationRenderer extends DefaultTableCellRenderer {

        RelationRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            Relation relation = relationAtView(row);
            boolean gone = relation != null && removed.containsKey(relation.id());
            boolean grey = gone || relation != null && "REJECTED".equals(relation.status());
            if (!selected) {
                setBackground(grey ? REJECTED_BACKGROUND : table.getBackground());
                setForeground(gone ? Comic.MUTED : table.getForeground());
            }
            java.awt.Font font = table.getFont();
            if (gone) {
                java.util.Map<java.awt.font.TextAttribute, Object> attributes = new java.util.HashMap<>();
                attributes.put(java.awt.font.TextAttribute.STRIKETHROUGH, java.awt.font.TextAttribute.STRIKETHROUGH_ON);
                font = font.deriveFont(attributes);
            }
            setFont(font);
            return this;
        }
    }

    static final java.awt.Color REJECTED_BACKGROUND = new java.awt.Color(0xECEDEF);

    /** Ob die Beziehung eben entfernt wurde und noch durchgestrichen dasteht. */
    boolean isRemoved(String id) {
        return removed.containsKey(id);
    }

    ComposerButton createButton() {
        return create;
    }

    /** Zeigt Beziehungen; {@code hint} steht da, solange es keine gibt. */
    void showRelations(List<Relation> list, String hint) {
        Relation current = selectedRelation();
        String select = keepSelected != null ? keepSelected : current == null ? null : current.id();
        keepSelected = null;
        List<Relation> merged = new ArrayList<>(list);
        java.util.Set<String> ids = new java.util.HashSet<>();
        list.forEach(r -> ids.add(r.id()));
        // Entfernte bleiben stehen, bis Reiter oder Bereich wechseln, damit Rückgängig möglich bleibt.
        removed.values().stream().filter(r -> !ids.contains(r.id())).forEach(merged::add);
        shownRelations = List.copyOf(merged);
        byId.clear();
        List<List<String>> rows = new ArrayList<>(merged.size());
        for (Relation relation : merged) {
            byId.put(relation.id(), relation);
            // Die Kennung steht unsichtbar hinter den Spalten.
            rows.add(List.of(relation.fromTerm(), Relation.typeText(relation.type()), relation.toTerm(),
                    Relation.statusText(relation.status()), Relation.originText(relation.origin()), relation.id()));
        }
        relations.show(rows, hint);
        if (select != null) {
            JTable table = relations.table();
            for (int row = 0; row < table.getRowCount(); row++) {
                Relation relation = relationAtView(row);
                if (relation != null && relation.id().equals(select)) {
                    table.setRowSelectionInterval(row, row);
                    table.scrollRectToVisible(table.getCellRect(row, 0, true));
                    break;
                }
            }
        }
        selectionChanged();
    }

    private Relation relationAtView(int row) {
        if (row < 0 || row >= relations.table().getRowCount()) {
            return null;
        }
        List<String> cells = relations.visibleRow(relations.table().convertRowIndexToModel(row));
        return cells.size() > 5 ? byId.get(cells.get(5)) : null;
    }

    /** {@code listener} bekommt Aktion ({@link #CONFIRM}, {@link #REJECT}, {@link #REMOVE}) und Beziehung. */
    void onReview(java.util.function.BiConsumer<String, Relation> listener) {
        onReview = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Die Schaltflächen passen zum Stand der gewählten Beziehung; Rechte prüft der Core. */
    private void selectionChanged() {
        Relation selected = selectedRelation();
        boolean gone = selected != null && removed.containsKey(selected.id());
        confirm.setEnabled(selected != null && !gone && !"CONFIRMED".equals(selected.status()));
        reject.setEnabled(selected != null && !gone && !"REJECTED".equals(selected.status()));
        remove.setEnabled(selected != null);
        remove.setText(gone ? UNDO : REMOVE);
        remove.setToolTipText(gone ? "Das Entfernen zurücknehmen" : "Eine manuelle Beziehung ganz löschen, jede "
                + "andere ablehnen");
    }

    private Relation selectedRelation() {
        return relationAtView(relations.table().getSelectedRow());
    }

    private void review(String action) {
        Relation selected = selectedRelation();
        if (selected != null) {
            onReview.accept(REMOVE.equals(action) && removed.containsKey(selected.id()) ? UNDO : action, selected);
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
        String coreQuery = !q.isEmpty() ? q : letter;
        identifierPager = new IdentifierPager((kind, cursor) -> context.runtime().workspace().terms(
                new TermQueries.TermSearch(null, null, kind, coreQuery, false, LIMIT, cursor)), letter);
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
                identifiers.show(rows(terms), emptyHint);
            } else {
                identifiers.append(rows(terms));
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
     * Führt die Seiten der Arten Nomen und Kennung alphabetisch zusammen, jede mit eigenem Cursor des Core. Mit
     * Anfangsbuchstaben bleiben nur Begriffe, deren Suchwert (ohne Akzente) so beginnt.
     */
    static final class IdentifierPager {

        interface Fetch {
            TermQueries.TermList fetch(TermKind kind, String cursor) throws Exception;
        }

        private final Fetch fetch;
        private final String letter;
        private final List<Stream> streams = List.of(new Stream(TermKind.NOUN), new Stream(TermKind.IDENTIFIER));

        IdentifierPager(Fetch fetch, String letter) {
            this.fetch = fetch;
            this.letter = letter == null ? null : letter.toLowerCase(Locale.ROOT);
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

        private boolean matches(TermQueries.TermSummary term) {
            if (letter == null) {
                return true;
            }
            String folded = java.text.Normalizer.normalize(term.normalized(), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
            return folded.startsWith(letter);
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
                        if (matches(term)) {
                            buffer.addLast(term);
                        }
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
            head.add(search, BorderLayout.EAST);

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
            head.remove(search);
            east.add(center, BorderLayout.CENTER);
            east.add(search, BorderLayout.EAST);
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
