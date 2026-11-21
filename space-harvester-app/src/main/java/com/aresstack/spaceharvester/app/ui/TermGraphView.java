package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Der Reiter „Graph“ unter Analysen ohne Seite: ein Wissensgraph um einen Startbegriff. Begriffe sind Knoten, Beziehungen Kanten,
 * beschriftet mit ihrer Art; bestätigte durchgezogen, auf Wunsch auch vorgeschlagene gestrichelt. Die Mitte steht im
 * Zentrum, ihre Nachbarn (Tiefe 1) im Kreis darum, deren Nachbarn (Tiefe 2) außen bei ihrem Vorgänger. Ein Doppelklick
 * auf einen Knoten macht ihn zur Mitte. Die Daten kommen aus einer {@link GraphSource}, außerhalb des EDT. Nur auf dem
 * EDT.
 */
public final class TermGraphView {

    /** Hilfe am ?-Knopf (keine Hinweistexte in der Ansicht); nennt keinen Reiterpfad. */
    public static final String HELP = "Der Graph zeigt Begriffe als Knoten und ihre Beziehungen als Kanten, "
            + "beschriftet mit der Art der Beziehung. Begriff oben rechts eingeben und Enter drücken oder „Zeigen“ "
            + "wählen: Er steht in der Mitte, seine Nachbarn im Kreis darum. „Tiefe 2“ zeigt auch die Nachbarn der "
            + "Nachbarn. Ein Doppelklick auf einen Knoten macht ihn zur Mitte.\n\n"
            + "Gezeigt werden bestätigte Beziehungen, mit „Vorschläge zeigen“ auch vorgeschlagene, gestrichelt. "
            + "Ist links eine Quelle, ein Space oder ein Ordner gewählt, zählen nur Beziehungen, deren Begriffe auf "
            + "diesen Seiten vorkommen.\n\n"
            + "„Nur Fachbegriffe“ zeigt die Fachsicht: Knoten sind nur bestätigte Fachbegriffe des Glossars und ihre "
            + "bestätigten Synonyme (kleiner, nur umrandet), Kanten die Verben aus den Aussagen der analysierten "
            + "Seiten, als Pfeil vom Handelnden zum Betroffenen mit „Grundform · Zahl der Belege“. Eine dünne Linie "
            + "ohne Pfeil verbindet einen Fachbegriff mit seinem Synonym. Ein Klick auf eine Verb-Kante zeigt die "
            + "Belege; der Seitentitel öffnet die Seite an der Stelle. Begriffe werden nur nach Wortlaut bzw. "
            + "Grundform zugeordnet, nie als identisch behauptet. Gelesen wird nur aus der Ablage des eingestellten "
            + "Analysators; veraltete Aussagen bleiben weg.\n\n"
            + "Die Linie zeigt, wie sicher eine Verbindung ist: kräftig, wenn mindestens ein Beleg geprüft ist (von Hand "
            + "bestätigt oder vom Prüfschritt als stimmig bewertet), gestrichelt, wenn keiner geprüft ist. Im Overlay "
            + "steht die Stufe an jedem Beleg. „Nur geprüft“ zählt nur geprüfte Belege; Verbindungen ohne solche "
            + "fallen weg.";
    static final String NO_NEIGHBOURS = "Zu diesem Begriff gibt es noch keine Beziehungen.";
    static final String NO_VERBS = "Zu diesem Fachbegriff gibt es auf den analysierten, für dich sichtbaren Seiten "
            + "noch keine Aussagen mit anderen Fachbegriffen.";
    /** Leer unter „Nur geprüft“: Es fehlen geprüfte Aussagen, nicht Aussagen überhaupt (Prüfen statt Neuanalyse). */
    static final String NO_CHECKED_VERBS = "Zu diesem Fachbegriff gibt es noch keine geprüften Aussagen mit anderen "
            + "Fachbegriffen.";
    static final String DOMAIN = "Nur Fachbegriffe";
    static final String CHECKED = "Nur geprüft";
    static final String CHECKED_TIP = "Nur Verbindungen mit geprüften Belegen; die Zahl zählt nur diese";
    static final String CHECKED_LOCKED = "Gilt nur mit „Nur Fachbegriffe“";
    static final String OPTIONS_TIP = "Optionen: Nur Fachbegriffe, Nur geprüft";
    static final String PROPOSALS_TIP = "Auch vorgeschlagene Beziehungen zeigen, gestrichelt";
    static final String PROPOSALS_LOCKED = "Gilt nicht in der Fachsicht: sie zeigt nur Bestätigtes";
    static final String TRUNCATED = "Gezeigt werden die ersten " + GraphSource.MAX_NODES + " Begriffe.";
    /** Höchstens so viele Vorschläge zeigt die Begriffssuche am Startfeld. */
    static final int SUGGESTIONS = 8;

    private final AppContext context;
    private final GraphSource source;
    /** Fachsicht (Paket W1) hinter „Nur Fachbegriffe“; {@code null} ohne Laufzeit. */
    private final GraphSource domainSource;
    private final javax.swing.JCheckBoxMenuItem domain = new javax.swing.JCheckBoxMenuItem(DOMAIN);
    /** „Nur geprüft“ (Paket W2): wirkt nur in der Fachsicht, sonst gesperrt. */
    private final javax.swing.JCheckBoxMenuItem checked = new javax.swing.JCheckBoxMenuItem(CHECKED);
    private final EvidencePopup evidence = new EvidencePopup();
    private java.util.function.Consumer<EvidencePopup.Target> onOpen = target -> { };
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final PlaceholderField start = new PlaceholderField(18, "Startbegriff …");
    private final JComboBox<String> depth = Comic.combo();
    private final javax.swing.JCheckBox proposals = Comic.checkBox("Vorschläge zeigen");
    /** Die Schalter der Fachsicht hinter einem Icon-Knopf am Zeilenende; die Bestandssicht bleibt unverändert. */
    private final IconButton options = new IconButton(Glyph.FILTER, OPTIONS_TIP);
    private final JPopupMenu optionsMenu = new JPopupMenu();
    private final ComposerButton showButton = Comic.button("Zeigen", "Den Graphen um den Startbegriff zeichnen");
    private final JLabel hint = Comic.muted(" ");
    private final Canvas canvas = new Canvas();
    private final JPopupMenu suggestions = new JPopupMenu();
    private String centre;
    /** Zähler der Graph-Anfragen; außerhalb des EDT gelesen, damit eine überholte Berechnung abbricht. */
    private volatile int request;
    /** Zähler der Klicks auf Kanten: nur das Overlay der zuletzt geklickten Kante erscheint. */
    private int evidenceRequest;
    private int suggestRequest;
    private boolean quiet;
    /** Die Seiten der Auswahl links (ADR 0029), außerhalb des EDT gelesen; {@code null} heißt alle. */
    private final AtomicReference<Supplier<PageSet>> scope;
    private final JPanel head = new JPanel(new BorderLayout(12, 0));
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

    /** @param context Laufzeit; {@code null} zeigt nur das Gerüst (Tests) */
    public TermGraphView(AppContext context) {
        this(context, new AtomicReference<>(() -> null));
    }

    private TermGraphView(AppContext context, AtomicReference<Supplier<PageSet>> scope) {
        this(context, context == null ? null : GraphSource.ofRelations((term, withProposals) -> {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                throw new IllegalStateException(TermDirectory.NO_RUNTIME);
            }
            return runtime.workspace().termRelations(term, null, withProposals ? null : ReviewStatus.CONFIRMED,
                    null, TermRelations.MAX_LIMIT, null, scope.get().get()).relations();
        }), context == null ? null : GraphSource.ofDomainTerms((term, levels, checkedOnly, cancelled) -> {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                throw new IllegalStateException(TermDirectory.NO_RUNTIME);
            }
            return runtime.workspace().domainTermGraph(term,
                    new com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Options(levels, false,
                            scope.get().get(), checkedOnly), cancelled);
        }, com.aresstack.spaceharvester.app.ui.content.StatementProvenance::analyzer), scope);
    }

    TermGraphView(AppContext context, GraphSource source) {
        this(context, source, null, new AtomicReference<>(() -> null));
    }

    TermGraphView(AppContext context, GraphSource source, GraphSource domainSource) {
        this(context, source, domainSource, new AtomicReference<>(() -> null));
    }

    private TermGraphView(AppContext context, GraphSource source, GraphSource domainSource,
                          AtomicReference<Supplier<PageSet>> scope) {
        this.context = context;
        this.source = source;
        this.domainSource = domainSource;
        this.scope = scope;
        root.setOpaque(false);
        depth.addItem("Tiefe 1");
        depth.addItem("Tiefe 2");
        depth.setSelectedIndex(1);
        proposals.setOpaque(false);
        proposals.setToolTipText(PROPOSALS_TIP);
        domain.setToolTipText("Fachsicht: nur bestätigte Fachbegriffe und Synonyme, Verben der Aussagen als Kanten");
        checkedState(false);
        for (javax.swing.JCheckBoxMenuItem item : List.of(domain, checked)) {
            // Wie die Suchoptionen: das Popup bleibt beim Umschalten offen.
            item.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
        }

        JLabel heading = Comic.text("Wissensgraph der Begriffe");
        heading.setFont(Comic.semiBold(15f));
        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        lines.add(heading, BorderLayout.NORTH);
        controls.setOpaque(false);
        controls.add(start);
        controls.add(depth);
        controls.add(proposals);
        optionsMenu.add(domain);
        optionsMenu.add(checked);
        com.aresstack.spaceharvester.app.ui.controls.PopupHover.clearOnClose(optionsMenu, options);
        options.addActionListener(e -> optionsMenu.show(options, 0, options.getHeight()));
        controls.add(showButton);
        controls.add(options);
        head.setOpaque(false);
        head.add(lines, BorderLayout.WEST);
        head.add(controls, BorderLayout.EAST);
        root.add(head, BorderLayout.NORTH);
        root.add(canvas, BorderLayout.CENTER);
        root.add(hint, BorderLayout.SOUTH);

        start.addActionListener(e -> showTerm(start.getText()));
        showButton.addActionListener(e -> showTerm(start.getText()));
        depth.addActionListener(e -> reload());
        proposals.addActionListener(e -> reload());
        domain.addActionListener(e -> {
            proposals.setEnabled(!domain.isSelected());
            proposals.setToolTipText(domain.isSelected() ? PROPOSALS_LOCKED : PROPOSALS_TIP);
            checkedState(domain.isSelected());
            markOptions();
            reload();
        });
        checked.addActionListener(e -> {
            markOptions();
            reload();
        });
        evidence.onOpen(target -> onOpen.accept(target));
        // Aufgeschobene Meldungen des Core holen nach, sobald Belege, Vorschläge oder das Startfeld frei sind.
        javax.swing.event.PopupMenuListener closed = new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                catchUp();
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        };
        evidence.addPopupMenuListener(closed);
        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && root.isShowing()) {
                catchUp();
            }
        });
        suggestions.addPopupMenuListener(closed);
        start.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                catchUp();
            }
        });
        // Nach einem Neustart der Laufzeit an der neuen anmelden, auch wenn der Reiter sichtbar bleibt.
        if (context != null) {
            context.onChange(changed -> javax.swing.SwingUtilities.invokeLater(() -> {
                if (context.runtime() != watched) {
                    watch();
                    followCore();
                }
            }));
        }
        start.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                suggest();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                suggest();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                suggest();
            }
        });
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    GraphSource.Node node = canvas.nodeAt(e.getPoint());
                    if (node != null) {
                        showTerm(node.label());
                    }
                } else if (e.getClickCount() == 1 && canvas.nodeAt(e.getPoint()) == null) {
                    GraphSource.Edge edge = canvas.edgeAt(e.getPoint());
                    if (edge != null && edge.style() == GraphSource.Style.VERB) {
                        showEvidence(edge, e.getX(), e.getY());
                    }
                }
            }
        });
        boolean enabled = source != null;
        start.setEnabled(enabled);
        showButton.setEnabled(enabled);
    }

    public JComponent component() {
        return root;
    }

    /**
     * Startfeld, Tiefe, Vorschläge und „Zeigen“ für die Reiterzeile oben rechts (BG2c); sie verlassen dabei den Kopf
     * des Graphen.
     */
    public JComponent tools() {
        head.remove(controls);
        return controls;
    }

    /**
     * Die Auswahl links (ADR 0029): Graph und Vorschläge am Startfeld kennen nur Beziehungen und Begriffe dieser
     * Seiten. {@code pages} läuft außerhalb des EDT, höchstens einmal je Auswahl; {@code null} oder ein Ergebnis
     * {@code null} heißt alle Seiten. Ein gezeigter Graph lädt neu.
     */
    public void pages(Supplier<PageSet> pages) {
        scope.set(pages == null ? () -> null : once(pages));
        if (root.isShowing()) {
            reload();
        }
    }

    private static Supplier<PageSet> once(Supplier<PageSet> pages) {
        return new Supplier<>() {
            private boolean done;
            private PageSet value;

            @Override
            public synchronized PageSet get() {
                if (!done) {
                    value = pages.get();
                    done = true;
                }
                return value;
            }
        };
    }

    /** Der Reiter oder Bereich wird sichtbar: ein schon gezeigter Graph lädt neu, Änderungen anderer Reiter folgen. */
    public void shown() {
        watch();
        reload();
    }

    /** Laufzeit, für die {@link #watches} gelten. */
    private AppRuntime watched;
    private final List<com.aresstack.spaceharvester.application.observe.Subscription> watches = new ArrayList<>();

    /**
     * Die Fachsicht folgt Änderungen der Analysen, der KI-Freigabe und abgeschlossenen Aufträgen (neue Analysen) per
     * Meldung des Core (ADR 0027), kein Polling. Einmal je Laufzeit angemeldet.
     */
    private void watch() {
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == watched) {
            return;
        }
        // Neue Laufzeit (Neustart, Neuladen): alte Anmeldungen lösen, damit die alte Laufzeit nicht hängen bleibt.
        watches.forEach(com.aresstack.spaceharvester.application.observe.Subscription::close);
        watches.clear();
        watched = runtime;
        if (runtime == null) {
            return;
        }
        Runnable later = () -> javax.swing.SwingUtilities.invokeLater(this::followCore);
        watches.add(runtime.workspace().subscribeStatementAnalyses(later));
        watches.add(runtime.workspace().subscribePageReleases(change -> later.run()));
        watches.add(runtime.workspace().subscribeJobs(status -> {
            if (status.state().terminal()) {
                later.run();
            }
        }));
    }

    /** Eine Meldung des Core steht noch aus, weil der Nutzer gerade tippt oder Belege liest. */
    private boolean coreDirty;
    /** Belege werden gerade gelesen. */
    private boolean evidencePending;
    /** Liest die Sätze der Belege; {@code null} = über die Laufzeit. Tests setzen es fest. */
    private java.util.function.Function<java.util.Map<com.aresstack.spaceharvester.application.knowledge
            .KnowledgeGraph.PageRef, java.util.List<com.aresstack.spaceharvester.application.knowledge
            .ParsedStatements.Statement>>, java.util.Map<String, com.aresstack.spaceharvester.application.standing
            .SentenceText>> sentenceReader;
    /** Ob die Ansicht zu sehen ist; Tests setzen es fest. */
    private java.util.function.BooleanSupplier showing = () -> root.isShowing();
    /** Ob der Nutzer gerade Belege liest, Vorschläge sieht oder tippt; Tests setzen es fest. */
    private java.util.function.BooleanSupplier userBusy =
            () -> evidence.isVisible() || evidencePending || suggestions.isVisible() || typing();

    /**
     * Eine Meldung des Core: eine gezeigte Fachsicht rechnet still neu. Sie lässt Startfeld, Vorschläge und Belege
     * stehen; tippt der Nutzer gerade oder liest er Belege, wartet sie, bis er fertig ist.
     */
    private void followCore() {
        if (!domainMode() || centre == null) {
            return;
        }
        // Verborgen oder der Nutzer beschäftigt: vormerken und beim Sichtbarwerden bzw. danach nachholen.
        if (!showing.getAsBoolean() || userBusy.getAsBoolean()) {
            coreDirty = true;
            return;
        }
        coreDirty = false;
        fetch(domainSource, centre, false);
    }

    /** Ob im Startfeld gerade ein anderer Begriff als die Mitte getippt wird. */
    private boolean typing() {
        String text = start.getText() == null ? "" : start.getText().trim();
        return start.isFocusOwner() && !text.equals(centre);
    }

    /** Eine aufgeschobene Meldung nachholen, sobald der Nutzer fertig ist. */
    private void catchUp() {
        if (coreDirty) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                // Inzwischen kann ein anderer Abruf die Meldung schon abgedeckt haben.
                if (coreDirty) {
                    followCore();
                }
            });
        }
    }

    private void reload() {
        if (centre != null) {
            showTerm(centre);
        }
    }

    /** {@code listener} öffnet die Seite eines Belegs an der Stelle (Overlay „Belege“ der Fachsicht). */
    public void onOpen(java.util.function.Consumer<EvidencePopup.Target> listener) {
        onOpen = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Punkt am Filter-Symbol und Tooltip mit den aktiven Optionen, sobald eine vom Standard abweicht. */
    void markOptions() {
        List<String> active = new ArrayList<>();
        if (domain.isSelected()) {
            active.add(domain.getText());
            if (checked.isSelected()) {
                active.add(checked.getText());
            }
        }
        options.marked(!active.isEmpty());
        options.setToolTipText(active.isEmpty() ? OPTIONS_TIP : "Optionen – aktiv: " + String.join(", ", active));
    }

    /** „Nur geprüft“ wirkt nur in der Fachsicht; gesperrt nennt der Tooltip den Grund. */
    private void checkedState(boolean enabled) {
        checked.setEnabled(enabled);
        checked.setToolTipText(enabled ? CHECKED_TIP : CHECKED_LOCKED);
    }

    IconButton optionsButton() {
        return options;
    }

    /** Ob die Fachsicht gezeigt wird. */
    private boolean domainMode() {
        return domain.isSelected() && domainSource != null;
    }

    /** Zeichnet den Graphen um {@code term}; leer räumt ihn ab. */
    void showTerm(String term) {
        suggestions.setVisible(false);
        GraphSource active = domainMode() ? domainSource : source;
        if (active == null || term == null || term.isBlank()) {
            return;
        }
        String wanted = term.trim();
        centre = wanted;
        coreDirty = false;
        if (!wanted.equals(start.getText())) {
            quiet = true;
            start.setText(wanted);
            quiet = false;
        }
        fetch(active, wanted, true);
    }

    /** Liest den Graphen um {@code wanted} im Hintergrund; {@code announce} zeigt „Lädt …“. */
    private void fetch(GraphSource active, String wanted, boolean announce) {
        int current = ++request;
        int levels = depth.getSelectedIndex() + 1;
        boolean withProposals = proposals.isSelected();
        boolean checkedOnly = domainMode() && checked.isSelected();
        if (announce) {
            hint.setText("Lädt …");
        }
        java.util.function.BooleanSupplier overtaken = () -> current != request;
        Background.run(() -> active.around(wanted, levels, withProposals, checkedOnly, overtaken), graph -> {
            if (current != request) {
                return;
            }
            if (!announce && userBusy.getAsBoolean()) {
                // Stilles Ergebnis, aber der Nutzer hat inzwischen Belege geöffnet oder tippt: zurückhalten und nach
                // dem Schließen neu rechnen, statt sein Overlay zu schließen.
                coreDirty = true;
                return;
            }
            show(graph);
        }, failure -> {
            if (current != request) {
                return;
            }
            if (!announce && userBusy.getAsBoolean()) {
                coreDirty = true;
                return;
            }
            if (!(failure instanceof java.util.concurrent.CancellationException)) {
                show(GraphSource.Graph.EMPTY);
                hint.setText("Graph nicht lesbar: " + failure.getMessage());
            }
        });
    }

    /** Zeigt einen fertigen Graphen. */
    void show(GraphSource.Graph graph) {
        evidence.setVisible(false);
        canvas.domain(domainMode());
        canvas.graph(graph);
        boolean fach = domainMode();
        boolean checkedOnly = fach && checked.isSelected();
        boolean alone = graph.nodes().size() == 1;
        String text;
        if (graph.nodes().isEmpty()) {
            text = graph.empty() == null ? " " : graph.empty();
        } else if (alone) {
            text = checkedOnly ? NO_CHECKED_VERBS : fach ? NO_VERBS : NO_NEIGHBOURS;
        } else {
            text = Texts.count(graph.nodes().size(), fach ? "Fachbegriff" : "Begriff", fach ? "Fachbegriffe"
                    : "Begriffe") + " · " + Texts.count(graph.edges().size(), fach ? "Verbindung" : "Beziehung",
                    fach ? "Verbindungen" : "Beziehungen") + (graph.truncated() ? " · " + TRUNCATED : "");
        }
        if (!graph.note().isBlank()) {
            text = (text.isBlank() ? "" : text + " · ") + graph.note();
        }
        if (checkedOnly && !graph.nodes().isEmpty() && !alone) {
            text = text + " · nur geprüft";
        }
        hint.setText(text);
    }

    /** Overlay „Belege“ einer Verb-Kante: Sätze im Hintergrund lesen, dann zeigen. */
    private void showEvidence(GraphSource.Edge edge, int x, int y) {
        String title = canvas.label(edge.from()) + " " + edge.label().replaceFirst("( ≠)? · \\d+$", "") + " "
                + canvas.label(edge.to());
        int total = edgeCount(edge);
        AppRuntime runtime = context == null ? null : context.runtime();
        int current = ++evidenceRequest;
        java.util.function.Function<java.util.Map<com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph
                .PageRef, java.util.List<com.aresstack.spaceharvester.application.knowledge.ParsedStatements
                .Statement>>, java.util.Map<String, com.aresstack.spaceharvester.application.standing.SentenceText>>
                reader = sentenceReader != null ? sentenceReader
                : runtime == null ? null : runtime.workspace()::statementSentences;
        if (reader == null) {
            evidence.fill(title, total, edge.evidence(), java.util.Map.of(), true).showAt(canvas, x, y);
            return;
        }
        java.util.Map<com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph.PageRef,
                java.util.List<com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Statement>> byPage =
                new java.util.LinkedHashMap<>();
        edge.evidence().forEach(item -> byPage.computeIfAbsent(item.page(), unused -> new ArrayList<>())
                .add(item.statement()));
        int graphRequest = request;
        evidencePending = true;
        Background.run(() -> reader.apply(byPage), sentences -> {
            // Nur die Antwort auf den letzten Klick beendet das Lesen; eine überholte lässt es stehen.
            if (current != evidenceRequest) {
                return;
            }
            evidencePending = false;
            if (current == evidenceRequest && graphRequest == request && canvas.isShowing()) {
                evidence.fill(title, total, edge.evidence(), sentences, false).showAt(canvas, x, y);
            }
            catchUp();
        }, failure -> {
            if (current != evidenceRequest) {
                return;
            }
            evidencePending = false;
            if (current == evidenceRequest && graphRequest == request && canvas.isShowing()) {
                evidence.fill(title, total, edge.evidence(), java.util.Map.of(), true).showAt(canvas, x, y);
            }
            catchUp();
        });
    }

    /** Zahl aller Belege aus der Beschriftung „Grundform · N“; sonst die mitgelieferten. */
    static int edgeCount(GraphSource.Edge edge) {
        if (edge.label() != null) {
            int dot = edge.label().lastIndexOf(" · ");
            if (dot >= 0) {
                try {
                    return Integer.parseInt(edge.label().substring(dot + 3).strip());
                } catch (NumberFormatException ignored) {
                    // fällt auf die Belege zurück
                }
            }
        }
        return edge.evidence().size();
    }

    /** Begriffssuche des Core am Startfeld: passende Begriffe als Vorschläge unter dem Feld. */
    private void suggest() {
        if (quiet || context == null || context.runtime() == null || !start.isShowing()) {
            return;
        }
        String q = start.getText() == null ? "" : start.getText().trim();
        int current = ++suggestRequest;
        if (q.length() < 2) {
            suggestions.setVisible(false);
            return;
        }
        AppRuntime runtime = context.runtime();
        Supplier<PageSet> pages = scope.get();
        boolean fach = domainMode();
        // Fachsicht: nur bestätigte Fachbegriffe und Synonyme, sonst alle Begriffe der Auswahl.
        Background.run(() -> fach ? runtime.workspace().domainTermSuggestions(q, SUGGESTIONS)
                : runtime.workspace().terms(new TermQueries.TermSearch(null, null, null, q, false,
                SUGGESTIONS, null, null, null, pages.get())).terms().stream().map(TermQueries.TermSummary::term)
                .toList(), terms -> {
            if (current != suggestRequest || !start.isShowing()) {
                return;
            }
            suggestions.setVisible(false);
            suggestions.removeAll();
            for (String term : terms) {
                JMenuItem item = new JMenuItem(term);
                item.addActionListener(e -> showTerm(term));
                suggestions.add(item);
            }
            if (!terms.isEmpty()) {
                suggestions.setFocusable(false);
                suggestions.show(start, 0, start.getHeight());
            }
        }, failure -> suggestions.setVisible(false));
    }

    // ---- für Tests

    String centre() {
        return centre;
    }

    /** Wie sichtbar, und eine Meldung des Core trifft ein, während der Nutzer {@code busy} beschäftigt ist. */
    void coreChanged(boolean busy) {
        showing = () -> true;
        userBusy = () -> busy;
        followCore();
    }

    /** Wie sichtbar, und eine Meldung des Core trifft ein; ob der Nutzer beschäftigt ist, entscheidet die Ansicht. */
    void coreChangedWhileShowing() {
        showing = () -> true;
        followCore();
    }

    /** Tests: der Nutzer öffnet Belege bzw. schließt sie wieder. */
    void userBusy(boolean busy) {
        userBusy = () -> busy;
        if (!busy) {
            catchUp();
        }
    }

    /** Tests: Sätze der Belege ohne Laufzeit. */
    void sentenceReader(java.util.function.Function<java.util.Map<com.aresstack.spaceharvester.application.knowledge
            .KnowledgeGraph.PageRef, java.util.List<com.aresstack.spaceharvester.application.knowledge
            .ParsedStatements.Statement>>, java.util.Map<String, com.aresstack.spaceharvester.application.standing
            .SentenceText>> reader) {
        sentenceReader = reader;
    }

    /** Der Nutzer ist fertig (Belege oder Vorschläge zu, Startfeld verlassen). */
    void userDone() {
        userBusy = () -> false;
        catchUp();
    }

    PlaceholderField startField() {
        return start;
    }

    JComboBox<String> depthChoice() {
        return depth;
    }

    javax.swing.JCheckBox proposalsChoice() {
        return proposals;
    }

    javax.swing.JCheckBoxMenuItem domainChoice() {
        return domain;
    }

    javax.swing.JCheckBoxMenuItem checkedChoice() {
        return checked;
    }

    EvidencePopup evidencePopup() {
        return evidence;
    }

    boolean coreDirty() {
        return coreDirty;
    }

    void showEvidenceFor(GraphSource.Edge edge) {
        showEvidence(edge, 10, 10);
    }

    String hint() {
        return hint.getText();
    }

    Canvas canvas() {
        return canvas;
    }

    /** Die Zeichenfläche: Kanten mit Beschriftung, darüber die Knoten als Comic-Kästen mit Schlagschatten. */
    static final class Canvas extends JComponent {

        private static final int PAD = 14;
        private static final int BOX_HEIGHT = 28;
        private GraphSource.Graph graph = GraphSource.Graph.EMPTY;

        Canvas() {
            setOpaque(true);
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(640, 420));
            setFont(Comic.semiBold(13f));
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        }

        /** Fachsicht: Bögen, freigestellte Beschriftungen und gedrehter Ring; die Bestandssicht zeichnet wie bisher. */
        private boolean domain;

        void domain(boolean domain) {
            this.domain = domain;
        }

        void graph(GraphSource.Graph graph) {
            this.graph = graph;
            repaint();
        }

        GraphSource.Graph graph() {
            return graph;
        }

        /** Wortlaut eines Knotens oder der Schlüssel, wenn es ihn nicht gibt. */
        String label(String key) {
            return graph.nodes().stream().filter(node -> node.key().equals(key)).map(GraphSource.Node::label)
                    .findFirst().orElse(key);
        }

        /** Die Kante unter dem Punkt (Beschriftung oder höchstens 5 px neben der Linie) oder {@code null}. */
        GraphSource.Edge edgeAt(Point point) {
            Map<String, Rectangle2D> boxes = layout(getWidth(), getHeight());
            GraphSource.Edge best = null;
            double bestDistance = 6;
            for (Segment segment : segments(boxes)) {
                if (segment.tag() != null && segment.tag().contains(point)) {
                    return segment.edge();
                }
                double distance = segment.distance(point);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = segment.edge();
                }
            }
            return best;
        }

        /** Eine gezeichnete Kante: Endpunkte und Kasten der Beschriftung ({@code null} ohne). */
        record Segment(GraphSource.Edge edge, Point2D a, Point2D b, Rectangle2D tag, Point2D control) {

            /** Die Linie: gerade oder, mit Kontrollpunkt, als Bogen. */
            java.awt.Shape shape() {
                return control == null ? new Line2D.Double(a, b)
                        : new java.awt.geom.QuadCurve2D.Double(a.getX(), a.getY(), control.getX(), control.getY(),
                        b.getX(), b.getY());
            }

            /** Ob die Linie das Rechteck schneidet. */
            boolean crosses(Rectangle2D area) {
                Point2D last = a;
                int steps = control == null ? 1 : 16;
                for (int i = 1; i <= steps; i++) {
                    Point2D next = at(i / (double) steps);
                    if (area.intersectsLine(last.getX(), last.getY(), next.getX(), next.getY())) {
                        return true;
                    }
                    last = next;
                }
                return false;
            }

            /** Abstand eines Punkts von der Linie. */
            double distance(Point point) {
                if (control == null) {
                    return new Line2D.Double(a, b).ptSegDist(point);
                }
                double best = Double.MAX_VALUE;
                Point2D last = a;
                for (int i = 1; i <= 16; i++) {
                    Point2D next = at(i / 16.0);
                    best = Math.min(best, new Line2D.Double(last, next).ptSegDist(point));
                    last = next;
                }
                return best;
            }

            /** Punkt auf dem Bogen bei {@code t} zwischen 0 und 1. */
            Point2D at(double t) {
                if (control == null) {
                    return new Point2D.Double(a.getX() + (b.getX() - a.getX()) * t, a.getY() + (b.getY() - a.getY()) * t);
                }
                double u = 1 - t;
                return new Point2D.Double(u * u * a.getX() + 2 * u * t * control.getX() + t * t * b.getX(),
                        u * u * a.getY() + 2 * u * t * control.getY() + t * t * b.getY());
            }
        }

        /** Lage aller Kanten, parallele nebeneinander; dieselbe Rechnung wie beim Zeichnen. */
        List<Segment> segments(Map<String, Rectangle2D> boxes) {
            List<Segment> segments = new ArrayList<>();
            Map<String, Integer> pairs = new java.util.HashMap<>();
            for (GraphSource.Edge edge : graph.edges()) {
                pairs.merge(pairKey(edge), 1, Integer::sum);
            }
            Map<String, Integer> seen = new java.util.HashMap<>();
            for (GraphSource.Edge edge : graph.edges()) {
                Rectangle2D from = boxes.get(edge.from());
                Rectangle2D to = boxes.get(edge.to());
                String pair = pairKey(edge);
                int index = seen.merge(pair, 1, Integer::sum) - 1;
                if (from == null || to == null) {
                    continue;
                }
                double offset = (index - (pairs.get(pair) - 1) / 2.0) * PARALLEL_GAP;
                Point2D a = exit(from, to);
                Point2D b = exit(to, from);
                if (offset != 0) {
                    boolean flip = edge.from().compareTo(edge.to()) > 0;
                    double dx = b.getX() - a.getX();
                    double dy = b.getY() - a.getY();
                    double length = Math.hypot(dx, dy);
                    if (length > 0) {
                        double sign = flip ? -1 : 1;
                        double nx = -dy / length * offset * sign;
                        double ny = dx / length * offset * sign;
                        a = new Point2D.Double(a.getX() + nx, a.getY() + ny);
                        b = new Point2D.Double(b.getX() + nx, b.getY() + ny);
                    }
                }
                Point2D control = null;
                Point2D centre = graph.nodes().isEmpty() ? null : boxes.get(graph.nodes().get(0).key()) == null
                        ? null : new Point2D.Double(boxes.get(graph.nodes().get(0).key()).getCenterX(),
                        boxes.get(graph.nodes().get(0).key()).getCenterY());
                if (domain && centre != null && offset == 0 && depthOf(edge.from()) > 0 && depthOf(edge.to()) > 0
                        && new Line2D.Double(a, b).ptSegDist(centre) < 60) {
                    // Kante zwischen zwei Nachbarn, die durch die Mitte liefe: als Bogen nach außen.
                    double mx = (a.getX() + b.getX()) / 2;
                    double my = (a.getY() + b.getY()) / 2;
                    double dx = b.getX() - a.getX();
                    double dy = b.getY() - a.getY();
                    double length = Math.max(1, Math.hypot(dx, dy));
                    double nx = -dy / length;
                    double ny = dx / length;
                    if ((mx + nx - centre.getX()) * nx + (my + ny - centre.getY()) * ny < 0) {
                        nx = -nx;
                        ny = -ny;
                    }
                    double bend = Math.max(45, length * 0.22);
                    control = new Point2D.Double(mx + nx * bend * 2, my + ny * bend * 2);
                }
                segments.add(new Segment(edge, a, b, null, control));
            }
            return placeLabels(segments, boxes);
        }

        /** Abstand einer Beschriftung zu Knoten, anderen Beschriftungen und fremden Kanten. */
        private static final double LABEL_GAP = 4;
        private static final double[] LABEL_POSITIONS = {0.5, 0.42, 0.58, 0.34, 0.66, 0.26, 0.74, 0.2, 0.8};

        /**
         * Setzt jede Beschriftung auf ihre eigene Kante, so weit von der Mitte verschoben, bis sie mit
         * {@link #LABEL_GAP} Abstand weder Knoten noch andere Beschriftungen berührt und keine fremde Kante sie kreuzt;
         * findet sich keine freie Stelle, die mit den wenigsten Berührungen.
         */
        private List<Segment> placeLabels(List<Segment> segments, Map<String, Rectangle2D> boxes) {
            FontMetrics metrics = getFontMetrics(Comic.regular(11f));
            List<Rectangle2D> placed = new ArrayList<>();
            List<Segment> result = new ArrayList<>();
            for (Segment segment : segments) {
                String label = labelOf(segment.edge());
                if (label == null) {
                    result.add(segment);
                    continue;
                }
                double w = metrics.stringWidth(label) + 10;
                double h = metrics.getHeight() + 2;
                Rectangle2D best = null;
                int bestHits = Integer.MAX_VALUE;
                for (double t : domain ? LABEL_POSITIONS : new double[] {0.5}) {
                    Point2D middle = segment.at(t);
                    Rectangle2D candidate = new Rectangle2D.Double(middle.getX() - w / 2, middle.getY() - h / 2, w,
                            h);
                    Rectangle2D padded = new Rectangle2D.Double(candidate.getX() - LABEL_GAP,
                            candidate.getY() - LABEL_GAP, w + 2 * LABEL_GAP, h + 2 * LABEL_GAP);
                    int hits = 0;
                    for (Rectangle2D other : placed) {
                        hits += other.intersects(padded) ? 1 : 0;
                    }
                    for (Rectangle2D box : boxes.values()) {
                        hits += box.intersects(padded) ? 1 : 0;
                    }
                    for (Segment other : segments) {
                        if (other != segment && other.crosses(padded)) {
                            hits++;
                        }
                    }
                    if (hits < bestHits) {
                        best = candidate;
                        bestHits = hits;
                    }
                    if (hits == 0 || !domain) {
                        break;
                    }
                }
                placed.add(best);
                result.add(new Segment(segment.edge(), segment.a(), segment.b(), best, segment.control()));
            }
            return result;
        }


        private int depthOf(String key) {
            return graph.nodes().stream().filter(node -> node.key().equals(key)).mapToInt(GraphSource.Node::depth)
                    .findFirst().orElse(0);
        }

        /** Beschriftung einer Kante: Fachsicht eigene, Synonym keine, sonst die Art der Beziehung. */
        static String labelOf(GraphSource.Edge edge) {
            return switch (edge.style()) {
                case SYNONYM -> null;
                case VERB -> edge.label();
                case RELATION -> Relation.typeText(edge.type());
            };
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            if (nodeAt(event.getPoint()) != null) {
                return null;
            }
            GraphSource.Edge edge = edgeAt(event.getPoint());
            if (edge == null) {
                return null;
            }
            return switch (edge.style()) {
                case SYNONYM -> "Synonym";
                case VERB -> edge.tip() == null ? "Klick zeigt die Belege" : edge.tip();
                case RELATION -> null;
            };
        }

        /** Wortlaute der gezeigten Knoten, die Mitte zuerst. */
        List<String> labels() {
            return graph.nodes().stream().map(GraphSource.Node::label).toList();
        }

        /** Der Knoten unter dem Punkt oder {@code null}. */
        GraphSource.Node nodeAt(Point point) {
            Map<String, Rectangle2D> boxes = layout(getWidth(), getHeight());
            // Später gemalte Knoten liegen oben: rückwärts suchen, damit der sichtbare Knoten getroffen wird.
            List<GraphSource.Node> nodes = graph.nodes();
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Rectangle2D box = boxes.get(nodes.get(i).key());
                if (box != null && box.contains(point)) {
                    return nodes.get(i);
                }
            }
            return null;
        }

        /**
         * Kästen je Knoten: die Mitte im Zentrum, Tiefe 1 gleichmäßig im Kreis, Tiefe 2 außen im Fächer ihres
         * Vorgängers.
         */
        Map<String, Rectangle2D> layout(int width, int height) {
            Map<String, Rectangle2D> boxes = new LinkedHashMap<>();
            if (graph.nodes().isEmpty() || width <= 0 || height <= 0) {
                return boxes;
            }
            FontMetrics metrics = getFontMetrics(getFont());
            double cx = width / 2.0;
            double cy = height / 2.0;
            List<GraphSource.Node> first = new ArrayList<>();
            Map<String, List<GraphSource.Node>> children = new LinkedHashMap<>();
            for (GraphSource.Node node : graph.nodes()) {
                if (node.depth() == 1) {
                    first.add(node);
                } else if (node.depth() >= 2) {
                    children.computeIfAbsent(node.parent(), k -> new ArrayList<>()).add(node);
                }
            }
            double radius = Math.min(width, height) / 2.0 - BOX_HEIGHT;
            double r1 = children.isEmpty() ? radius * 0.75 : radius * 0.5;
            double r2 = radius * 0.95;
            Map<String, Point2D> centres = new LinkedHashMap<>();
            Map<String, Double> angles = new LinkedHashMap<>();
            centres.put(graph.nodes().get(0).key(), new Point2D.Double(cx, cy));
            int n = first.size();
            // Ab vier Nachbarn um einen halben Schritt gedreht: kein breiter Kasten genau links oder rechts der Mitte.
            double turn = domain && n >= 4 && n % 2 == 0 ? Math.PI / n : 0;
            for (int i = 0; i < n; i++) {
                double angle = -Math.PI / 2 + turn + 2 * Math.PI * i / n;
                angles.put(first.get(i).key(), angle);
                centres.put(first.get(i).key(), new Point2D.Double(cx + r1 * Math.cos(angle) * 1.35,
                        cy + r1 * Math.sin(angle)));
            }
            double sector = n == 0 ? 2 * Math.PI : 2 * Math.PI / n;
            for (Map.Entry<String, List<GraphSource.Node>> entry : children.entrySet()) {
                double base = angles.getOrDefault(entry.getKey(), -Math.PI / 2);
                List<GraphSource.Node> kids = entry.getValue();
                double step = Math.min(0.45, sector * 0.8 / Math.max(1, kids.size()));
                for (int j = 0; j < kids.size(); j++) {
                    double angle = base + (j - (kids.size() - 1) / 2.0) * step;
                    centres.put(kids.get(j).key(), new Point2D.Double(cx + r2 * Math.cos(angle) * 1.35,
                            cy + r2 * Math.sin(angle)));
                }
            }
            for (GraphSource.Node node : graph.nodes()) {
                Point2D c = centres.get(node.key());
                if (c == null) {
                    continue;
                }
                double w = metrics.stringWidth(node.label()) + 2 * PAD;
                double x = Math.max(4, Math.min(width - w - 8, c.getX() - w / 2));
                double y = Math.max(4, Math.min(height - BOX_HEIGHT - 8, c.getY() - BOX_HEIGHT / 2.0));
                boxes.put(node.key(), new Rectangle2D.Double(x, y, w, BOX_HEIGHT));
            }
            return boxes;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                Map<String, Rectangle2D> boxes = layout(getWidth(), getHeight());
                // Mehrere Beziehungen zwischen denselben Begriffen nebeneinander zeichnen statt übereinander.
                for (Segment segment : segments(boxes)) {
                    paintEdge(g2, segment);
                }
                for (GraphSource.Node node : graph.nodes()) {
                    Rectangle2D box = boxes.get(node.key());
                    if (box != null) {
                        paintNode(g2, node, box);
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        private static final double PARALLEL_GAP = 22;

        /** Gleicher Schlüssel für beide Richtungen eines Begriffspaars. */
        static String pairKey(GraphSource.Edge edge) {
            return edge.from().compareTo(edge.to()) <= 0 ? edge.from() + "\u0000" + edge.to()
                    : edge.to() + "\u0000" + edge.from();
        }

        private void paintEdge(Graphics2D g2, Segment segment) {
            GraphSource.Edge edge = segment.edge();
            Point2D a = segment.a();
            Point2D b = segment.b();
            Color color = switch (edge.style()) {
                case VERB -> Comic.INK;
                case SYNONYM -> Comic.MUTED;
                case RELATION -> edgeColor(edge.type());
            };
            g2.setColor(color);
            if (edge.style() == GraphSource.Style.SYNONYM) {
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            } else if (edge.style() == GraphSource.Style.VERB && edge.trust() != null) {
                g2.setStroke(trustStroke(edge));
            } else {
                g2.setStroke(edge.confirmed() ? new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        : new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f},
                        0f));
            }
            g2.draw(segment.shape());
            if (edge.directed()) {
                arrow(g2, segment.control() == null ? a : segment.at(0.9), b);
            }
            Rectangle2D box = segment.tag();
            String label = labelOf(edge);
            if (box == null || label == null) {
                return;
            }
            Font font = Comic.regular(11f);
            FontMetrics metrics = g2.getFontMetrics(font);
            RoundRectangle2D tag = new RoundRectangle2D.Double(box.getX(), box.getY(), box.getWidth(),
                    box.getHeight(), 10, 10);
            g2.setColor(Color.WHITE);
            g2.fill(tag);
            g2.setStroke(new BasicStroke(1.2f));
            g2.setColor(color);
            g2.draw(tag);
            g2.setFont(font);
            float x = (float) (box.getCenterX() - metrics.stringWidth(label) / 2.0);
            float y = (float) (box.getY() + 1 + metrics.getAscent());
            int dot = edge.style() == GraphSource.Style.VERB ? label.lastIndexOf(" · ") : -1;
            if (dot < 0) {
                g2.setColor(Comic.INK);
                g2.drawString(label, x, y);
            } else {
                // „Grundform · N“: die Zahl gedämpft.
                String verb = label.substring(0, dot);
                g2.setColor(Comic.INK);
                g2.drawString(verb, x, y);
                g2.setColor(Comic.MUTED);
                g2.drawString(label.substring(dot), x + metrics.stringWidth(verb), y);
            }
        }

        /**
         * Linienart einer Verb-Kante nach Vertrauen (Paket W2), ohne Farbe: kräftig geprüft, dünn einig, gestrichelt
         * ungeprüft, gepunktet abweichend.
         */
        static BasicStroke trustStroke(GraphSource.Edge edge) {
            if (edge.differs()) {
                return new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{0.5f, 5f},
                        0f);
            }
            return switch (edge.trust()) {
                case GEPRUEFT -> new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
                case EINIG -> new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
                case UNGEPRUEFT, ABWEICHEND -> new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                        new float[]{7f, 5f}, 0f);
            };
        }

        private static void arrow(Graphics2D g2, Point2D from, Point2D to) {
            double angle = Math.atan2(to.getY() - from.getY(), to.getX() - from.getX());
            double size = 10;
            Path2D head = new Path2D.Double();
            head.moveTo(to.getX(), to.getY());
            head.lineTo(to.getX() - size * Math.cos(angle - 0.4), to.getY() - size * Math.sin(angle - 0.4));
            head.lineTo(to.getX() - size * Math.cos(angle + 0.4), to.getY() - size * Math.sin(angle + 0.4));
            head.closePath();
            g2.setStroke(new BasicStroke(1.5f));
            g2.fill(head);
        }

        /** Wo die Linie von der Mitte von {@code box} zur Mitte von {@code towards} den Kasten verlässt. */
        private static Point2D exit(Rectangle2D box, Rectangle2D towards) {
            double dx = towards.getCenterX() - box.getCenterX();
            double dy = towards.getCenterY() - box.getCenterY();
            if (dx == 0 && dy == 0) {
                return new Point2D.Double(box.getCenterX(), box.getCenterY());
            }
            double tx = dx == 0 ? Double.MAX_VALUE : box.getWidth() / 2 / Math.abs(dx);
            double ty = dy == 0 ? Double.MAX_VALUE : box.getHeight() / 2 / Math.abs(dy);
            double t = Math.min(tx, ty);
            return new Point2D.Double(box.getCenterX() + dx * t, box.getCenterY() + dy * t);
        }

        private void paintNode(Graphics2D g2, GraphSource.Node node, Rectangle2D box) {
            if (node.synonym() && node.depth() > 0) {
                // Synonym der Fachsicht: kleiner, nur umrandet, ohne Füllung und ohne Schatten.
                RoundRectangle2D outline = new RoundRectangle2D.Double(box.getX() + 4, box.getY() + 3,
                        box.getWidth() - 8, box.getHeight() - 6, 14, 14);
                g2.setColor(getBackground());
                g2.fill(outline);
                g2.setColor(Comic.INK);
                g2.setStroke(new BasicStroke(1.4f));
                g2.draw(outline);
                Font small = Comic.regular(12f);
                FontMetrics metrics = g2.getFontMetrics(small);
                g2.setFont(small);
                g2.drawString(node.label(), (float) (box.getCenterX() - metrics.stringWidth(node.label()) / 2.0),
                        (float) (box.getCenterY() - metrics.getHeight() / 2.0 + metrics.getAscent()));
                return;
            }
            RoundRectangle2D shape = new RoundRectangle2D.Double(box.getX(), box.getY(), box.getWidth(),
                    box.getHeight(), 16, 16);
            RoundRectangle2D shadow = new RoundRectangle2D.Double(box.getX() + 3, box.getY() + 3, box.getWidth(),
                    box.getHeight(), 16, 16);
            g2.setColor(Comic.INK);
            g2.fill(shadow);
            g2.setColor(node.depth() == 0 ? Comic.YELLOW : node.depth() == 1 ? Color.WHITE : Comic.SURFACE);
            g2.fill(shape);
            g2.setColor(Comic.INK);
            g2.setStroke(new BasicStroke(node.depth() == 0 ? 2.6f : 2f));
            g2.draw(shape);
            Font font = node.depth() == 0 ? Comic.semiBold(14f) : getFont();
            FontMetrics metrics = g2.getFontMetrics(font);
            g2.setFont(font);
            g2.setColor(node.depth() == 2 ? Comic.TEXT : Comic.INK);
            g2.drawString(node.label(), (float) (box.getCenterX() - metrics.stringWidth(node.label()) / 2.0),
                    (float) (box.getCenterY() - metrics.getHeight() / 2.0 + metrics.getAscent()));
        }

        /** Farbe je Art: Abkürzung blau, Synonym grün, Schreibvariante orange, Verwandt grau. */
        static Color edgeColor(String type) {
            return switch (String.valueOf(type)) {
                case "ABBREVIATION" -> Comic.BLUE;
                case "SYNONYM" -> Comic.GREEN;
                case "VARIANT" -> Comic.ORANGE;
                default -> Comic.MUTED;
            };
        }
    }
}
