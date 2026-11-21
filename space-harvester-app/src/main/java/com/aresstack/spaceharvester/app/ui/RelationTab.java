package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.terms.RelationConflictException;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.SynonymDiscovery;
import com.aresstack.spaceharvester.application.terms.SynonymScope;
import com.aresstack.spaceharvester.application.terms.TermIndex;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermRelations;
import com.aresstack.spaceharvester.application.terms.TermText;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;


/**
 * Die Liste der Beziehungen zwischen Begriffen (#120), eine eigenständige Komponente ohne Annahmen über ihren Einbau
 * (im Unterreiter „Analyse“ des Technik-Reiters, ADR 0029). Liste mit Buchstabenleiste, Suchfeld, Chips
 * zum Filtern nach Art (Abkürzung, Synonym, Schreibvariante, Verwandt; wie im Reiter Stand: kein Chip gedrückt =
 * alles, gedrückte = nur diese Arten; der Filter fragt den Core) und
 * sortierbaren Spalten, Bestätigen, Ablehnen und Entfernen mit Rückgängig. Die Aktionen „Neue Beziehung …“
 * ({@link #createButton()}) und „Synonyme vorschlagen“ ({@link #proposeButton()}, #336: Umfang und Budget im Dialog,
 * Lauf mit Wartekreis über der Liste und Abbrechen, Ergebnis in der Statuszeile) setzt der Einbau oben rechts in seine
 * Reiterzeile. Eine optionale Seitenmenge ({@link #pages(PageSet)}) begrenzt auf die Auswahl links; ohne Menge gilt
 * alles. Die Liste blättert wie die Bezeichner: Seiten von je
 * {@link #RELATION_LIMIT} über einen {@link Pager}, die nächste beim Scrollen ans Ende; vorgeschlagene und
 * bestätigte zuerst, danach die abgelehnten, beides in der Ordnung des Core (Schlüssel). Den Anfangsbuchstaben und die
 * Arten grenzt der Core ein ({@code RelationSearch.startsWith}, {@code RelationSearch.type}); die App leitet nichts
 * ab. Bei ungerichteten Arten steht in der ersten Spalte das Ende, nach dem der Core sortiert, damit die Liste
 * alphabetisch aussieht. Nur auf dem EDT.
 */
public final class RelationTab {

    static final String CONFIRM = "Bestätigen";
    static final String REJECT = "Ablehnen";
    static final String REMOVE = "Entfernen";
    static final String UNDO = "Rückgängig";
    static final String NEW_RELATION = "Neue Beziehung …";
    static final String NO_RELATIONS = "Noch keine Beziehungen. Vorschläge entstehen aus den Akronymen der Seiten "
            + "und aus „Synonyme vorschlagen“; „Neue Beziehung …“ legt eine an; bestätigte gelten für die Suche.";
    static final String CONFLICT = "Die Beziehung wurde inzwischen geändert; die Liste ist neu geladen.";
    /** Beziehungen je Seite ({@code TermRelations.relations}); die nächste Seite lädt beim Scrollen ans Ende. */
    static final int RELATION_LIMIT = TermRelations.MAX_LIMIT;

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

    private final AppContext context;
    private final TermDirectory directory;
    private final int columnCount;
    /** Gezeigte Arten in der Reihenfolge des Dialogs; leer heißt alle. */
    private final List<RelationType> types;
    private final RelationType presetType;
    private final Function<Relation, List<String>> cells;
    private final String emptyHint;

    private final ComposerButton confirm = Comic.button(CONFIRM, "Die gewählte Beziehung gilt");
    private final ComposerButton reject = Comic.button(REJECT, "Die gewählte Beziehung gilt nicht; sie bleibt als "
            + "abgelehnt sichtbar und kommt nicht wieder");
    private final ComposerButton remove = Comic.button(REMOVE, "Eine manuelle Beziehung ganz löschen, jede andere "
            + "ablehnen");
    private final ComposerButton create = Comic.button(NEW_RELATION, "Eine eigene Beziehung zwischen zwei "
            + "Begriffen anlegen; sie gilt sofort als bestätigt");
    /** „Synonyme vorschlagen“ (#336): nur auf Klick, Umfang und Budget im Dialog, Lauf mit Fortschritt und Abbrechen. */
    static final String PROPOSE_SYNONYMS = "Synonyme vorschlagen";
    static final String PROPOSING = "Synonyme werden vorgeschlagen …";
    /** Meldungen in der Statuszeile des Hauptfensters; kein Meldungsband unter der Liste. */
    static final String RULES_ONLY = "Ohne Embedding-Modell nur Regelvorschläge";
    static final String CANCELLED = "Synonymerkennung abgebrochen; bisherige Vorschläge bleiben";
    private static final int NOTICE_MS = 8000;
    private final ComposerButton proposeSynonyms = Comic.button(PROPOSE_SYNONYMS, "Schreibvarianten und "
            + "Formulierungen im Text als Regelvorschläge; mit Chat-Modell werden Kandidatenpaare anhand kurzer "
            + "Fundstellen aus freigegebenen Quellen geprüft. Nur Vorschläge, nichts wird bestätigt");
    private final BusyOverlay overlay;
    /** Anmeldung bei den Aufträgen, solange die Synonymerkennung läuft (ADR 0027, kein Zeitgeber). */
    private com.aresstack.spaceharvester.application.observe.Subscription synonymSubscription;
    private String synonymJob;
    private boolean synonymRulesOnly;
    /** Chips je Art; leer, wenn die Liste nur eine Art zeigt. */
    private final Map<RelationType, CountChip> typeChips = new EnumMap<>(RelationType.class);
    /** Begrenzung auf die Auswahl links oder {@code null} für alles. */
    private PageSet pages;

    private List<Relation> shownRelations = List.of();
    /** Beziehungen der Liste nach Kennung, für Auswahl und Darstellung. */
    private final Map<String, Relation> byId = new HashMap<>();
    /** Stand im Core je Kennung, damit Rückgängig eine entfernte Beziehung wiederherstellen kann. */
    private final Map<String, TermRelation> loaded = new HashMap<>();
    /** Eben entfernte Beziehungen: bleiben durchgestrichen stehen, bis Reiter oder Bereich wechseln. */
    private final Map<String, Relation> removed = new LinkedHashMap<>();
    private final Map<String, TermRelation> removedCore = new HashMap<>();
    /** Nach dem Neuladen wieder zu wählende Beziehung. */
    private String keepSelected;
    private BiConsumer<String, Relation> onReview = (action, relation) -> { };
    private Runnable onChanged = () -> { };
    private int request;
    private Pager<TermRelation> pager;
    private boolean loading;
    /** Abruf einer Seite Beziehungen; Tests setzen einen eigenen, sonst liest der Reiter aus der Laufzeit. */
    private RelationFetch fetch;

    /** Eine Seite Beziehungen zu Art, Status, Suchbegriff und Anfangsbuchstaben nach {@code cursor}. */
    public interface RelationFetch {
        TermRelations.RelationList fetch(RelationType type, ReviewStatus status, String term, String startsWith,
                                         String cursor) throws Exception;
    }

    /** Die Urteile im Core, wie {@link Workspace} sie anbietet; Tests setzen einen eigenen Stand. */
    interface RelationActions {
        TermRelation confirm(String id, long revision);

        TermRelation reject(String id, long revision);

        /** Leer, wenn der Core die (manuelle) Beziehung gelöscht hat. */
        java.util.Optional<TermRelation> remove(String id, long revision);

        TermRelation restore(String id);

        TermRelation confirm(String id);

        TermRelation add(TermRelations.NewRelation relation);
    }

    private RelationActions actions;

    /**
     * @param columns sichtbare Spalten; {@code cells} liefert je Beziehung genau so viele Werte
     * @param types   gezeigte Arten; leer heißt alle
     * @param preset  im Dialog „Neue Beziehung“ vorgewählte Art
     */
    RelationTab(AppContext context, String title, String[] columns, Function<Relation, List<String>> cells,
                List<RelationType> types, RelationType preset, String emptyHint) {
        this.context = context;
        this.directory = new TermDirectory(title, columns);
        this.columnCount = columns.length;
        this.cells = cells;
        this.types = List.copyOf(types);
        this.presetType = preset;
        this.emptyHint = emptyHint;
        directory.show(List.of(), TermDirectory.PENDING);
        JPanel review = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        review.setOpaque(false);
        for (ComposerButton button : List.of(confirm, reject, remove)) {
            button.setEnabled(false);
            review.add(button);
        }
        directory.actions(review);
        directory.sortable();
        RelationRenderer renderer = new RelationRenderer();
        for (int c = 0; c < directory.table().getColumnCount(); c++) {
            directory.table().getColumnModel().getColumn(c).setCellRenderer(renderer);
        }
        // „Neue Beziehung …“ steht nicht im Kopf (dort Buchstabenleiste und Suche), sondern als Aktion des Reiters
        // oben rechts in der Reiterzeile; wer den Reiter einbaut, setzt createButton() dorthin.
        create.addActionListener(e -> askNewRelation());
        create.setEnabled(context != null);
        directory.table().getSelectionModel().addListSelectionListener(e -> selectionChanged());
        confirm.addActionListener(e -> review(CONFIRM));
        reject.addActionListener(e -> review(REJECT));
        remove.addActionListener(e -> review(REMOVE));
        directory.letters(letter -> load(null));
        directory.onSearch(q -> load(null));
        directory.onScrolledToEnd(this::more);
        onReview = this::reviewInCore;
        List<RelationType> offered = dialogTypes();
        if (offered.size() > 1) {
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            chips.setOpaque(false);
            for (RelationType type : offered) {
                // Wie im Reiter Stand: kein Chip gedrückt = alles; gedrückt = nur diese Arten, mehrere möglich.
                CountChip chip = CountChip.choice(Relation.typeText(type.name()), typeTone(type));
                String word = Relation.typeText(type.name());
                chip.tips("Nur „" + word + "“ zeigen", "Filter „" + word + "“ aufheben");
                chip.addActionListener(e -> load(null));
                typeChips.put(type, chip);
                chips.add(chip);
            }
            directory.filters(chips);
        }
        overlay = new BusyOverlay(directory);
        overlay.setHighlight(Comic.BLUE);
        overlay.setVeilColor(Comic.SURFACE);
        overlay.onCancel(this::cancelSynonymRun);
        proposeSynonyms.setEnabled(context != null);
        proposeSynonyms.addActionListener(e -> askSynonymRun());
    }

    private static java.awt.Color typeTone(RelationType type) {
        return switch (type) {
            case ABBREVIATION -> Comic.PETROL;
            case SYNONYM -> Comic.BLUE;
            case VARIANT -> Comic.STANDING_CURRENT;
            case RELATED -> Comic.STANDING_OUTDATED;
        };
    }

    /** Die Arten, die der Core liefern soll: die gedrückten Chips; keiner gedrückt heißt ohne Eingrenzung. */
    List<RelationType> queriedTypes() {
        if (typeChips.values().stream().noneMatch(CountChip::isSelected)) {
            return types;
        }
        return typeChips.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey).toList();
    }

    CountChip typeChip(RelationType type) {
        return typeChips.get(type);
    }

    /**
     * Die Liste aller Arten mit den Spalten Begriff, Beziehung, Zu Begriff, Status, Herkunft, wie im Reiter Begriffe
     * und im Unterreiter „Analyse“ des Technik-Reiters (BG2b).
     */
    public static RelationTab all(AppContext context) {
        return new RelationTab(context, "Beziehungen zwischen Begriffen",
                new String[]{"Begriff", "Beziehung", "Zu Begriff", "Status", "Herkunft"},
                r -> List.of(r.fromTerm(), Relation.typeText(r.type()), r.toTerm(), Relation.statusText(r.status()),
                        Relation.originText(r.origin())),
                List.of(), RelationType.ABBREVIATION, NO_RELATIONS);
    }

    /**
     * Meldet jede Änderung, nach der sich die Zahl der Beziehungen geändert haben kann: neu geladene Liste (auch nach
     * Anlegen oder Synonymlauf) und jedes Urteil. Auf dem EDT.
     */
    public void onChanged(Runnable listener) {
        this.onChanged = Objects.requireNonNull(listener, "listener");
    }

    /** Begrenzt die Liste auf diese Seiten (Auswahl links, ADR 0029) oder mit {@code null} auf alles; lädt neu. */
    public void pages(PageSet pages) {
        this.pages = pages;
        load(null);
    }

    /**
     * Kopf für den Einbau: Titel und Unterzeile ({@code null} lässt die Zeile weg). Im Hauptreiter Begriffe bleiben
     * „Beziehungen zwischen Begriffen“ und „Alle berechtigten, aktuell indexierten Seiten“; mit einer Seitenmenge oder
     * einer eigenen Überschrift darüber setzt der Einbau beides passend oder entfernt den Kopf.
     */
    public void heading(String title, String subtitle) {
        directory.heading(title, subtitle);
    }

    /** Die Liste samt dem Wartekreis des Synonymlaufs; das ist das Element für den Einbau. */
    public JComponent component() {
        return overlay;
    }

    /** Tests: liest Beziehungen von {@code fetch} statt aus der Laufzeit. */
    public void fetch(RelationFetch fetch) {
        this.fetch = fetch;
    }

    /** Tests: urteilt über {@code actions} statt über die Laufzeit. */
    void actions(RelationActions actions) {
        this.actions = actions;
    }

    private RelationActions actions() {
        if (actions != null) {
            return actions;
        }
        if (context == null || context.runtime() == null) {
            return null;
        }
        Workspace workspace = context.runtime().workspace();
        return new RelationActions() {
            @Override
            public TermRelation confirm(String id, long revision) {
                return workspace.confirmRelation(id, revision);
            }

            @Override
            public TermRelation reject(String id, long revision) {
                return workspace.rejectRelation(id, revision);
            }

            @Override
            public java.util.Optional<TermRelation> remove(String id, long revision) {
                return workspace.removeRelation(id, revision);
            }

            @Override
            public TermRelation restore(String id) {
                return workspace.restoreRelation(id);
            }

            @Override
            public TermRelation confirm(String id) {
                return workspace.confirmRelation(id);
            }

            @Override
            public TermRelation add(TermRelations.NewRelation relation) {
                return workspace.addRelation(relation);
            }
        };
    }

    private RelationFetch source() {
        if (fetch != null) {
            return fetch;
        }
        if (context == null || context.runtime() == null) {
            return null;
        }
        Workspace workspace = context.runtime().workspace();
        return (type, status, term, startsWith, cursor) -> workspace.termRelations(term, type, status, startsWith,
                RELATION_LIMIT, cursor, pages);
    }

    /** Der Bereich wird sichtbar: entfernte Zeilen sind besiegelt, die Liste lädt neu. */
    public void shown() {
        removed.clear();
        removedCore.clear();
        load(null);
    }

    /** Ein Wechsel von Reiter oder Bereich besiegelt das Entfernen: die durchgestrichenen Zeilen verschwinden. */
    public void forgetRemoved() {
        if (!removed.isEmpty()) {
            removed.clear();
            removedCore.clear();
            show(shownRelations.stream().filter(r -> byId.containsKey(r.id()) && loaded.containsKey(r.id()))
                    .toList(), emptyHint);
            directory.more(pager != null && pager.hasMore());
        }
    }

    /**
     * Lädt die Liste von vorn: vorgeschlagene und bestätigte Beziehungen, danach die abgelehnten (sie bleiben sichtbar
     * und kommen nicht wieder), je nach Suchbegriff und Anfangsbuchstabe gefiltert wie im Dienst. {@code notice} steht
     * danach unter der Liste.
     */
    void load(String notice) {
        RelationFetch source = source();
        if (source == null) {
            if (context != null) {
                directory.show(List.of(), TermDirectory.NO_RUNTIME);
            }
            return;
        }
        int current = ++request;
        String q = directory.query();
        String term = q.isEmpty() ? null : q;
        String letter = directory.letter();
        List<RelationType> chosen = queriedTypes();
        List<RelationType> queried = chosen.isEmpty() ? java.util.Collections.singletonList(null) : chosen;
        List<Pager.Fetch<TermRelation>> open = new ArrayList<>();
        List<Pager.Fetch<TermRelation>> rejected = new ArrayList<>();
        for (RelationType type : queried) {
            open.add(cursor -> page(source.fetch(type, null, term, letter, cursor)));
            rejected.add(cursor -> page(source.fetch(type, ReviewStatus.REJECTED, term, letter, cursor)));
        }
        // Je Art sortiert der Core nach Schlüssel; der Pager führt die Arten in derselben Ordnung zusammen.
        pager = new Pager<>(ORDER, List.of(open, rejected));
        loading = false;
        more(current, true, q.isEmpty() && letter == null ? emptyHint : TermDirectory.NO_MATCH, notice);
    }

    /** Die Ordnung des Core: Schlüssel als UTF-8-Bytes. */
    static final Comparator<TermRelation> ORDER = Comparator.comparing(TermRelation::key, TermIndex.ORDER);

    private static Pager.Page<TermRelation> page(TermRelations.RelationList list) {
        return new Pager.Page<>(list.relations(), list.nextCursor());
    }

    /** Die nächste Seite, wenn noch eine da ist und gerade keine lädt. */
    private void more() {
        Pager<TermRelation> current = pager;
        if (current != null && !loading && current.hasMore()) {
            more(request, false, TermDirectory.NO_MATCH, null);
        }
    }

    private void more(int current, boolean first, String hint, String notice) {
        Pager<TermRelation> paging = pager;
        loading = true;
        Background.run(() -> paging.next(RELATION_LIMIT), found -> {
            if (current != request) {
                return;
            }
            loading = false;
            List<Relation> list = new ArrayList<>(found.size());
            for (TermRelation relation : found) {
                list.add(inKeyOrder(relation));
                if (first) {
                    loaded.remove(relation.id());
                }
            }
            if (first) {
                loaded.keySet().retainAll(removedCore.keySet());
                found.forEach(r -> loaded.put(r.id(), r));
                show(list, hint);
                onChanged.run();
            } else {
                found.forEach(r -> loaded.put(r.id(), r));
                append(list);
            }
            directory.more(paging.hasMore());
            if (notice != null) {
                directory.message(notice);
            }
        }, failure -> {
            if (current == request) {
                loading = false;
                if (first) {
                    show(List.of(), "Beziehungen nicht lesbar: " + failure.getMessage());
                } else {
                    directory.message("Beziehungen nicht lesbar: " + failure.getMessage());
                }
            }
        });
    }

    /**
     * Die Beziehung in Worten der Oberfläche, die Enden in der Reihenfolge des Schlüssels: bei gerichteten Arten
     * Kurzform, dann Langform; bei ungerichteten zuerst das Ende, nach dem der Core sortiert.
     */
    static Relation inKeyOrder(TermRelation relation) {
        Relation plain = Relation.of(relation);
        if (relation.type().directed() || TermText.normalize(relation.fromTerm()).equals(relation.sortTerm())) {
            return plain;
        }
        return new Relation(plain.id(), relation.toTerm(), plain.type(), relation.fromTerm(), plain.status(),
                plain.origin(), plain.revision());
    }

    /**
     * Bestätigen, Ablehnen oder Entfernen mit dem gezeigten Stand; der Core prüft Rechte und Revision. Danach wird nur
     * die Zeile an Ort und Stelle erneuert (Status, Revision): geladener Umfang, Rollposition und Auswahl bleiben,
     * eine abgelehnte Zeile bleibt bis zum nächsten vollen Laden an ihrem Platz. Bei einem Konflikt liest der Reiter
     * nur diese Beziehung aus dem Core nach.
     */
    private void reviewInCore(String action, Relation relation) {
        RelationActions core = actions();
        if (core == null) {
            return;
        }
        TermRelation before = removed.containsKey(relation.id()) ? removedCore.get(relation.id())
                : loaded.get(relation.id());
        Background.run(() -> switch (action) {
            case CONFIRM -> java.util.Optional.of(core.confirm(relation.id(), relation.revision()));
            case REJECT -> java.util.Optional.of(core.reject(relation.id(), relation.revision()));
            case UNDO -> java.util.Optional.of(undoRemove(core, relation, before));
            default -> core.remove(relation.id(), relation.revision());
        }, after -> {
            if (REMOVE.equals(action)) {
                removed.put(relation.id(), relation);
                if (before != null) {
                    removedCore.put(relation.id(), before);
                }
            } else if (UNDO.equals(action)) {
                removed.remove(relation.id());
                removedCore.remove(relation.id());
            }
            replace(relation.id(), after.orElse(null));
            onChanged.run();
        }, failure -> {
            if (failure instanceof RelationConflictException) {
                reread(relation);
            } else {
                directory.message(action + " nicht möglich: " + failure.getMessage());
            }
        });
    }

    /** Liest nach einem Konflikt nur diese Beziehung aus dem Core nach und erneuert ihre Zeile. */
    private void reread(Relation relation) {
        RelationFetch source = source();
        if (source == null) {
            return;
        }
        Background.run(() -> {
            for (ReviewStatus status : new ReviewStatus[] {null, ReviewStatus.REJECTED}) {
                for (TermRelation found : source.fetch(null, status, relation.fromTerm(), null, null).relations()) {
                    if (found.id().equals(relation.id())) {
                        return java.util.Optional.of(found);
                    }
                }
            }
            return java.util.Optional.<TermRelation>empty();
        }, found -> {
            if (found.isPresent()) {
                replace(relation.id(), found.get());
                directory.message(CONFLICT);
            } else {
                // Im Core verschwunden: die Liste kennt sie nicht mehr, also voll neu laden.
                load(CONFLICT);
            }
        }, failure -> load(CONFLICT));
    }

    /**
     * Erneuert die Zeile der Beziehung {@code id} mit dem Stand {@code after} des Core; {@code null} heißt, der Core
     * hat sie gelöscht (manuelle Beziehung entfernt): die Zeile bleibt durchgestrichen stehen, bis Rückgängig oder ein
     * volles Laden sie klärt.
     */
    void replace(String id, TermRelation after) {
        if (after == null) {
            loaded.remove(id);
        } else {
            loaded.put(id, after);
            Relation shown = inKeyOrder(after);
            if (byId.containsKey(id)) {
                byId.put(id, shown);
                List<Relation> list = new ArrayList<>(shownRelations);
                list.replaceAll(r -> r.id().equals(id) ? shown : r);
                shownRelations = List.copyOf(list);
                List<String> row = new ArrayList<>(cells.apply(shown));
                row.add(id);
                directory.replace(columnCount, id, List.copyOf(row));
            }
            if (removed.containsKey(id)) {
                removed.put(id, shown);
            }
        }
        directory.table().repaint();
        selectionChanged();
    }

    /**
     * Stellt den Stand vor dem Entfernen wieder her: Eine manuelle Beziehung hat der Core gelöscht, sie entsteht neu
     * (gleicher Schlüssel, gleiche Kennung); eine automatische hat er abgelehnt, die Ablehnung wird aufgehoben.
     *
     * @return der Stand im Core danach
     */
    private static TermRelation undoRemove(RelationActions core, Relation relation, TermRelation before) {
        if (before == null) {
            throw new IllegalStateException("der frühere Stand ist nicht bekannt");
        }
        if (before.origin() == RelationOrigin.MANUAL) {
            TermRelation again = core.add(new TermRelations.NewRelation(before.fromTerm(), before.toTerm(),
                    before.type(), before.scope(), before.note(), before.evidence()));
            return before.status() == ReviewStatus.REJECTED ? core.reject(again.id(), again.revision()) : again;
        }
        if (before.status() == ReviewStatus.REJECTED) {
            return before;
        }
        TermRelation restored = core.restore(relation.id());
        return before.status() == ReviewStatus.CONFIRMED ? core.confirm(relation.id()) : restored;
    }

    // ---- Synonyme vorschlagen (#336)

    /** Dialog „Synonyme vorschlagen“: Umfang, Budget, was der Lauf kann; „Starten“ reiht den Auftrag ein. */
    private void askSynonymRun() {
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == null) {
            directory.message(TermDirectory.NO_RUNTIME);
            return;
        }
        Workspace workspace = runtime.workspace();
        SynonymRunForm form;
        try {
            com.aresstack.spaceharvester.application.terms.SynonymBudget budget =
                    workspace.synonymAvailability(SynonymScope.ALL).budget();
            form = new SynonymRunForm(workspace.sources(), workspace::spaces, workspace::synonymAvailability,
                    budget.maxJudgedPairs(), budget.glossaryTermsOnly());
        } catch (RuntimeException e) {
            directory.message("Nicht möglich: " + e.getMessage());
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(directory, PROPOSE_SYNONYMS);
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

    /** Startet den Lauf und zeigt ihn über der Liste; jeden neuen Stand meldet der Core (ADR 0027). */
    void startSynonymRun(SynonymDiscovery.Request request) {
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == null || synonymJob != null) {
            return;
        }
        Workspace workspace = runtime.workspace();
        overlay.setText(PROPOSING);
        overlay.setBusy(true);
        proposeSynonyms.setEnabled(false);
        Background.run(() -> {
            synonymRulesOnly = !workspace.synonymAvailability(request.scope()).embeddingModel();
            return workspace.startSynonymDiscovery(request);
        }, status -> {
            synonymJob = status.id();
            // Erst anmelden, dann einmal lesen: was zwischen Start und Anmeldung geschah, bringt das Lesen.
            synonymSubscription = workspace.subscribeSynonymJob(status.id(),
                    changed -> SwingUtilities.invokeLater(() -> showSynonymRun(changed)));
            String jobId = synonymJob;
            Background.run(() -> workspace.synonymJob(jobId), this::showSynonymRun,
                    failure -> finishSynonymRun(null));
        }, failure -> {
            overlay.setBusy(false);
            proposeSynonyms.setEnabled(true);
            notice("Synonymerkennung nicht gestartet: " + failure.getMessage(), Severity.WARN);
        });
    }

    /** Ein Stand des laufenden Auftrags, auf dem EDT; ein Stand eines anderen Auftrags zählt nicht. */
    private void showSynonymRun(JobStatus status) {
        if (!status.id().equals(synonymJob)) {
            return;
        }
        overlay.setText(progressText(status));
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
        overlay.setBusy(false);
        proposeSynonyms.setEnabled(true);
        if (status != null) {
            String text = outcomeText(status, synonymRulesOnly);
            notice(text, status.state() == JobState.FAILED ? Severity.WARN : Severity.INFO);
        }
        load(null);
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

    public ComposerButton proposeButton() {
        return proposeSynonyms;
    }

    BusyOverlay overlay() {
        return overlay;
    }

    /** Die Arten im Dialog „Neue Beziehung“: die des Reiters, sonst alle. */
    List<RelationType> dialogTypes() {
        return types.isEmpty() ? List.of(RelationType.values()) : types;
    }

    /** Dialog „Neue Beziehung“: Begriff, Art (vorgewählt die des Reiters), Zu Begriff. */
    private void askNewRelation() {
        javax.swing.JDialog dialog = Dialogs.dialog(directory, "Neue Beziehung");
        javax.swing.JTextField from = Comic.field(20);
        javax.swing.JTextField to = Comic.field(20);
        javax.swing.JComboBox<String> type = Comic.combo();
        List<RelationType> offered = dialogTypes();
        for (RelationType t : offered) {
            type.addItem(Relation.typeText(t.name()));
        }
        type.setSelectedIndex(Math.max(0, offered.indexOf(presetType)));
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
            createRelation(from.getText(), offered.get(type.getSelectedIndex()), to.getText());
        });
        Dialogs.show(dialog, "Neue Beziehung", form, cancel, ok);
    }

    /** Legt die Beziehung im Core an (manuell, bestätigt, global) und wählt sie danach in der Liste. */
    void createRelation(String from, RelationType type, String to) {
        RelationActions core = actions();
        if (core == null) {
            return;
        }
        Background.run(() -> core.add(new TermRelations.NewRelation(from.trim(), to.trim(), type, null, null, null)),
                created -> {
            // Neu angelegt ist sie nicht mehr entfernt, auch wenn sie eben noch durchgestrichen dastand.
            removed.remove(created.id());
            removedCore.remove(created.id());
            keepSelected = created.id();
            load(null);
        }, failure -> directory.message("Anlegen nicht möglich: " + failure.getMessage()));
    }

    /** Grau hinterlegt: abgelehnte; zusätzlich durchgestrichen: eben entfernte. Alle Spalten linksbündig. */
    private final class RelationRenderer extends DefaultTableCellRenderer {

        RelationRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            Relation relation = relationAtView(row);
            boolean gone = relation != null && removed.containsKey(relation.id());
            boolean grey = gone || relation != null && "REJECTED".equals(relation.status());
            if (!selected) {
                setBackground(grey ? TermDirectory.REJECTED_BACKGROUND : table.getBackground());
                setForeground(gone ? Comic.MUTED : table.getForeground());
            }
            Font font = table.getFont();
            if (gone) {
                Map<TextAttribute, Object> attributes = new HashMap<>();
                attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
                font = font.deriveFont(attributes);
            }
            setFont(font);
            return this;
        }
    }

    /** Zeigt Beziehungen; {@code hint} steht da, solange es keine gibt. */
    void show(List<Relation> list, String hint) {
        Relation current = selectedRelation();
        String select = keepSelected != null ? keepSelected : current == null ? null : current.id();
        keepSelected = null;
        List<Relation> merged = new ArrayList<>(list);
        Set<String> ids = new HashSet<>();
        list.forEach(r -> ids.add(r.id()));
        // Entfernte bleiben stehen, bis Reiter oder Bereich wechseln, damit Rückgängig möglich bleibt.
        removed.values().stream().filter(r -> !ids.contains(r.id())).forEach(merged::add);
        shownRelations = List.copyOf(merged);
        byId.clear();
        List<List<String>> rows = new ArrayList<>(merged.size());
        for (Relation relation : merged) {
            byId.put(relation.id(), relation);
            // Die Kennung steht unsichtbar hinter den Spalten.
            List<String> row = new ArrayList<>(cells.apply(relation));
            row.add(relation.id());
            rows.add(List.copyOf(row));
        }
        directory.show(rows, hint);
        if (select != null) {
            JTable table = directory.table();
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

    /** Hängt eine weitere Seite an, ohne Auswahl und entfernte Zeilen zu verlieren. */
    void append(List<Relation> list) {
        List<Relation> merged = new ArrayList<>(shownRelations);
        List<List<String>> rows = new ArrayList<>(list.size());
        for (Relation relation : list) {
            if (byId.containsKey(relation.id())) {
                continue;
            }
            merged.add(relation);
            byId.put(relation.id(), relation);
            List<String> row = new ArrayList<>(cells.apply(relation));
            row.add(relation.id());
            rows.add(List.copyOf(row));
        }
        shownRelations = List.copyOf(merged);
        directory.append(rows);
        selectionChanged();
    }

    private Relation relationAtView(int row) {
        if (row < 0 || row >= directory.table().getRowCount()) {
            return null;
        }
        List<String> row0 = directory.visibleRow(directory.table().convertRowIndexToModel(row));
        return row0.size() > columnCount ? byId.get(row0.get(columnCount)) : null;
    }

    /** {@code listener} bekommt Aktion ({@code CONFIRM}, {@code REJECT}, {@code REMOVE}) und Beziehung. */
    void onReview(BiConsumer<String, Relation> listener) {
        onReview = Objects.requireNonNull(listener, "listener");
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
        return relationAtView(directory.table().getSelectedRow());
    }

    private void review(String action) {
        Relation selected = selectedRelation();
        if (selected != null) {
            onReview.accept(REMOVE.equals(action) && removed.containsKey(selected.id()) ? UNDO : action, selected);
        }
    }

    /** Ob die Beziehung eben entfernt wurde und noch durchgestrichen dasteht. */
    boolean isRemoved(String id) {
        return removed.containsKey(id);
    }

    TermDirectory directory() {
        return directory;
    }

    public ComposerButton createButton() {
        return create;
    }

    List<ComposerButton> reviewButtons() {
        return List.of(confirm, reject, remove);
    }
}
