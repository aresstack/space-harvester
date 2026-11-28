package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.observe.Coalescer;
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
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Der Reiter „Graph“ unter Analysen ohne Seite: ein Wissensgraph um einen Startbegriff. Begriffe sind Knoten, Beziehungen Kanten,
 * beschriftet mit ihrer Art; bestätigte durchgezogen, auf Wunsch auch vorgeschlagene gestrichelt. Die Mitte steht im
 * Zentrum, ihre Nachbarn (Tiefe 1) im Kreis darum, deren Nachbarn (Tiefe 2) außen bei ihrem Vorgänger. Ein Doppelklick
 * auf einen Knoten macht ihn zur Mitte. Die Daten kommen aus einer {@link GraphSource}, außerhalb des EDT. Nur auf dem
 * EDT.
 */
public final class TermGraphView {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TermGraphView.class);

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
            + "bestätigt oder vom Prüfschritt als stimmig bewertet), dünn, wenn Parser und KI den Satz gleich lesen "
            + "(einig), gestrichelt, wenn nicht verglichen werden konnte (nur eine Lesart, eine andere Fassung oder "
            + "kein Gegenstück), gepunktet mit „≠“, wenn Parser und KI ihn verschieden lesen und kein Beleg geprüft ist "
            + "(abweichend). Verglichen werden Verb, Handelnde und Betroffene, nur wenn beide Analysatoren die Seite in "
            + "derselben Fassung gelesen haben. Empfänger und Präpositionalobjekte werden nicht verglichen: Eine Kante, die "
            + "an ihnen endet, ist nie einig und bleibt gestrichelt, auch wenn Parser und KI den Rest gleich lesen. "
            + "Abweichend wird sie nur durch ein anderes Verb oder einen anderen Handelnden, nicht durch andere "
            + "Betroffene. Im Overlay steht die Stufe an jedem Beleg, bei einem abweichenden auch, "
            + "wie der andere Analysator ihn liest. „Nur geprüft“ zählt nur geprüfte Belege; Verbindungen ohne solche "
            + "fallen weg.";
    static final String NO_NEIGHBOURS = "Zu diesem Begriff gibt es noch keine Beziehungen.";
    static final String NO_VERBS = "Zu diesem Fachbegriff gibt es auf den analysierten, für dich sichtbaren Seiten "
            + "noch keine Aussagen mit anderen Fachbegriffen.";
    /** Leer unter „Nur geprüft“: Es fehlen geprüfte Aussagen, nicht Aussagen überhaupt (Prüfen statt Neuanalyse). */
    static final String NO_CHECKED_VERBS = "Zu diesem Fachbegriff gibt es noch keine geprüften Aussagen mit anderen "
            + "Fachbegriffen.";
    public static final String DOMAIN = "Nur Fachbegriffe";
    public static final String CHECKED = "Nur geprüft";
    public static final String CHECKED_TIP = "Nur Verbindungen mit geprüften Belegen; die Zahl zählt nur diese";
    public static final String CHECKED_LOCKED = "Gilt nur mit „Nur Fachbegriffe“";
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
    /**
     * Vor dem Zähler: eine Entscheidung (✓/✕), die erst nach dem Schließen des Overlays scheiterte (AT W3) – rot mit
     * Kreispfeil zum Wiederholen oder gelb „inzwischen geändert“; sonst unsichtbar.
     */
    private final com.aresstack.spaceharvester.app.ui.controls.StatusLabel reviewLine =
            new com.aresstack.spaceharvester.app.ui.controls.StatusLabel();
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
            return runtime.workspace().domainTermGraph(term, domainOptions(levels, scope.get().get(), checkedOnly),
                    cancelled);
        }, com.aresstack.spaceharvester.app.ui.content.StatementProvenance::analyzer), scope);
    }

    /** Optionen der Fachsicht: nie veraltete Aussagen, Bereich und „Nur geprüft“ wie gewählt. */
    static com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Options domainOptions(int levels,
            PageSet pages, boolean checkedOnly) {
        return new com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Options(levels, false, pages,
                checkedOnly);
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
        // Bis die Fachsicht für einen neuen Graphen ihre Lage gefunden hat (abseits des EDT), dreht der Ring.
        com.aresstack.spaceharvester.app.ui.controls.BusyOverlay canvasBusy =
                new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(canvas);
        canvas.onBusy(canvasBusy::setBusy);
        root.add(canvasBusy, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setOpaque(false);
        reviewLine.setVisible(false);
        south.add(reviewLine, BorderLayout.WEST);
        south.add(hint, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

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
        if (context != null) {
            // ✓/✕ je Beleg (W3): gespeichert abseits des EDT; der Graph zieht über den Beobachter nach, sobald das
            // Overlay zu ist (userBusy hält eine stille Neuberechnung bis dahin zurück).
            evidence.reviewing(EvidencePopup.Reviewing.of(context::runtime), Background::run);
        }
        evidence.onClosedFailure(failure -> reviewNote(failure.note(() -> reviewNote(null))));
        // Aufgeschobene Meldungen des Core holen nach, sobald Belege, Vorschläge oder das Startfeld frei sind.
        javax.swing.event.PopupMenuListener closed = new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                // „inzwischen geändert“ ist gesehen, sobald man wieder prüft; ein Fehler mit Kreispfeil bleibt stehen.
                com.aresstack.spaceharvester.app.ui.controls.StatusNote shown = reviewNote();
                if (shown != null && shown.icon() == null) {
                    reviewNote(null);
                }
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
                    : "Begriffe") + " · " + Texts.count(connections(graph), fach ? "Verbindung" : "Beziehung",
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

    /** Zahl der Verbindungen: jede Aussage zählt, auch die hinter „+ n weitere“ (UX W9). */
    static int connections(GraphSource.Graph graph) {
        return graph.edges().stream().mapToInt(edge -> edge.bundle() ? edge.hidden().size() : 1).sum();
    }

    /**
     * Overlay „Belege“ einer Verb-Kante: Sätze im Hintergrund lesen, dann zeigen. Tragen Plan und Ziel in dieser
     * Richtung mehrere Aussagen, steht jede unter eigener Überschrift, die geklickte oben und aufgeklappt (UX W9).
     */
    private void showEvidence(GraphSource.Edge edge, int x, int y) {
        List<EvidencePopup.Statement> statements = statements(canvas.graph(), edge);
        String title = statements.size() == 1 ? evidenceTitle(canvas, edge)
                : canvas.label(edge.from()) + " – " + canvas.label(edge.to());
        int total = statements.stream().mapToInt(EvidencePopup.Statement::total).sum();
        java.util.List<com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Evidence> all =
                statements.stream().flatMap(statement -> statement.evidence().stream()).toList();
        AppRuntime runtime = context == null ? null : context.runtime();
        int current = ++evidenceRequest;
        java.util.function.Function<java.util.Map<com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph
                .PageRef, java.util.List<com.aresstack.spaceharvester.application.knowledge.ParsedStatements
                .Statement>>, java.util.Map<String, com.aresstack.spaceharvester.application.standing.SentenceText>>
                reader = sentenceReader != null ? sentenceReader
                : runtime == null ? null : runtime.workspace()::statementSentences;
        if (reader == null) {
            evidence.fillStatements(title, total, statements, java.util.Map.of(), true).showAt(canvas, x, y);
            return;
        }
        java.util.Map<com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph.PageRef,
                java.util.List<com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Statement>> byPage =
                new java.util.LinkedHashMap<>();
        all.forEach(item -> byPage.computeIfAbsent(item.page(), unused -> new ArrayList<>())
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
                evidence.fillStatements(title, total, statements, sentences, false).showAt(canvas, x, y);
            }
            catchUp();
        }, failure -> {
            if (current != evidenceRequest) {
                return;
            }
            evidencePending = false;
            if (current == evidenceRequest && graphRequest == request && canvas.isShowing()) {
                evidence.fillStatements(title, total, statements, java.util.Map.of(), true).showAt(canvas, x, y);
            }
            catchUp();
        });
    }

    /**
     * Die Aussagen des Knotenpaars in Richtung der geklickten Kante für das Overlay (UX W9): die geklickte zuerst (beim
     * Bogen „+ n weitere“ die, für die er steht), dann die übrigen; jeweils die mit den meisten Belegen zuerst.
     */
    public static List<EvidencePopup.Statement> statements(GraphSource.Graph graph, GraphSource.Edge clicked) {
        List<GraphSource.Edge> first = clicked.bundle() ? new ArrayList<>(clicked.hidden())
                : new ArrayList<>(List.of(clicked));
        List<GraphSource.Edge> rest = new ArrayList<>();
        for (GraphSource.Edge edge : graph.edges()) {
            // Gleich, nicht dieselbe: Nach einem Neuladen mit gleichem Inhalt hält die Lage die alten Kanten.
            if (edge.equals(clicked) || edge.style() != GraphSource.Style.VERB || !edge.from().equals(clicked.from())
                    || !edge.to().equals(clicked.to())) {
                continue;
            }
            if (edge.bundle()) {
                rest.addAll(edge.hidden());
            } else {
                rest.add(edge);
            }
        }
        java.util.Comparator<GraphSource.Edge> strongest =
                java.util.Comparator.comparingInt(TermGraphView::edgeCount).reversed();
        first.sort(strongest);
        rest.sort(strongest);
        List<EvidencePopup.Statement> statements = new ArrayList<>();
        for (GraphSource.Edge edge : first) {
            statements.add(statementOf(edge));
        }
        for (GraphSource.Edge edge : rest) {
            statements.add(statementOf(edge));
        }
        return statements;
    }

    private static EvidencePopup.Statement statementOf(GraphSource.Edge edge) {
        return new EvidencePopup.Statement(edge.phrase(), edgeCount(edge),
                GraphSource.trustText(edge.trust(), edge.differs()), edge.evidence());
    }

    /** Kopf des Overlays „Belege“ einer Verb-Kante: „Plan – nicht prüfen – Ziel“ ohne Zahl und „≠“ (UX W9). */
    public static String evidenceTitle(Canvas canvas, GraphSource.Edge edge) {
        return canvas.label(edge.from()) + " – " + edge.phrase() + " – " + canvas.label(edge.to());
    }

    /** Zahl aller Belege aus der Beschriftung „Grundform · N“; sonst die mitgelieferten. */
    public static int edgeCount(GraphSource.Edge edge) {
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

    /** Zeigt eine nach dem Schließen gescheiterte Entscheidung in der Statuszeile ({@code null}: entfernt sie). */
    private void reviewNote(com.aresstack.spaceharvester.app.ui.controls.StatusNote note) {
        if (note == null) {
            reviewLine.show(com.aresstack.spaceharvester.app.ui.controls.StatusNote.plain(" "));
        } else {
            reviewLine.show(note);
        }
        reviewLine.setVisible(note != null);
        root.revalidate();
        root.repaint();
    }

    /** Die Meldung einer nach dem Schließen gescheiterten Entscheidung, sonst {@code null} (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.StatusNote reviewNote() {
        return reviewLine.isVisible() ? reviewLine.note() : null;
    }

    /** Das Overlay „Belege“ (für Tests). */
    EvidencePopup evidence() {
        return evidence;
    }

    String hint() {
        return hint.getText();
    }

    Canvas canvas() {
        return canvas;
    }

    /**
     * Die Zeichenfläche: Kanten mit Beschriftung, darüber die Knoten als Comic-Kästen mit Schlagschatten. Auch der
     * Seitenreiter „Graph“ zeichnet seine Fachsicht damit (Paket W4b), dort anfangs ohne Mitte ({@link #centred}).
     */
    public static final class Canvas extends JComponent {

        private static final int PAD = 14;
        private static final int BOX_HEIGHT = 28;
        private GraphSource.Graph graph = GraphSource.Graph.EMPTY;
        /** Ob der erste Knoten die Mitte ist; ohne Mitte liegen alle Knoten gleichrangig auf Ringen. */
        private boolean centred = true;
        /** Gedämpfter Text mitten in der leeren Fläche oder {@code null} (die Übersicht nutzt ihn nicht). */
        private String emptyText;
        /** Fachsicht: Bögen, freigestellte Beschriftungen und gedrehter Ring; die Bestandssicht zeichnet wie bisher. */
        private boolean domain;

        /**
         * Bündelt Größenänderungen der Fachsicht (Fenster oder Teiler ziehen): Erst die gebündelte Meldung stößt eine
         * neue Lagesuche an, nicht jeder Zwischenschritt. Ein Thread für alle Flächen, nur bei Bedarf.
         */
        private static final Coalescer<Canvas, Object> RESIZES = new Coalescer<>("graph-resize",
                (canvas, ignored) -> javax.swing.SwingUtilities.invokeLater(canvas::sizeSettled));

        /** Wo die Lagesuche der Fachsicht läuft: abseits des EDT; Tests setzen einen eigenen Executor. */
        private java.util.concurrent.Executor searches = Background.executor();
        /** Zähler der Suchaufträge; außerhalb des EDT gelesen, damit eine überholte Suche abbricht und verfällt. */
        private final java.util.concurrent.atomic.AtomicInteger request = new java.util.concurrent.atomic.AtomicInteger();
        /** Wie oft eine Lagesuche gelaufen ist (Tests: Zeichnen und Treffersuche suchen nie). */
        private final java.util.concurrent.atomic.AtomicInteger searched = new java.util.concurrent.atomic.AtomicInteger();
        /** Die zuletzt fertig gesuchte Lage der Fachsicht; sie gilt, bis eine neuere fertig ist. Nur auf dem EDT. */
        private Layout computed;
        /** {@link #computed} auf die aktuelle Fläche gezogen, solange für diese Größe noch keine Suche fertig ist. */
        private Layout scaled;
        /** Die Lage, aus der {@link #scaled} gezogen ist. */
        private Layout scaledFrom;
        /**
         * {@link #computed} auf einen inhaltlich geänderten Graphen übertragen (nur die Knoten und Kanten, die es dort
         * noch gibt), solange dessen Suche läuft.
         */
        private Layout carried;
        /** Die Lage, aus der {@link #carried} übertragen ist. */
        private Layout carriedFrom;
        /** Was gerade gesucht wird, oder {@code null}. */
        private Wanted pending;
        /** Wofür die Suche zuletzt ohne jede Lage gescheitert ist (kein Ring, keine Wiederholung), oder {@code null}. */
        private Wanted failed;
        /** Die eigentliche Suche; Tests ersetzen sie (etwa durch eine, die scheitert). */
        private java.util.function.Function<Geometry, Layout> searcher = Geometry::search;
        /** Die Bestandssicht: billig, darum beim Zeichnen gerechnet und je Graph und Fläche gemerkt. */
        private Layout overview;
        private boolean busy;
        private java.util.function.Consumer<Boolean> onBusy = busy -> { };

        public Canvas() {
            setOpaque(true);
            setBackground(AppColors.of("app.TermGraphView.canvas"));
            setPreferredSize(new Dimension(640, 420));
            setFont(Comic.semiBold(13f));
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    resized();
                }
            });
            addPropertyChangeListener("font", e -> requestLayout());
        }

        public void domain(boolean domain) {
            this.domain = domain;
            requestLayout();
            repaint();
        }

        /**
         * {@code false}: keine Mitte (seitenweite Fachsicht, alle Knoten Tiefe 0); die Knoten liegen gleichrangig auf
         * Ringen, die mit den meisten Belegen innen. Standard {@code true}.
         */
        public void centred(boolean centred) {
            this.centred = centred;
            requestLayout();
            repaint();
        }

        public boolean centred() {
            return centred;
        }

        /** Gedämpfter Text mitten in der Fläche, solange der Graph leer ist; {@code null} zeigt keinen. */
        public void emptyText(String text) {
            emptyText = text;
            repaint();
        }

        public String emptyText() {
            return emptyText;
        }

        public void graph(GraphSource.Graph graph) {
            this.graph = graph;
            // Neu geholt, aber inhaltlich gleich (Graph, Knoten und Kanten sind Werte): Die fertige Lage gilt weiter,
            // ohne neue Suche, ohne Ring und ohne leere Fläche.
            if (computed != null && computed.graph() != graph && computed.graph().equals(graph)) {
                computed = computed.regraph(graph);
            }
            requestLayout();
            repaint();
        }

        public GraphSource.Graph graph() {
            return graph;
        }

        /**
         * Meldet, ob die Fachsicht für den gezeigten Graphen noch keine Lage hat und darum leer bleibt (etwa für einen
         * {@link com.aresstack.spaceharvester.app.ui.controls.BusyOverlay}); sofort einmal mit dem jetzigen Stand.
         */
        public void onBusy(java.util.function.Consumer<Boolean> listener) {
            onBusy = java.util.Objects.requireNonNull(listener, "listener");
            listener.accept(busy);
        }

        public boolean busy() {
            return busy;
        }

        /** Executor der Lagesuche (Tests: von Hand ausführen). */
        void searches(java.util.concurrent.Executor executor) {
            searches = java.util.Objects.requireNonNull(executor, "executor");
        }

        /**
         * Das Arbeitsbudget der Suche (Tests: klein, damit es unabhängig von den Schriftmaßen der Maschine sicher
         * erreicht wird).
         */
        void budget(long budget) {
            if (budget < 1) {
                throw new IllegalArgumentException("Budget mindestens 1");
            }
            this.budget = budget;
        }

        /** Die Suche selbst (Tests: etwa eine, die scheitert). */
        void searcher(java.util.function.Function<Geometry, Layout> searcher) {
            this.searcher = java.util.Objects.requireNonNull(searcher, "searcher");
        }

        /** Wie oft eine Lagesuche gelaufen ist, synchron oder abseits des EDT. */
        int searchCount() {
            return searched.get();
        }

        /** Arbeit ({@link Geometry#work}) der zuletzt fertigen Lagesuche, 0 ohne. */
        long lastWork() {
            return computed == null ? 0 : computed.work();
        }

        /** Wie viele Ausdehnungen die zuletzt fertige Lagesuche geprüft hat, 0 ohne. */
        int lastVariants() {
            return computed == null ? 0 : computed.variants();
        }

        /** Wortlaut eines Knotens oder der Schlüssel, wenn es ihn nicht gibt. */
        public String label(String key) {
            return graph.nodes().stream().filter(node -> node.key().equals(key)).map(GraphSource.Node::label)
                    .findFirst().orElse(key);
        }

        /** Die Kante unter dem Punkt (Beschriftung oder höchstens 5 px neben der Linie) oder {@code null}. */
        public GraphSource.Edge edgeAt(Point point) {
            Layout view = view();
            if (view == null) {
                return null;
            }
            GraphSource.Edge best = null;
            double bestDistance = 6;
            for (Segment segment : view.segments()) {
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
                // Schneller Ausschluss: Linie und Bogen liegen ganz in der Hülle ihrer Punkte (samt Kontrollpunkt).
                double minX = Math.min(a.getX(), b.getX());
                double maxX = Math.max(a.getX(), b.getX());
                double minY = Math.min(a.getY(), b.getY());
                double maxY = Math.max(a.getY(), b.getY());
                if (control != null) {
                    minX = Math.min(minX, control.getX());
                    maxX = Math.max(maxX, control.getX());
                    minY = Math.min(minY, control.getY());
                    maxY = Math.max(maxY, control.getY());
                }
                if (maxX < area.getMinX() || minX > area.getMaxX() || maxY < area.getMinY()
                        || minY > area.getMaxY()) {
                    return false;
                }
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

            /** Dieselbe Kante, die Enden mit ihren Knoten um {@code from} bzw. {@code to} verschoben. */
            Segment moved(Point2D from, Point2D to) {
                double mx = (from.getX() + to.getX()) / 2;
                double my = (from.getY() + to.getY()) / 2;
                return new Segment(edge, new Point2D.Double(a.getX() + from.getX(), a.getY() + from.getY()),
                        new Point2D.Double(b.getX() + to.getX(), b.getY() + to.getY()),
                        tag == null ? null : new Rectangle2D.Double(tag.getX() + mx, tag.getY() + my, tag.getWidth(),
                                tag.getHeight()),
                        control == null ? null : new Point2D.Double(control.getX() + mx, control.getY() + my));
            }
        }

        /**
         * Eine fertige Lage: Kästen und Kanten samt Beschriftungen für einen Graphen auf einer Fläche, dazu die Arbeit
         * und die geprüften Ausdehnungen der Suche.
         */
        record Layout(GraphSource.Graph graph, String flags, int width, int height, Map<String, Rectangle2D> boxes,
                      List<Segment> segments, long work, int variants) {

            boolean fits(GraphSource.Graph graph, String flags, int width, int height) {
                return (this.graph == graph || this.graph.equals(graph)) && this.flags.equals(flags)
                        && this.width == width && this.height == height;
            }

            /** Dieselbe Lage für einen inhaltlich gleichen Graphen (neu geholt). */
            Layout regraph(GraphSource.Graph graph) {
                return new Layout(graph, flags, width, height, boxes, segments, work, variants);
            }

            /**
             * Übergangsweise auf einen inhaltlich geänderten Graphen übertragen, ohne Suche: Knoten, die es dort noch
             * gibt, bleiben, wo sie waren; Kanten bleiben, wenn es sie dort noch gibt (gleiche Enden, Art und Linie;
             * dann mit der neuen Kante, etwa neuer Zahl). Neue Knoten fehlen, bis die Suche fertig ist.
             */
            Layout carried(GraphSource.Graph graph) {
                Map<String, Rectangle2D> kept = new LinkedHashMap<>();
                for (GraphSource.Node node : graph.nodes()) {
                    Rectangle2D box = boxes.get(node.key());
                    if (box != null) {
                        kept.putIfAbsent(node.key(), box);
                    }
                }
                Map<String, java.util.ArrayDeque<GraphSource.Edge>> edges = new java.util.HashMap<>();
                for (GraphSource.Edge edge : graph.edges()) {
                    edges.computeIfAbsent(edgeKey(edge), key -> new java.util.ArrayDeque<>()).add(edge);
                }
                List<Segment> lines = new ArrayList<>();
                for (Segment segment : segments) {
                    if (!kept.containsKey(segment.edge().from()) || !kept.containsKey(segment.edge().to())) {
                        continue;
                    }
                    java.util.ArrayDeque<GraphSource.Edge> same = edges.get(edgeKey(segment.edge()));
                    GraphSource.Edge edge = same == null ? null : same.poll();
                    if (edge != null) {
                        lines.add(new Segment(edge, segment.a(), segment.b(), segment.tag(), segment.control()));
                    }
                }
                return new Layout(graph, flags, width, height, java.util.Collections.unmodifiableMap(kept),
                        List.copyOf(lines), work, variants);
            }

            private static String edgeKey(GraphSource.Edge edge) {
                return edge.from() + "\u0000" + edge.to() + "\u0000" + edge.type() + "\u0000" + edge.style()
                        + "\u0000" + edge.phrase();
            }

            /**
             * Übergangsweise auf eine andere Fläche gezogen, ohne Suche: Jeder Knoten rückt mit seiner Mitte im
             * Verhältnis der Flächen (in der Fläche gehalten), Kanten und Beschriftungen wandern mit ihren Knoten.
             */
            Layout scaled(int width, int height) {
                double sx = width / (double) this.width;
                double sy = height / (double) this.height;
                Map<String, Rectangle2D> moved = new LinkedHashMap<>();
                Map<String, Point2D> shift = new java.util.HashMap<>();
                boxes.forEach((key, box) -> {
                    double x = Math.max(4, Math.min(width - box.getWidth() - 8,
                            box.getCenterX() * sx - box.getWidth() / 2));
                    double y = Math.max(4, Math.min(height - box.getHeight() - 8,
                            box.getCenterY() * sy - box.getHeight() / 2));
                    moved.put(key, new Rectangle2D.Double(x, y, box.getWidth(), box.getHeight()));
                    shift.put(key, new Point2D.Double(x - box.getX(), y - box.getY()));
                });
                Point2D none = new Point2D.Double();
                List<Segment> lines = new ArrayList<>();
                for (Segment segment : segments) {
                    lines.add(segment.moved(shift.getOrDefault(segment.edge().from(), none),
                            shift.getOrDefault(segment.edge().to(), none)));
                }
                return new Layout(graph, flags, width, height, moved, List.copyOf(lines), work, variants);
            }
        }

        /** Eine angestoßene Suche: Graph, Schalter und Fläche. */
        private record Wanted(GraphSource.Graph graph, String flags, int width, int height) {
        }

        /** Schalter, die eine Lage bestimmen (außer Graph und Fläche). */
        private String flags() {
            return domain + "/" + centred + "/" + getFont();
        }

        /** Die Rechnung für den jetzigen Stand und diese Fläche; liest danach nichts mehr von der Fläche. */
        private Geometry geometry(int width, int height, java.util.function.BooleanSupplier cancelled) {
            return new Geometry(graph, domain, centred, flags(), getFontMetrics(getFont()),
                    getFontMetrics(Comic.regular(11f)), width, height, budget, cancelled);
        }

        /**
         * Die Lage, die gezeichnet und getroffen wird. Sucht nie: Die Bestandssicht ist billig und wird hier gerechnet;
         * die Fachsicht zeigt die zuletzt fertig gesuchte Lage desselben Graphen, bei anderer Größe auf die Fläche
         * gezogen, und ohne eine solche nichts ({@code null}), bis die Suche fertig ist.
         */
        private Layout view() {
            int width = getWidth();
            int height = getHeight();
            if (graph.nodes().isEmpty() || width <= 0 || height <= 0) {
                return null;
            }
            if (!domain) {
                String flags = flags();
                if (overview == null || !overview.fits(graph, flags, width, height)) {
                    Geometry geometry = geometry(width, height, () -> false);
                    Map<String, Rectangle2D> boxes = geometry.boxes(Spread.BASE);
                    overview = new Layout(graph, flags, width, height, boxes, List.copyOf(geometry.labels(boxes)),
                            geometry.work, 1);
                }
                return overview;
            }
            Layout shown = shown();
            if (shown == null) {
                return null;
            }
            if (shown.width() == width && shown.height() == height) {
                return shown;
            }
            if (scaled == null || scaledFrom != shown || scaled.width() != width || scaled.height() != height) {
                scaled = shown.scaled(width, height);
                scaledFrom = shown;
            }
            return scaled;
        }

        /**
         * Die zuletzt fertige Lage der Fachsicht für den gezeigten Graphen (in ihrer eigenen Größe): die fertige
         * selbst, oder bei inhaltlich geändertem Graphen auf ihn übertragen ({@link Layout#carried}); {@code null},
         * wenn es keine gibt oder vom neuen Graphen kein Knoten darin liegt.
         */
        private Layout shown() {
            if (computed == null) {
                return null;
            }
            if (computed.graph() == graph) {
                return computed;
            }
            if (carried == null || carriedFrom != computed || carried.graph() != graph) {
                carried = computed.carried(graph);
                carriedFrom = computed;
            }
            return carried.boxes().isEmpty() ? null : carried;
        }

        /** Größe geändert: ohne Lage für diesen Graphen gleich suchen, sonst gebündelt ({@link #RESIZES}). */
        private void resized() {
            if (!domain) {
                return;
            }
            if (pending == null && shown() == null) {
                requestLayout();
            } else {
                RESIZES.mark(this);
            }
        }

        /** Die gebündelte Größenänderung ist da: für die jetzige Größe suchen, falls nötig. */
        void sizeSettled() {
            requestLayout();
        }

        /**
         * Stößt die Lagesuche der Fachsicht für den jetzigen Stand an, abseits des EDT ({@link #searches}). Bis sie
         * fertig ist, gilt die bisherige Lage ({@link #view}); eine überholte Suche bricht ab, ihr Ergebnis verfällt.
         * Ist die passende Lage schon da oder schon angestoßen, geschieht nichts.
         */
        private void requestLayout() {
            int width = getWidth();
            int height = getHeight();
            if (!domain || graph.nodes().isEmpty() || width <= 0 || height <= 0
                    || computed != null && computed.fits(graph, flags(), width, height)) {
                if (pending != null) {
                    request.incrementAndGet();
                    pending = null;
                }
                updateBusy();
                return;
            }
            // Wanted vergleicht den Graphen als Wert: Ein neu geholter, gleicher Graph stößt keine zweite Suche an.
            Wanted wanted = new Wanted(graph, flags(), width, height);
            if (wanted.equals(pending)) {
                return;
            }
            if (wanted.equals(failed)) {
                // Für genau diesen Stand ist die Suche schon gescheitert: nicht erneut, kein Ring.
                if (pending != null) {
                    request.incrementAndGet();
                    pending = null;
                }
                updateBusy();
                return;
            }
            int id = request.incrementAndGet();
            pending = wanted;
            Geometry geometry = geometry(width, height, () -> request.get() != id);
            updateBusy();
            java.util.function.Function<Geometry, Layout> search = searcher;
            searches.execute(() -> {
                Layout found = null;
                boolean broken = false;
                try {
                    found = search(geometry, search);
                } catch (Throwable e) {
                    broken = true;
                    found = fallback(geometry, e);
                } finally {
                    // Immer zurück auf den EDT, auch nach einem Error: sonst bliebe {@link #pending} hängen.
                    Layout result = found;
                    boolean failure = broken;
                    javax.swing.SwingUtilities.invokeLater(() -> searched(id, wanted, result, failure));
                }
            });
        }

        /**
         * Nach einer gescheiterten Suche (protokolliert): die Grundlage ohne Suche, Beschriftungen ohne Budget mittig;
         * {@code null}, wenn auch sie scheitert oder überholt ist.
         */
        private static Layout fallback(Geometry geometry, Throwable failure) {
            try {
                LOG.warn("Die Lagesuche der Fachsicht ist gescheitert; es gilt die Grundlage ohne Suche", failure);
            } catch (Throwable ignored) {
                // Auch ohne Protokoll weiter: die Fläche darf nicht leer weiterdrehen.
            }
            try {
                return geometry.base();
            } catch (Throwable e) {
                return null;
            }
        }

        /** Eine Suche, sofern sie nicht schon überholt ist; nur begonnene Suchen zählen ({@link #searchCount}). */
        private Layout search(Geometry geometry, java.util.function.Function<Geometry, Layout> search) {
            if (geometry.cancelled.getAsBoolean()) {
                return null;
            }
            searched.incrementAndGet();
            return search.apply(geometry);
        }

        /**
         * Ergebnis einer Suche auf dem EDT: gilt nur, wenn sie die jüngste war und der Graph (als Wert) noch derselbe
         * ist. Ist sie ohne jede Lage gescheitert, wird für diesen Stand nicht erneut gesucht und kein Ring gezeigt.
         */
        private void searched(int id, Wanted wanted, Layout result, boolean failure) {
            if (id != request.get()) {
                return;
            }
            pending = null;
            if (result != null && (result.graph() == graph || result.graph().equals(graph))) {
                computed = result.graph() == graph ? result : result.regraph(graph);
                scaled = null;
                failed = null;
                repaint();
            } else if (failure) {
                failed = wanted;
            }
            updateBusy();
        }

        private void updateBusy() {
            boolean now = domain && !graph.nodes().isEmpty() && getWidth() > 0 && getHeight() > 0
                    && shown() == null && !new Wanted(graph, flags(), getWidth(), getHeight()).equals(failed);
            if (now != busy) {
                busy = now;
                onBusy.accept(now);
            }
        }

        /**
         * Kästen je Knoten bei dieser Größe, synchron gerechnet (Tests; die Fläche selbst rechnet die Fachsicht nur
         * abseits des EDT, siehe {@link #requestLayout}). Die Fachsicht merkt sich die Lage wie eine fertige Suche.
         */
        Map<String, Rectangle2D> layout(int width, int height) {
            if (graph.nodes().isEmpty() || width <= 0 || height <= 0) {
                return new LinkedHashMap<>();
            }
            if (!domain) {
                return geometry(width, height, () -> false).boxes(Spread.BASE);
            }
            if (computed == null || !computed.fits(graph, flags(), width, height)) {
                computed = search(geometry(width, height, () -> false), searcher);
                scaled = null;
                if (pending != null && computed.fits(pending.graph(), pending.flags(), pending.width(),
                        pending.height())) {
                    request.incrementAndGet();
                    pending = null;
                }
                updateBusy();
            }
            return new LinkedHashMap<>(computed.boxes());
        }

        /** Lage aller Kanten zu diesen Kästen; dieselbe wie beim Zeichnen, wenn es die gezeichneten Kästen sind. */
        List<Segment> segments(Map<String, Rectangle2D> boxes) {
            Layout view = view();
            if (view != null && view.boxes().equals(boxes)) {
                return view.segments();
            }
            return geometry(getWidth(), getHeight(), () -> false).labels(boxes);
        }

        /** Abstand, den eine Kante zu fremden Knoten hält. */
        private static final double NODE_CLEARANCE = 3;
        /** Wie weit ein Umweg-Bogen höchstens ausholt (Scheitel neben der geraden Linie), in Stufen. */
        private static final double[] BENDS = {22, 36, 52, 70, 92, 118};

        /**
         * Die Kante als Bogen, dessen Scheitel {@code bend} px neben der Verbindung der beiden Knotenmitten liegt
         * (Vorzeichen: Seite); die Enden verlassen die Kästen in Richtung des Kontrollpunkts. {@code null}, wenn ein
         * Knoten fehlt.
         */
        private static Segment bowed(Segment segment, Map<String, Rectangle2D> boxes, double bend) {
            Rectangle2D from = boxes.get(segment.edge().from());
            Rectangle2D to = boxes.get(segment.edge().to());
            if (from == null || to == null) {
                return null;
            }
            double dx = to.getCenterX() - from.getCenterX();
            double dy = to.getCenterY() - from.getCenterY();
            double length = Math.max(1, Math.hypot(dx, dy));
            Point2D control = new Point2D.Double((from.getCenterX() + to.getCenterX()) / 2 - dy / length * bend * 2,
                    (from.getCenterY() + to.getCenterY()) / 2 + dx / length * bend * 2);
            Rectangle2D towards = new Rectangle2D.Double(control.getX(), control.getY(), 0, 0);
            return new Segment(segment.edge(), exit(from, towards), exit(to, towards), segment.tag(), control);
        }

        /** Die Bögen, die eine Kante statt ihrer Lage nehmen kann: zuerst flach, abwechselnd beide Seiten. */
        private static List<Segment> detours(Segment segment, Map<String, Rectangle2D> boxes) {
            List<Segment> shapes = new ArrayList<>();
            for (double bend : BENDS) {
                for (double sign : new double[] {1, -1}) {
                    Segment shape = bowed(segment, boxes, sign * bend);
                    if (shape != null) {
                        shapes.add(shape);
                    }
                }
            }
            return shapes;
        }

        /** Abstand einer Beschriftung zu Knoten, anderen Beschriftungen und fremden Kanten. */
        private static final double LABEL_GAP = 4;
        /** Wie weit der Schatten eines Knotens (3 px versetzt, dazu die halbe Randlinie) rechts und unten übersteht. */
        static final double NODE_SHADOW = 4;

        /** Die gezeichnete Fläche eines Knotens: sein Kasten samt Schatten rechts und unten. */
        static Rectangle2D drawn(Rectangle2D box) {
            return new Rectangle2D.Double(box.getX(), box.getY(), box.getWidth() + NODE_SHADOW,
                    box.getHeight() + NODE_SHADOW);
        }

        private static Rectangle2D padded(Rectangle2D box, double gap) {
            return new Rectangle2D.Double(box.getX() - gap, box.getY() - gap, box.getWidth() + 2 * gap,
                    box.getHeight() + 2 * gap);
        }

        private static final double[] LABEL_POSITIONS = {0.5, 0.45, 0.55, 0.4, 0.6, 0.35, 0.65, 0.3, 0.7, 0.25, 0.75,
                0.2, 0.8, 0.16, 0.84};
        private static final double[] MIDDLE = {0.5};

        /** Ergebnis einer Beschriftungsrunde: die Kanten mit ihren Beschriftungen und die verbliebenen Berührungen. */
        private record Placed(List<Segment> segments, int hits) {
        }

        /** Wie oft die Fachsicht die Beschriftungen in anderer Reihenfolge neu setzt, wenn welche noch berühren. */
        private static final int LABEL_ROUNDS = 3;

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
        public List<String> labels() {
            return graph.nodes().stream().map(GraphSource.Node::label).toList();
        }

        /** Der Knoten unter dem Punkt oder {@code null}. */
        public GraphSource.Node nodeAt(Point point) {
            Layout view = view();
            if (view == null) {
                return null;
            }
            // Später gemalte Knoten liegen oben: rückwärts suchen, damit der sichtbare Knoten getroffen wird.
            List<GraphSource.Node> nodes = graph.nodes();
            for (int i = nodes.size() - 1; i >= 0; i--) {
                Rectangle2D box = view.boxes().get(nodes.get(i).key());
                if (box != null && box.contains(point)) {
                    return nodes.get(i);
                }
            }
            return null;
        }

        /**
         * Wie weit die Ringe ausgreifen, relativ zur Grundlage: {@code inner} streckt den inneren Ring (mit Mitte: die
         * Nachbarn), {@code outer} den äußeren, {@code stretch} ist die waagrechte Dehnung (Grundlage 1,35) und
         * {@code turn} dreht den inneren Ring um diesen Bruchteil eines Schritts.
         */
        record Spread(double inner, double outer, double stretch, double turn) {
            static final Spread BASE = new Spread(1, 1, 1.35, 0);
        }

        private static final double[] SPREAD_STRETCH = {1.35, 1.6, 1.85};
        private static final double[] SPREAD_INNER = {1, 1.16, 1.32, 1.48, 0.85};
        private static final double[] SPREAD_TURN = {0, 0.25, -0.25, 0.5};
        private static final double[] SPREAD_OUTER = {1, 1.05, 0.9};
        /** Alle Ausdehnungen in der Reihenfolge der Suche, die Grundlage zuerst. */
        private static final List<Spread> SPREADS = spreads();

        private static List<Spread> spreads() {
            List<Spread> spreads = new ArrayList<>();
            for (double stretch : SPREAD_STRETCH) {
                for (double inner : SPREAD_INNER) {
                    for (double turn : SPREAD_TURN) {
                        for (double outer : SPREAD_OUTER) {
                            spreads.add(new Spread(inner, outer, stretch, turn));
                        }
                    }
                }
            }
            return List.copyOf(spreads);
        }

        /**
         * Höchstens so viel Arbeit ({@link Geometry#work}: gezählte Prüfungen Rechteck gegen Rechteck oder Linie, nicht
         * Zeit) steckt die Fachsicht in die Suche nach einer Lage. Ist es aufgebraucht, wird keine weitere Ausdehnung
         * mehr probiert, die laufende setzt ihre übrigen Beschriftungen mittig, und es gilt die beste bisher gefundene
         * Lage. Fest gezählt, damit derselbe Graph auf derselben Fläche immer gleich liegt. Kleine Graphen bleiben weit
         * darunter und werden ganz durchsucht.
         */
        static final long WORK_BUDGET = 24_000_000L;
        /** Das geltende Budget, sonst {@link #WORK_BUDGET}. */
        private long budget = WORK_BUDGET;

        /**
         * Übereinanderliegende oder angeschnittene Knoten wiegen schwerer als alle Berührungen von Beschriftungen
         * zusammen: Eine Lage ohne überdeckte Knoten geht immer vor.
         */
        private static final int NODE_WEIGHT = 100_000;

        /** Abstand der Knoten vom Rand der Fläche in der Fachsicht. */
        private static final double EDGE_MARGIN = 12;

        /**
         * Die Rechnung einer Lage für einen festen Stand (Graph, Schalter, Schriftmaße, Fläche): liest nach dem Anlegen
         * nichts mehr von der Zeichenfläche und läuft darum auch abseits des EDT. Zählt ihre Arbeit ({@link #work}) und
         * hält sich in der Fachsicht an {@link #budget}.
         */
        static final class Geometry {

            private final GraphSource.Graph graph;
            private final boolean domain;
            private final boolean centred;
            private final String flags;
            private final FontMetrics nodeMetrics;
            private final FontMetrics labelMetrics;
            private final int width;
            private final int height;
            private final long budget;
            private final java.util.function.BooleanSupplier cancelled;
            private final Map<String, Integer> depths = new java.util.HashMap<>();
            /** Zahl der Prüfungen (Rechteck gegen Rechteck oder Linie) bisher. */
            private long work;
            private int variants;

            Geometry(GraphSource.Graph graph, boolean domain, boolean centred, String flags, FontMetrics nodeMetrics,
                     FontMetrics labelMetrics, int width, int height, long budget,
                     java.util.function.BooleanSupplier cancelled) {
                this.graph = graph;
                this.domain = domain;
                this.centred = centred;
                this.flags = flags;
                this.nodeMetrics = nodeMetrics;
                this.labelMetrics = labelMetrics;
                this.width = width;
                this.height = height;
                this.budget = budget;
                this.cancelled = cancelled;
                for (GraphSource.Node node : graph.nodes()) {
                    depths.putIfAbsent(node.key(), node.depth());
                }
            }

            /**
             * Die Grundlage ohne Suche ({@link Spread#BASE}) mit Beschriftungen ohne Budget (mittig, keine Runden):
             * billig, für den Fall, dass die Suche scheitert.
             */
            Layout base() {
                Geometry cheap = new Geometry(graph, domain, centred, flags, nodeMetrics, labelMetrics, width, height,
                        0, cancelled);
                Map<String, Rectangle2D> boxes = cheap.boxes(Spread.BASE);
                return new Layout(graph, flags, width, height, java.util.Collections.unmodifiableMap(boxes),
                        List.copyOf(cheap.labels(boxes)), cheap.work, 1);
            }

            /** Bricht ab, wenn eine neuere Suche angestoßen ist. */
            private void check() {
                if (cancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException();
                }
            }

            /**
             * Die Lage der Fachsicht: die Mitte im Zentrum, Tiefe 1 gleichmäßig im Kreis, Tiefe 2 außen im Fächer
             * ihres Vorgängers; ohne Mitte alle auf Ringen ({@link #rings}). Berührt danach noch eine Beschriftung
             * einen Knoten, eine andere Beschriftung oder eine fremde Kante, oder läuft eine Kante unter einem fremden
             * Knoten durch, rückt sie die Ringe in festen Stufen auseinander, dreht den inneren Ring und dehnt
             * waagrecht ({@link Spread}), bis nichts mehr berührt oder das {@link #budget} aufgebraucht ist; sonst
             * gilt die Lage mit den wenigsten Berührungen (überdeckte Knoten zuerst, {@link #NODE_WEIGHT}). Eine Lage,
             * die schon frei ist, bleibt wie sie ist.
             *
             * @return die Lage, oder {@code null}, wenn eine neuere Suche sie überholt hat
             */
            Layout search() {
                if (graph.nodes().isEmpty() || width <= 0 || height <= 0) {
                    return new Layout(graph, flags, width, height, Map.of(), List.of(), 0, 0);
                }
                try {
                    Rectangle2D area = new Rectangle2D.Double(0, 0, width, height);
                    // Vorab, billig: die Kästen jeder Ausdehnung und ihre überdeckten Knoten. Geprüft wird dann in
                    // der Reihenfolge der Suche, aber Lagen ohne überdeckte Knoten zuerst (stabil sortiert). Eine Lage
                    // mit mehr überdeckten Knoten als die beste kann sie nie schlagen ({@link #NODE_WEIGHT}); dort
                    // endet die Suche. Ohne Budget ergibt das dieselbe Lage wie alle der Reihe nach zu prüfen.
                    List<Map<String, Rectangle2D>> candidates = new ArrayList<>();
                    Map<Map<String, Rectangle2D>, Integer> overlaps = new java.util.HashMap<>();
                    for (Spread spread : SPREADS) {
                        Map<String, Rectangle2D> boxes = boxes(spread);
                        if (!overlaps.containsKey(boxes)) {
                            overlaps.put(boxes, nodeConflicts(boxes, area));
                            candidates.add(boxes);
                        }
                    }
                    candidates.sort(java.util.Comparator.comparingInt(overlaps::get));
                    Map<String, Rectangle2D> bestBoxes = null;
                    Placed best = null;
                    long bestConflicts = Long.MAX_VALUE;
                    for (Map<String, Rectangle2D> boxes : candidates) {
                        check();
                        if (best != null && (work >= budget || overlaps.get(boxes) > overlaps.get(bestBoxes))) {
                            // Budget aufgebraucht oder keine bessere Lage mehr möglich: es gilt die beste bisher.
                            break;
                        }
                        variants++;
                        Placed placed = place(lines(boxes), boxes, area);
                        long conflicts = conflicts(boxes, placed.segments(), area);
                        if (conflicts < bestConflicts) {
                            bestBoxes = boxes;
                            best = placed;
                            bestConflicts = conflicts;
                        }
                        if (conflicts == 0) {
                            break;
                        }
                    }
                    return new Layout(graph, flags, width, height, java.util.Collections.unmodifiableMap(bestBoxes),
                            List.copyOf(best.segments()), work, variants);
                } catch (java.util.concurrent.CancellationException e) {
                    return null;
                }
            }

            /** Die Linien aller Kanten ohne Beschriftung: gerade, parallel versetzt oder als Bogen um fremde Knoten. */
            private List<Segment> lines(Map<String, Rectangle2D> boxes) {
                arcGaps.clear();
                List<Segment> segments = new ArrayList<>();
                Map<String, Integer> pairs = new java.util.HashMap<>();
                for (GraphSource.Edge edge : graph.edges()) {
                    pairs.merge(pairKey(edge), 1, Integer::sum);
                }
                Rectangle2D middle = !centred || graph.nodes().isEmpty() ? null
                        : boxes.get(graph.nodes().get(0).key());
                Point2D centre = middle == null ? null : new Point2D.Double(middle.getCenterX(), middle.getCenterY());
                Map<String, Integer> seen = new java.util.HashMap<>();
                for (GraphSource.Edge edge : graph.edges()) {
                    Rectangle2D from = boxes.get(edge.from());
                    Rectangle2D to = boxes.get(edge.to());
                    String pair = pairKey(edge);
                    int index = seen.merge(pair, 1, Integer::sum) - 1;
                    if (from == null || to == null) {
                        continue;
                    }
                    if (domain && pairs.get(pair) > 1) {
                        // Mehrere Aussagen zwischen denselben Knoten (UX W9): nebeneinander gebogen, jede Beschriftung
                        // auf ihrem eigenen Bogen; die Seiten gelten für beide Richtungen gleich.
                        double gap = arcGap(pair, boxes);
                        double bend = (index - (pairs.get(pair) - 1) / 2.0) * gap;
                        boolean flip = edge.from().compareTo(edge.to()) > 0;
                        Rectangle2D first = flip ? to : from;
                        Rectangle2D second = flip ? from : to;
                        double outward = outward(first, second, centre, edge);
                        if (outward != 0) {
                            // Zwischen zwei Nachbarn durch die Mitte: der ganze Fächer außen um die Mitte, der
                            // innerste Bogen wie eine einzelne Kante dort, die übrigen weiter außen.
                            bend = outward + Math.signum(outward) * index * gap;
                        }
                        Segment arc = new Segment(edge, exit(from, to), exit(to, from), null, null);
                        if (bend != 0) {
                            arc = bowed(arc, boxes, flip ? -bend : bend);
                        }
                        segments.add(arc);
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
                    if (domain && centre != null && offset == 0 && depthOf(edge.from()) > 0
                            && depthOf(edge.to()) > 0 && new Line2D.Double(a, b).ptSegDist(centre) < 60) {
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
                    Segment segment = new Segment(edge, a, b, null, control);
                    if (domain && foreignHits(segment, boxes, 1) > 0) {
                        segment = detour(segment, boxes);
                    }
                    segments.add(segment);
                }
                return segments;
            }

            /**
             * Abstand der Scheitel nebeneinander gebogener Aussagen eines Knotenpaars (UX W9): so weit, dass ihre
             * Beschriftungen quer zur Verbindung nebeneinander passen (bei steilen Paaren die Breite, bei flachen die
             * Höhe), mindestens {@link #ARC_GAP}.
             */
            private double arcGap(String pair, Map<String, Rectangle2D> boxes) {
                Double known = arcGaps.get(pair);
                if (known != null) {
                    return known;
                }
                double gap = ARC_GAP;
                for (GraphSource.Edge edge : graph.edges()) {
                    String label = labelOf(edge);
                    Rectangle2D from = boxes.get(edge.from());
                    Rectangle2D to = boxes.get(edge.to());
                    if (label == null || from == null || to == null || !pairKey(edge).equals(pair)) {
                        continue;
                    }
                    double dx = to.getCenterX() - from.getCenterX();
                    double dy = to.getCenterY() - from.getCenterY();
                    double length = Math.max(1, Math.hypot(dx, dy));
                    double w = labelMetrics.stringWidth(label) + 10;
                    double h = labelMetrics.getHeight() + 2;
                    gap = Math.max(gap, Math.abs(dy / length) * w + Math.abs(dx / length) * h + 2 * LABEL_GAP + 2);
                }
                arcGaps.put(pair, gap);
                return gap;
            }

            /** Je Knotenpaar der Abstand seiner Bögen für die aktuelle Lage ({@link #arcGap}). */
            private final Map<String, Double> arcGaps = new java.util.HashMap<>();

            /**
             * Scheitelabstand, mit dem eine Kante zwischen zwei Nachbarn außen um die Mitte biegt (dieselbe Regel wie
             * für einzelne Kanten), mit Vorzeichen in Richtung von {@code first} nach {@code second} wie bei
             * {@link #bowed}; 0, wenn sie nicht durch die Mitte liefe.
             */
            private double outward(Rectangle2D first, Rectangle2D second, Point2D centre, GraphSource.Edge edge) {
                if (!domain || centre == null || depthOf(edge.from()) == 0 || depthOf(edge.to()) == 0) {
                    return 0;
                }
                Point2D a = new Point2D.Double(first.getCenterX(), first.getCenterY());
                Point2D b = new Point2D.Double(second.getCenterX(), second.getCenterY());
                if (new Line2D.Double(exit(first, second), exit(second, first)).ptSegDist(centre) >= 60) {
                    return 0;
                }
                double dx = b.getX() - a.getX();
                double dy = b.getY() - a.getY();
                double length = Math.max(1, Math.hypot(dx, dy));
                double nx = -dy / length;
                double ny = dx / length;
                double side = ((a.getX() + b.getX()) / 2 - centre.getX()) * nx
                        + ((a.getY() + b.getY()) / 2 - centre.getY()) * ny;
                return (side < 0 ? -1 : 1) * Math.max(45, length * 0.22);
            }

            /** Wie viele Knoten außer ihren eigenen Enden die Kante kreuzt; hört bei {@code limit} auf zu zählen. */
            private int foreignHits(Segment segment, Map<String, Rectangle2D> boxes, int limit) {
                int hits = 0;
                work += boxes.size();
                for (Map.Entry<String, Rectangle2D> box : boxes.entrySet()) {
                    if (hits >= limit) {
                        break;
                    }
                    if (box.getKey().equals(segment.edge().from()) || box.getKey().equals(segment.edge().to())) {
                        continue;
                    }
                    if (segment.crosses(padded(box.getValue(), NODE_CLEARANCE))) {
                        hits++;
                    }
                }
                return hits;
            }

            /**
             * Eine Kante, die unter einem fremden Knoten durchliefe, nimmt den flachsten Bogen, der frei ist; gibt es
             * keinen, den mit den wenigsten Kreuzungen (die eigene Lage bei Gleichstand).
             */
            private Segment detour(Segment segment, Map<String, Rectangle2D> boxes) {
                Segment best = segment;
                int bestHits = foreignHits(segment, boxes, Integer.MAX_VALUE);
                for (Segment shape : detours(segment, boxes)) {
                    // Nur zählen, solange es besser werden kann.
                    int hits = foreignHits(shape, boxes, bestHits);
                    if (hits < bestHits) {
                        best = shape;
                        bestHits = hits;
                        if (hits == 0) {
                            break;
                        }
                    }
                }
                return best;
            }

            /**
             * Setzt jede Beschriftung auf ihre eigene Kante, so weit von der Mitte verschoben, bis sie mit
             * {@link #LABEL_GAP} Abstand weder Knoten noch andere Beschriftungen berührt, in der Fläche bleibt und
             * keine fremde Kante sie kreuzt. Findet sich auf der Kante keine freie Stelle, nimmt die Fachsicht die
             * Kante als flachen Bogen ({@link #detours}), sofern der weder fremde Knoten noch schon gesetzte
             * Beschriftungen kreuzt; findet sich auch so keine, gilt die Stelle mit den wenigsten Berührungen.
             */
            List<Segment> labels(Map<String, Rectangle2D> boxes) {
                Rectangle2D area = width > 0 && height > 0 ? new Rectangle2D.Double(0, 0, width, height) : null;
                return place(lines(boxes), boxes, area).segments();
            }

            /**
             * Setzt die Beschriftungen (siehe {@link #labels}). Bleiben in der Fachsicht Berührungen, versucht sie es
             * erneut und setzt die Beschriftungen, die keinen freien Platz fanden, zuerst: Ein einzelner gieriger
             * Durchlauf kann eine früh gesetzte Beschriftung nicht mehr verschieben. Es gilt die Runde mit den
             * wenigsten Berührungen.
             */
            private Placed place(List<Segment> segments, Map<String, Rectangle2D> boxes, Rectangle2D area) {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < segments.size(); i++) {
                    order.add(i);
                }
                int[] hits = new int[segments.size()];
                Placed best = place(segments, boxes, area, order, hits);
                for (int round = 1; round < LABEL_ROUNDS && domain && best.hits() > 0 && work < budget; round++) {
                    List<Integer> next = new ArrayList<>();
                    for (int i : order) {
                        if (hits[i] > 0) {
                            next.add(i);
                        }
                    }
                    for (int i : order) {
                        if (hits[i] == 0) {
                            next.add(i);
                        }
                    }
                    if (next.equals(order)) {
                        break;
                    }
                    order = next;
                    int[] roundHits = new int[segments.size()];
                    Placed attempt = place(segments, boxes, area, order, roundHits);
                    if (attempt.hits() < best.hits()) {
                        best = attempt;
                    }
                    hits = roundHits;
                }
                return best;
            }

            /** Eine Runde in der Reihenfolge {@code order}; trägt die Berührungen je Kante in {@code hitsOut} ein. */
            private Placed place(List<Segment> segments, Map<String, Rectangle2D> boxes, Rectangle2D area,
                                 List<Integer> order, int[] hitsOut) {
                List<Rectangle2D> placed = new ArrayList<>();
                List<Rectangle2D> drawn = new ArrayList<>();
                for (Rectangle2D box : boxes.values()) {
                    drawn.add(drawn(box));
                }
                List<Segment> current = new ArrayList<>(segments);
                // Nebeneinander gebogene Aussagen behalten ihren Bogen: ein Umweg legte sie auf die Nachbarn.
                Map<String, Integer> pairs = new java.util.HashMap<>();
                for (Segment segment : segments) {
                    pairs.merge(pairKey(segment.edge()), 1, Integer::sum);
                }
                java.util.Set<String> fanned = new java.util.HashSet<>();
                if (domain) {
                    pairs.forEach((pair, count) -> {
                        if (count > 1) {
                            fanned.add(pair);
                        }
                    });
                }
                int total = 0;
                for (int i : order) {
                    Segment segment = current.get(i);
                    String label = labelOf(segment.edge());
                    if (label == null) {
                        continue;
                    }
                    check();
                    double w = labelMetrics.stringWidth(label) + 10;
                    double h = labelMetrics.getHeight() + 2;
                    Rectangle2D best = null;
                    Segment bestShape = segment;
                    int bestHits = Integer.MAX_VALUE;
                    List<Segment> shapes = new ArrayList<>();
                    shapes.add(segment);
                    // Ist das Budget aufgebraucht, setzt die Fachsicht die übrigen Beschriftungen nur noch mittig auf
                    // ihre Linie wie die Bestandssicht: jede Kante behält eine Beschriftung, die Rechnung bleibt
                    // begrenzt.
                    boolean thorough = domain && work < budget;
                    for (int s = 0; s < shapes.size() && bestHits > 0; s++) {
                        Segment shape = shapes.get(s);
                        if (s > 0 && (foreignHits(shape, boxes, 1) > 0 || crossesAny(shape, placed))) {
                            continue;
                        }
                        for (double t : thorough ? LABEL_POSITIONS : MIDDLE) {
                            Point2D middle = shape.at(t);
                            Rectangle2D candidate = new Rectangle2D.Double(middle.getX() - w / 2,
                                    middle.getY() - h / 2, w, h);
                            int hits = labelHits(candidate, i, current, placed, drawn, area, bestHits);
                            if (hits < bestHits) {
                                best = candidate;
                                bestShape = shape;
                                bestHits = hits;
                            }
                            if (hits == 0 || !thorough) {
                                break;
                            }
                        }
                        if (s == 0 && bestHits > 0 && thorough && !fanned.contains(pairKey(segment.edge()))) {
                            shapes.addAll(detours(segment, boxes));
                        }
                    }
                    placed.add(best);
                    hitsOut[i] = bestHits;
                    total += bestHits;
                    current.set(i, new Segment(segment.edge(), bestShape.a(), bestShape.b(), best,
                            bestShape.control()));
                }
                return new Placed(current, total);
            }

            /**
             * Berührungen einer Beschriftung (mit {@link #LABEL_GAP} Abstand) für die Kante an Stelle {@code own}; hört
             * bei {@code limit} auf zu zählen, weil dann schon eine bessere Stelle bekannt ist.
             */
            private int labelHits(Rectangle2D candidate, int own, List<Segment> segments, List<Rectangle2D> placed,
                                  List<Rectangle2D> drawn, Rectangle2D area, int limit) {
                work += placed.size() + drawn.size() + segments.size();
                Rectangle2D padded = padded(candidate, LABEL_GAP);
                int hits = area != null && !area.contains(candidate) ? 1 : 0;
                for (int j = 0; j < placed.size() && hits < limit; j++) {
                    hits += placed.get(j).intersects(padded) ? 1 : 0;
                }
                for (int j = 0; j < drawn.size() && hits < limit; j++) {
                    hits += drawn.get(j).intersects(padded) ? 1 : 0;
                }
                for (int j = 0; j < segments.size() && hits < limit; j++) {
                    if (j != own && segments.get(j).crosses(padded)) {
                        hits++;
                    }
                }
                return hits;
            }

            /** Ob die Linie eine der schon gesetzten Beschriftungen (mit Abstand) kreuzt. */
            private boolean crossesAny(Segment shape, List<Rectangle2D> placed) {
                work += placed.size();
                for (Rectangle2D tag : placed) {
                    if (shape.crosses(padded(tag, LABEL_GAP))) {
                        return true;
                    }
                }
                return false;
            }

            private int depthOf(String key) {
                return depths.getOrDefault(key, 0);
            }

            /**
             * Alle Berührungen einer Lage: Knoten außerhalb der Fläche oder übereinander ({@link #NODE_WEIGHT}), Kanten
             * unter fremden Knoten, Beschriftungen außerhalb der Fläche, auf Knoten, auf anderen Beschriftungen oder
             * von fremden Kanten gekreuzt (jeweils mit {@link #LABEL_GAP} Abstand).
             */
            long conflicts(Map<String, Rectangle2D> boxes, List<Segment> segments, Rectangle2D area) {
                List<Rectangle2D> nodes = new ArrayList<>(boxes.values());
                long conflicts = (long) nodeConflicts(boxes, area) * NODE_WEIGHT;
                work += (long) segments.size() * (nodes.size() + segments.size());
                for (Segment segment : segments) {
                    conflicts += foreignHits(segment, boxes, Integer.MAX_VALUE);
                }
                for (int i = 0; i < segments.size(); i++) {
                    Rectangle2D tag = segments.get(i).tag();
                    if (tag == null) {
                        continue;
                    }
                    Rectangle2D padded = padded(tag, LABEL_GAP);
                    conflicts += area.contains(tag) ? 0 : 1;
                    for (Rectangle2D box : nodes) {
                        conflicts += drawn(box).intersects(padded) ? 1 : 0;
                    }
                    for (int j = 0; j < segments.size(); j++) {
                        if (j == i) {
                            continue;
                        }
                        Rectangle2D other = segments.get(j).tag();
                        conflicts += j > i && other != null && other.intersects(padded) ? 1 : 0;
                        conflicts += segments.get(j).crosses(padded) ? 1 : 0;
                    }
                }
                return conflicts;
            }

            /** Knoten außerhalb der Fläche oder übereinander (je Paar), ungewichtet. */
            private int nodeConflicts(Map<String, Rectangle2D> boxes, Rectangle2D area) {
                List<Rectangle2D> nodes = new ArrayList<>(boxes.values());
                work += (long) nodes.size() * nodes.size() / 2;
                int conflicts = 0;
                for (int i = 0; i < nodes.size(); i++) {
                    conflicts += area.contains(nodes.get(i)) ? 0 : 1;
                    for (int j = i + 1; j < nodes.size(); j++) {
                        conflicts += nodes.get(i).intersects(nodes.get(j)) ? 1 : 0;
                    }
                }
                return conflicts;
            }

            /**
             * Die Kästen bei dieser Ausdehnung der Ringe: die Mitte im Zentrum, Tiefe 1 gleichmäßig im Kreis, Tiefe 2
             * außen im Fächer ihres Vorgängers; ohne Mitte alle auf Ringen ({@link #rings}).
             */
            Map<String, Rectangle2D> boxes(Spread spread) {
                if (graph.nodes().isEmpty() || width <= 0 || height <= 0) {
                    return new LinkedHashMap<>();
                }
                double cx = width / 2.0;
                double cy = height / 2.0;
                if (!centred) {
                    return boxes(rings(spread));
                }
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
                double r1 = Math.min(radius * 0.95, (children.isEmpty() ? radius * 0.75 : radius * 0.5)
                        * spread.inner());
                double r2 = radius * 0.95 * spread.outer();
                double stretch = spread.stretch();
                Map<String, Point2D> centres = new LinkedHashMap<>();
                Map<String, Double> angles = new LinkedHashMap<>();
                centres.put(graph.nodes().get(0).key(), new Point2D.Double(cx, cy));
                int n = first.size();
                // Ab vier Nachbarn um einen halben Schritt gedreht: kein breiter Kasten genau links oder rechts der
                // Mitte.
                double turn = (domain && n >= 4 && n % 2 == 0 ? Math.PI / n : 0)
                        + (n == 0 ? 0 : spread.turn() * 2 * Math.PI / n);
                for (int i = 0; i < n; i++) {
                    double angle = -Math.PI / 2 + turn + 2 * Math.PI * i / n;
                    angles.put(first.get(i).key(), angle);
                    centres.put(first.get(i).key(), new Point2D.Double(cx + r1 * Math.cos(angle) * stretch,
                            cy + r1 * Math.sin(angle)));
                }
                double sector = n == 0 ? 2 * Math.PI : 2 * Math.PI / n;
                for (Map.Entry<String, List<GraphSource.Node>> entry : children.entrySet()) {
                    double base = angles.getOrDefault(entry.getKey(), -Math.PI / 2);
                    List<GraphSource.Node> kids = entry.getValue();
                    double step = Math.min(0.45, sector * 0.8 / Math.max(1, kids.size()));
                    for (int j = 0; j < kids.size(); j++) {
                        double angle = base + (j - (kids.size() - 1) / 2.0) * step;
                        centres.put(kids.get(j).key(), new Point2D.Double(cx + r2 * Math.cos(angle) * stretch,
                                cy + r2 * Math.sin(angle)));
                    }
                }
                return boxes(centres);
            }

            /** Kästen um die Mittelpunkte, in der Fläche gehalten. */
            private Map<String, Rectangle2D> boxes(Map<String, Point2D> centres) {
                Map<String, Rectangle2D> boxes = new LinkedHashMap<>();
                for (GraphSource.Node node : graph.nodes()) {
                    Point2D c = centres.get(node.key());
                    if (c == null) {
                        continue;
                    }
                    double w = nodeMetrics.stringWidth(node.label()) + 2 * PAD;
                    // Die Fachsicht hält Knoten samt Schatten ein Stück vom Rand, damit keiner an der Kante klebt.
                    double edge = domain ? EDGE_MARGIN : 4;
                    double far = domain ? EDGE_MARGIN + NODE_SHADOW : 8;
                    double x = Math.max(edge, Math.min(width - w - far, c.getX() - w / 2));
                    double y = Math.max(edge, Math.min(height - BOX_HEIGHT - far, c.getY() - BOX_HEIGHT / 2.0));
                    boxes.put(node.key(), new Rectangle2D.Double(x, y, w, BOX_HEIGHT));
                }
                return boxes;
            }

            /**
             * Ohne Mitte: alle Knoten in ihrer Reihenfolge (die mit den meisten Belegen zuerst) auf bis zu drei
             * Ringen, innen die ersten; je Ring so viele, wie sein Umfang trägt, versetzt gegen den Ring davor.
             */
            private Map<String, Point2D> rings(Spread spread) {
                Map<String, Point2D> centres = new LinkedHashMap<>();
                List<GraphSource.Node> nodes = graph.nodes();
                int n = nodes.size();
                double cx = width / 2.0;
                double cy = height / 2.0;
                if (n == 1) {
                    centres.put(nodes.get(0).key(), new Point2D.Double(cx, cy));
                    return centres;
                }
                double radius = Math.min(width, height) / 2.0 - BOX_HEIGHT;
                int count = n <= 10 ? 1 : n <= 24 ? 2 : 3;
                double[] radii = new double[count];
                double total = 0;
                for (int k = 0; k < count; k++) {
                    radii[k] = count == 1 ? radius * 0.8 : radius * (0.38 + 0.57 * k / (count - 1));
                    total += radii[k];
                }
                // Die Verteilung auf die Ringe bleibt die der Grundlage; gestreckt werden nur die Halbmesser.
                int[] onRings = new int[count];
                int assigned = 0;
                for (int k = 0; k < count; k++) {
                    onRings[k] = k == count - 1 ? n - assigned : (int) Math.round(n * radii[k] / total);
                    assigned += onRings[k];
                }
                for (int k = 0; k < count; k++) {
                    boolean inner = count == 1 || k < count - 1;
                    radii[k] = Math.min(radius, radii[k] * (inner ? spread.inner() : spread.outer()));
                }
                int index = 0;
                for (int k = 0; k < count; k++) {
                    int onRing = onRings[k];
                    double shift = (k % 2 == 0 ? 0 : Math.PI / Math.max(1, onRing))
                            + (count == 1 || k < count - 1 ? spread.turn() * 2 * Math.PI / Math.max(1, onRing) : 0);
                    for (int i = 0; i < onRing && index < n; i++, index++) {
                        double angle = -Math.PI / 2 + shift + 2 * Math.PI * i / onRing;
                        centres.put(nodes.get(index).key(), new Point2D.Double(
                                cx + radii[k] * Math.cos(angle) * spread.stretch(), cy + radii[k] * Math.sin(angle)));
                    }
                }
                return centres;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (graph.nodes().isEmpty() && emptyText != null && !emptyText.isBlank()) {
                    paintEmpty(g2);
                    return;
                }
                // Nur die fertige Lage: Zeichnen sucht nie (die Fachsicht sucht abseits des EDT, siehe requestLayout).
                Layout view = view();
                if (view == null) {
                    return;
                }
                // Mehrere Beziehungen zwischen denselben Begriffen nebeneinander zeichnen statt übereinander.
                for (Segment segment : view.segments()) {
                    paintEdge(g2, segment);
                }
                for (GraphSource.Node node : graph.nodes()) {
                    Rectangle2D box = view.boxes().get(node.key());
                    if (box != null) {
                        paintNode(g2, node, box);
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        /** Der Text der leeren Fläche: gedämpft, mittig, umbrochen. */
        private void paintEmpty(Graphics2D g2) {
            Font font = Comic.regular(13f);
            FontMetrics metrics = g2.getFontMetrics(font);
            int max = Math.max(120, Math.min(getWidth() - 48, 460));
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : emptyText.strip().split("\\s+")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && metrics.stringWidth(next) > max) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            g2.setFont(font);
            g2.setColor(Comic.MUTED);
            float y = (float) (getHeight() / 2.0 - lines.size() * metrics.getHeight() / 2.0 + metrics.getAscent());
            for (String text : lines) {
                g2.drawString(text, (float) (getWidth() / 2.0 - metrics.stringWidth(text) / 2.0), y);
                y += metrics.getHeight();
            }
        }

        private static final double PARALLEL_GAP = 22;
        /** Abstand der Scheitel nebeneinander gebogener Aussagen eines Knotenpaars (UX W9). */
        static final double ARC_GAP = 30;

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
                case VERB -> edge.bundle() ? Comic.MUTED : edge.qualifiers().past() ? PALE : Comic.INK;
                case SYNONYM -> Comic.MUTED;
                case RELATION -> edgeColor(edge.type());
            };
            g2.setColor(color);
            if (edge.style() == GraphSource.Style.SYNONYM || edge.bundle()) {
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
            if (edge.style() == GraphSource.Style.VERB && edge.qualifiers().negated()) {
                negation(g2, segment, box);
            }
            String label = labelOf(edge);
            if (box == null || label == null) {
                return;
            }
            Font font = Comic.regular(11f);
            FontMetrics metrics = g2.getFontMetrics(font);
            RoundRectangle2D tag = new RoundRectangle2D.Double(box.getX(), box.getY(), box.getWidth(),
                    box.getHeight(), 10, 10);
            g2.setColor(AppColors.of("app.TermGraphView.paintEdge"));
            g2.fill(tag);
            g2.setStroke(new BasicStroke(1.2f));
            g2.setColor(color);
            g2.draw(tag);
            g2.setFont(font);
            float x = (float) (box.getCenterX() - metrics.stringWidth(label) / 2.0);
            float y = (float) (box.getY() + 1 + metrics.getAscent());
            int dot = edge.style() == GraphSource.Style.VERB ? label.lastIndexOf(" · ") : -1;
            Color text = edge.style() == GraphSource.Style.VERB && edge.qualifiers().past() ? PALE : Comic.INK;
            if (dot < 0) {
                g2.setColor(text);
                g2.drawString(label, x, y);
            } else {
                // „Grundform · N“: die Zahl gedämpft.
                String verb = label.substring(0, dot);
                g2.setColor(text);
                g2.drawString(verb, x, y);
                g2.setColor(Comic.MUTED);
                g2.drawString(label.substring(dot), x + metrics.stringWidth(verb), y);
            }
        }

        /** Linie und Schrift einer Aussage, die früher galt oder verworfen ist (UX W9): blasser, nie farbig. */
        static final Color PALE = new Color(Comic.INK.getRed(), Comic.INK.getGreen(), Comic.INK.getBlue(), 105);
        /** Länge des Querstrichs einer verneinten Aussage (UX W9). */
        static final double NEGATION_LENGTH = 14;
        /** Abstand des Querstrichs zur Beschriftung (UX W9). */
        static final double NEGATION_GAP = 8;
        /** So lang ist eine gestrichelte oder gepunktete Linie um den Querstrich durchgezogen (UX W9). */
        static final double NEGATION_SOLID = 12;

        /**
         * Querstrich einer verneinten Aussage (UX W9): 14 px, etwa 60° zur Linie, in Linienfarbe, mindestens 2 px stark,
         * {@link #NEGATION_GAP} vor der Beschriftung auf der Seite des Startknotens, obenauf. Auf gestrichelter oder
         * gepunkteter Linie ist die Linie {@link #NEGATION_SOLID} um ihn durchgezogen, damit er sich vom Muster abhebt.
         */
        private static void negation(Graphics2D g2, Segment segment, Rectangle2D tag) {
            double t = negationAt(segment, tag);
            Point2D at = segment.at(t);
            Point2D ahead = segment.at(Math.min(1, t + 0.01));
            Point2D behind = segment.at(Math.max(0, t - 0.01));
            double direction = Math.atan2(ahead.getY() - behind.getY(), ahead.getX() - behind.getX());
            java.awt.Stroke before = g2.getStroke();
            float line = before instanceof BasicStroke basic ? basic.getLineWidth() : 2f;
            if (before instanceof BasicStroke basic && basic.getDashArray() != null) {
                // Ein Stück durchgezogene Linie unter dem Querstrich, entlang der Kante.
                double step = 0.002;
                Point2D[] ends = new Point2D[2];
                for (int side = 0; side < 2; side++) {
                    Point2D last = at;
                    double walked = 0;
                    for (double u = t; walked < NEGATION_SOLID / 2 && u >= 0 && u <= 1;
                         u += side == 0 ? -step : step) {
                        Point2D next = segment.at(Math.max(0, Math.min(1, u)));
                        walked += next.distance(last);
                        last = next;
                    }
                    ends[side] = last;
                }
                g2.setStroke(new BasicStroke(line, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g2.draw(new Line2D.Double(ends[0], at));
                g2.draw(new Line2D.Double(at, ends[1]));
            }
            double angle = direction + Math.toRadians(60);
            double dx = Math.cos(angle) * NEGATION_LENGTH / 2;
            double dy = Math.sin(angle) * NEGATION_LENGTH / 2;
            g2.setStroke(new BasicStroke(Math.max(2f, line), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Line2D.Double(at.getX() - dx, at.getY() - dy, at.getX() + dx, at.getY() + dy));
            g2.setStroke(before);
        }

        /**
         * Wo der Querstrich sitzt: auf der Linie, so weit vor der Beschriftung (Seite des Startknotens), dass er
         * {@link #NEGATION_GAP} Abstand hält; ohne Beschriftung die Mitte.
         */
        static double negationAt(Segment segment, Rectangle2D tag) {
            if (tag == null) {
                return 0.5;
            }
            double margin = NEGATION_GAP + NEGATION_LENGTH / 2;
            Rectangle2D near = new Rectangle2D.Double(tag.getX() - margin, tag.getY() - margin,
                    tag.getWidth() + 2 * margin, tag.getHeight() + 2 * margin);
            int steps = 200;
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                if (near.contains(segment.at(t))) {
                    return Math.max(0.02, t);
                }
            }
            return 0.5;
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
            // Ohne Mitte ist kein Knoten hervorgehoben: alle wie Nachbarn.
            int depth = centred ? node.depth() : 1;
            if (node.synonym() && depth > 0) {
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
            g2.setColor(depth == 0 ? Comic.YELLOW : depth == 1 ? AppColors.of("app.TermGraphView.paintNode") : Comic.SURFACE);
            g2.fill(shape);
            g2.setColor(Comic.INK);
            g2.setStroke(new BasicStroke(depth == 0 ? 2.6f : 2f));
            g2.draw(shape);
            Font font = depth == 0 ? Comic.semiBold(14f) : getFont();
            FontMetrics metrics = g2.getFontMetrics(font);
            g2.setFont(font);
            g2.setColor(depth == 2 ? Comic.TEXT : Comic.INK);
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
