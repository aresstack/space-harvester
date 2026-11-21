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
            + "Analysators; veraltete Aussagen bleiben weg.";
    static final String NO_NEIGHBOURS = "Zu diesem Begriff gibt es noch keine Beziehungen.";
    static final String NO_VERBS = "Zu diesem Fachbegriff gibt es auf den analysierten, für dich sichtbaren Seiten "
            + "noch keine Aussagen mit anderen Fachbegriffen.";
    static final String DOMAIN = "Nur Fachbegriffe";
    static final String OPTIONS_TIP = "Optionen: Vorschläge zeigen, Nur Fachbegriffe";
    static final String TRUNCATED = "Gezeigt werden die ersten " + GraphSource.MAX_NODES + " Begriffe.";
    /** Höchstens so viele Vorschläge zeigt die Begriffssuche am Startfeld. */
    static final int SUGGESTIONS = 8;

    private final AppContext context;
    private final GraphSource source;
    /** Fachsicht (Paket W1) hinter „Nur Fachbegriffe“; {@code null} ohne Laufzeit. */
    private final GraphSource domainSource;
    private final javax.swing.JCheckBoxMenuItem domain = new javax.swing.JCheckBoxMenuItem(DOMAIN);
    private final EvidencePopup evidence = new EvidencePopup();
    private java.util.function.Consumer<EvidencePopup.Target> onOpen = target -> { };
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final PlaceholderField start = new PlaceholderField(18, "Startbegriff …");
    private final JComboBox<String> depth = Comic.combo();
    private final javax.swing.JCheckBoxMenuItem proposals = new javax.swing.JCheckBoxMenuItem("Vorschläge zeigen");
    /** Die Schalter hinter einem Icon-Knopf am Zeilenende, damit die Reiterzeile auch bei 960 px in eine Zeile passt. */
    private final IconButton options = new IconButton(Glyph.FILTER, OPTIONS_TIP);
    private final JPopupMenu optionsMenu = new JPopupMenu();
    private final ComposerButton showButton = Comic.button("Zeigen", "Den Graphen um den Startbegriff zeichnen");
    private final JLabel hint = Comic.muted(" ");
    private final Canvas canvas = new Canvas();
    private final JPopupMenu suggestions = new JPopupMenu();
    private String centre;
    private int request;
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
        }), context == null ? null : GraphSource.ofDomainTerms((term, levels) -> {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                throw new IllegalStateException(TermDirectory.NO_RUNTIME);
            }
            return runtime.workspace().domainTermGraph(term,
                    new com.aresstack.spaceharvester.application.knowledge.DomainTermGraph.Options(levels, false,
                            scope.get().get()), () -> false);
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
        proposals.setToolTipText("Auch vorgeschlagene Beziehungen zeigen, gestrichelt");
        domain.setToolTipText("Fachsicht: nur bestätigte Fachbegriffe und Synonyme, Verben der Aussagen als Kanten");

        JLabel heading = Comic.text("Wissensgraph der Begriffe");
        heading.setFont(Comic.semiBold(15f));
        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        lines.add(heading, BorderLayout.NORTH);
        controls.setOpaque(false);
        controls.add(start);
        controls.add(depth);
        optionsMenu.add(proposals);
        optionsMenu.add(domain);
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
        domain.addActionListener(e -> reload());
        evidence.onOpen(target -> onOpen.accept(target));
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
        reload();
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
        if (!wanted.equals(start.getText())) {
            quiet = true;
            start.setText(wanted);
            quiet = false;
        }
        int current = ++request;
        int levels = depth.getSelectedIndex() + 1;
        boolean withProposals = proposals.isSelected();
        hint.setText("Lädt …");
        Background.run(() -> active.around(wanted, levels, withProposals), graph -> {
            if (current == request) {
                show(graph);
            }
        }, failure -> {
            if (current == request) {
                show(GraphSource.Graph.EMPTY);
                hint.setText("Graph nicht lesbar: " + failure.getMessage());
            }
        });
    }

    /** Zeigt einen fertigen Graphen. */
    void show(GraphSource.Graph graph) {
        evidence.setVisible(false);
        canvas.graph(graph);
        boolean fach = domainMode();
        String text;
        if (graph.nodes().isEmpty()) {
            text = graph.empty() == null ? " " : graph.empty();
        } else if (graph.nodes().size() == 1) {
            text = fach ? NO_VERBS : NO_NEIGHBOURS;
        } else {
            text = Texts.count(graph.nodes().size(), fach ? "Fachbegriff" : "Begriff", fach ? "Fachbegriffe"
                    : "Begriffe") + " · " + Texts.count(graph.edges().size(), fach ? "Verbindung" : "Beziehung",
                    fach ? "Verbindungen" : "Beziehungen") + (graph.truncated() ? " · " + TRUNCATED : "");
        }
        if (!graph.note().isBlank()) {
            text = (text.isBlank() ? "" : text + " · ") + graph.note();
        }
        hint.setText(text);
    }

    /** Overlay „Belege“ einer Verb-Kante: Sätze im Hintergrund lesen, dann zeigen. */
    private void showEvidence(GraphSource.Edge edge, int x, int y) {
        String title = canvas.label(edge.from()) + " " + edge.label().replaceFirst(" · \\d+$", "") + " "
                + canvas.label(edge.to());
        int total = edgeCount(edge);
        AppRuntime runtime = context == null ? null : context.runtime();
        if (runtime == null) {
            evidence.fill(title, total, edge.evidence(), java.util.Map.of()).showAt(canvas, x, y);
            return;
        }
        java.util.Map<com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph.PageRef,
                java.util.List<com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Statement>> byPage =
                new java.util.LinkedHashMap<>();
        edge.evidence().forEach(item -> byPage.computeIfAbsent(item.page(), unused -> new ArrayList<>())
                .add(item.statement()));
        int current = request;
        Background.run(() -> runtime.workspace().statementSentences(byPage), sentences -> {
            if (current == request && canvas.isShowing()) {
                evidence.fill(title, total, edge.evidence(), sentences).showAt(canvas, x, y);
            }
        }, failure -> {
            if (current == request && canvas.isShowing()) {
                evidence.fill(title, total, edge.evidence(), java.util.Map.of()).showAt(canvas, x, y);
            }
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
        Background.run(() -> runtime.workspace().terms(new TermQueries.TermSearch(null, null, null, q, false,
                SUGGESTIONS, null, null, null, pages.get())).terms(), terms -> {
            if (current != suggestRequest || !start.isShowing()) {
                return;
            }
            suggestions.setVisible(false);
            suggestions.removeAll();
            for (TermQueries.TermSummary term : terms) {
                JMenuItem item = new JMenuItem(term.term());
                item.addActionListener(e -> showTerm(term.term()));
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

    PlaceholderField startField() {
        return start;
    }

    JComboBox<String> depthChoice() {
        return depth;
    }

    javax.swing.JCheckBoxMenuItem proposalsChoice() {
        return proposals;
    }

    javax.swing.JCheckBoxMenuItem domainChoice() {
        return domain;
    }

    EvidencePopup evidencePopup() {
        return evidence;
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
            List<Rectangle2D> placed = new ArrayList<>();
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
                if (centre != null && offset == 0 && depthOf(edge.from()) > 0 && depthOf(edge.to()) > 0
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
                Rectangle2D tag = null;
                String label = labelOf(edge);
                if (label != null) {
                    FontMetrics metrics = getFontMetrics(Comic.regular(11f));
                    double w = metrics.stringWidth(label) + 10;
                    double h = metrics.getHeight() + 2;
                    Segment line = new Segment(edge, a, b, null, control);
                    // Beschriftung entlang der Kante verschieben, bis sie weder Knoten noch andere Beschriftungen
                    // überdeckt; sonst bleibt sie in der Mitte.
                    for (double t : new double[] {0.5, 0.38, 0.62, 0.3, 0.7}) {
                        Point2D middle = line.at(t);
                        Rectangle2D candidate = new Rectangle2D.Double(middle.getX() - w / 2, middle.getY() - h / 2,
                                w, h);
                        if (tag == null) {
                            tag = candidate;
                        }
                        if (placed.stream().noneMatch(candidate::intersects)
                                && boxes.values().stream().noneMatch(candidate::intersects)) {
                            tag = candidate;
                            break;
                        }
                    }
                    placed.add(tag);
                }
                segments.add(new Segment(edge, a, b, tag, control));
            }
            return segments;
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
                case VERB -> "Klick zeigt die Belege";
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
            double turn = n >= 4 && n % 2 == 0 ? Math.PI / n : 0;
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
