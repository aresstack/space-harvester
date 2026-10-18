package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.ranking.RelevanceSearch;
import com.aresstack.spaceharvester.application.ranking.Reranking;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;

import javax.swing.BorderFactory;
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
 * Globale Suche als reine Schlagwortsuche ({@code KeywordSearch} des Core, ADR 0010 Punkt 9): Jede angehakte Quelle
 * sucht selbst über Titel und Inhalt (Confluence-Websuche), jeder Treffer ist live auf Freigabe geprüft und zeigt
 * Titel, Quelle, Space und den Textauszug der Quelle, in deren Rangfolge. Eine Synchronisierung ist nie
 * Voraussetzung. Unter der Liste steht je Quelle, ob sie keine eigene Suche kennt oder nicht erreichbar war, statt
 * still „keine Treffer“ zu melden; ein unvollständiges Ergebnis ist so gekennzeichnet.
 * Unter der Suchzeile steht je Quelle ein Häkchen: „Alle“ (Vorgabe) sucht ohne {@code sourceIds}, eine einzelne Quelle
 * mit {@code sourceId}, mehrere mit {@code sourceIds} (ADR 0009). Die Space-Auswahl bietet die Spaces der angehakten
 * Quellen an. Das Häkchen „Semantische Suche (experimentell)“ ist die Relevanzsuche des Core ({@code sort=relevance},
 * ADR 0011) über die bereits eingebetteten Seiten im Cache; es lässt sich erst setzen, wenn ein KI-Dienst mit
 * Embedding-Modell und Dimension eingerichtet ist, und liefert einen Block ohne „Weitere laden“.
 * „Weitere laden“ folgt dem Cursor des Core, der nur für dieselbe Anfrage gilt. Nur die jeweils letzte Anfrage darf die
 * Liste ändern.
 */
public final class SearchView implements View {

    private static final int LIMIT = KeywordSearch.MAX_LIMIT;
    static final String UNAVAILABLE = "noch nicht verfügbar, KI-Dienst unter Einstellungen → KI einrichten";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final JTextField query = Comic.field(30);
    private final SourcePicker picker;
    /** Relevanzsuche ({@code sort=relevance}); nur mit eingerichtetem Embedding-Dienst aktivierbar. */
    final JCheckBox semantic = checkBox("Semantische Suche (experimentell)",
            "Sucht nach Bedeutung in den bereits eingebetteten Seiten freigegebener Quellen");
    private final JLabel semanticHint = Comic.muted(UNAVAILABLE);
    private final JPanel sourcesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    private final JCheckBox all = checkBox("Alle", "Alle freigegebenen Quellen durchsuchen (ohne sourceIds)");
    private final Map<String, JCheckBox> boxes = new LinkedHashMap<>();
    private final ComposerButton run = Comic.primary("Suchen", "In den angehakten Quellen suchen (Titel und Inhalt)");
    private final ComposerButton more = Comic.button("Weitere laden", "Die nächsten Treffer derselben Suche laden");
    private final HitsModel model = new HitsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel status = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    /** Anfrage und Cursor der zuletzt angezeigten Suche, für „Weitere laden“; der Cursor gilt nur für sie. */
    private KeywordSearch.Query lastQuery;
    /** Ob ein Stapel derselben Suche unvollständig war; bleibt bis zur nächsten Suche. */
    private boolean incomplete;
    /** Zustand je Quelle aus dem ersten Stapel, ergänzt um spätere Ausfälle. */
    private final Map<String, KeywordSearch.SourceState> states = new LinkedHashMap<>();
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

        JPanel bar = Comic.row(Comic.caption("Suche"), query, Comic.caption("Space"), picker.spaceBox(), run);
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
        experimental.add(semanticHint);
        lines.add(experimental, BorderLayout.SOUTH);
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(lines, BorderLayout.NORTH);
        head.add(Comic.note("Schlagwortsuche über Titel und Inhalt, direkt in den angehakten Quellen. "
                + "Doppelklick öffnet die Seite unter Inhalte. Eine Seitenadresse oder ein Lesezeichen "
                + "(space-harvester://…) im Suchfeld öffnet die Seite direkt."), BorderLayout.CENTER);
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
        boolean available = runtime != null && runtime.workspace().semanticSearch();
        semantic.setEnabled(available);
        if (!available) {
            semantic.setSelected(false);
        }
        semanticHint.setText(available ? "Embeddings über den KI-Dienst; nur Seiten freigegebener Quellen, die "
                + "schon im Cache liegen" : UNAVAILABLE);
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

    /** Adressen, die das Suchfeld direkt öffnet statt zu suchen: Seiten-URL, Lesezeichen, Confluence-Bookmark. */
    static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile(
            "(?i)(https?|space-harvester|confluence)://\\S+");

    /**
     * Öffnet eine Adresse über die Adressauflösung des Core ({@code Harvester.addresses().resolve}, #178): eine Seite
     * direkt unter Inhalte, mehrere (dieselbe Adresse in mehreren Quellen) als Treffer zur Wahl.
     */
    private void open(AppRuntime runtime, String address) {
        int id = ++request;
        run.setEnabled(false);
        more.setEnabled(false);
        feedback.working("Adresse wird aufgelöst …");
        Background.run(() -> runtime.workspace().resolveAddress(address), resolution -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            states.clear();
            List<Hit> hits = resolution.matches().stream().map(Hit::of).toList();
            model.set(hits);
            String unavailable = resolution.unavailableSources().isEmpty() ? ""
                    : " Nicht erreichbar: " + String.join(", ", resolution.unavailableSources()) + ".";
            if (hits.size() == 1) {
                feedback.clear();
                status.setText("Adresse aufgelöst: " + hits.get(0).title());
                context.navigator().openPage(hits.get(0).sourceId(), hits.get(0).pageId());
            } else if (hits.isEmpty()) {
                status.setText(" ");
                feedback.info("Keine Seite zu dieser Adresse", "Keine eingerichtete Quelle kennt eine Seite unter "
                        + "dieser Adresse, die du lesen darfst." + unavailable);
            } else {
                status.setText(Texts.count(hits.size(), "Seite", "Seiten") + " zu dieser Adresse");
                feedback.info("Mehrere Quellen", "Die Adresse passt in mehreren Quellen; Doppelklick öffnet die Seite."
                        + unavailable);
            }
        }, error -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            feedback.problem(error);
        });
    }

    private void search() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            feedback.warning("Keine Laufzeit", "Die Konfiguration konnte nicht gestartet werden; siehe Einstellungen.");
            return;
        }
        String text = query.getText().trim();
        if (picker.allSourceIds().isEmpty()) {
            feedback.warning("Keine Quelle eingerichtet", "Wissensquellen legst du unter Wissensquellen an.");
            return;
        }
        if (text.isEmpty()) {
            feedback.warning("Suchtext fehlt", "Ein oder mehrere Schlagwörter eingeben.");
            return;
        }
        if (ADDRESS.matcher(text).matches()) {
            open(runtime, text);
            return;
        }
        if (semantic.isSelected()) {
            relevance(runtime, text);
            return;
        }
        KeywordSearch.Query q;
        try {
            // Eine angehakte Quelle sucht wie die Suche unter der Quelle (sourceId), mehrere mit sourceIds.
            String sourceId = chosen.size() == 1 ? chosen.iterator().next() : null;
            String spaceKey = picker.spaceKey();
            q = new KeywordSearch.Query(text, sourceId, chosen.size() > 1 ? Set.copyOf(chosen) : Set.of(),
                    spaceKey.isEmpty() ? Set.of() : Set.of(spaceKey));
        } catch (InvalidQueryException invalid) {
            feedback.warning("Suche so nicht möglich", invalid.getMessage());
            return;
        }
        int id = ++request;
        run.setEnabled(false);
        more.setEnabled(false);
        feedback.working("Suche läuft …");
        Background.run(() -> runtime.workspace().keywordSearch(q, null, LIMIT), results -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            states.clear();
            incomplete = false;
            model.set(hits(results));
            show(q, results);
            if (results.hits().isEmpty() && results.nextCursor() != null) {
                // Dieser Stapel war leer, weitere Kandidaten folgen noch: kein endgültiges „Keine Treffer“.
                feedback.info("Noch keine Treffer", "Im ersten Stapel war nichts Passendes; „Weitere laden“ sucht weiter.");
            } else if (results.hits().isEmpty() && !results.complete()) {
                feedback.warning("Keine Treffer, Suche unvollständig",
                        "Nicht jede Quelle konnte vollständig suchen; siehe Zeile unter der Liste.");
            } else if (results.hits().isEmpty()) {
                feedback.info("Keine Treffer", searched()
                        ? "Keine Quelle kennt eine passende Seite, die du lesen darfst."
                        : "Keine der gewählten Quellen konnte suchen; siehe Zeile unter der Liste.");
            } else {
                feedback.clear();
            }
        }, error -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            model.set(List.of());
            states.clear();
            reset();
            feedback.problem(error);
        });
    }

    /**
     * Semantische Suche: ein nach Ähnlichkeit geordneter Block aus dem Cache, ohne Cursor. Ohne Dienst meldet der Core
     * {@code SemanticSearchUnavailableException}, nie still Titeltreffer.
     */
    private void relevance(AppRuntime runtime, String text) {
        PageSearchQuery q;
        try {
            if (text.length() > RelevanceSearch.MAX_TEXT_LENGTH) {
                throw new InvalidQueryException("höchstens " + RelevanceSearch.MAX_TEXT_LENGTH + " Zeichen");
            }
            String sourceId = chosen.size() == 1 ? chosen.iterator().next() : null;
            String spaceKey = picker.spaceKey();
            q = PageSearchQuery.of(sourceId, chosen.size() > 1 ? List.copyOf(chosen) : List.of(),
                    spaceKey.isEmpty() ? List.of() : List.of(spaceKey), null, null, null, null, null, null,
                    SearchSort.TITLE);
        } catch (InvalidQueryException invalid) {
            feedback.warning("Suche so nicht möglich", invalid.getMessage());
            return;
        }
        int id = ++request;
        run.setEnabled(false);
        more.setEnabled(false);
        feedback.working("Semantische Suche läuft …");
        Background.run(() -> runtime.workspace().relevance(q, text, Reranking.MAX_WINDOW), results -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            states.clear();
            incomplete = false;
            model.set(results.hits().stream().map(Hit::of).toList());
            reset();
            // Unvollständig: ein Treffer war bei seiner Quelle nicht prüfbar und fehlt deshalb (Recht und Version live).
            status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer")
                    + " · semantisch, nach Ähnlichkeit geordnet" + (results.complete() ? ""
                    : " · unvollständig: nicht jeder Treffer ließ sich bei seiner Quelle prüfen"));
            status.setForeground(results.complete() ? Comic.MUTED : Comic.ORANGE);
            if (results.hits().isEmpty() && !results.complete()) {
                feedback.warning("Keine Treffer, Suche unvollständig",
                        "Nicht jeder Treffer ließ sich bei seiner Quelle prüfen; siehe Zeile unter der Liste.");
            } else if (results.hits().isEmpty()) {
                feedback.info("Keine Treffer", "Keine eingebettete Seite im Cache passt; die Schlagwortsuche ohne "
                        + "Häkchen sucht direkt in den Quellen.");
            } else {
                feedback.clear();
            }
        }, error -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            model.set(List.of());
            states.clear();
            reset();
            feedback.problem(error);
        });
    }

    /** Der nächste Stapel derselben Suche, angehängt an die bisherigen Treffer. */
    private void loadMore() {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Query q = lastQuery;
        String cursor = nextCursor;
        if (runtime == null || q == null || cursor == null) {
            return;
        }
        int id = ++request;
        run.setEnabled(false);
        more.setEnabled(false);
        feedback.working("Weitere Treffer werden geladen …");
        Background.run(() -> runtime.workspace().keywordSearch(q, cursor, LIMIT), results -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            model.add(hits(results));
            show(q, results);
            if (model.rows.isEmpty() && results.nextCursor() != null) {
                feedback.info("Noch keine Treffer", "Auch dieser Stapel war leer; „Weitere laden“ sucht weiter.");
            } else if (model.rows.isEmpty()) {
                feedback.info("Keine Treffer", "Keine Quelle kennt eine passende Seite, die du lesen darfst.");
            } else {
                feedback.clear();
            }
        }, error -> {
            if (id != request) {
                return;
            }
            // Die bisherigen Treffer bleiben stehen; eine neue Suche beginnt von vorn.
            run.setEnabled(true);
            reset();
            feedback.problem(error);
        });
    }

    private void reset() {
        lastQuery = null;
        nextCursor = null;
        more.setEnabled(false);
        status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer"));
        status.setForeground(Comic.MUTED);
    }

    private void show(KeywordSearch.Query q, KeywordSearch.Results results) {
        lastQuery = q;
        nextCursor = results.nextCursor();
        // complete ist auch false, solange noch Treffer folgen; unvollständig heißt: eine Quelle fiel aus, oder am
        // Ende ließ sich nicht alles prüfen.
        incomplete |= !results.complete() && results.nextCursor() == null
                || results.sources().stream().anyMatch(state -> state.status()
                == KeywordSearch.SourceState.Status.UNAVAILABLE);
        // Folgestapel nennen nur noch offene Quellen; ein späterer Ausfall ersetzt den früheren Zustand.
        results.sources().forEach(state -> states.put(state.sourceId(), state));
        more.setEnabled(nextCursor != null);
        StringBuilder line = new StringBuilder(Texts.count(model.rows.size(), "Treffer", "Treffer"));
        if (nextCursor != null) {
            line.append(" · weitere vorhanden");
        }
        for (KeywordSearch.SourceState state : states.values()) {
            switch (state.status()) {
                case NOT_SUPPORTED -> line.append(" · ").append(title(state.sourceId()))
                        .append(": keine eigene Suche");
                case UNAVAILABLE -> line.append(" · ").append(title(state.sourceId())).append(": nicht erreichbar")
                        .append(state.reason() == null ? "" : " (" + reason(state.reason()) + ")");
                case SEARCHED -> {
                }
            }
        }
        if (incomplete) {
            line.append(" · unvollständig: nicht jede Quelle oder jeder Treffer ließ sich prüfen");
        }
        status.setText(line.toString());
        status.setForeground(incomplete ? Comic.ORANGE : Comic.MUTED);
    }

    /** Ob mindestens eine Quelle gesucht hat. */
    private boolean searched() {
        return states.values().stream().anyMatch(s -> s.status() == KeywordSearch.SourceState.Status.SEARCHED);
    }

    private String title(String sourceId) {
        AppRuntime runtime = context.runtime();
        if (runtime != null) {
            for (HarvestSource source : runtime.workspace().sources()) {
                if (source.id().equals(sourceId) && source.title() != null && !source.title().isBlank()) {
                    return source.title();
                }
            }
        }
        return sourceId;
    }

    /** {@code SourceAccessException.Reason} verständlich. */
    private static String reason(String reason) {
        return switch (reason) {
            case "ACCESS_DENIED" -> "Anmeldung abgelehnt";
            case "UNAVAILABLE" -> "keine Verbindung";
            case "RATE_LIMITED" -> "gedrosselt";
            case "INVALID_RESPONSE" -> "unerwartete Antwort";
            default -> reason.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }

    /** Für Tests: die Zeile unter der Liste. */
    String statusText() {
        return status.getText();
    }

    private static List<Hit> hits(KeywordSearch.Results results) {
        return results.hits().stream().map(Hit::of).toList();
    }

    /** Eine Zeile; die Quelle stammt aus dem Treffer selbst, nie aus der Auswahl. */
    record Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt) {

        /** Eine aufgelöste Adresse; statt eines Auszugs die Version. */
        static Hit of(PageAddresses.Match match) {
            return new Hit(match.sourceId(), match.pageId(), match.title(), match.spaceKey(),
                    match.version() == null ? "" : "Version " + match.version(), null);
        }

        static Hit of(KeywordSearch.Hit hit) {
            return new Hit(hit.sourceId(), hit.pageId(), hit.title(), hit.spaceKey(), hit.snippet(), hit.modifiedAt());
        }

        /** Ein Treffer der semantischen Suche; statt eines Auszugs die Ähnlichkeit. */
        static Hit of(RelevanceSearch.RankedHit ranked) {
            var header = ranked.hit().page().header();
            var metadata = header.metadata();
            RelevanceSearch.Relevance relevance = ranked.relevance();
            String snippet = relevance == null ? "Titeltreffer"
                    : String.format(java.util.Locale.GERMANY, "Ähnlichkeit %.2f%s", relevance.similarity(),
                    relevance.freshness() == RelevanceSearch.Freshness.STALE ? " (ältere Fassung)" : "");
            return new Hit(header.key().sourceId(), header.key().pageId(), metadata.title(), metadata.partitionKey(),
                    snippet, metadata.modifiedAt());
        }
    }

    private static final class HitsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Quelle", "Space", "Auszug", "Geändert"};
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
                case 3 -> hit.snippet() == null ? "" : hit.snippet().replaceAll("\\s+", " ").strip();
                default -> Texts.time(hit.modifiedAt());
            };
        }
    }
}
