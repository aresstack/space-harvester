package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageAddresses;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
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
 * Globale Suche als reine Schlagwortsuche über den Cache ({@code KeywordSearch} des Core, ADR 0013): Die native Suche
 * jeder angehakten Quelle entdeckt passende Seiten, der Core legt sie mit den Rechten des Aufrufers in den Cache, und
 * die Treffer kommen aus dessen Volltextindex, mit Textauszug aus dem Cache. Die Funde der Quellen stehen sofort als
 * vorläufige Treffer da ({@code Results.ranked()}, ADR 0016); während der Core lädt, zeigt die Ansicht seinen
 * Ladestand und ordnet geladene Treffer nach vorn, ohne dass einer verschwindet. Die Suchlogik bleibt im Core, die
 * Ansicht stellt nur dar. Eine Synchronisierung ist nie Voraussetzung. Unter der Liste steht je Quelle, ob sie keine eigene Suche kennt oder nicht erreichbar war, statt
 * still „keine Treffer“ zu melden; ein unvollständiges Ergebnis ist so gekennzeichnet.
 * Unter der Suchzeile steht je Quelle ein Häkchen: „Alle“ (Vorgabe) sucht ohne {@code sourceIds}, eine einzelne Quelle
 * mit {@code sourceId}, mehrere mit {@code sourceIds} (ADR 0009). Die Space-Auswahl bietet die Spaces der angehakten
 * Quellen an. Das Häkchen „Semantische Suche“ ist die progressive Suche des Core (ADR 0017): Originalanfrage zuerst,
 * dann Varianten (vom Chat-Modell, sonst nach Regeln) als Schlagwörter, alle Treffer gegen die Originalanfrage
 * bewertet und eingeordnet, bis der Core einen Abschlussgrund meldet; ohne „Weitere laden“.
 * „Weitere laden“ folgt dem Cursor des Core, der nur für dieselbe Anfrage gilt. Nur die jeweils letzte Anfrage darf die
 * Liste ändern.
 */
public final class SearchView implements View {

    /**
     * Treffer je Stapel: ein Bildschirm voll statt des Höchstwerts des Core ({@link KeywordSearch#MAX_LIMIT}), damit
     * eine Suche nicht für jede Eingabe hundert Seiten in den Cache holt.
     */
    static final int LIMIT = 25;
    /** Schluss der Fortschrittszeile, wenn nichts mehr nachlädt. */
    static final String DONE = "Die Suche ist abgeschlossen";
    /** Abstand der Abfragen des Ladestands ({@code KeywordSearch.progress}), solange Seiten in den Cache laden. */
    private static final int POLL_MILLIS = 400;
    static final String SEMANTIC = "Semantische Suche";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final JTextField query = Comic.field(30);
    /** Progressive Suche des Core (ADR 0017): Originalanfrage, dann Varianten bis zum Qualitätsziel. */
    final JCheckBox semantic = checkBox(SEMANTIC, "Sucht zusätzlich mit Varianten der Anfrage und ordnet alle Treffer "
            + "nach ihrer Nähe zur ursprünglichen Anfrage");
    /** Die Suchanfragen der semantischen Suche als Schlagwörter; die ursprüngliche steht im Suchfeld. */
    final JPanel tags = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    /** Die laufende semantische Suche; {@code null}, wenn keine läuft. */
    private ProgressiveSearch running;
    /** Der Umfang aus dem Explorer: „In: Alle Wissensquellen“ bzw. die gewählten Quellen und Spaces. */
    final JLabel scopeLabel = Comic.muted(" ");
    /** Der Modus der nächsten Suche: Schlagwortsuche oder semantisch, wie der Core ihn meldet. */
    final JLabel modeLabel = Comic.muted(" ");
    final ComposerButton everywhere = Comic.button("Überall suchen", "Auswahl im Explorer aufheben und in allen "
            + "Wissensquellen suchen");
    private final ComposerButton run = Comic.primary("Suchen", "Im gewählten Umfang suchen (Titel und Inhalt)");
    private final ComposerButton more = Comic.button("Weitere laden", "Die nächsten Treffer derselben Suche laden");
    private final HitsModel model = new HitsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel status = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    /** Schmale Zeile unter dem Suchfeld, solange gefundene Seiten in den Cache laden: Balken und Zähler. */
    final JProgressBar progress = new JProgressBar();
    final JLabel progressText = Comic.muted(" ");
    /** Mittig im Balken nach dem Ende: „Fertig“, „Abgebrochen“ oder „Fehlgeschlagen“, wie im globalen Balken. */
    final JLabel endState = new JLabel(" ");
    /** Aktionszeile über der Statusleiste: Fortschritt über die volle Breite, Zeile mit Stand, Abbrechen und „Weitere laden“. */
    private final JPanel actions = new JPanel(new BorderLayout(12, 4));
    final ComposerButton cancel = Comic.button("Abbrechen", "Das Laden der gefundenen Seiten abbrechen; Geladenes "
            + "bleibt im Cache");
    /** Fragt den Ladestand ab; steigt er, wird derselbe Stapel neu geordnet ({@link #refresh}). */
    private final Timer poll = new Timer(POLL_MILLIS, e -> poll());
    /** Kennung des ladenden Stapels für {@code progress} und {@code cancel}; {@code null}, wenn nichts lädt. */
    private String progressId;
    /** Zuletzt gemeldete geladene Seiten; steigt die Zahl, sucht die Ansicht denselben Stapel erneut. */
    private int lastLoaded;
    /** Ob der erste Stapel leer kam, während der Core noch lädt; „Keine Treffer“ erst nach dem Laden. */
    private boolean awaitingHits;
    /** Ob ein erneutes Suchen desselben Stapels läuft; nie zwei zugleich. */
    private boolean refreshing;
    /** Ob das Laden abgebrochen wurde; dann sucht die Ansicht nicht erneut, denn das stieße das Laden wieder an. */
    private boolean cancelled;
    /** Zeile, ab der der angezeigte Stapel in der Liste steht; nur er wird neu geordnet. */
    private int batchStart;
    /** Anfrage und Cursor der zuletzt angezeigten Suche, für „Weitere laden“; der Cursor gilt nur für sie. */
    private KeywordSearch.Query lastQuery;
    /** Ob ein Stapel derselben Suche unvollständig war; bleibt bis zur nächsten Suche. */
    private boolean incomplete;
    /** Zustand je Quelle aus dem ersten Stapel, ergänzt um spätere Ausfälle. */
    private final Map<String, KeywordSearch.SourceState> states = new LinkedHashMap<>();
    private String nextCursor;
    /** Cursor des zuletzt angezeigten Stapels ({@code null} für den ersten); das Nachfragen wiederholt genau ihn. */
    private String shownCursor;
    /** Zählt Suchen und „Weitere laden“; Ergebnisse einer überholten Anfrage werden verworfen. */
    private int request;
    /** Quellen aus der Auswahl im Explorer; leer für alle. */
    private final Set<String> chosen = new TreeSet<>();
    /** Spaces aus der Auswahl im Explorer; leer für alle Spaces der Quellen. */
    private final Set<String> spaces = new TreeSet<>();
    /** Gewählte Knoten mit Namen für die Umfangszeile. */
    private List<Explorer.Scope> scopes = List.of();
    /** Hebt die Auswahl im Explorer auf („Überall suchen“). */
    private Runnable clearScope = () -> scope(List.of());

    public SearchView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        query.setToolTipText("Wörter in Titel und Inhalt; die Quellen werden live durchsucht");
        query.addActionListener(e -> search());
        run.addActionListener(e -> search());
        more.addActionListener(e -> loadMore());
        more.setEnabled(false);
        poll.setRepeats(false);
        cancel.addActionListener(e -> cancelLoading());
        progress.setPreferredSize(new java.awt.Dimension(200, 26));
        progress.setStringPainted(false);
        progress.setForeground(Comic.BLUE);
        endState.setFont(Comic.semiBold(13f));
        endState.setForeground(Comic.INK);
        endState.setVisible(false);
        cancel.setVisible(false);
        more.setVisible(false);
        semantic.setSelected(false);
        semantic.addItemListener(e -> mode());
        tags.setOpaque(false);
        tags.setVisible(false);
        everywhere.addActionListener(e -> clearScope.run());
        context.onChange(ignored -> refreshSources());
        refreshSources();
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.getSelectedRow();
                if (e.getClickCount() == 2 && row >= 0) {
                    Hit hit = model.rows.get(table.convertRowIndexToModel(row));
                    // Mit der Passage-ID seines Absatzes springt der Treffer dorthin, sonst öffnet er die Seite.
                    if (hit.passageId() != null) {
                        context.navigator().openPassage(hit.sourceId(), hit.passageId());
                    } else {
                        context.navigator().openPage(hit.sourceId(), hit.pageId());
                    }
                }
            }
        });
        table.setToolTipText("Doppelklick öffnet die Seite unter Inhalte, beim Absatz des Auszugs");

        // Eine Suchleiste, daneben die semantische Suche; Erklärungen stehen im Tooltip des Infosymbols.
        JLabel info = new JLabel(com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.info());
        info.setToolTipText("<html>Schlagwortsuche über Titel und Inhalt, direkt in den Quellen. Wo gesucht wird, "
                + "wählst du im Explorer links:<br>eine Wissensquelle, einen Space oder mit Strg-Klick mehrere; "
                + "ohne Auswahl in allen.<br>"
                + "Eine Seitenadresse oder ein Lesezeichen (space-harvester://…) im Suchfeld öffnet die Seite direkt."
                + "<br>Semantische Suche: zusätzlich Varianten der Anfrage, alle Treffer nach Nähe zur Anfrage geordnet."
                + "<br>Doppelklick auf einen Treffer öffnet ihn unter Inhalte.</html>");
        info.getAccessibleContext().setAccessibleName("Hinweise zur Suche");
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);
        bar.add(query, BorderLayout.CENTER);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        options.setOpaque(false);
        options.add(semantic);
        options.add(info);
        options.add(run);
        bar.add(options, BorderLayout.EAST);
        JPanel scopeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        scopeRow.setOpaque(false);
        scopeRow.add(scopeLabel);
        scopeRow.add(everywhere);
        scopeRow.add(modeLabel);
        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.setOpaque(false);
        head.add(bar, BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout(0, 6));
        middle.setOpaque(false);
        middle.add(scopeRow, BorderLayout.NORTH);
        middle.add(tags, BorderLayout.CENTER);
        head.add(middle, BorderLayout.CENTER);
        head.add(feedback, BorderLayout.SOUTH);
        root.add(head, BorderLayout.NORTH);
        root.add(Comic.scroll(table), BorderLayout.CENTER);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Derselbe Balken wie der globale der Aufträge: links der Stand, mittig Abbrechen bzw. der Endzustand.
        actions.setOpaque(false);
        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        center.setOpaque(false);
        center.add(cancel);
        center.add(endState);
        JPanel overlay = new JPanel(new BorderLayout());
        overlay.setOpaque(false);
        overlay.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        overlay.add(progressText, BorderLayout.WEST);
        overlay.add(center, BorderLayout.CENTER);
        JPanel layered = new JPanel();
        layered.setOpaque(false);
        layered.setLayout(new javax.swing.OverlayLayout(layered));
        overlay.setAlignmentX(0.5f);
        overlay.setAlignmentY(0.5f);
        progress.setAlignmentX(0.5f);
        progress.setAlignmentY(0.5f);
        overlay.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        progress.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        layered.add(overlay);
        layered.add(progress);
        actions.add(layered, BorderLayout.CENTER);
        JPanel side = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        side.setOpaque(false);
        side.add(status);
        side.add(more);
        actions.add(side, BorderLayout.EAST);
    }

    @Override
    public JComponent actions() {
        return actions;
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

    /** Wie {@link #scope(List)} für ganze Quellen. */
    void restrictTo(Collection<String> sourceIds) {
        scope(sourceIds.stream().map(id -> new Explorer.Scope(id, null)).toList());
    }

    /**
     * Der Umfang aus der Auswahl im Explorer: die genannten Quellen, bei einem Space nur dieser; leer sucht in allen
     * Wissensquellen. Eine laufende Suche bleibt; die nächste nutzt den neuen Umfang.
     */
    public void scope(List<Explorer.Scope> selection) {
        Set<String> known = knownSources();
        scopes = selection.stream().filter(scope -> scope.sourceId() != null && known.contains(scope.sourceId()))
                .distinct().toList();
        chosen.clear();
        spaces.clear();
        for (Explorer.Scope scope : scopes) {
            chosen.add(scope.sourceId());
            if (scope.spaceKey() != null) {
                spaces.add(scope.spaceKey());
            }
        }
        if (scopes.stream().anyMatch(scope -> scope.spaceKey() == null)) {
            // Eine ganze Quelle in der Auswahl: alle ihre Spaces, kein Space-Filter für die anderen.
            spaces.clear();
        }
        scopeLabel.setText(scopes.isEmpty() ? "In: Alle Wissensquellen" : "In: " + String.join(", ",
                scopes.stream().map(scope -> title(scope.sourceId())
                        + (scope.spaceKey() == null ? "" : " › " + scope.spaceKey())).toList()));
        everywhere.setVisible(!scopes.isEmpty());
    }

    /** Hebt beim Klick auf „Überall suchen“ die Auswahl im Explorer auf; der meldet dann den leeren Umfang. */
    public void onClearScope(Runnable action) {
        this.clearScope = java.util.Objects.requireNonNull(action, "action");
    }

    /** Die Quellen des Umfangs; leer heißt alle. */
    Set<String> chosenSources() {
        return Set.copyOf(chosen);
    }

    /** Die Spaces des Umfangs; leer heißt alle. */
    Set<String> chosenSpaces() {
        return Set.copyOf(spaces);
    }

    private Set<String> knownSources() {
        AppRuntime runtime = context.runtime();
        return runtime == null ? Set.of() : runtime.workspace().sources().stream().map(HarvestSource::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Woher die Varianten der semantischen Suche kommen; entfernte Quellen fallen aus dem Umfang. */
    private void refreshSources() {
        AppRuntime runtime = context.runtime();
        boolean available = runtime != null && runtime.workspace().semanticRanking();
        semantic.setEnabled(available);
        if (!available) {
            semantic.setSelected(false);
        }
        semantic.setToolTipText(semanticTip(runtime == null ? null : available, runtime != null
                && runtime.workspace().aiVariants()));
        mode();
        scope(scopes);
    }

    /**
     * Der Tooltip des Häkchens: mit Modell, woher die Varianten kommen; ohne Modell, warum es gesperrt ist;
     * {@code null} ohne Laufzeit.
     */
    static String semanticTip(Boolean available, boolean aiVariants) {
        if (available == null) {
            return null;
        }
        if (!available) {
            return "Gesperrt: kein Embedding-Modell eingerichtet (Einstellungen › Modelle); ohne Modell sucht die App "
                    + "nach Schlagwörtern";
        }
        return aiVariants
                ? "Sucht semantisch mit Varianten der Anfrage vom Chat-Modell des KI-Dienstes und ordnet alle Treffer "
                + "nach ihrer Nähe zur ursprünglichen Anfrage"
                : "Sucht semantisch mit Varianten der Anfrage nach Regeln (kein Chat-Modell eingerichtet, unter "
                + "Einstellungen › Modelle) und ordnet alle Treffer nach ihrer Nähe zur ursprünglichen Anfrage";
    }

    /** Nennt den Modus der nächsten Suche neben dem Umfang. */
    private void mode() {
        modeLabel.setText(semantic.isSelected() ? "Modus: Semantische Suche" : "Modus: Schlagwortsuche");
    }

    private static JCheckBox checkBox(String text, String tooltip) {
        JCheckBox box = new JCheckBox(text);
        box.setOpaque(false);
        box.setFont(Comic.regular(13f));
        box.setForeground(Comic.INK);
        box.setToolTipText(tooltip);
        return box;
    }

    /**
     * Adressen, die das Suchfeld direkt öffnet statt zu suchen: Seiten-URL, Lesezeichen, Confluence-Bookmark und die
     * Ressourcen-ID aus den technischen Angaben einer Seite ({@code confluence:<host>/page/<ID>}, {@code files:…}).
     */
    static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile(
            "(?i)(https?|space-harvester|confluence)://\\S+|(confluence|files):[^\\s:/][^\\s]*/\\S+");

    /**
     * Öffnet eine Adresse über die Adressauflösung des Core ({@code Harvester.addresses().resolve}, #178): eine Seite
     * direkt unter Inhalte, mehrere (dieselbe Adresse in mehreren Quellen) als Treffer zur Wahl.
     */
    private void open(AppRuntime runtime, String address) {
        int id = ++request;
        // Das Laden der vorigen Suche endet; ihr Nachfragen darf die Adressauflösung nicht überholen.
        stopLoading();
        run.setEnabled(false);
        more.setVisible(false);
        feedback.clear();
        starting("Adresse wird aufgelöst …");
        Background.run(() -> runtime.workspace().resolveAddress(address), resolution -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            stopLoading();
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
            stopLoading();
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
        if (runtime.workspace().sources().isEmpty()) {
            feedback.warning("Keine Quelle eingerichtet", "Wissensquellen legst du unter Wissensquellen verwalten "
                    + "an (rechts oben).");
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
        KeywordSearch.Query q;
        try {
            // Eine gewählte Quelle sucht wie die Suche unter der Quelle (sourceId), mehrere mit sourceIds.
            String sourceId = chosen.size() == 1 ? chosen.iterator().next() : null;
            q = new KeywordSearch.Query(text, sourceId, chosen.size() > 1 ? Set.copyOf(chosen) : Set.of(),
                    Set.copyOf(spaces));
        } catch (InvalidQueryException invalid) {
            feedback.warning("Suche so nicht möglich", invalid.getMessage());
            return;
        }
        if (semantic.isSelected()) {
            progressive(runtime, q);
            return;
        }
        stopLoading();
        showVariants(List.of());
        int id = ++request;
        run.setEnabled(false);
        more.setVisible(false);
        feedback.clear();
        starting("Suche läuft …");
        Background.run(() -> runtime.workspace().keywordSearch(q, null, LIMIT), results -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            states.clear();
            incomplete = false;
            shownCursor = null;
            batchStart = 0;
            model.set(hits(results));
            show(q, results);
            awaitingHits = false;
            if (!results.ranked().isEmpty()) {
                feedback.clear();
            } else if (results.progress() != null && !results.progress().done()) {
                // Delegierte Benutzer sehen keine vorläufigen Funde: Treffer erscheinen erst, wenn der Core die
                // geladenen Seiten geprüft hat. Bis dahin spricht der Balken, kein vorzeitiges „Keine Treffer“.
                awaitingHits = true;
                feedback.clear();
            } else if (results.nextCursor() != null) {
                // Dieser Stapel war leer, weitere Kandidaten folgen noch: kein endgültiges „Keine Treffer“.
                feedback.info("Noch keine Treffer", "Im ersten Stapel war nichts Passendes; „Weitere laden“ sucht weiter.");
            } else if (!results.complete()) {
                feedback.warning("Keine Treffer, Suche unvollständig",
                        "Nicht jede Quelle konnte vollständig suchen; siehe Zeile unter der Liste.");
            } else {
                feedback.info("Keine Treffer", searched()
                        ? "Keine Quelle kennt eine passende Seite, die du lesen darfst."
                        : "Keine der gewählten Quellen konnte suchen; siehe Zeile unter der Liste.");
            }
            track(results.progress());
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
     * Semantische Suche (ADR 0017): Der Core sucht mit der ursprünglichen Anfrage und ihren Varianten und meldet nach
     * jeder Freigabe die ganze Liste; die Ansicht sortiert ein, ohne die Auswahl zu verlieren, und zeigt am Ende, warum
     * die Suche endete. Ereignisse einer überholten Suche werden verworfen.
     */
    private void progressive(AppRuntime runtime, KeywordSearch.Query q) {
        stopLoading();
        int id = ++request;
        states.clear();
        incomplete = false;
        lastQuery = null;
        nextCursor = null;
        model.set(List.of());
        showVariants(List.of());
        feedback.clear();
        status.setText(" ");
        starting("Suche läuft · Suchanfragen werden gebildet …");
        cancel.setVisible(true);
        cancel.setEnabled(true);
        ProgressiveSearchListener listener = new ProgressiveSearchListener() {
            @Override
            public void variants(List<ProgressiveResult.Variant> variants) {
                SwingUtilities.invokeLater(() -> {
                    if (id == request) {
                        showVariants(variants);
                    }
                });
            }

            @Override
            public void ranked(List<ProgressiveResult.RankedHit> hits) {
                SwingUtilities.invokeLater(() -> {
                    if (id == request) {
                        showRanked(hits);
                    }
                });
            }

            @Override
            public void completed(ProgressiveResult result) {
                SwingUtilities.invokeLater(() -> {
                    if (id == request) {
                        ended(result);
                    }
                });
            }
        };
        running = runtime.workspace().progressiveSearch(q, listener);
    }

    /** Die ganze Liste nach einer Freigabe: neue Treffer eingeordnet, bekannte an ihrem neuen Platz, Auswahl bleibt. */
    private void showRanked(List<ProgressiveResult.RankedHit> hits) {
        Hit selected = selectedHit();
        model.set(hits.stream().map(ranked -> Hit.of(ranked.hit())).toList());
        select(selected);
        status.setText(Texts.count(model.rows.size(), "Treffer", "Treffer") + " · semantisch, nach Nähe zur Anfrage");
        status.setForeground(Comic.MUTED);
    }

    /** Ende der semantischen Suche: Endliste, Grund in der Fortschrittszeile, Hinweis bei keinen Treffern. */
    private void ended(ProgressiveResult result) {
        running = null;
        showRanked(result.hits());
        showVariants(result.variants());
        idle(switch (result.reason()) {
            case CANCELLED -> "Abgebrochen";
            case FAILED -> "Fehlgeschlagen";
            default -> "Fertig";
        });
        progressText.setText(completion(result.reason()) + " · "
                + Texts.count(model.rows.size(), "Treffer", "Treffer"));
        if (result.reason() == ProgressiveResult.Completion.FAILED) {
            feedback.warning("Suche fehlgeschlagen", "Keine Suchanfrage ließ sich ausführen; die Quellen waren "
                    + "nicht erreichbar.");
        } else if (model.rows.isEmpty() && result.reason() != ProgressiveResult.Completion.CANCELLED) {
            feedback.info("Keine Treffer", "Weder die Anfrage noch ihre Varianten fanden eine Seite, die du lesen "
                    + "darfst.");
        }
    }

    static String completion(ProgressiveResult.Completion reason) {
        return switch (reason) {
            case QUALITY_REACHED -> DONE + " · genug passende Treffer";
            case ALL_VARIANTS_DONE -> DONE + " · alle Suchanfragen gelaufen";
            case BUDGET_EXHAUSTED -> DONE + " · Zeit- oder Abfragebudget ausgeschöpft";
            case CANCELLED -> "Suche abgebrochen";
            case FAILED -> "Suche fehlgeschlagen";
        };
    }

    /** Die Varianten als Schlagwörter und der Stand in der Fortschrittszeile („2 von 6 Suchanfragen“). */
    private void showVariants(List<ProgressiveResult.Variant> variants) {
        tags.removeAll();
        int finished = 0;
        for (ProgressiveResult.Variant variant : variants) {
            ProgressiveResult.Status state = variant.status();
            if (state != ProgressiveResult.Status.PENDING && state != ProgressiveResult.Status.RUNNING) {
                finished++;
            }
            if (variant.query().priority() > 0) {
                tags.add(tag(variant));
            }
        }
        tags.setVisible(tags.getComponentCount() > 0);
        tags.revalidate();
        tags.repaint();
        if (running != null && !variants.isEmpty()) {
            progress.setIndeterminate(false);
            progress.setMaximum(variants.size());
            progress.setValue(finished);
            progressText.setText("Suche läuft · " + finished + " von " + Texts.count(variants.size(), "Suchanfrage",
                    "Suchanfragen") + " · " + Texts.count(model.rows.size(), "Treffer", "Treffer"));
        }
    }

    private static JLabel tag(ProgressiveResult.Variant variant) {
        JLabel tag = new JLabel(variant.query().text());
        tag.setFont(Comic.regular(12f));
        tag.setOpaque(true);
        boolean useful = variant.status() == ProgressiveResult.Status.DONE && variant.newHits() > 0;
        boolean dropped = variant.status() == ProgressiveResult.Status.FAILED
                || variant.status() == ProgressiveResult.Status.SKIPPED;
        tag.setBackground(useful ? Comic.YELLOW : java.awt.Color.WHITE);
        tag.setForeground(dropped ? Comic.MUTED : Comic.INK);
        tag.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(dropped ? Comic.LINE
                : Comic.INK, 1, true), BorderFactory.createEmptyBorder(1, 7, 1, 7)));
        tag.setToolTipText("Variante der Anfrage · " + switch (variant.status()) {
            case PENDING -> "wartet";
            case RUNNING -> "läuft";
            case DONE -> Texts.count(variant.newHits(), "neuer Treffer", "neue Treffer");
            case FAILED -> "fehlgeschlagen";
            case SKIPPED -> "übersprungen";
        });
        return tag;
    }

    /** Der nächste Stapel derselben Suche, angehängt an die bisherigen Treffer. */
    private void loadMore() {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Query q = lastQuery;
        String cursor = nextCursor;
        if (runtime == null || q == null || cursor == null) {
            return;
        }
        // Das Laden des vorigen Stapels läuft im Core weiter; die Ansicht folgt ab jetzt dem neuen.
        poll.stop();
        progressId = null;
        // Bis zur Antwort gibt es keinen weiteren Stapel; ein zweiter Klick verwürfe sonst den laufenden.
        nextCursor = null;
        int id = ++request;
        run.setEnabled(false);
        feedback.clear();
        starting("Weitere Treffer werden geladen …");
        Background.run(() -> runtime.workspace().keywordSearch(q, cursor, LIMIT), results -> {
            if (id != request) {
                return;
            }
            run.setEnabled(true);
            shownCursor = cursor;
            batchStart = model.rows.size();
            model.add(hits(results));
            show(q, results);
            track(results.progress());
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

    /**
     * Folgt dem Laden eines Stapels ({@code Results.progress()}): zeigt „x von y“ und fragt den Stand ab, bis der Core
     * {@code done} meldet. Ohne zu ladende Seiten ist die Suche sofort abgeschlossen.
     */
    private void track(KeywordSearch.Progress progress) {
        poll.stop();
        cancelled = false;
        if (progress == null) {
            progressId = null;
            finished(false);
            return;
        }
        progressId = progress.id();
        lastLoaded = progress.loaded();
        progress(progress);
    }

    /** Ein Takt: Ladestand holen; ist mehr geladen, denselben Stapel neu einsortieren. */
    private void poll() {
        AppRuntime runtime = context.runtime();
        String id = progressId;
        if (runtime == null || id == null) {
            return;
        }
        Background.run(() -> runtime.workspace().keywordSearchProgress(id), current -> {
            if (!id.equals(progressId)) {
                return;
            }
            if (current.isEmpty()) {
                // Der Core kennt den Stapel nicht mehr: nichts lädt mehr.
                progressId = null;
                refresh();
                finished(false);
                return;
            }
            KeywordSearch.Progress state = current.get();
            if (state.loaded() > lastLoaded) {
                lastLoaded = state.loaded();
                refresh();
            }
            progress(state);
        }, error -> {
            if (id.equals(progressId)) {
                poll.restart();
            }
        });
    }

    /**
     * Stellt den angezeigten Stapel erneut und ordnet ihn nach der Rangfolge des Core neu ({@code ranked()}): geladene
     * Seiten rücken nach vorn, kein Treffer verschwindet, die Auswahl bleibt. Nie den nächsten Cursor: Neue Funde holt
     * nur „Weitere laden“.
     */
    private void refresh() {
        AppRuntime runtime = context.runtime();
        KeywordSearch.Query q = lastQuery;
        if (runtime == null || q == null || refreshing) {
            return;
        }
        refreshing = true;
        int id = request;
        String cursor = shownCursor;
        int start = batchStart;
        Background.run(() -> runtime.workspace().keywordSearch(q, cursor, LIMIT), results -> {
            refreshing = false;
            if (id != request) {
                return;
            }
            Hit selected = selectedHit();
            model.reorder(start, hits(results));
            select(selected);
            show(q, results);
            if (progressId == null && !cancelled) {
                finished(false);
            }
        }, error -> refreshing = false);
    }

    /** Bricht das Laden ab; die Treffer bleiben, die Zeile meldet den Abbruch. */
    private void cancelLoading() {
        if (running != null) {
            // Der Core bricht Suchanfragen und Ladevorgänge ab und meldet completed(CANCELLED).
            running.cancel();
            cancel.setEnabled(false);
            return;
        }
        AppRuntime runtime = context.runtime();
        String id = progressId;
        if (runtime == null || id == null) {
            return;
        }
        poll.stop();
        progressId = null;
        cancelled = true;
        // Kein erneutes Suchen danach: Es lüde die abgebrochenen Seiten wieder in den Cache.
        Background.run(() -> runtime.workspace().cancelKeywordSearch(id), ignored -> finished(true),
                error -> finished(true));
    }

    /** Beendet das Verfolgen ohne Meldung, etwa bei einer neuen Eingabe; der Core bricht das Laden ab. */
    private void stopLoading() {
        if (running != null) {
            running.cancel();
            running = null;
        }
        AppRuntime runtime = context.runtime();
        String id = progressId;
        poll.stop();
        progressId = null;
        idle(null);
        progressText.setText("");
        if (runtime != null && id != null) {
            Background.run(() -> runtime.workspace().cancelKeywordSearch(id), ignored -> { }, ignored -> { });
        }
    }

    private Hit selectedHit() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.rows.get(table.convertRowIndexToModel(row));
    }

    private void select(Hit hit) {
        int at = hit == null ? -1 : model.indexOf(hit);
        if (at >= 0) {
            int view = table.convertRowIndexToView(at);
            table.getSelectionModel().setSelectionInterval(view, view);
        }
    }

    private void reset() {
        lastQuery = null;
        nextCursor = null;
        shownCursor = null;
        stopLoading();
        updateMore();
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
        updateMore();
        StringBuilder line = new StringBuilder(Texts.count(model.rows.size(), "Treffer", "Treffer"));
        if (results.progress() != null && !results.progress().done()) {
            line.append(" · Seiten laden in den Cache, geladene rücken nach vorn");
        } else if (nextCursor != null) {
            line.append(" · weitere vorhanden");
        }
        for (KeywordSearch.SourceState state : states.values()) {
            switch (state.status()) {
                case NOT_SUPPORTED -> line.append(" · ").append(title(state.sourceId()))
                        .append(": keine eigene Suche");
                case UNAVAILABLE -> line.append(" · ").append(title(state.sourceId())).append(": nicht erreichbar")
                        .append(state.reason() == null ? "" : " (" + reason(state.reason()) + ")");
                case INDEXED -> line.append(" · ").append(title(state.sourceId()))
                        .append(": Volltext im Cache");
                case SEARCHED -> {
                    // Von der Quelle gefunden und im Cache gesucht (ADR 0013): der Normalfall, ohne Zusatz.
                }
            }
        }
        if (incomplete) {
            line.append(" · unvollständig: nicht jede Quelle oder jeder Treffer ließ sich prüfen");
        }
        status.setText(line.toString());
        status.setForeground(incomplete ? Comic.ORANGE : Comic.MUTED);
    }

    /** Die schmale Zeile unter dem Suchfeld: Balken und „x von y“, bis der Core meldet, dass nichts mehr lädt. */
    private void progress(KeywordSearch.Progress state) {
        if (state.done()) {
            progressId = null;
            poll.stop();
            if (!state.cancelled()) {
                refresh();
            }
            finished(state.cancelled());
            return;
        }
        // Unbestimmt, bis der Core einen Gesamtumfang nennt.
        progress.setIndeterminate(state.total() <= 0);
        progress.setMaximum(Math.max(1, state.total()));
        progress.setValue(state.loaded() + state.failed());
        progressText.setText(progressLine(state.loaded(), state.total(), model.rows.size()));
        endState.setVisible(false);
        cancel.setVisible(true);
        updateMore();
        poll.restart();
    }

    /** Ende der Zeile: „Die Suche ist abgeschlossen“ mit der Zahl der Treffer, nach einem Abbruch als solcher. */
    private void finished(boolean cancelled) {
        if (awaitingHits) {
            awaitingHits = !cancelled && model.rows.isEmpty() && refreshing;
            if (model.rows.isEmpty() && !awaitingHits) {
                feedback.info("Keine Treffer", "Keine Quelle kennt eine passende Seite, die du lesen darfst.");
            } else if (!model.rows.isEmpty()) {
                feedback.clear();
            }
        }
        idle(cancelled ? "Abgebrochen" : "Fertig");
        progressText.setText((cancelled ? "Laden abgebrochen" : DONE) + " · "
                + Texts.count(model.rows.size(), "Treffer", "Treffer"));
        updateMore();
    }

    /** Leere Spur ohne Abbrechen; mittig der Endzustand, {@code null} ohne. */
    private void idle(String end) {
        progress.setIndeterminate(false);
        progress.setValue(0);
        cancel.setVisible(false);
        endState.setText(end == null ? " " : end);
        endState.setVisible(end != null);
    }

    /** Sofort beim Start: unbestimmter Balken, bis der Core antwortet. */
    private void starting(String text) {
        progress.setIndeterminate(true);
        endState.setVisible(false);
        progressText.setText(text);
        cancel.setVisible(false);
        more.setVisible(false);
        more.setEnabled(false);
    }

    /** „Weitere laden“ nur nach Abschluss des Ladens und nur, wenn der Core weitere Treffer kennt. */
    private void updateMore() {
        boolean available = nextCursor != null && progressId == null;
        more.setVisible(available);
        more.setEnabled(available);
    }

    /** „12 von 25 Seiten im Cache · 7 Treffer“: geladene gegen gefundene Seiten dieser Suche. */
    static String progressLine(int loaded, int total, int hits) {
        return loaded + " von " + Texts.count(total, "Seite", "Seiten") + " im Cache · "
                + Texts.count(hits, "Treffer", "Treffer");
    }

    /** Ob mindestens eine Quelle gesucht hat. */
    private boolean searched() {
        return states.values().stream().anyMatch(s -> s.status() == KeywordSearch.SourceState.Status.SEARCHED
                || s.status() == KeywordSearch.SourceState.Status.INDEXED);
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

    /** Die ganze Liste des Stapels: geladene Treffer zuerst, dann die vorläufigen ({@code ranked()}). */
    private static List<Hit> hits(KeywordSearch.Results results) {
        return results.ranked().stream().map(Hit::of).toList();
    }

    /**
     * Eine Zeile; die Quelle stammt aus dem Treffer selbst, nie aus der Auswahl.
     *
     * @param attachment Dateiname des Anhangs, in dessen Text der Treffer liegt (#183), oder {@code null}
     * @param passageId  Passage-ID des Absatzes, aus dem der Auszug stammt, oder {@code null}
     */
    record Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt,
               String attachment, String passageId) {

        Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt) {
            this(sourceId, pageId, title, spaceKey, snippet, modifiedAt, null, null);
        }

        Hit(String sourceId, String pageId, String title, String spaceKey, String snippet, Instant modifiedAt,
            String attachment) {
            this(sourceId, pageId, title, spaceKey, snippet, modifiedAt, attachment, null);
        }

        /** Der Auszug in einer Zeile; bei einem Anhangstreffer vorn „Treffer im Anhang „Name““. */
        String excerpt() {
            String text = snippet == null ? "" : snippet.replaceAll("\\s+", " ").strip();
            if (attachment == null) {
                return text;
            }
            String where = "Treffer im Anhang „" + attachment + "“";
            return text.isEmpty() ? where : where + " · " + text;
        }

        /** Eine aufgelöste Adresse; statt eines Auszugs die Version. */
        static Hit of(PageAddresses.Match match) {
            return new Hit(match.sourceId(), match.pageId(), match.title(), match.spaceKey(),
                    match.version() == null ? "" : "Version " + match.version(), null);
        }

        static Hit of(KeywordSearch.Hit hit) {
            return new Hit(hit.sourceId(), hit.pageId(), hit.title(), hit.spaceKey(), hit.snippet(), hit.modifiedAt(),
                    hit.attachment() == null ? null : hit.attachment().title().isBlank()
                            ? hit.attachment().attachmentId() : hit.attachment().title(), hit.passageId());
        }
    }

    static final class HitsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Quelle", "Space", "Auszug", "Geändert"};
        List<Hit> rows = new ArrayList<>();

        void set(List<Hit> hits) {
            rows = new ArrayList<>(hits);
            fireTableDataChanged();
        }

        /**
         * Hängt Treffer an; eine schon gezeigte Seite (gleiche Quelle und ID) wird ersetzt statt verdoppelt, so kann
         * ein Treffer aus dem Cache einen vorläufigen ersetzen.
         */
        void add(List<Hit> hits) {
            boolean replaced = false;
            List<Hit> appended = new ArrayList<>();
            for (Hit hit : hits) {
                int at = indexOf(hit);
                if (at >= 0) {
                    rows.set(at, hit);
                    replaced = true;
                } else if (appended.stream().noneMatch(other -> same(other, hit))) {
                    appended.add(hit);
                }
            }
            int first = rows.size();
            rows.addAll(appended);
            if (replaced) {
                fireTableDataChanged();
            } else if (!appended.isEmpty()) {
                fireTableRowsInserted(first, rows.size() - 1);
            }
        }

        /**
         * Ordnet die Zeilen ab {@code start} nach {@code ranked} neu: dessen Treffer zuerst in seiner Reihenfolge,
         * danach die übrigen bisherigen in ihrer Reihenfolge. Keine Zeile verschwindet; frühere Stapel bleiben oben.
         */
        void reorder(int start, List<Hit> ranked) {
            int from = Math.min(start, rows.size());
            List<Hit> next = new ArrayList<>(rows.subList(0, from));
            List<Hit> rest = new ArrayList<>(rows.subList(from, rows.size()));
            for (Hit hit : ranked) {
                int fixed = indexIn(next, hit);
                if (fixed >= 0) {
                    if (fixed < from) {
                        next.set(fixed, hit);
                    }
                    continue;
                }
                next.add(hit);
            }
            for (Hit hit : rest) {
                if (indexIn(next, hit) < 0) {
                    next.add(hit);
                }
            }
            if (!next.equals(rows)) {
                rows = next;
                fireTableDataChanged();
            }
        }

        private static int indexIn(List<Hit> list, Hit hit) {
            for (int i = 0; i < list.size(); i++) {
                if (same(list.get(i), hit)) {
                    return i;
                }
            }
            return -1;
        }

        int indexOf(Hit hit) {
            for (int i = 0; i < rows.size(); i++) {
                if (same(rows.get(i), hit)) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean same(Hit a, Hit b) {
            return a.sourceId().equals(b.sourceId()) && a.pageId().equals(b.pageId());
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
                case 3 -> hit.excerpt();
                default -> Texts.time(hit.modifiedAt());
            };
        }
    }
}
