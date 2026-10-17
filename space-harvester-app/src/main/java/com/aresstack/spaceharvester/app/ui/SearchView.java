package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchResults;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.sources.PageMetadata;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Globale Suche über den lokalen Bestand: Titelwörter, optional nach Quelle und Space gefiltert, sortiert nach Titel,
 * Änderung oder Relevanz (semantisch nur mit eingerichtetem Modell). Ohne gewählte Quelle durchsucht sie jede Quelle
 * einzeln mit derselben Anfrage; Quellen, die die Suche ablehnen, nennt sie, statt sie still zu überspringen.
 */
public final class SearchView implements View {

    private static final int LIMIT = 100;

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final JTextField query = Comic.field(30);
    private final SourcePicker picker;
    private final ComposerToggleButton byTitle = toggle("Titel", "Nach Titel sortieren");
    private final ComposerToggleButton byModified = toggle("Neueste", "Zuletzt geänderte zuerst");
    private final ComposerToggleButton byRelevance = toggle("Relevanz", "Nach Ähnlichkeit zur Anfrage");
    private final ComposerButton run = Comic.primary("Suchen", "Im lokalen Bestand suchen");
    private final HitsModel model = new HitsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel status = Comic.muted(" ");
    private final Feedback feedback = new Feedback();

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

        JPanel bar = Comic.row(Comic.caption("Suche"), query, Comic.caption("Quelle"), picker.sourceBox(),
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
        body.add(status, BorderLayout.SOUTH);
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

    private void search() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            feedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Einstellungen.");
            return;
        }
        String text = query.getText().trim();
        String sourceId = picker.sourceId();
        List<String> sources = sourceId == null ? picker.allSourceIds() : List.of(sourceId);
        String spaceKey = picker.spaceKey();
        boolean relevance = byRelevance.isSelected();
        SearchSort sort = byModified.isSelected() ? SearchSort.MODIFIED_DESC : SearchSort.TITLE;
        if (sources.isEmpty()) {
            feedback.warning("Keine Quelle eingerichtet", "Wissensquellen werden in der Konfigurationsdatei angelegt.");
            return;
        }
        if (relevance && text.isEmpty()) {
            feedback.warning("Suchtext fehlt", "Die Relevanzsuche braucht einen Suchtext.");
            return;
        }
        run.setEnabled(false);
        feedback.working("Suche läuft …");
        Background.run(() -> {
            List<Hit> hits = new ArrayList<>();
            List<String> refused = new ArrayList<>();
            boolean more = false;
            String semanticNote = null;
            for (String id : sources) {
                try {
                    if (relevance) {
                        boolean semantic = runtime.workspace().semanticSearch();
                        PageSearchQuery q = PageSearchQuery.of(id, spaceKey.isEmpty() ? List.of() : List.of(spaceKey),
                                semantic ? null : text, null, null, null, null, null, SearchSort.TITLE);
                        RelevanceSearch.RelevanceResults results = runtime.workspace().relevance(q, text, LIMIT);
                        if (!semantic) {
                            semanticNote = "Kein Embedding-Modell eingerichtet: sortiert nach Titeltreffern.";
                        }
                        for (RelevanceSearch.RankedHit ranked : results.hits()) {
                            hits.add(Hit.of(id, ranked.hit(), ranked.relevance()));
                        }
                    } else {
                        PageSearchQuery q = PageSearchQuery.of(id, spaceKey.isEmpty() ? List.of() : List.of(spaceKey),
                                text.isEmpty() ? null : text, null, null, null, null, null, sort);
                        SearchResults results = runtime.workspace().search(q, null, LIMIT);
                        for (SearchHit hit : results.hits()) {
                            hits.add(Hit.of(id, hit, null));
                        }
                        more |= !results.complete();
                    }
                } catch (RuntimeException e) {
                    if (sources.size() == 1) {
                        throw e;
                    }
                    refused.add(id + " (" + Problems.describe(e).title() + ")");
                }
            }
            if (sources.size() > 1) {
                hits.sort(relevance ? Hit.BY_SIMILARITY : sort == SearchSort.MODIFIED_DESC ? Hit.BY_MODIFIED
                        : Hit.BY_TITLE);
            }
            return new Outcome(hits, refused, more, semanticNote);
        }, outcome -> {
            run.setEnabled(true);
            model.set(outcome.hits());
            status.setText(Texts.count(outcome.hits().size(), "Treffer", "Treffer")
                    + (outcome.more() ? " · weitere vorhanden, Suche eingrenzen" : ""));
            if (!outcome.refused().isEmpty()) {
                feedback.warning("Nicht alle Quellen durchsucht", String.join(", ", outcome.refused()));
            } else if (outcome.semanticNote() != null) {
                feedback.info("Relevanz ohne Modell", outcome.semanticNote());
            } else if (outcome.hits().isEmpty()) {
                feedback.info("Keine Treffer", "Im Bestand passt keine Seite. Vielleicht ist der Space noch nicht "
                        + "abgeglichen.");
            } else {
                feedback.clear();
            }
        }, error -> {
            run.setEnabled(true);
            model.set(List.of());
            status.setText(" ");
            feedback.problem(error);
        });
    }

    private static ComposerToggleButton toggle(String text, String tooltip) {
        ComposerToggleButton button = new ComposerToggleButton(null, text, tooltip);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        return button;
    }

    private record Outcome(List<Hit> hits, List<String> refused, boolean more, String semanticNote) {
    }

    record Hit(String sourceId, String pageId, String title, String spaceKey, Instant modifiedAt, String state,
               Double similarity, String freshness) {

        static final java.util.Comparator<Hit> BY_TITLE = java.util.Comparator
                .comparing((Hit h) -> h.title().toLowerCase(Locale.ROOT)).thenComparing(Hit::pageId);
        static final java.util.Comparator<Hit> BY_MODIFIED = java.util.Comparator
                .comparing(Hit::modifiedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(Hit::pageId);
        static final java.util.Comparator<Hit> BY_SIMILARITY = java.util.Comparator
                .comparing(Hit::similarity, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(Hit::pageId);

        static Hit of(String sourceId, SearchHit hit, RelevanceSearch.Relevance relevance) {
            PageMetadata m = hit.page().header().metadata();
            return new Hit(sourceId, m.pageId(), m.title(), m.partitionKey(), m.modifiedAt(),
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
