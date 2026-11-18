package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ResearchPillButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Pager;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.Relation;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import com.aresstack.spaceharvester.application.terms.TermQueries;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
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
    /** So viele Begriffe bzw. Beziehungen lädt ein Schritt beim Scrollen. */
    static final int STEP = 120;
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

        /**
         * Die echten Gesamtzahlen der Auswahl aus dem Core (BG1b); {@code null}: noch nicht lieferbar, die Kennzahlen
         * bleiben auf „…“. Kann bei großen Auswahlen etwa eine Sekunde dauern, läuft daher nur im Hintergrund.
         */
        default Totals totals() throws Exception {
            return null;
        }
    }

    /**
     * Die Gesamtzahlen einer Auswahl: Akronyme, Bezeichner (Nomen und Kennungen), Beziehungen; dazu aus dem Bestand,
     * wie viele ihrer Seiten lokal gespeichert sind ({@code stored}) von allen ({@code pages}), {@code -1} unbekannt.
     */
    record Totals(int acronyms, int identifiers, int relations, int stored, int pages) {

        Totals(int acronyms, int identifiers, int relations) {
            this(acronyms, identifiers, relations, -1, -1);
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
        if (totals.stored() >= totals.pages()) {
            return "Begriffe aus " + number.format(totals.pages()) + pages;
        }
        return "Begriffe aus " + number.format(totals.stored()) + " von " + number.format(totals.pages()) + pages
                + " · übrige nicht lokal gespeichert";
    }

    /** Führt Arbeit im Hintergrund aus und meldet das Ergebnis auf dem EDT; in Tests sofort. */
    interface Runner {
        <T> void run(Callable<T> work, java.util.function.Consumer<T> onSuccess,
                     java.util.function.Consumer<Throwable> onFailure);
    }

    private final TabHeader head = new TabHeader("Begriffe dieser Seite");
    private final JLabel acronymCount = number();
    private final JLabel identifierCount = number();
    private final JLabel relationCount = number();
    private final JLabel completeness = badge();
    private final JLabel analysis = Comic.text("Analyse vom letzten Seitenabgleich");
    private final PlaceholderField filter = new PlaceholderField(12, "Begriffe filtern …");
    private final JLabel acronymTitle = Comic.text("Akronyme");
    private final JPanel chips = new JPanel(new WrapFlow());
    private final JLabel identifierTitle = Comic.text("Bezeichner");
    private final JPanel identifierChips = new JPanel(new WrapFlow());
    private final JLabel relationTitle = Comic.text("Beziehungen");
    private final JPanel relationLines = new JPanel();
    private final JLabel hint = Comic.muted(" ");
    private Consumer<List<Span>> onJump = spans -> { };
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
    /** Die gezeigte Auswahl; {@code null} zeigt eine einzelne Seite. */
    private Selection selection;
    private final List<Section> sections = new ArrayList<>();
    private int request;
    private boolean loading;
    private int loadedTerms;

    /** Eine Fundstelle im Lesetext (Zeichenpositionen wie im Core-Ergebnis). */
    record Span(int start, int end) {
    }

    TermsTab() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));

        head.search(com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(filter)).copy(this::copyText);

        JPanel counts = new JPanel(new GridLayout(1, 3, 12, 0));
        counts.setOpaque(false);
        counts.add(count(acronymCount, "Akronyme"));
        counts.add(count(identifierCount, "Bezeichner"));
        counts.add(count(relationCount, "Beziehungen"));

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
        JPanel acronyms = new JPanel(new BorderLayout(0, 4));
        acronyms.setOpaque(false);
        acronyms.add(acronymTitle, BorderLayout.NORTH);
        acronyms.add(chips, BorderLayout.CENTER);
        identifierTitle.setFont(Comic.semiBold(13f));
        JPanel identifiers = new JPanel(new BorderLayout(0, 4));
        identifiers.setOpaque(false);
        identifiers.add(identifierTitle, BorderLayout.NORTH);
        identifiers.add(identifierChips, BorderLayout.CENTER);
        relationTitle.setFont(Comic.semiBold(13f));
        relationLines.setOpaque(false);
        relationLines.setLayout(new BoxLayout(relationLines, BoxLayout.Y_AXIS));
        JPanel relations = new JPanel(new BorderLayout(0, 4));
        relations.setOpaque(false);
        relations.add(relationTitle, BorderLayout.NORTH);
        relations.add(relationLines, BorderLayout.CENTER);
        JPanel lists = new JPanel(new BorderLayout(0, 10));
        lists.setOpaque(false);
        lists.add(acronyms, BorderLayout.NORTH);
        lists.add(identifiers, BorderLayout.CENTER);
        lists.add(relations, BorderLayout.SOUTH);
        // Folgt der Breite des Sichtfensters: die Chips brechen um, statt einen waagerechten Balken zu erzeugen.
        JPanel column = new JPanel(new BorderLayout(0, 6));
        column.setOpaque(false);
        column.add(lists, BorderLayout.NORTH);
        column.add(hint, BorderLayout.CENTER);
        JPanel body = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout());
        body.add(column, BorderLayout.NORTH);

        add(top, BorderLayout.NORTH);
        scroll = Comic.scroll(body);
        add(scroll, BorderLayout.CENTER);
        // Auswahl: Nachladen, sobald das Ende der Liste in Sicht kommt (kein Zeitgeber).
        scroll.getVerticalScrollBar().getModel().addChangeListener(e -> {
            if (selection != null && nearEnd()) {
                more();
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
        head.status(stateText(current), stateColor(current));
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
    }

    /**
     * Zeigt die Beziehungen zwischen den Begriffen dieser Seite; {@code null} heißt: nicht abgefragt oder ohne
     * Begriffsbeziehungen im Core.
     */
    void showRelations(List<Relation> relations) {
        relationCount.setText(relations == null ? NOT_YET : String.valueOf(relations.size()));
        relationCount.setToolTipText(relations == null ? "Beziehungen liegen für diese Seite nicht vor"
                : "Bestätigte und vorgeschlagene Beziehungen zwischen den Begriffen dieser Seite");
        relationLines.removeAll();
        for (Relation relation : relations == null ? List.<Relation>of() : relations) {
            JLabel line = Comic.text(relation.line());
            line.setAlignmentX(LEFT_ALIGNMENT);
            relationLines.add(line);
        }
        relationTitle.setVisible(relations != null && !relations.isEmpty());
        relationLines.revalidate();
        relationLines.repaint();
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
        selection = Objects.requireNonNull(chosen, "chosen");
        shown = null;
        shownTerms = null;
        head.title("Begriffe in " + chosen.title());
        head.status(null, null);
        analysisRow.setVisible(false);
        totals(null, null, null);
        onStatus.accept(" ");
        query();
        count();
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
        count();
    }

    private void count() {
        Selection chosen = selection;
        counting = true;
        countAgain = false;
        // Die Kennzahlen kommen getrennt von den Listen; die Listen warten nie auf sie.
        runner.run(chosen::totals, counted -> {
            counted(chosen);
            if (selection == chosen && counted != null) {
                totals(counted.acronyms(), counted.identifiers(), counted.relations());
                String origin = coverage(counted);
                onStatus.accept(origin == null ? " " : origin);
            }
        }, failure -> {
            counted(chosen);
            if (selection == chosen) {
                String unreadable = "Gesamtzahl nicht lesbar: " + failure.getClass().getSimpleName();
                for (JLabel count : List.of(acronymCount, identifierCount, relationCount)) {
                    count.setToolTipText(unreadable);
                }
            }
        });
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
            count();
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
        relationLines.removeAll();
        acronymTitle.setVisible(false);
        identifierTitle.setVisible(false);
        relationTitle.setVisible(false);
        hint.setText(" ");
        sections.clear();
        sections.add(new Section<>(current.acronyms(q), terms -> append(chips, acronymTitle, terms)));
        sections.add(new Section<>(current.identifiers(q), terms -> append(identifierChips, identifierTitle, terms)));
        sections.add(new Section<>(current.relations(q), this::appendRelations));
        for (JPanel list : List.of(chips, identifierChips, relationLines)) {
            list.revalidate();
            list.repaint();
        }
        more();
    }

    /** Lädt den nächsten Schritt des ersten Abschnitts, der noch etwas hat; nie zwei Schritte zugleich. */
    void more() {
        if (loading || selection == null) {
            return;
        }
        for (Section<?> section : sections) {
            if (section.pager.hasMore()) {
                load(section);
                return;
            }
        }
        finished();
    }

    private <T> void load(Section<T> section) {
        int mine = request;
        loading = true;
        runner.run(() -> section.pager.next(STEP), items -> {
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
        boolean empty = loadedTerms == 0 && relationLines.getComponentCount() == 0;
        String q = filter.getText() == null ? "" : filter.getText().trim();
        hint.setText(!empty ? " " : q.isEmpty() ? NO_TERMS : NO_MATCH);
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
    private ResearchPillButton chip(TermQueries.TermSummary term) {
        ResearchPillButton chip = chip(selectionLabel(term), selectionTooltip(term), List.of());
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
        runner.run(() -> current.occurrences(term), found -> {
            if (mine != request) {
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
            if (mine == request) {
                hint.setText("Fundstellen nicht lesbar: " + failure.getClass().getSimpleName());
            }
        });
    }

    /** Ein Abschnitt der Auswahl mit eigenem Pager. */
    private static final class Section<T> {
        final Pager<T> pager;
        final Consumer<List<T>> append;

        Section(Pager<T> pager, Consumer<List<T>> append) {
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
            chips.add(chip(label(acronym), tooltip(acronym), acronym.occurrences().stream()
                    .map(occurrence -> new Span(occurrence.start(), occurrence.end())).toList()));
        }
        identifierChips.removeAll();
        int identifiers = 0;
        if (shownTerms != null && shownTerms.state() == PageTerms.State.CURRENT) {
            for (TermPayload.Entry entry : shownTerms.terms()) {
                if (entry.kind() == TermKind.ACRONYM || !query.isEmpty()
                        && !entry.term().toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                identifierChips.add(chip(entry.term(), tooltip(entry), entry.occurrences().stream()
                        .map(occurrence -> new Span(occurrence.start(), occurrence.end())).toList()));
                identifiers++;
            }
        }
        acronymTitle.setVisible(!visible.isEmpty());
        identifierTitle.setVisible(identifiers > 0);
        hint.setText(hint(shown, visible.size() + identifiers, !query.isEmpty()));
        for (JPanel list : List.of(chips, identifierChips)) {
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
        if (!relations.isEmpty()) {
            text.append("Beziehungen\n").append(String.join("\n", relations)).append("\n");
        }
        return text.toString().stripTrailing();
    }

    private ResearchPillButton chip(String label, String tooltip, List<Span> spans) {
        ResearchPillButton chip = new ResearchPillButton(label, 26, 26, 10);
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
            case STALE -> "Veraltet";
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
        PageAcronyms.State state = acronyms == null ? PageAcronyms.State.MISSING : acronyms.state();
        return switch (state) {
            case MISSING -> "Noch keine Analyse für diese Seite. Sie entsteht nach dem nächsten Abgleich oder über "
                    + "„Analyse neu berechnen“ unter Text.";
            case STALE -> "Die Analyse passt nicht mehr zum angezeigten Text; „Analyse neu berechnen“ unter Text "
                    + "zeigt die Begriffe wieder.";
            case FAILED -> "Die letzte Analyse ist fehlgeschlagen; „Analyse neu berechnen“ unter Text startet sie neu.";
            case CURRENT -> visible == 0 && !filtered ? "Auf dieser Seite wurden keine Begriffe erkannt."
                    : visible == 0 && filtered ? "Kein Begriff passt zum Filter." : " ";
        };
    }

    private static JPanel count(JLabel number, String caption) {
        JPanel cell = new JPanel(new BorderLayout(0, 2));
        cell.setOpaque(false);
        cell.add(number, BorderLayout.CENTER);
        JLabel label = Comic.muted(caption);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        cell.add(label, BorderLayout.SOUTH);
        return cell;
    }

    private static JLabel number() {
        JLabel label = new JLabel(NOT_YET, SwingConstants.CENTER);
        label.setFont(Comic.semiBold(22f));
        label.setForeground(Comic.INK);
        return label;
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

    List<String> counts() {
        return List.of(acronymCount.getText(), identifierCount.getText(), relationCount.getText());
    }

    List<ResearchPillButton> chips() {
        List<ResearchPillButton> list = new ArrayList<>();
        for (java.awt.Component component : chips.getComponents()) {
            list.add((ResearchPillButton) component);
        }
        return list;
    }

    List<ResearchPillButton> identifierChips() {
        List<ResearchPillButton> list = new ArrayList<>();
        for (java.awt.Component component : identifierChips.getComponents()) {
            list.add((ResearchPillButton) component);
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
}
