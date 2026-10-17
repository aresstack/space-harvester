package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.query.CacheGenerationChangedException;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Globale Suche über den lokalen Bestand: Titelwörter, optional nach Quelle und Space gefiltert, sortiert nach Titel,
 * Änderung oder Relevanz (semantisch nur mit eingerichtetem Modell). Nur Bedienung und Darstellung: Suche, Sortierung,
 * Rechte und Cursor kommen aus dem Core ({@code PageSearch}, {@code RelevanceSearch}, #114), ohne gewählte Quelle als
 * eine globale Suche über alle Quellen, mit gewählter Quelle mit demselben Anwendungsfall und {@code sourceId}.
 * Unter „Alle Quellen“ beschränkt „Quellen …“ die globale Suche auf ausgewählte Quellen ({@code sourceIds}).
 * „Weitere laden“ folgt dem Cursor des Core; hat ein Abgleich den Index inzwischen geändert, beginnt dieselbe Suche von
 * vorn. Nur die jeweils letzte Anfrage darf die Liste ändern.
 */
public final class SearchView implements View {

    private static final int LIMIT = PageSearch.MAX_LIMIT;

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final JTextField query = Comic.field(30);
    private final SourcePicker picker;
    private final ComposerToggleButton byTitle = toggle("Titel", "Nach Titel sortieren");
    private final ComposerToggleButton byModified = toggle("Neueste", "Zuletzt geänderte zuerst");
    private final ComposerToggleButton byRelevance = toggle("Relevanz", "Nach Ähnlichkeit zur Anfrage");
    private final ComposerButton chooser = Comic.button("Quellen: alle", "Die Suche über alle Quellen auf einzelne "
            + "Quellen beschränken");
    private final ComposerButton run = Comic.primary("Suchen", "Im lokalen Bestand suchen");
    private final ComposerButton more = Comic.button("Weitere laden", "Die nächsten Treffer derselben Suche laden");
    private final HitsModel model = new HitsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel status = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    /** Anfrage und Cursor der zuletzt angezeigten Titelsuche, für „Weitere laden“. */
    private PageSearchQuery lastQuery;
    private String nextCursor;
    /** Zählt Suchen und „Weitere laden“; Ergebnisse einer überholten Anfrage werden verworfen. */
    private int request;
    /** Quellen, auf die „Alle Quellen“ beschränkt ist; leer für alle. */
    private final Set<String> chosen = new TreeSet<>();

    public SearchView(AppContext context) {
        this.context = context;
        this.picker = new SourcePicker(context, true, true);
        root.setBackground(Comic.SURFACE);
        ButtonGroup sorts = new ButtonGroup();
        sorts.add(byTitle);
        sorts.add(byModified);
        sorts.add(byRelevance);
        byTitle.setSelected(true);
        query.setToolTipText("Wörter im Titel; bei Relevanz auch freier Text");
        query.addActionListener(e -> search());
        run.addActionListener(e -> search());
        more.addActionListener(e -> loadMore());
        more.setEnabled(false);
        chooser.addActionListener(e -> chooseSources());
        picker.sourceBox().addActionListener(e -> chooser.setEnabled(picker.sourceId() == null));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.getSelectedRow();
                if (e.getClickCount() == 2 && row >= 0) {
                    Hit hit = model.rows.get(table.convertRowIndexToModel(row));
                    context.navigator().openPage(hit.sourceId(), hit.pageId());
                }
            }
        });

        JPanel bar = Comic.row(Comic.caption("Suche"), query, Comic.caption("Quelle"), picker.sourceBox(), chooser,
                Comic.caption("Space"), picker.spaceBox(), byTitle, byModified, byRelevance, run);
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(bar, BorderLayout.NORTH);
        head.add(Comic.note("Gesucht wird nur im lokalen Bestand, also in Seiten, die ein Abgleich oder ein Seitenabruf "
                + "gespeichert hat. Doppelklick öffnet die Seite unter Inhalte."), BorderLayout.CENTER);
        head.add(feedback, BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(Comic.scroll(table), BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout(12, 0));
        foot.setOpaque(false);
        foot.add(status, BorderLayout.CENTER);
        foot.add(more, BorderLayout.EAST);
        body.add(foot, BorderLayout.SOUTH);
        root.add(Comic.plate("Globale Suche", Comic.BLUE, head), BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        query.requestFocusInWindow();
    }

    /** Sucht nach einem Text, z. B. aus dem Suchfeld der Kopfleiste. */
    public void search(String text) {
        query.setText(text);
        search();
    }

    /** Beschränkt „Alle Quellen“ auf diese Quellen; leer hebt die Beschränkung auf. */
    void restrictTo(Collection<String> sourceIds) {
        chosen.clear();
        chosen.addAll(sourceIds);
        chooser.setText(chosen.isEmpty() ? "Quellen: alle" : "Quellen: " + chosen.size());
    }

    private void chooseSources() {
        AppRuntime runtime = context.runtime();
        List<HarvestSource> sources = runtime == null ? List.of() : runtime.workspace().sources();
        // Entfernte Quellen fallen aus der Auswahl.
        chosen.retainAll(sources.stream().map(HarvestSource::id).toList());
        JPopupMenu menu = new JPopupMenu();
        for (HarvestSource source : sources) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(source.title() == null || source.title().isBlank()
                    ? source.id() : source.title(), chosen.contains(source.id()));
            item.addActionListener(e -> {
                Set<String> next = new TreeSet<>(chosen);
                if (item.isSelected()) {
                    next.add(source.id());
                } else {
                    next.remove(source.id());
                }
                restrictTo(next);
            });
            menu.add(item);
        }
        restrictTo(chosen);
        menu.show(chooser, 0, chooser.getHeight());
    }

    private void search() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            feedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Einstellungen.");
            return;
        }
        String text = query.getText().trim();
        String sourceId = picker.sourceId();
        String spaceKey = picker.spaceKey();
        boolean relevance = byRelevance.isSelected();
        SearchSort sort = byModified.isSelected() ? SearchSort.MODIFIED_DESC : SearchSort.TITLE;
        if (picker.allSourceIds().isEmpty()) {
            feedback.warning("Keine Quelle eingerichtet", "Wissensquellen werden in der Konfigurationsdatei angelegt.");
            return;
        }
        if (relevance && text.isEmpty()) {
            feedback.warning("Suchtext fehlt", "Die Relevanzsuche braucht einen Suchtext.");
            return;
        }
        List<String> spaces = spaceKey.isEmpty() ? List.of() : List.of(spaceKey);
        List<String> only = sourceId == null ? List.copyOf(chosen) : List.of();
        int id = ++request;
        run.setEnabled(false);
        more.setEnabled(false);
        feedback.working("Suche läuft …");
        Background.run(() -> {
            if (relevance) {
                // Ein Block über alle gewählten Quellen; ohne Modell nur Titeltreffer.
                boolean semantic = runtime.workspace().semanticSearch();
                PageSearchQuery q = PageSearchQuery.of(sourceId, only, spaces, semantic ? null : text, null, null,
                        null, null, null, SearchSort.TITLE);
                RelevanceSearch.RelevanceResults results = runtime.workspace().relevance(q, text, LIMIT);
                List<Hit> hits = results.hits().stream().map(ranked -> Hit.of(ranked.hit(), ranked.relevance()))
                        .toList();
                return new Outcome(null, hits, null, semantic ? null
                        : "Kein Embedding-Modell eingerichtet: sortiert nach Titeltreffern.");
            }
            PageSearchQuery q = PageSearchQuery.of(sourceId, only, spaces, text.isEmpty() ? null : text, null, null,
                    null, null, null, sort);
            SearchResults results = runtime.workspace().search(q, null, LIMIT);
            return new Outcome(q, hits(results), results.nextCursor(), null);
        }, outcome -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            model.set(outcome.hits());
            show(outcome);
            if (outcome.semanticNote() != null) {
                feedback.info("Relevanz ohne Modell", outcome.semanticNote());
            } else if (outcome.hits().isEmpty()) {
                feedback.info("Keine Treffer", "Im Bestand passt keine Seite. Vielleicht ist der Space noch nicht "
                        + "abgeglichen.");
            } else {
                feedback.clear();
            }
        }, error -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            model.set(List.of());
            show(new Outcome(null, List.of(), null, null));
            feedback.problem(error);
        });
    }

    /**
     * Der nächste Stapel derselben Suche, angehängt an die bisherigen Treffer. Lehnt der Core den Cursor ab, weil sich
     * der Index geändert hat (409 {@code cache-generation-changed}), ersetzt die erste Seite derselben Anfrage die Liste.
     */
    private void loadMore() {
        AppRuntime runtime = context.runtime();
        PageSearchQuery q = lastQuery;
        String cursor = nextCursor;
        if (runtime == null || q == null || cursor == null) {
            return;
        }
        int id = ++request;
        run.setEnabled(false);
        more.setEnabled(false);
        feedback.working("Weitere Treffer werden geladen …");
        Background.run(() -> {
            try {
                SearchResults results = runtime.workspace().search(q, cursor, LIMIT);
                return new Page(new Outcome(q, hits(results), results.nextCursor(), null), false);
            } catch (CacheGenerationChangedException changed) {
                SearchResults results = runtime.workspace().search(q, null, LIMIT);
                return new Page(new Outcome(q, hits(results), results.nextCursor(), null), true);
            }
        }, page -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            if (page.restarted()) {
                model.set(page.outcome().hits());
                show(page.outcome());
                feedback.info("Bestand geändert", "Ein Abgleich hat den Index geändert; die Suche beginnt von vorn.");
            } else {
                model.add(page.outcome().hits());
                show(page.outcome());
                feedback.clear();
            }
        }, error -> {
            if (id != request) {
                return;
            }
            // Die bisherigen Treffer bleiben stehen; eine neue Suche beginnt von vorn.
            run.setEnabled(true);
            show(new Outcome(null, List.of(), null, null));
            feedback.problem(error);
        });
    }

    private void show(Outcome outcome) {
        lastQuery = outcome.query();
        nextCursor = outcome.nextCursor();
        more.setEnabled(nextCursor != null);
        status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer")
                + (nextCursor != null ? " · weitere vorhanden" : ""));
    }

    private static List<Hit> hits(SearchResults results) {
        return results.hits().stream().map(hit -> Hit.of(hit, null)).toList();
    }

    private static ComposerToggleButton toggle(String text, String tooltip) {
        ComposerToggleButton button = new ComposerToggleButton(null, text, tooltip);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        return button;
    }

    /**
     * @param query      Anfrage der Titelsuche für „Weitere laden“ oder {@code null}
     * @param nextCursor Cursor des Core oder {@code null}, wenn alle Treffer geladen sind
     */
    private record Outcome(PageSearchQuery query, List<Hit> hits, String nextCursor, String semanticNote) {
    }

    /** Ein Stapel von „Weitere laden“; {@code restarted}: die erste Seite nach geändertem Index. */
    private record Page(Outcome outcome, boolean restarted) {
    }

    /** Eine Zeile; die Quelle stammt aus dem Treffer selbst, nie aus der Auswahl. */
    record Hit(String sourceId, String pageId, String title, String spaceKey, Instant modifiedAt, String state,
               Double similarity, String freshness) {

        static Hit of(SearchHit hit, RelevanceSearch.Relevance relevance) {
            PageMetadata m = hit.page().header().metadata();
            return new Hit(hit.page().header().key().sourceId(), m.pageId(), m.title(), m.partitionKey(),
                    m.modifiedAt(),
                    Texts.cacheState(hit.generation().state()),
                    relevance == null ? null : relevance.similarity(),
                    relevance == null ? null : relevance.freshness() == RelevanceSearch.Freshness.CURRENT
                            ? "aktuell" : "veraltet");
        }
    }

    private static final class HitsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Quelle", "Space", "Seiten-ID", "Geändert", "Bestand",
                "Ähnlichkeit"};
        private List<Hit> rows = new ArrayList<>();

        void set(List<Hit> hits) {
            rows = new ArrayList<>(hits);
            fireTableDataChanged();
        }

        void add(List<Hit> hits) {
            int first = rows.size();
            rows.addAll(hits);
            if (!hits.isEmpty()) {
                fireTableRowsInserted(first, rows.size() - 1);
            }
        }

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
        public Object getValueAt(int row, int column) {
            Hit hit = rows.get(row);
            return switch (column) {
                case 0 -> hit.title();
                case 1 -> hit.sourceId();
                case 2 -> hit.spaceKey();
                case 3 -> hit.pageId();
                case 4 -> Texts.time(hit.modifiedAt());
                case 5 -> hit.state();
                default -> hit.similarity() == null ? "–"
                        : String.format(Locale.GERMANY, "%.3f · %s", hit.similarity(), hit.freshness());
            };
        }
    }
}
