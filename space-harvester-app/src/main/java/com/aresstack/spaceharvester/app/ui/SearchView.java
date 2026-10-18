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
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Globale Suche als reine Schlagwortsuche: Mit Suchtext sucht der Core live in den Quellen über Titel und Inhalt, lädt
 * fehlende Treffer nach und liefert sie (ADR 0010 Punkt 9); eine Synchronisierung ist nie Voraussetzung. Optional nach
 * Quelle und Space gefiltert, sortiert nach Titel oder Änderung. Nur Bedienung und Darstellung: Suche, Sortierung,
 * Rechte und Cursor kommen aus dem Core ({@code PageSearch}, #114), ohne gewählte Quelle als eine globale Suche über
 * alle Quellen. Unter der Suchzeile steht je Quelle ein Häkchen: „Alle“ (Vorgabe) sucht ohne {@code sourceIds}, eine
 * einzelne Quelle mit {@code sourceId}, mehrere mit {@code sourceIds} (ADR 0009). Die Space-Auswahl bietet die Spaces
 * der angehakten Quellen an. Die semantische Suche ist nur als deaktiviertes Häkchen vorgesehen.
 * „Weitere laden“ folgt dem Cursor des Core; haben sich die Treffer inzwischen geändert, beginnt dieselbe Suche von
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
    /** Platzhalter für die spätere semantische Suche: sichtbar, aber deaktiviert und nie gesetzt. */
    private final JCheckBox semantic = checkBox("Semantische Suche (experimentell)", "Noch nicht verfügbar");
    private final JPanel sourcesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private final JCheckBox all = checkBox("Alle", "Alle freigegebenen Quellen durchsuchen (ohne sourceIds)");
    private final Map<String, JCheckBox> boxes = new LinkedHashMap<>();
    private final ComposerButton run = Comic.primary("Suchen", "In den angehakten Quellen suchen (Titel und Inhalt)");
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
    /** Angehakte Quellen; leer für alle. */
    private final Set<String> chosen = new TreeSet<>();
    private boolean updating;

    public SearchView(AppContext context) {
        this.context = context;
        this.picker = new SourcePicker(context, true, true);
        root.setBackground(Comic.SURFACE);
        ButtonGroup sorts = new ButtonGroup();
        sorts.add(byTitle);
        sorts.add(byModified);
        byTitle.setSelected(true);
        query.setToolTipText("Wörter in Titel und Inhalt; die Quellen werden live durchsucht");
        query.addActionListener(e -> search());
        run.addActionListener(e -> search());
        more.addActionListener(e -> loadMore());
        more.setEnabled(false);
        semantic.setSelected(false);
        semantic.setEnabled(false);
        all.addActionListener(e -> restrictTo(List.of()));
        sourcesRow.setOpaque(false);
        context.onChange(ignored -> refreshSources());
        refreshSources();
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

        JPanel bar = Comic.row(Comic.caption("Suche"), query, Comic.caption("Space"), picker.spaceBox(), byTitle,
                byModified, run);
        JPanel lines = new JPanel(new BorderLayout(0, 6));
        lines.setOpaque(false);
        lines.add(bar, BorderLayout.NORTH);
        JPanel scope = new JPanel(new BorderLayout(8, 0));
        scope.setOpaque(false);
        scope.add(Comic.caption("Quellen"), BorderLayout.WEST);
        scope.add(sourcesRow, BorderLayout.CENTER);
        lines.add(scope, BorderLayout.CENTER);
        JPanel experimental = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        experimental.setOpaque(false);
        experimental.add(semantic);
        experimental.add(Comic.muted("noch nicht verfügbar, KI-Dienst unter Einstellungen → KI einrichten"));
        lines.add(experimental, BorderLayout.SOUTH);
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(lines, BorderLayout.NORTH);
        head.add(Comic.note("Schlagwortsuche über Titel und Inhalt, direkt in den angehakten Quellen. "
                + "Doppelklick öffnet die Seite unter Inhalte."), BorderLayout.CENTER);
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

    /** Beschränkt die Suche auf diese Quellen; leer sucht in allen. */
    void restrictTo(Collection<String> sourceIds) {
        chosen.clear();
        chosen.addAll(sourceIds);
        chosen.retainAll(boxes.keySet());
        updating = true;
        all.setSelected(chosen.isEmpty());
        boxes.forEach((id, box) -> box.setSelected(chosen.contains(id)));
        updating = false;
        picker.spaceScope(chosen);
    }

    /** Die angehakten Quellen; leer heißt alle. */
    Set<String> chosenSources() {
        return Set.copyOf(chosen);
    }

    /** Ein Häkchen je laufender Quelle; entfernte Quellen fallen aus der Auswahl. */
    private void refreshSources() {
        AppRuntime runtime = context.runtime();
        List<HarvestSource> sources = runtime == null ? List.of() : runtime.workspace().sources();
        boxes.clear();
        sourcesRow.removeAll();
        sourcesRow.add(all);
        for (HarvestSource source : sources) {
            JCheckBox box = checkBox(source.title() == null || source.title().isBlank() ? source.id() : source.title(),
                    "Nur angehakte Quellen durchsuchen (" + source.id() + ")");
            box.addActionListener(e -> {
                if (updating) {
                    return;
                }
                Set<String> next = new TreeSet<>(chosen);
                if (box.isSelected()) {
                    next.add(source.id());
                } else {
                    next.remove(source.id());
                }
                restrictTo(next);
            });
            boxes.put(source.id(), box);
            sourcesRow.add(box);
        }
        restrictTo(new ArrayList<>(chosen));
        sourcesRow.revalidate();
        sourcesRow.repaint();
    }

    private static JCheckBox checkBox(String text, String tooltip) {
        JCheckBox box = new JCheckBox(text);
        box.setOpaque(false);
        box.setFont(Comic.regular(13f));
        box.setForeground(Comic.INK);
        box.setToolTipText(tooltip);
        return box;
    }

    private void search() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            feedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Einstellungen.");
            return;
        }
        String text = query.getText().trim();
        // Eine angehakte Quelle sucht wie die Suche unter der Quelle (sourceId), mehrere mit sourceIds.
        String sourceId = chosen.size() == 1 ? chosen.iterator().next() : null;
        String spaceKey = picker.spaceKey();
        SearchSort sort = byModified.isSelected() ? SearchSort.MODIFIED_DESC : SearchSort.TITLE;
        if (picker.allSourceIds().isEmpty()) {
            feedback.warning("Keine Quelle eingerichtet", "Wissensquellen werden in der Konfigurationsdatei angelegt.");
            return;
        }
        if (text.isEmpty()) {
            // Ohne Schlagwort würde nur der Index befragt, keine Quelle; das wäre keine Schlagwortsuche.
            feedback.warning("Suchtext fehlt", "Ein oder mehrere Schlagwörter eingeben.");
            return;
        }
        List<String> spaces = spaceKey.isEmpty() ? List.of() : List.of(spaceKey);
        List<String> only = chosen.size() > 1 ? List.copyOf(chosen) : List.of();
        int id = ++request;
        run.setEnabled(false);
        more.setEnabled(false);
        feedback.working("Suche läuft …");
        Background.run(() -> {
            PageSearchQuery q = PageSearchQuery.of(sourceId, only, spaces, text, null, null,
                    null, null, null, sort);
            SearchResults results = runtime.workspace().search(q, null, LIMIT);
            return new Outcome(q, hits(results), results.nextCursor());
        }, outcome -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            model.set(outcome.hits());
            show(outcome);
            if (outcome.hits().isEmpty()) {
                feedback.info("Keine Treffer", "Keine Quelle kennt eine passende Seite, die du lesen darfst.");
            } else {
                feedback.clear();
            }
        }, error -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            model.set(List.of());
            show(new Outcome(null, List.of(), null));
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
                return new Page(new Outcome(q, hits(results), results.nextCursor()), false);
            } catch (CacheGenerationChangedException changed) {
                SearchResults results = runtime.workspace().search(q, null, LIMIT);
                return new Page(new Outcome(q, hits(results), results.nextCursor()), true);
            }
        }, page -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            if (page.restarted()) {
                model.set(page.outcome().hits());
                show(page.outcome());
                feedback.info("Ergebnisse geändert", "Die Treffer haben sich inzwischen geändert; die Suche beginnt von vorn.");
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
            show(new Outcome(null, List.of(), null));
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
        return results.hits().stream().map(Hit::of).toList();
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
    private record Outcome(PageSearchQuery query, List<Hit> hits, String nextCursor) {
    }

    /** Ein Stapel von „Weitere laden“; {@code restarted}: die erste Seite nach geändertem Index. */
    private record Page(Outcome outcome, boolean restarted) {
    }

    /** Eine Zeile; die Quelle stammt aus dem Treffer selbst, nie aus der Auswahl. */
    record Hit(String sourceId, String pageId, String title, String spaceKey, Instant modifiedAt) {

        static Hit of(SearchHit hit) {
            PageMetadata m = hit.page().header().metadata();
            return new Hit(hit.page().header().key().sourceId(), m.pageId(), m.title(), m.partitionKey(),
                    m.modifiedAt());
        }
    }

    private static final class HitsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Quelle", "Space", "Seiten-ID", "Geändert"};
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
                default -> Texts.time(hit.modifiedAt());
            };
        }
    }
}
