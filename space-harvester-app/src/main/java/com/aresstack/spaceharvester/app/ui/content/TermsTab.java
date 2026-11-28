package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ResearchPillButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.FindablePill;
import com.aresstack.spaceharvester.app.ui.Pager;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.Relation;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.CountTile;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.knowledge.VerbQueries;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import com.aresstack.spaceharvester.application.terms.TermQueries;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Der Reiter „Begriffe“ der Leseansicht (#339): nur für die angezeigte Seite die Zahl der erkannten Akronyme,
 * Bezeichner und Beziehungen, der Stand der Analyse vom letzten Seitenabgleich und die erkannten Begriffe als Pillen,
 * erst die Akronyme, dann die Bezeichner. Ein Klick auf einen Begriff springt zu seiner nächsten Fundstelle im Lesetext
 * ({@code onJump}). Die Daten stammen aus dem Core: Akronyme aus {@link PageAcronyms}, Bezeichner (Nomen,
 * Nominalgruppen, Kennungen) aus {@link PageTerms} (#119); Beziehungen folgen mit #120. Nur auf dem EDT.
 */
final class TermsTab extends JPanel {

    static final String NOT_YET = "–";
    /** Kennzahl einer Auswahl, solange der Core die Gesamtzahl noch nicht geliefert hat. */
    static final String COUNTING = "…";
    /** So viele Begriffe bzw. Beziehungen lädt ein Schritt beim Scrollen; einstellbar ({@code ListSteps}). */
    static int step() {
        return com.aresstack.spaceharvester.app.ui.ListSteps.shared()
                .get(com.aresstack.spaceharvester.app.ui.ListSteps.Value.TERMS);
    }
    static final String NO_TERMS = "In der Auswahl wurden keine Begriffe erkannt.";
    static final String NO_MATCH = "Kein Begriff passt zum Filter.";

    /**
     * Die Begriffe einer Auswahl links (Wissensquellen, Quelle, Space, Ordner; Angelo 17.11.2026): dieselbe Darstellung
     * wie auf der Seite, nur über die Seiten der Auswahl zusammengezählt. Jede Abfrage geht an den Core, nie auf dem
     * EDT; die Reihenfolge ist die des Core (alphabetisch).
     */
    interface Selection {

        /** Der Name des gewählten Knotens für die Überschrift. */
        String title();

        /** Akronyme zur Suche {@code q} (leer: alle). */
        Pager<TermQueries.TermSummary> acronyms(String q);

        /** Bezeichner (Nomen und Kennungen) zur Suche {@code q}. */
        Pager<TermQueries.TermSummary> identifiers(String q);

        /** Beziehungen, deren Sortierbegriff mit {@code q} beginnt (leer: alle). */
        Pager<Relation> relations(String q);

        /** Die Seiten mit Fundstellen eines Begriffs. */
        TermQueries.OccurrenceList occurrences(TermQueries.TermSummary term) throws Exception;

        /** Verben aus den Aussagen (K1) zur Suche {@code q}; {@code null}: diese Auswahl kennt keine Verben. */
        default Pager<VerbQueries.Verb> verbs(String q) {
            return null;
        }

        /** Die Aussagen eines Verbs auf den Seiten der Auswahl (K1). */
        default List<VerbQueries.Occurrence> verbOccurrences(VerbQueries.Verb verb) throws Exception {
            return List.of();
        }

        /**
         * Die echten Gesamtzahlen der Auswahl aus dem Core (BG1b); {@code null}: noch nicht lieferbar, die Kennzahlen
         * bleiben auf „…“. Kann bei großen Auswahlen etwa eine Sekunde dauern, läuft daher nur im Hintergrund.
         */
        default Totals totals() throws Exception {
            return null;
        }

        /**
         * Wie {@link #totals()}; {@code progress} erfährt aus dem Hintergrund Zwischenstände, damit die Kacheln
         * mitzählen (Angelo 24.11.2026).
         */
        default Totals totals(Consumer<Partial> progress) throws Exception {
            return totals();
        }

        /** Lässt den Core die verwalteten Zahlen dieser Auswahl samt Unterknoten verwerfen; ohne Core nichts. */
        default void rebuild() {
        }

        /** Schlüssel, unter dem die Gesamtzahlen gemerkt werden; {@code null}: nicht merken. */
        default Object key() {
            return null;
        }
    }

    /** Ein Zwischenstand der Zählung; {@code null}: noch nicht begonnen. */
    record Partial(Integer acronyms, Integer identifiers, Integer verbs, Integer relations) {
    }

    /**
     * Die Gesamtzahlen einer Auswahl: Akronyme, Bezeichner (Nomen und Kennungen), Beziehungen; dazu aus dem Bestand,
     * wie viele ihrer Seiten lokal gespeichert sind ({@code stored}) von allen ({@code pages}), {@code -1} unbekannt.
     */
    record Totals(int acronyms, int identifiers, int relations, int stored, int pages, int verbs, int unanalysed,
                  int stale) {

        Totals(int acronyms, int identifiers, int relations) {
            this(acronyms, identifiers, relations, -1, -1);
        }

        Totals(int acronyms, int identifiers, int relations, int stored, int pages) {
            this(acronyms, identifiers, relations, stored, pages, -1, 0, 0);
        }

        Totals(int acronyms, int identifiers, int relations, int stored, int pages, int verbs, int unanalysed) {
            this(acronyms, identifiers, relations, stored, pages, verbs, unanalysed, 0);
        }
    }

    /**
     * Für die Statuszeile: aus wie vielen Seiten die Begriffe stammen (AT 17.11.2026). Nur lokal gespeicherte Seiten
     * haben Text und damit Begriffe; {@code null}, wenn der Bestand es nicht sagt.
     */
    static String coverage(Totals totals) {
        if (totals == null || totals.pages() < 0 || totals.stored() < 0) {
            return null;
        }
        java.text.NumberFormat number = java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY);
        String pages = totals.pages() == 1 ? " Seite" : " Seiten";
        String line = totals.stored() >= totals.pages() ? "Begriffe aus " + number.format(totals.pages()) + pages
                : "Begriffe aus " + number.format(totals.stored()) + " von " + number.format(totals.pages()) + pages
                + " · übrige nicht lokal gespeichert";
        if (totals.unanalysed() > 0) {
            line += " · " + number.format(totals.unanalysed())
                    + (totals.unanalysed() == 1 ? " Seite ohne Aussagenanalyse" : " Seiten ohne Aussagenanalyse")
                    + ", ihre Verben fehlen";
        }
        if (totals.stale() > 0) {
            line += " · " + number.format(totals.stale()) + (totals.stale() == 1
                    ? " Seite mit veralteter Aussagenanalyse" : " Seiten mit veralteter Aussagenanalyse")
                    + ", ihre Verben zählen nicht";
        }
        return line;
    }

    /** Ein Verb einer einzelnen Seite mit den Stellen seiner Aussagen im Lesetext (K1). */
    record PageVerb(VerbQueries.Verb verb, List<Span> spans) {
    }

    /** Führt Arbeit im Hintergrund aus und meldet das Ergebnis auf dem EDT; in Tests sofort. */
    interface Runner {
        <T> void run(Callable<T> work, java.util.function.Consumer<T> onSuccess,
                     java.util.function.Consumer<Throwable> onFailure);
    }

    private final TabHeader head = new TabHeader("Begriffe dieser Seite");
    private final CountTile acronymCount = new CountTile(NOT_YET, "Akronyme");
    private final CountTile identifierCount = new CountTile(NOT_YET, "Bezeichner");
    private final CountTile relationCount = new CountTile(NOT_YET, "Beziehungen");
    private final CountTile verbCount = new CountTile(NOT_YET, "Verben");
    private final JLabel completeness = badge();
    private final JLabel analysis = Comic.text("Analyse vom letzten Seitenabgleich");
    private final PlaceholderField filter = new PlaceholderField(12, "Begriffe filtern …");
    /**
     * Abschnitte (Angelo 24.11.2026): die vier Kennzahlen oben sind zugleich die Umschalter; genau eine ist gewählt und
     * bestimmt, welcher Abschnitt darunter zu sehen ist. Keine zweite Reiterzeile in der unteren Leiste.
     */
    static final String ACRONYMS = "Akronyme";
    static final String IDENTIFIERS = "Bezeichner";
    static final String VERBS = "Verben";
    static final String RELATIONS = "Beziehungen";
    static final List<String> KINDS = List.of(ACRONYMS, IDENTIFIERS, VERBS, RELATIONS);
    /** Der gewählte Abschnitt; {@code null}: alle Abschnitte untereinander (etwa beim Stand der Auswahl). */
    private String kind;
    private final javax.swing.ButtonGroup kindGroup = new javax.swing.ButtonGroup();
    private final JPanel acronymsBox = new JPanel(new BorderLayout(0, 4));
    private final JPanel identifiersBox = new JPanel(new BorderLayout(0, 4));
    private final JPanel verbsBox = new JPanel(new BorderLayout(0, 4));
    private final JPanel relationsBox = new JPanel(new BorderLayout(0, 4));
    private final JLabel acronymTitle = heading("Akronyme");
    private final JPanel chips = new JPanel(new WrapFlow());
    private final JLabel identifierTitle = heading("Bezeichner");
    private final JPanel identifierChips = new JPanel(new WrapFlow());
    private final JLabel verbTitle = heading("Verben");
    private final JPanel verbChips = new JPanel(new WrapFlow());
    /** Die Verben der gezeigten Seite; {@code null}: unbekannt (keine aktuelle Aussagenanalyse). */
    private List<PageVerb> shownVerbs;
    private final JLabel relationTitle = heading("Beziehungen");
    /** Die Beziehungen der gezeigten Seite; {@code null}: nicht abgefragt. Der Filter wirkt auch auf sie. */
    private List<Relation> shownRelations;
    private final JPanel relationLines = new JPanel();
    private final JLabel hint = Comic.muted(" ");
    private Consumer<List<Span>> onJump = spans -> { };
    /** Klick auf den Stand, wenn die Analyse nicht aktuell ist; {@code null}: der Stand ist nur Anzeige. */
    private Runnable onRecompute;
    /** Tooltip des Stands, wenn er zugleich der Knopf zum Neuberechnen ist (Begriffe und Absätze gleich). */
    static final String RECOMPUTE_TIP = "Klick berechnet die Analysen dieser Seite neu";
    private Consumer<TermQueries.Occurrence> onOpen = occurrence -> { };
    private PageAcronyms shown;
    private PageTerms shownTerms;
    private final JPanel analysisRow = new JPanel(new BorderLayout());
    private final javax.swing.JScrollPane scroll;
    private java.util.function.Consumer<String> onStatus = text -> { };
    private boolean counting;
    private boolean countAgain;
    private final InfoPopup popup = new InfoPopup();
    private Runner runner = Background::run;
    /** Höchstens so viele Auswahlen behalten ihre Gesamtzahlen. */
    private static final int REMEMBERED = 200;
    /**
     * Die zuletzt gezählten Gesamtzahlen je Auswahl (Angelo 24.11.2026): Ein erneuter Klick zeigt sie sofort; gezählt
     * wird dann still im Hintergrund nach, damit sie nach Änderungen am Bestand stimmen.
     */
    private final java.util.Map<Object, Totals> remembered = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Object, Totals> eldest) {
            return size() > REMEMBERED;
        }
    };
    /** Nummer der laufenden Zählung; Zwischenstände einer beendeten oder überholten Zählung gelten nicht. */
    private int countRun;
    /** Die gezeigte Auswahl; {@code null} zeigt eine einzelne Seite. */
    private Selection selection;
    private final List<Section> sections = new ArrayList<>();
    private int request;
    private boolean loading;
    private int loadedTerms;

    /** Eine Fundstelle im Lesetext (Zeichenpositionen wie im Core-Ergebnis). */
    record Span(int start, int end) {
    }

    /** Überschrift des Abschnitts mit den Begriffen der Unterseiten (Fahrplan P2). */
    static final String BELOW = "Aus Unterseiten";

    /**
     * Die Unterseiten einer Seite für den Abschnitt „Aus Unterseiten“ (Fahrplan P2): ihre Begriffe als Auswahl und je
     * Begriff die Zahl der Unterseiten, auf denen er vorkommt. Nie auf dem EDT abfragen.
     */
    interface Below {

        /** Die Begriffe der Unterseiten, wie bei Ordner und Space. */
        Selection terms();

        /** Je Begriff die Zahl der Unterseiten mit einer Fundstelle. */
        java.util.Map<TermQueries.TermRef, Integer> pageCounts() throws Exception;

        /** Zahl der Unterseiten. */
        int pages();

        /**
         * Je Verb (K1) die Zahl der Unterseiten mit einer seiner Aussagen, vollständig; Verben ohne ermittelte
         * Grundform stehen mit {@link VerbRef#fromText()} getrennt.
         */
        default java.util.Map<VerbRef, Integer> verbPageCounts() throws Exception {
            return java.util.Map.of();
        }
    }

    /** Ein Verb als Schlüssel: Grundform bzw. Textform, wenn der Analysator keine Grundform lieferte. */
    record VerbRef(String lemma, boolean fromText) {

        static VerbRef of(VerbQueries.Verb verb) {
            return new VerbRef(verb.lemma(), verb.fromText());
        }
    }

    private final JPanel belowPanel = new JPanel(new BorderLayout(0, 6));
    private final com.aresstack.spaceharvester.app.ui.controls.SectionHeader belowHeader =
            new com.aresstack.spaceharvester.app.ui.controls.SectionHeader(BELOW, true, this::belowToggled);
    private final com.aresstack.spaceharvester.app.ui.controls.CountChip belowCount =
            new com.aresstack.spaceharvester.app.ui.controls.CountChip("Begriffe", Comic.MUTED);
    private final JPanel belowChips = new JPanel(new WrapFlow());
    private final JPanel belowRelations = new JPanel();
    private final JPanel belowBody = new JPanel(new BorderLayout(0, 6));
    private final com.aresstack.spaceharvester.app.ui.controls.BusyOverlay belowBusy;
    private Below below;
    private java.util.Map<TermQueries.TermRef, Integer> belowCounts = java.util.Map.of();
    private java.util.Map<VerbRef, Integer> belowVerbCounts = java.util.Map.of();
    private final List<Section<?>> belowSections = new ArrayList<>();
    private int belowRequest;
    private boolean belowLoading;
    private java.util.Set<String> ownRelationLines = java.util.Set.of();

    TermsTab() {
        super(new BorderLayout(0, 10));
        belowBusy = new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(belowBody);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));

        head.search(com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(filter)).copy(this::copyText);

        JPanel counts = new JPanel(new GridLayout(1, 4, 12, 0));
        counts.setOpaque(false);
        for (CountTile tile : tiles()) {
            counts.add(cell(tile));
            kindGroup.add(tile);
            tile.addActionListener(event -> kind(tile.caption()));
        }

        analysisRow.setOpaque(false);
        analysisRow.add(analysis, BorderLayout.WEST);
        analysisRow.add(completeness, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JPanel row : List.of(head, counts, analysisRow)) {
            row.setAlignmentX(LEFT_ALIGNMENT);
            top.add(row);
            top.add(javax.swing.Box.createVerticalStrut(10));
        }

        chips.setOpaque(false);
        identifierChips.setOpaque(false);
        acronymTitle.setFont(Comic.semiBold(13f));
        JPanel acronyms = acronymsBox;
        acronyms.setOpaque(false);
        acronyms.add(acronymTitle, BorderLayout.NORTH);
        acronyms.add(chips, BorderLayout.CENTER);
        identifierTitle.setFont(Comic.semiBold(13f));
        JPanel identifiers = identifiersBox;
        identifiers.setOpaque(false);
        identifiers.add(identifierTitle, BorderLayout.NORTH);
        identifiers.add(identifierChips, BorderLayout.CENTER);
        verbChips.setOpaque(false);
        verbTitle.setFont(Comic.semiBold(13f));
        JPanel verbs = verbsBox;
        verbs.setOpaque(false);
        verbs.add(verbTitle, BorderLayout.NORTH);
        verbs.add(verbChips, BorderLayout.CENTER);
        relationTitle.setFont(Comic.semiBold(13f));
        relationLines.setOpaque(false);
        relationLines.setLayout(new BoxLayout(relationLines, BoxLayout.Y_AXIS));
        JPanel relations = relationsBox;
        relations.setOpaque(false);
        relations.add(relationTitle, BorderLayout.NORTH);
        relations.add(relationLines, BorderLayout.CENTER);
        JPanel lists = new JPanel(new BorderLayout(0, 10));
        lists.setOpaque(false);
        JPanel words = new JPanel(new BorderLayout(0, 10));
        words.setOpaque(false);
        words.add(identifiers, BorderLayout.NORTH);
        words.add(verbs, BorderLayout.CENTER);
        lists.add(acronyms, BorderLayout.NORTH);
        lists.add(words, BorderLayout.CENTER);
        lists.add(relations, BorderLayout.SOUTH);
        // Folgt der Breite des Sichtfensters: die Chips brechen um, statt einen waagerechten Balken zu erzeugen.
        JPanel column = new JPanel(new BorderLayout(0, 6));
        column.setOpaque(false);
        column.add(lists, BorderLayout.NORTH);
        column.add(hint, BorderLayout.CENTER);
        column.add(belowSection(), BorderLayout.SOUTH);
        JPanel body = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout());
        body.add(column, BorderLayout.NORTH);

        add(top, BorderLayout.NORTH);
        scroll = Comic.scroll(body);
        add(scroll, BorderLayout.CENTER);
        // Auswahl: Nachladen, sobald das Ende der Liste in Sicht kommt (kein Zeitgeber).
        scroll.getVerticalScrollBar().getModel().addChangeListener(e -> {
            if (selection != null && nearEnd()) {
                more();
            } else if (below != null && nearEnd()) {
                moreBelow();
            }
        });

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filtered();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filtered();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filtered();
            }
        });
        show(null);
    }

    /** {@code listener} bekommt die Fundstellen des angeklickten Begriffs in Textreihenfolge. */
    void onJump(Consumer<List<Span>> listener) {
        onJump = Objects.requireNonNull(listener, "listener");
    }

    /** {@code action} läuft beim Klick auf den Stand, solange die Analyse der Seite nicht aktuell ist. */
    void onRecompute(Runnable action) {
        onRecompute = Objects.requireNonNull(action, "action");
    }

    /** Zeigt nur die Akronyme der Seite; Bezeichner bleiben offen. */
    void show(PageAcronyms acronyms) {
        show(acronyms, null);
    }

    /**
     * Zeigt die Begriffe der Seite; {@code acronyms} {@code null} heißt: keine Seite oder noch nicht geladen,
     * {@code terms} {@code null}: Bezeichner unbekannt.
     */
    void show(PageAcronyms acronyms, PageTerms terms) {
        leaveSelection();
        shown = acronyms;
        shownTerms = terms;
        PageAcronyms.State current = acronyms == null ? PageAcronyms.State.MISSING : acronyms.state();
        // Ohne Seite (acronyms null) gibt es nichts neu zu berechnen; sonst ist jeder Stand außer „Aktuell“ ein Knopf.
        boolean clickable = acronyms != null && current != PageAcronyms.State.CURRENT && onRecompute != null;
        head.status(stateText(current), stateColor(current), clickable ? RECOMPUTE_TIP : null,
                clickable ? onRecompute : null);
        // Nur ein aktuelles Ergebnis hat Begriffe und Fundstellen; veraltet oder fehlgeschlagen zählt nichts.
        boolean any = current == PageAcronyms.State.CURRENT;
        acronymCount.setText(any ? String.valueOf(acronyms.acronyms().size()) : NOT_YET);
        boolean identifiers = terms != null && terms.state() == PageTerms.State.CURRENT;
        identifierCount.setText(identifiers ? String.valueOf(terms.identifierCount()) : NOT_YET);
        identifierCount.setToolTipText(identifiers ? "Nomen, Nominalgruppen und Kennungen"
                : "Für diesen Text liegt noch keine aktuelle Begriffsanalyse vor");
        showRelations(null);
        analysis.setText(acronyms == null || acronyms.result() == null ? "Analyse vom letzten Seitenabgleich"
                : "Analyse vom letzten Seitenabgleich · " + Texts.time(acronyms.result().computedAt()));
        String complete = any ? completeness(acronyms) : null;
        completeness.setVisible(complete != null);
        completeness.setText(complete == null ? " " : complete);
        tone(completeness, acronyms != null && acronyms.warnings().isEmpty() ? Comic.GREEN : Comic.ORANGE);
        fill();
        if (below != null) {
            // Neue eigene Begriffe: was oben steht, steht unten nicht noch einmal.
            belowCount.count(belowEntries());
            queryBelow();
        }
    }

    /**
     * Zeigt die Beziehungen zwischen den Begriffen dieser Seite; {@code null} heißt: nicht abgefragt oder ohne
     * Begriffsbeziehungen im Core.
     */
    void showRelations(List<Relation> relations) {
        relationCount.setText(relations == null ? NOT_YET : String.valueOf(relations.size()));
        relationCount.setToolTipText(relations == null ? "Beziehungen liegen für diese Seite nicht vor"
                : "Bestätigte und vorgeschlagene Beziehungen zwischen den Begriffen dieser Seite");
        shownRelations = relations == null ? null : List.copyOf(relations);
        ownRelationLines = relations == null ? java.util.Set.of()
                : relations.stream().map(Relation::line).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (selection == null) {
            fill();
        }
    }

    /** Die Beziehungen der Seite, die zum Filter {@code query} (klein geschrieben) passen; gibt ihre Zahl zurück. */
    private int fillRelations(String query) {
        relationLines.removeAll();
        int visible = 0;
        for (Relation relation : shownRelations == null ? List.<Relation>of() : shownRelations) {
            if (!query.isEmpty() && !relation.line().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            JLabel line = Comic.text(relation.line());
            line.setAlignmentX(LEFT_ALIGNMENT);
            relationLines.add(line);
            visible++;
        }
        relationTitle.setVisible(visible > 0);
        relationLines.revalidate();
        relationLines.repaint();
        return visible;
    }

    /** {@code listener} öffnet eine Seite aus den Fundstellen eines Begriffs der Auswahl. */
    void onOpen(Consumer<TermQueries.Occurrence> listener) {
        onOpen = Objects.requireNonNull(listener, "listener");
    }

    /** Für Tests: Hintergrundarbeit sofort ausführen. */
    void runner(Runner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Zeigt die Begriffe einer Auswahl links: Überschrift mit ihrem Namen, ohne Seitenabgleich und Plaketten; die
     * Kennzahlen stehen auf „…“, bis {@link #totals} sie setzt. Die Listen laden im Hintergrund, Akronyme zuerst, dann
     * Bezeichner, dann Beziehungen, und laden beim Scrollen nach.
     */
    void selection(Selection chosen) {
        below(null);
        selection = Objects.requireNonNull(chosen, "chosen");
        shown = null;
        shownTerms = null;
        shownVerbs = null;
        shownRelations = null;
        head.title("Begriffe in " + chosen.title());
        head.status(null, null);
        analysisRow.setVisible(false);
        Object key = chosen.key();
        Totals known = key == null ? null : remembered.get(key);
        if (known == null) {
            totals(null, null, null);
            onStatus.accept(" ");
        } else {
            showTotals(known);
        }
        query();
        count(known == null);
    }

    private void showTotals(Totals counted) {
        totals(counted.acronyms(), counted.identifiers(), counted.verbs(), counted.relations());
        String origin = coverage(counted);
        onStatus.accept(origin == null ? " " : origin);
    }

    /** Lässt die Kacheln „atmen“, solange ihre Zahlen noch hochzählen. */
    private void breathing(boolean on) {
        for (CountTile tile : tiles()) {
            tile.counting(on);
        }
    }

    /**
     * Von Hand neu aufbauen, für den Fehlerfall (Angelo 24.11.2026): verwirft alle gemerkten Zahlen, lässt den Core
     * den Knoten samt Unterknoten neu lesen und zählt sichtbar neu.
     */
    void rebuild() {
        if (selection == null) {
            return;
        }
        remembered.clear();
        selection.rebuild();
        totals(null, null, null);
        count(true);
    }

    /**
     * Zählt die Auswahl neu (Gesamtzahlen und Herkunft der Begriffe), etwa nachdem der Bestand sich geändert hat; die
     * Listen bleiben stehen. Läuft schon eine Zählung, folgt genau eine weitere danach.
     */
    void recount() {
        if (selection == null) {
            return;
        }
        if (counting) {
            countAgain = true;
            return;
        }
        count(false);
    }

    /**
     * @param visible ob die Kacheln mitzählen und atmen (erste Zählung einer Auswahl); sonst bleiben die bekannten
     *                Zahlen stehen, bis das Ergebnis da ist
     */
    private void count(boolean visible) {
        Selection chosen = selection;
        counting = true;
        countAgain = false;
        int run = ++countRun;
        breathing(visible);
        Consumer<Partial> progress = partial -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (visible && run == countRun && selection == chosen) {
                totals(partial.acronyms(), partial.identifiers(), partial.verbs(), partial.relations());
            }
        });
        // Die Kennzahlen kommen getrennt von den Listen; die Listen warten nie auf sie.
        runner.run(() -> chosen.totals(progress), counted -> {
            finished(run);
            Object key = chosen.key();
            if (key != null && counted != null) {
                remembered.put(key, counted);
            }
            if (selection == chosen && counted != null) {
                showTotals(counted);
            }
            counted(chosen);
        }, failure -> {
            finished(run);
            if (selection == chosen) {
                String unreadable = "Gesamtzahl nicht lesbar: " + failure.getClass().getSimpleName();
                for (CountTile count : tiles()) {
                    if (COUNTING.equals(count.getText()) || visible) {
                        count.setText(NOT_YET);
                    }
                    count.setToolTipText(unreadable);
                }
            }
            counted(chosen);
        });
    }

    private void finished(int run) {
        if (run == countRun) {
            countRun++;
            breathing(false);
        }
    }

    /** Ob gerade eine Auswahl statt einer Seite zu sehen ist. */
    boolean showsSelection() {
        return selection != null;
    }

    /**
     * Die echten Gesamtzahlen der Auswahl aus dem Core; {@code null} heißt: noch nicht bekannt („…“). Nie die Zahl der
     * geladenen Chips.
     */
    void totals(Integer acronyms, Integer identifiers, Integer relations) {
        totals(acronyms, identifiers, null, relations);
    }

    /** Wie {@link #totals(Integer, Integer, Integer)} mit der Zahl der Verben (K1); {@code -1}: nicht bekannt. */
    void totals(Integer acronyms, Integer identifiers, Integer verbs, Integer relations) {
        verbCount.setText(verbs == null ? COUNTING : verbs < 0 ? NOT_YET : String.valueOf(verbs));
        verbCount.setToolTipText(verbs == null ? "Die Gesamtzahl der Auswahl wird noch ermittelt"
                : "Verben (Grundform) aus den Aussagen des eingestellten Analysators");
        acronymCount.setText(acronyms == null ? COUNTING : String.valueOf(acronyms));
        identifierCount.setText(identifiers == null ? COUNTING : String.valueOf(identifiers));
        relationCount.setText(relations == null ? COUNTING : String.valueOf(relations));
        String pending = "Die Gesamtzahl der Auswahl wird noch ermittelt";
        acronymCount.setToolTipText(acronyms == null ? pending : null);
        identifierCount.setToolTipText(identifiers == null ? pending : "Nomen, Nominalgruppen und Kennungen");
        relationCount.setToolTipText(relations == null ? pending : null);
    }

    private void counted(Selection chosen) {
        counting = false;
        if (countAgain && selection == chosen) {
            count(false);
        }
    }

    /** Die Statuszeile des Hauptfensters: woraus die Begriffe der Auswahl stammen. */
    void onStatus(java.util.function.Consumer<String> listener) {
        this.onStatus = Objects.requireNonNull(listener, "listener");
    }

    private void leaveSelection() {
        if (selection == null) {
            return;
        }
        selection = null;
        request++;
        loading = false;
        sections.clear();
        popup.setVisible(false);
        head.title("Begriffe dieser Seite");
        analysisRow.setVisible(true);
    }

    private void filtered() {
        if (selection != null) {
            query();
        } else {
            fill();
            queryBelow();
        }
    }

    /** Fragt den Core neu (Auswahl oder Filter geändert); verworfen wird alles Geladene. */
    private void query() {
        Selection current = selection;
        String q = filter.getText() == null ? "" : filter.getText().trim();
        request++;
        loading = false;
        loadedTerms = 0;
        popup.setVisible(false);
        chips.removeAll();
        identifierChips.removeAll();
        verbChips.removeAll();
        relationLines.removeAll();
        acronymTitle.setVisible(false);
        identifierTitle.setVisible(false);
        verbTitle.setVisible(false);
        relationTitle.setVisible(false);
        hint.setText(" ");
        sections.clear();
        sections.add(new Section<>(ACRONYMS, current.acronyms(q), terms -> append(chips, acronymTitle, terms)));
        sections.add(new Section<>(IDENTIFIERS, current.identifiers(q),
                terms -> append(identifierChips, identifierTitle, terms)));
        Pager<VerbQueries.Verb> verbs = current.verbs(q);
        if (verbs != null) {
            sections.add(new Section<>(VERBS, verbs, this::appendVerbs));
        }
        sections.add(new Section<>(RELATIONS, current.relations(q), this::appendRelations));
        for (JPanel list : List.of(chips, identifierChips, verbChips, relationLines)) {
            list.revalidate();
            list.repaint();
        }
        more();
    }

    /**
     * Lädt den nächsten Schritt; nie zwei Schritte zugleich. Ohne gewählten Abschnitt ist der erste dran, der noch
     * etwas hat. Mit gewähltem Abschnitt nur dieser: Sonst müssten erst alle Akronyme und Bezeichner der Auswahl
     * geladen sein, bevor „Verben“ oder „Beziehungen“ ihre erste Zeile zeigen, und weil unsichtbare Abschnitte die
     * Fläche nicht füllen, kam dieser Punkt bei großen Auswahlen nie (Angelo 27.11.2026).
     */
    void more() {
        if (loading || selection == null) {
            return;
        }
        for (Section<?> section : sections) {
            if (shows(section.kind) && section.pager.hasMore()) {
                load(section);
                return;
            }
        }
        finished();
    }

    private <T> void load(Section<T> section) {
        int mine = request;
        loading = true;
        runner.run(() -> section.pager.next(step()), items -> {
            if (mine != request) {
                return;
            }
            loading = false;
            section.append.accept(items);
            // Erst nach dem Auslegen prüfen, ob die Fläche schon voll ist; sonst gleich weiterladen.
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (mine == request && nearEnd()) {
                    more();
                }
            });
        }, failure -> {
            if (mine == request) {
                loading = false;
                hint.setText("Begriffe nicht lesbar: " + failure.getClass().getSimpleName());
            }
        });
    }

    private void finished() {
        boolean empty = visible(chips.getComponentCount(), identifierChips.getComponentCount(),
                verbChips.getComponentCount(), relationLines.getComponentCount()) == 0;
        String q = filter.getText() == null ? "" : filter.getText().trim();
        hint.setText(!empty ? " " : q.isEmpty() ? (kind == null ? NO_TERMS
                : "In der Auswahl wurden keine " + plural(kind) + " erkannt.") : noMatch(kind));
    }

    /** Wie viele Einträge der gewählte Abschnitt zeigt; ohne Wahl alle zusammen. */
    private int visible(int acronyms, int identifiers, int verbs, int relations) {
        return kind == null ? acronyms + identifiers + verbs + relations : switch (kind) {
            case ACRONYMS -> acronyms;
            case IDENTIFIERS -> identifiers;
            case VERBS -> verbs;
            default -> relations;
        };
    }

    /** „Kein Begriff passt zum Filter.“, bezogen auf den gewählten Abschnitt („Keine Beziehung …“). */
    static String noMatch(String kind) {
        if (kind == null) {
            return NO_MATCH;
        }
        return switch (kind) {
            case ACRONYMS -> "Kein Akronym passt zum Filter.";
            case IDENTIFIERS -> "Kein Bezeichner passt zum Filter.";
            case VERBS -> "Kein Verb passt zum Filter.";
            default -> "Keine Beziehung passt zum Filter.";
        };
    }

    /** Der Abschnitt in der Mehrzahl für Hinweise („Beziehungen“); ohne Wahl „Begriffe“. */
    private static String plural(String kind) {
        return kind == null ? "Begriffe" : kind;
    }

    private boolean nearEnd() {
        javax.swing.BoundedRangeModel model = scroll.getVerticalScrollBar().getModel();
        return model.getValue() + model.getExtent() >= model.getMaximum() - 200;
    }

    private void append(JPanel list, JLabel title, List<TermQueries.TermSummary> terms) {
        for (TermQueries.TermSummary term : terms) {
            list.add(chip(term));
        }
        loadedTerms += terms.size();
        title.setVisible(list.getComponentCount() > 0);
        list.revalidate();
        list.repaint();
    }

    private void appendVerbs(List<VerbQueries.Verb> verbs) {
        for (VerbQueries.Verb verb : verbs) {
            verbChips.add(verbChip(verb));
        }
        loadedTerms += verbs.size();
        verbTitle.setVisible(verbChips.getComponentCount() > 0);
        verbChips.revalidate();
        verbChips.repaint();
    }

    /** Ein Verb der Auswahl: Pille mit der Grundform, Klick zeigt die Seiten mit seinen Aussagen. */
    private FindablePill verbChip(VerbQueries.Verb verb) {
        FindablePill chip = textForm(chip(verb.lemma(), verbTooltip(verb, false), List.of()), verb);
        chip.setEnabled(true);
        for (java.awt.event.ActionListener listener : chip.getActionListeners()) {
            chip.removeActionListener(listener);
        }
        Selection current = selection;
        int mine = request;
        chip.addActionListener(e -> verbOccurrences(current, verb, chip, () -> mine == request));
        return chip;
    }

    /**
     * Ohne ermittelte Grundform ist die Pille selbst erkennbar (UX-Manager 21.11.2026): Text kursiv und gedämpft,
     * keine Farbe und kein Zusatz; sie bleibt klickbar wie die anderen.
     */
    static FindablePill textForm(FindablePill chip, VerbQueries.Verb verb) {
        if (verb.fromText()) {
            chip.setFont(Comic.semiBold(12f).deriveFont(java.awt.Font.ITALIC));
            chip.setForegrounds(Comic.MUTED, Color.WHITE, Color.WHITE);
        }
        return chip;
    }

    /** Wie {@link #verbTooltip}, aber „auf 2 Unterseiten · Klick zeigt die Unterseiten“. */
    static String belowVerbTooltip(VerbQueries.Verb verb) {
        String counts = Texts.count(verb.statements(), "Aussage", "Aussagen") + " auf "
                + Texts.count(verb.pages(), "Unterseite", "Unterseiten") + " · Klick zeigt die Unterseiten";
        return verb.fromText() ? "Grundform nicht ermittelt, so steht es im Text · " + counts : counts;
    }

    /** „5 Aussagen auf 3 Seiten“, dazu „Grundform nicht ermittelt“, wenn der Analysator keine lieferte. */
    static String verbTooltip(VerbQueries.Verb verb, boolean page) {
        String counts = page ? Texts.count(verb.statements(), "Aussage", "Aussagen")
                + " · Klick springt zur nächsten Aussage im Lesetext"
                : Texts.count(verb.statements(), "Aussage", "Aussagen") + " auf "
                + Texts.count(verb.pages(), "Seite", "Seiten") + " · Klick zeigt die Seiten";
        return verb.fromText() ? "Grundform nicht ermittelt, so steht es im Text · " + counts : counts;
    }

    private void verbOccurrences(Selection current, VerbQueries.Verb verb, javax.swing.JComponent chip,
                                 java.util.function.BooleanSupplier still) {
        if (current == null) {
            return;
        }
        runner.run(() -> current.verbOccurrences(verb), found -> {
            if (!still.getAsBoolean()) {
                return;
            }
            popup.clearLinks();
            java.util.Map<String, List<VerbQueries.Occurrence>> byPage = new java.util.LinkedHashMap<>();
            for (VerbQueries.Occurrence occurrence : found) {
                byPage.computeIfAbsent(occurrence.page().key().sourceId() + "/" + occurrence.page().key().pageId(),
                        key -> new ArrayList<>()).add(occurrence);
            }
            popup.text("Aussagen mit „" + verb.lemma() + "“", verbOccurrenceLine(byPage.size(), found.size()));
            for (List<VerbQueries.Occurrence> hits : byPage.values()) {
                VerbQueries.Occurrence first = hits.get(0);
                TermQueries.Occurrence target = new TermQueries.Occurrence(first.page().key().sourceId(),
                        first.page().spaceKey(), first.page().key().pageId(), first.page().title(), null,
                        first.predicateText(), null, null, first.start(), first.end(), null, null, null, null);
                popup.link(first.page().title() + " · " + Texts.count(hits.size(), "Aussage", "Aussagen"), () -> {
                    popup.setVisible(false);
                    onOpen.accept(target);
                });
            }
            popup.showAt(chip, 0, chip.getHeight());
        }, failure -> {
            if (still.getAsBoolean()) {
                hint.setText("Aussagen nicht lesbar: " + failure.getClass().getSimpleName());
            }
        });
    }

    /** So viele Aussagen holt das Overlay eines Verbs höchstens ({@code SelectionTerms#verbOccurrences}). */
    static final int VERB_OCCURRENCES = TermQueries.MAX_LIMIT;
    /** Zahl der Verben, wenn die Aussagenanalyse der Seite nicht mehr zum Text passt. */
    static final String STALE_COUNT = "\u2013";
    static final String STALE_TIP = "Aussagenanalyse veraltet";

    /**
     * „3 Seiten; ein Klick öffnet die Seite.“; ist die Grenze erreicht, „Mindestens 3 Seiten (die ersten 200
     * Aussagen); …“, denn weitere Seiten können fehlen.
     */
    static String verbOccurrenceLine(int pages, int statements) {
        if (pages == 0) {
            return "Keine sichtbare Aussage.";
        }
        if (statements >= VERB_OCCURRENCES) {
            return "Mindestens " + Texts.count(pages, "Seite", "Seiten") + " (die ersten "
                    + VERB_OCCURRENCES + " Aussagen); ein Klick öffnet die Seite.";
        }
        return Texts.count(pages, "Seite", "Seiten") + "; ein Klick öffnet die Seite.";
    }

    /** Wie {@link #showVerbs(List, boolean)} mit aktueller Analyse. */
    void showVerbs(List<PageVerb> verbs) {
        showVerbs(verbs, false);
    }

    /**
     * Die Verben der gezeigten Seite aus ihrer Aussagenanalyse (K1); {@code null}: keine Analyse, {@code stale}:
     * die Analyse passt nicht mehr zum Text („–“, nicht „0“). Ein Klick auf ein Verb springt zur nächsten seiner
     * Aussagen im Lesetext. Auch der Abschnitt „Aus Unterseiten“ folgt: was oben steht, steht unten nicht noch einmal.
     */
    void showVerbs(List<PageVerb> verbs, boolean stale) {
        if (selection != null) {
            return;
        }
        shownVerbs = verbs == null || stale ? null : List.copyOf(verbs);
        verbCount.setText(stale ? STALE_COUNT : verbs == null ? NOT_YET : String.valueOf(verbs.size()));
        verbCount.setToolTipText(stale ? STALE_TIP : verbs == null
                ? "Für diese Seite liegt keine aktuelle Aussagenanalyse vor"
                : "Verben (Grundform) aus den Aussagen des eingestellten Analysators");
        fill();
        if (below != null) {
            belowCount.count(belowEntries());
            queryBelow();
        }
    }

    /** Für Tests: die Pillen der Verben. */
    List<ResearchPillButton> verbChips() {
        List<ResearchPillButton> found = new ArrayList<>();
        for (java.awt.Component component : cells(verbChips)) {
            if (component instanceof ResearchPillButton pill) {
                found.add(pill);
            }
        }
        return found;
    }

    private void appendRelations(List<Relation> relations) {
        for (Relation relation : relations) {
            JLabel line = Comic.text(relation.line());
            line.setAlignmentX(LEFT_ALIGNMENT);
            relationLines.add(line);
        }
        relationTitle.setVisible(relationLines.getComponentCount() > 0);
        relationLines.revalidate();
        relationLines.repaint();
    }

    /** Ein Begriff der Auswahl: Klick zeigt die Seiten mit Fundstellen als Overlay am Begriff. */
    private FindablePill chip(TermQueries.TermSummary term) {
        FindablePill chip = chip(selectionLabel(term), selectionTooltip(term), List.of());
        chip.setEnabled(true);
        for (java.awt.event.ActionListener listener : chip.getActionListeners()) {
            chip.removeActionListener(listener);
        }
        chip.addActionListener(e -> occurrences(term, chip));
        return chip;
    }

    /** Wie auf der Seite: ein Akronym mit genau einer Ausschreibung als „RSL · Rückmeldeschlüssel“. */
    static String selectionLabel(TermQueries.TermSummary term) {
        return term.kind() == TermKind.ACRONYM && term.expansions().size() == 1
                ? term.term() + " · " + term.expansions().get(0) : term.term();
    }

    /** Wie {@link #selectionTooltip}, aber „auf 2 Unterseiten · Klick zeigt die Unterseiten“ (Herkunft, P2). */
    static String belowTooltip(TermQueries.TermSummary term) {
        String counts = Texts.count(term.occurrences(), "Fundstelle", "Fundstellen") + " auf "
                + Texts.count(term.pages(), "Unterseite", "Unterseiten") + " · Klick zeigt die Unterseiten";
        return term.kind() == TermKind.ACRONYM && !term.expansions().isEmpty()
                ? String.join(" · ", term.expansions()) + " · " + counts : counts;
    }

    /** „12 Fundstellen auf 4 Seiten · Klick zeigt die Seiten“; bei Akronymen davor die Ausschreibungen. */
    static String selectionTooltip(TermQueries.TermSummary term) {
        String counts = Texts.count(term.occurrences(), "Fundstelle", "Fundstellen") + " auf "
                + Texts.count(term.pages(), "Seite", "Seiten") + " · Klick zeigt die Seiten";
        return term.kind() == TermKind.ACRONYM && !term.expansions().isEmpty()
                ? String.join(" · ", term.expansions()) + " · " + counts : counts;
    }

    private void occurrences(TermQueries.TermSummary term, ResearchPillButton chip) {
        Selection current = selection;
        if (current == null) {
            return;
        }
        int mine = request;
        occurrences(current, term, chip, () -> mine == request);
    }

    private void occurrences(Selection current, TermQueries.TermSummary term, javax.swing.JComponent chip,
                             java.util.function.BooleanSupplier still) {
        runner.run(() -> current.occurrences(term), found -> {
            if (!still.getAsBoolean()) {
                return;
            }
            popup.clearLinks();
            java.util.Map<String, List<TermQueries.Occurrence>> byPage = new java.util.LinkedHashMap<>();
            for (TermQueries.Occurrence occurrence : found.occurrences()) {
                byPage.computeIfAbsent(occurrence.sourceId() + "/" + occurrence.pageId(),
                        key -> new ArrayList<>()).add(occurrence);
            }
            popup.text("Fundstellen von „" + term.term() + "“", byPage.isEmpty() ? "Keine sichtbare Fundstelle."
                    : Texts.count(byPage.size(), "Seite", "Seiten") + (found.nextCursor() == null ? ""
                    : " (die ersten)") + "; ein Klick öffnet die Seite.");
            for (List<TermQueries.Occurrence> hits : byPage.values()) {
                TermQueries.Occurrence first = hits.get(0);
                popup.link(first.title() + " · " + Texts.count(hits.size(), "Fundstelle", "Fundstellen"), () -> {
                    popup.setVisible(false);
                    onOpen.accept(first);
                });
            }
            popup.showAt(chip, 0, chip.getHeight());
        }, failure -> {
            if (still.getAsBoolean()) {
                hint.setText("Fundstellen nicht lesbar: " + failure.getClass().getSimpleName());
            }
        });
    }

    /** Ein Abschnitt der Auswahl mit eigenem Pager. */
    private static final class Section<T> {
        /** Der Abschnitt aus {@link #KINDS}, zu dem diese Abfrage gehört. */
        final String kind;
        final Pager<T> pager;
        final Consumer<List<T>> append;

        Section(String kind, Pager<T> pager, Consumer<List<T>> append) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.pager = Objects.requireNonNull(pager, "pager");
            this.append = Objects.requireNonNull(append, "append");
        }
    }

    private void fill() {
        chips.removeAll();
        String query = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        List<PageAcronyms.Acronym> visible = new ArrayList<>();
        if (shown != null) {
            for (PageAcronyms.Acronym acronym : shown.acronyms()) {
                if (query.isEmpty() || acronym.acronym().toLowerCase(Locale.ROOT).contains(query)
                        || (acronym.expansion() != null
                        && acronym.expansion().toLowerCase(Locale.ROOT).contains(query))) {
                    visible.add(acronym);
                }
            }
        }
        for (PageAcronyms.Acronym acronym : visible) {
            addPill(chips, chip(label(acronym), tooltip(acronym), acronym.occurrences().stream()
                    .map(occurrence -> new Span(occurrence.start(), occurrence.end())).toList()), ref(acronym),
                    acronym.acronym());
        }
        identifierChips.removeAll();
        int identifiers = 0;
        if (shownTerms != null && shownTerms.state() == PageTerms.State.CURRENT) {
            for (TermPayload.Entry entry : shownTerms.terms()) {
                if (entry.kind() == TermKind.ACRONYM || !query.isEmpty()
                        && !entry.term().toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                addPill(identifierChips, chip(entry.term(), tooltip(entry), entry.occurrences().stream()
                        .map(occurrence -> new Span(occurrence.start(), occurrence.end())).toList()),
                        new TermQueries.TermRef(entry.normalized(), entry.kind()), entry.term());
                identifiers++;
            }
        }
        verbChips.removeAll();
        int verbs = 0;
        for (PageVerb verb : shownVerbs == null ? List.<PageVerb>of() : shownVerbs) {
            if (!query.isEmpty() && !verb.verb().lemma().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            addVerbPill(textForm(chip(verb.verb().lemma(), verbTooltip(verb.verb(), true), verb.spans()),
                    verb.verb()), verb.verb());
            verbs++;
        }
        int relations = fillRelations(query);
        acronymTitle.setVisible(!visible.isEmpty());
        identifierTitle.setVisible(identifiers > 0);
        verbTitle.setVisible(verbs > 0);
        hint.setText(hint(shown, visible(visible.size(), identifiers, verbs, relations), !query.isEmpty(), kind));
        for (JPanel list : List.of(chips, identifierChips, verbChips)) {
            list.revalidate();
            list.repaint();
        }
    }

    /** Die gezeigten Begriffe als Text: Akronyme, Bezeichner und Beziehungen als Listen unter ihren Überschriften. */
    String copyText() {
        StringBuilder text = new StringBuilder();
        List<String> acronyms = chips().stream().map(ResearchPillButton::getText).toList();
        List<String> identifiers = identifierChips().stream().map(ResearchPillButton::getText).toList();
        List<String> relations = relationLines();
        if (!acronyms.isEmpty()) {
            text.append("Akronyme\n").append(String.join("\n", acronyms)).append("\n\n");
        }
        if (!identifiers.isEmpty()) {
            text.append("Bezeichner\n").append(String.join("\n", identifiers)).append("\n\n");
        }
        List<String> verbs = verbChips().stream().map(ResearchPillButton::getText).toList();
        if (!verbs.isEmpty()) {
            text.append("Verben\n").append(String.join("\n", verbs)).append("\n\n");
        }
        if (!relations.isEmpty()) {
            text.append("Beziehungen\n").append(String.join("\n", relations)).append("\n\n");
        }
        if (showsBelow()) {
            List<String> fromBelow = new ArrayList<>(belowChips().stream().map(ResearchPillButton::getText).toList());
            for (java.awt.Component component : belowRelations.getComponents()) {
                if (component instanceof JLabel line) {
                    fromBelow.add(line.getText());
                }
            }
            if (!fromBelow.isEmpty()) {
                text.append(BELOW).append("\n").append(String.join("\n", fromBelow)).append("\n");
            }
        }
        return text.toString().stripTrailing();
    }

    private FindablePill chip(String label, String tooltip, List<Span> spans) {
        FindablePill chip = new FindablePill(label, 26, 26, 10);
        chip.setFont(Comic.semiBold(12f));
        chip.setFills(Comic.SURFACE, ResearchUiPalette.SECONDARY_SURFACE, ResearchUiPalette.ACCENT_BLUE);
        chip.setForegrounds(Comic.INK, Color.WHITE, Color.WHITE);
        chip.setBorders(Comic.MUTED, Comic.INK, ResearchUiPalette.ACCENT_BLUE);
        chip.setToolTipText(tooltip);
        chip.setEnabled(!spans.isEmpty());
        chip.addActionListener(e -> onJump.accept(spans));
        return chip;
    }

    /** „Kennung · 3 Fundstellen · zuerst in „Betrieb“ · Klick …“ */
    static String tooltip(TermPayload.Entry entry) {
        String kind = entry.kind() == TermKind.IDENTIFIER ? "Kennung" : "Nomen";
        String where = entry.count() == 1 ? "1 Fundstelle" : entry.count() + " Fundstellen";
        return kind + " · " + where + (entry.section() == null ? "" : " · zuerst in „" + entry.section() + "“")
                + (entry.occurrences().isEmpty() ? "" : " · Klick springt zur nächsten Fundstelle im Lesetext");
    }

    /** „RSL · Rückmeldeschlüssel“, ohne Ausschreibung nur das Akronym. */
    static String label(PageAcronyms.Acronym acronym) {
        return acronym.expansion() == null ? acronym.acronym() : acronym.acronym() + " · " + acronym.expansion();
    }

    static String tooltip(PageAcronyms.Acronym acronym) {
        String where = acronym.count() == 1 ? "1 Fundstelle" : acronym.count() + " Fundstellen";
        return (acronym.expansion() == null ? "Ausschreibung steht nicht auf der Seite" : acronym.expansion())
                + " · " + where + (acronym.section() == null ? "" : " · zuerst in „" + acronym.section() + "“")
                + (acronym.occurrences().isEmpty() ? "" : " · Klick springt zur nächsten Fundstelle im Lesetext");
    }

    static String stateText(PageAcronyms.State state) {
        return switch (state) {
            case CURRENT -> "Aktuell";
            case STALE -> TabHeader.STALE;
            case FAILED -> "Fehlgeschlagen";
            case MISSING -> "Nicht durchgeführt";
        };
    }

    private static Color stateColor(PageAcronyms.State state) {
        return switch (state) {
            case CURRENT -> Comic.GREEN;
            case STALE -> Comic.ORANGE;
            case FAILED -> Comic.RED;
            case MISSING -> Comic.MUTED;
        };
    }

    /** „Vollständig“ ohne Hinweise des Prozessors, sonst sein erster Hinweis. */
    static String completeness(PageAcronyms acronyms) {
        return acronyms.warnings().isEmpty() ? "Vollständig" : "Unvollständig: " + acronyms.warnings().get(0);
    }

    static String hint(PageAcronyms acronyms, int visible, boolean filtered) {
        return hint(acronyms, visible, filtered, null);
    }

    /** Wie {@link #hint(PageAcronyms, int, boolean)}, bezogen auf den gewählten Abschnitt {@code kind}. */
    static String hint(PageAcronyms acronyms, int visible, boolean filtered, String kind) {
        PageAcronyms.State state = acronyms == null ? PageAcronyms.State.MISSING : acronyms.state();
        return switch (state) {
            case MISSING -> "Noch keine Analyse für diese Seite. Ein Klick auf „Nicht durchgeführt“ oben rechts "
                    + "berechnet sie.";
            case STALE -> "Die Analyse passt nicht mehr zum angezeigten Text; ein Klick auf „Veraltet“ oben rechts "
                    + "berechnet sie neu.";
            case FAILED -> "Die letzte Analyse ist fehlgeschlagen; ein Klick auf „Fehlgeschlagen“ oben rechts "
                    + "startet sie neu.";
            case CURRENT -> visible == 0 && !filtered ? "Auf dieser Seite wurden keine " + plural(kind) + " erkannt."
                    : visible == 0 && filtered ? noMatch(kind) : " ";
        };
    }

    /** Die Kennzahlen in der Reihenfolge der Abschnitte ({@link #KINDS}). */
    private List<CountTile> tiles() {
        return List.of(acronymCount, identifierCount, verbCount, relationCount);
    }

    /** Hält eine Kachel mittig in ihrer Spalte, damit sie quadratisch bleibt. */
    private static JPanel cell(CountTile tile) {
        JPanel cell = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        cell.setOpaque(false);
        cell.add(tile);
        return cell;
    }

    private static JLabel badge() {
        return TabHeader.statusBadge(" ", Comic.MUTED);
    }

    private static void tone(JLabel badge, Color color) {
        TabHeader.tone(badge, color);
    }

    // ---- für Tests

    String stateLabel() {
        return head.statusText();
    }

    TabHeader header() {
        return head;
    }

    List<CountTile> countTiles() {
        return tiles();
    }

    /** Für Tests: die Kennzahlen. */
    List<String> counts() {
        return List.of(acronymCount.getText(), identifierCount.getText(), relationCount.getText());
    }

    /** Für Tests: die Kennzahl „Verben“. */
    String verbCountText() {
        return verbCount.getText();
    }

    List<ResearchPillButton> chips() {
        List<ResearchPillButton> list = new ArrayList<>();
        for (java.awt.Component component : cells(chips)) {
            if (component instanceof ResearchPillButton pill) {
                list.add(pill);
            }
        }
        return list;
    }

    List<ResearchPillButton> identifierChips() {
        List<ResearchPillButton> list = new ArrayList<>();
        for (java.awt.Component component : cells(identifierChips)) {
            if (component instanceof ResearchPillButton pill) {
                list.add(pill);
            }
        }
        return list;
    }

    List<String> relationLines() {
        List<String> lines = new ArrayList<>();
        for (java.awt.Component component : relationLines.getComponents()) {
            lines.add(((JLabel) component).getText());
        }
        return lines;
    }

    PlaceholderField filter() {
        return filter;
    }

    String hint() {
        return hint.getText();
    }

    String analysis() {
        return analysis.getText();
    }

    String title() {
        return head.titleText();
    }

    boolean analysisShown() {
        return analysisRow.isVisible();
    }

    InfoPopup occurrencePopup() {
        return popup;
    }

    /** Wie {@link FlowLayout}, meldet aber die Höhe aller umbrochenen Zeilen in der Breite des Elternteils. */
    private static final class WrapFlow extends FlowLayout {

        WrapFlow() {
            super(FlowLayout.LEFT, 6, 6);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container target) {
            int available = target.getParent() == null ? 0 : target.getParent().getWidth();
            if (available <= 0) {
                return super.preferredLayoutSize(target);
            }
            int x = getHgap();
            int rowHeight = 0;
            int height = getVgap();
            for (java.awt.Component part : target.getComponents()) {
                if (!part.isVisible()) {
                    continue;
                }
                java.awt.Dimension size = part.getPreferredSize();
                if (x > getHgap() && x + size.width + getHgap() > available) {
                    height += rowHeight + getVgap();
                    x = getHgap();
                    rowHeight = 0;
                }
                x += size.width + getHgap();
                rowHeight = Math.max(rowHeight, size.height);
            }
            return new java.awt.Dimension(available, height + rowHeight + getVgap());
        }
    }

    // --- Abschnitte Akronyme/Bezeichner/Verben/Beziehungen über die Kennzahlen (Angelo 24.11.2026) -----------------

    private static final String KIND_KEY = "spaceHarvester.terms.kind";

    /** Eine Überschrift eines Abschnitts; mit gewähltem Abschnitt überflüssig und ausgeblendet. */
    private JLabel heading(String text) {
        JLabel label = new JLabel(text) {
            @Override
            public boolean isVisible() {
                return super.isVisible() && kind == null;
            }
        };
        label.setFont(Comic.regular(13f));
        label.setForeground(Comic.INK);
        return label;
    }

    /**
     * Schaltet die Kennzahlen als Umschalter ein (Seitenansicht): ab dann ist genau ein Abschnitt gewählt, „Akronyme“
     * zuerst. Ohne Aufruf (Stand der Auswahl) bleiben alle Abschnitte untereinander, bis eine Kennzahl geklickt wird.
     */
    void selectableKinds() {
        if (kind == null) {
            kind(ACRONYMS);
        }
    }

    /** Wählt den Abschnitt; {@code null} zeigt alle Abschnitte. */
    void kind(String chosen) {
        if (chosen != null && !KINDS.contains(chosen)) {
            throw new IllegalArgumentException("Unbekannter Abschnitt: " + chosen);
        }
        kind = chosen;
        if (chosen == null) {
            kindGroup.clearSelection();
        } else {
            tiles().get(KINDS.indexOf(chosen)).setSelected(true);
        }
        acronymsBox.setVisible(shows(ACRONYMS));
        identifiersBox.setVisible(shows(IDENTIFIERS));
        verbsBox.setVisible(shows(VERBS));
        relationsBox.setVisible(shows(RELATIONS));
        belowRelations.setVisible(shows(RELATIONS));
        for (java.awt.Component chip : belowChips.getComponents()) {
            if (chip instanceof JComponent part && part.getClientProperty(KIND_KEY) instanceof String of) {
                part.setVisible(shows(of));
            }
        }
        // Filter und Hinweis beziehen sich immer auf den gewählten Abschnitt.
        if (selection == null) {
            fill();
            moreBelow();
        } else {
            // Der gewählte Abschnitt lädt jetzt selbst; ist er schon vollständig, setzt more() nur den Hinweis.
            // Der Hinweis des vorigen Abschnitts („keine Akronyme erkannt“) gilt nicht mehr, solange noch geladen wird.
            hint.setText(" ");
            more();
        }
        revalidate();
        repaint();
    }

    /** Der gewählte Abschnitt; {@code null}: alle. */
    String kind() {
        return kind;
    }

    private boolean shows(String of) {
        return kind == null || kind.equals(of);
    }

    private <T extends JComponent> T ofKind(T chip, String of) {
        chip.putClientProperty(KIND_KEY, of);
        chip.setVisible(shows(of));
        return chip;
    }

    // --- Aus Unterseiten (Fahrplan P2) ---------------------------------------------------------------------------

    private JPanel belowSection() {
        belowPanel.setOpaque(false);
        belowChips.setOpaque(false);
        belowRelations.setOpaque(false);
        belowRelations.setLayout(new BoxLayout(belowRelations, BoxLayout.Y_AXIS));
        belowBody.setOpaque(false);
        belowBody.add(belowChips, BorderLayout.NORTH);
        belowBody.add(belowRelations, BorderLayout.CENTER);
        belowCount.setFocusable(false);
        belowCount.tips("Begriffe der Unterseiten, die nicht schon oben stehen",
                "Begriffe der Unterseiten, die nicht schon oben stehen");
        // Die Zahl ist nur eine Anzeige, kein Filter.
        belowCount.addActionListener(e -> belowCount.setSelected(false));
        JPanel title = new JPanel(new BorderLayout(8, 0));
        title.setOpaque(false);
        title.add(belowHeader, BorderLayout.CENTER);
        JPanel chipCell = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        chipCell.setOpaque(false);
        chipCell.add(belowCount);
        title.add(chipCell, BorderLayout.EAST);
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        // Die dünne Linie über dem Abschnitt trennt das Eigene von dem der Unterseiten.
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.MUTED),
                BorderFactory.createEmptyBorder(8, 0, 0, 0)));
        head.add(title, BorderLayout.CENTER);
        belowPanel.add(head, BorderLayout.NORTH);
        belowPanel.add(belowBusy, BorderLayout.CENTER);
        belowPanel.setVisible(false);
        return belowPanel;
    }

    private void belowToggled() {
        belowBusy.setVisible(belowHeader.isOpen());
        belowPanel.revalidate();
        belowPanel.repaint();
    }

    /**
     * Zeigt unter den eigenen Begriffen der Seite abgetrennt die ihrer Unterseiten ({@code null}: Abschnitt weg). Die
     * eigenen Einträge bleiben stehen; ein Begriff, der oben schon steht, bekommt dort „+N“ statt einer zweiten Pille.
     */
    /** Liest den Abschnitt „Aus Unterseiten“ neu, etwa nach einer neuen Aussagenanalyse; ohne Abschnitt nichts. */
    void reloadBelow() {
        Below current = below;
        if (current == null) {
            return;
        }
        below = null;
        below(current);
    }

    void below(Below chosen) {
        if (chosen == below) {
            return;
        }
        below = chosen;
        belowRequest++;
        belowLoading = false;
        belowCounts = java.util.Map.of();
        belowVerbCounts = java.util.Map.of();
        belowSections.clear();
        belowChips.removeAll();
        belowRelations.removeAll();
        belowCount.count(0);
        belowBusy.setBusy(false);
        belowPanel.setVisible(chosen != null && selection == null);
        if (chosen == null || selection != null) {
            fill();
            belowPanel.revalidate();
            belowPanel.repaint();
            return;
        }
        belowBusy.setBusy(true);
        // Die Zahlen gelten für die Unterseiten, nicht für den Filter: Ein neuer Filter verwirft sie nicht.
        runner.run(() -> new BelowCounts(chosen.pageCounts(), chosen.verbPageCounts()), both -> {
            if (chosen != below) {
                return;
            }
            java.util.Map<TermQueries.TermRef, Integer> counts = both.terms();
            belowCounts = counts == null ? java.util.Map.of() : java.util.Map.copyOf(counts);
            belowVerbCounts = both.verbs() == null ? java.util.Map.of() : java.util.Map.copyOf(both.verbs());
            belowCount.count(belowEntries());
            fill();
            queryBelow();
        }, failure -> {
            if (chosen == below) {
                belowBusy.setBusy(false);
                hint.setText("Begriffe der Unterseiten nicht lesbar: " + failure.getClass().getSimpleName());
            }
        });
    }

    /** Die Zahlen der Unterseiten: je Begriff und je Verb. */
    private record BelowCounts(java.util.Map<TermQueries.TermRef, Integer> terms,
                               java.util.Map<VerbRef, Integer> verbs) {
    }

    /** Ob der Abschnitt „Aus Unterseiten“ gerade zu sehen ist. */
    boolean showsBelow() {
        return below != null && belowPanel.isVisible();
    }

    /** Für Tests: die Pillen im Abschnitt „Aus Unterseiten“. */
    List<ResearchPillButton> belowChips() {
        List<ResearchPillButton> found = new ArrayList<>();
        for (java.awt.Component component : belowChips.getComponents()) {
            if (component instanceof ResearchPillButton pill) {
                found.add(pill);
            }
        }
        return found;
    }

    /** Für Tests: die Zahl am Abschnitt „Aus Unterseiten“. */
    int belowCount() {
        return belowCount.count();
    }

    /** Für Tests: die „+N“-Zusätze an den eigenen Begriffen als Steuerelemente. */
    List<com.aresstack.spaceharvester.app.ui.controls.CountChip> alsoBelowChips() {
        List<com.aresstack.spaceharvester.app.ui.controls.CountChip> found = new ArrayList<>();
        for (JPanel list : List.of(chips, identifierChips, verbChips)) {
            for (java.awt.Component component : cells(list)) {
                if (component instanceof com.aresstack.spaceharvester.app.ui.controls.CountChip chip) {
                    found.add(chip);
                }
            }
        }
        return found;
    }

    /** Für Tests: die „+N“-Zusätze an den eigenen Begriffen, in Reihenfolge. */
    List<String> alsoBelowTexts() {
        List<String> found = new ArrayList<>();
        for (JPanel list : List.of(chips, identifierChips, verbChips)) {
            for (java.awt.Component component : cells(list)) {
                if (component instanceof com.aresstack.spaceharvester.app.ui.controls.CountChip chip) {
                    found.add(chip.getText());
                }
            }
        }
        return found;
    }

    /** Die Bausteine einer Pillenliste; eine Zelle aus Pille und „+N“ zählt als ihre beiden Teile. */
    private static List<java.awt.Component> cells(JPanel list) {
        List<java.awt.Component> found = new ArrayList<>();
        for (java.awt.Component component : list.getComponents()) {
            if (component instanceof JPanel pair) {
                found.addAll(List.of(pair.getComponents()));
            } else {
                found.add(component);
            }
        }
        return found;
    }

    /** Die eigenen Begriffe der Seite, wie der Core sie zählt. */
    private java.util.Set<TermQueries.TermRef> ownRefs() {
        java.util.Set<TermQueries.TermRef> own = new java.util.HashSet<>();
        if (shown != null && shown.state() == PageAcronyms.State.CURRENT) {
            shown.acronyms().forEach(acronym -> own.add(ref(acronym)));
        }
        if (shownTerms != null && shownTerms.state() == PageTerms.State.CURRENT) {
            shownTerms.terms().forEach(entry -> own.add(new TermQueries.TermRef(entry.normalized(), entry.kind())));
        }
        return own;
    }

    /** Die eigenen Verben der Seite (Grundformen mit ermittelter Grundform). */
    private java.util.Set<String> ownVerbs() {
        java.util.Set<String> own = new java.util.HashSet<>();
        for (PageVerb verb : shownVerbs == null ? List.<PageVerb>of() : shownVerbs) {
            if (!verb.verb().fromText()) {
                own.add(verb.verb().lemma());
            }
        }
        return own;
    }

    /** „+N“ an einem eigenen Verb, wenn es auch auf N Unterseiten vorkommt; Klick zeigt diese Unterseiten. */
    private void addVerbPill(FindablePill pill, VerbQueries.Verb verb) {
        Below current = below;
        Integer count = current == null || verb.fromText() ? null : belowVerbCounts.get(VerbRef.of(verb));
        if (count == null || count == 0) {
            verbChips.add(pill);
            return;
        }
        String tip = "auch auf " + (count == 1 ? "1 Unterseite" : count + " Unterseiten");
        com.aresstack.spaceharvester.app.ui.controls.CountChip also =
                com.aresstack.spaceharvester.app.ui.controls.CountChip.choice("+" + count, Comic.MUTED).tips(tip, tip);
        also.setFocusable(false);
        also.addActionListener(e -> {
            also.setSelected(false);
            verbOccurrences(current.terms(), verb, also, () -> below == current);
        });
        JPanel pair = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pair.setOpaque(false);
        pair.add(pill);
        pair.add(javax.swing.Box.createHorizontalStrut(2));
        pair.add(also);
        verbChips.add(pair);
    }

    private static TermQueries.TermRef ref(PageAcronyms.Acronym acronym) {
        return new TermQueries.TermRef(com.aresstack.spaceharvester.application.terms.TermText.normalize(
                acronym.acronym()), TermKind.ACRONYM);
    }

    /** Zahl der Begriffe der Unterseiten, die nicht schon oben stehen. */
    private int belowEntries() {
        java.util.Set<TermQueries.TermRef> own = ownRefs();
        int count = 0;
        for (TermQueries.TermRef ref : belowCounts.keySet()) {
            if (!own.contains(ref)) {
                count++;
            }
        }
        java.util.Set<String> ownVerbs = ownVerbs();
        for (VerbRef verb : belowVerbCounts.keySet()) {
            // Wie die Liste: ohne Grundform immer, sonst nur, was oben nicht schon steht.
            if (verb.fromText() || !ownVerbs.contains(verb.lemma())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Legt eine eigene Pille aus; kommt der Begriff auch auf Unterseiten vor, steht „+N“ dicht daneben (2 px, UX), in
     * einer gemeinsamen Zelle, damit die Zugehörigkeit klar ist.
     */
    private void addPill(JPanel list, FindablePill pill, TermQueries.TermRef ref, String text) {
        com.aresstack.spaceharvester.app.ui.controls.CountChip also = alsoBelow(ref, text);
        if (also == null) {
            list.add(pill);
            return;
        }
        JPanel pair = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pair.setOpaque(false);
        pair.add(pill);
        pair.add(javax.swing.Box.createHorizontalStrut(2));
        pair.add(also);
        list.add(pair);
    }

    /** „+N“ für eine eigene Pille, wenn der Begriff auch auf N Unterseiten vorkommt; sonst {@code null}. */
    private com.aresstack.spaceharvester.app.ui.controls.CountChip alsoBelow(TermQueries.TermRef ref, String text) {
        Below current = below;
        if (current == null) {
            return null;
        }
        Integer count = belowCounts.get(ref);
        if (count == null || count == 0) {
            return null;
        }
        String tip = "auch auf " + (count == 1 ? "1 Unterseite" : count + " Unterseiten");
        com.aresstack.spaceharvester.app.ui.controls.CountChip also =
                com.aresstack.spaceharvester.app.ui.controls.CountChip.choice("+" + count, Comic.MUTED).tips(tip, tip);
        also.setFocusable(false);
        // Klick: dieselben Fundstellen wie unten, nur auf den Unterseiten; ein Klick dort öffnet die Seite.
        TermQueries.TermSummary term = new TermQueries.TermSummary(text, ref.normalized(), ref.kind(), 0, count, 1,
                List.of(text), List.of());
        also.addActionListener(e -> {
            also.setSelected(false);
            occurrences(current.terms(), term, also, () -> below == current);
        });
        return also;
    }

    /** Lädt den Abschnitt „Aus Unterseiten“ neu (Unterseiten oder Filter geändert); die eigenen Begriffe bleiben. */
    private void queryBelow() {
        Below current = below;
        belowRequest++;
        belowLoading = false;
        belowSections.clear();
        belowChips.removeAll();
        belowRelations.removeAll();
        if (current == null || selection != null) {
            belowBusy.setBusy(false);
            return;
        }
        String q = filter.getText() == null ? "" : filter.getText().trim();
        Selection terms = current.terms();
        java.util.Set<TermQueries.TermRef> own = ownRefs();
        java.util.function.Function<String, Consumer<List<TermQueries.TermSummary>>> appendTerms = of -> found -> {
            for (TermQueries.TermSummary term : found) {
                if (!own.contains(TermQueries.TermRef.of(term))) {
                    belowChips.add(ofKind(belowChip(terms, term), of));
                }
            }
            belowChips.revalidate();
            belowChips.repaint();
        };
        belowSections.add(new Section<>(ACRONYMS, terms.acronyms(q), appendTerms.apply(ACRONYMS)));
        belowSections.add(new Section<>(IDENTIFIERS, terms.identifiers(q), appendTerms.apply(IDENTIFIERS)));
        Pager<VerbQueries.Verb> belowVerbs = terms.verbs(q);
        if (belowVerbs != null) {
            java.util.Set<String> ownVerbs = ownVerbs();
            int mine = belowRequest;
            belowSections.add(new Section<>(VERBS, belowVerbs, found -> {
                for (VerbQueries.Verb verb : found) {
                    if (verb.fromText() || !ownVerbs.contains(verb.lemma())) {
                        FindablePill chip = textForm(chip(verb.lemma(), belowVerbTooltip(verb), List.of()), verb);
                        chip.setEnabled(true);
                        for (java.awt.event.ActionListener listener : chip.getActionListeners()) {
                            chip.removeActionListener(listener);
                        }
                        chip.addActionListener(e -> verbOccurrences(terms, verb, chip, () -> mine == belowRequest));
                        belowChips.add(ofKind(chip, VERBS));
                    }
                }
                belowChips.revalidate();
                belowChips.repaint();
            }));
        }
        belowSections.add(new Section<>(RELATIONS, terms.relations(q), found -> {
            for (Relation relation : found) {
                if (!ownRelationLines.contains(relation.line())) {
                    JLabel line = Comic.text(relation.line());
                    line.setAlignmentX(LEFT_ALIGNMENT);
                    belowRelations.add(line);
                }
            }
            belowRelations.revalidate();
            belowRelations.repaint();
        }));
        belowBusy.setBusy(true);
        moreBelow();
    }

    /** Lädt den nächsten Schritt des Abschnitts „Aus Unterseiten“; nie zwei zugleich. Wie {@link #more()}. */
    void moreBelow() {
        if (belowLoading || below == null || selection != null) {
            return;
        }
        for (Section<?> section : belowSections) {
            if (shows(section.kind) && section.pager.hasMore()) {
                loadBelow(section);
                return;
            }
        }
        belowBusy.setBusy(false);
    }

    private <T> void loadBelow(Section<T> section) {
        int mine = belowRequest;
        belowLoading = true;
        runner.run(() -> section.pager.next(step()), items -> {
            if (mine != belowRequest) {
                return;
            }
            belowLoading = false;
            // Teilergebnisse sofort; die Überlagerung bleibt nur, solange noch nachgeladen wird.
            section.append.accept(items);
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (mine == belowRequest && (nearEnd() || !scroll.isShowing())) {
                    moreBelow();
                } else if (mine == belowRequest) {
                    belowBusy.setBusy(false);
                }
            });
        }, failure -> {
            if (mine == belowRequest) {
                belowLoading = false;
                belowBusy.setBusy(false);
                hint.setText("Begriffe der Unterseiten nicht lesbar: " + failure.getClass().getSimpleName());
            }
        });
    }

    /** Ein Begriff der Unterseiten: Klick zeigt die Unterseiten mit Fundstellen; ein Klick dort öffnet die Seite. */
    private FindablePill belowChip(Selection terms, TermQueries.TermSummary term) {
        FindablePill chip = chip(selectionLabel(term), belowTooltip(term), List.of());
        chip.setEnabled(true);
        for (java.awt.event.ActionListener listener : chip.getActionListeners()) {
            chip.removeActionListener(listener);
        }
        int mine = belowRequest;
        chip.addActionListener(e -> occurrences(terms, term, chip, () -> mine == belowRequest));
        return chip;
    }
}
