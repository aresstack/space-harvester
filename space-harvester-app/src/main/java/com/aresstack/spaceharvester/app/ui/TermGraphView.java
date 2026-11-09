package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;
import com.aresstack.spaceharvester.application.terms.TermRelations;

import javax.swing.JCheckBox;
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

/**
 * Der Reiter „Graph“ unter Begriffe: ein Wissensgraph um einen Startbegriff. Begriffe sind Knoten, Beziehungen Kanten,
 * beschriftet mit ihrer Art; bestätigte durchgezogen, auf Wunsch auch vorgeschlagene gestrichelt. Die Mitte steht im
 * Zentrum, ihre Nachbarn (Tiefe 1) im Kreis darum, deren Nachbarn (Tiefe 2) außen bei ihrem Vorgänger. Ein Doppelklick
 * auf einen Knoten macht ihn zur Mitte. Die Daten kommen aus einer {@link GraphSource}, außerhalb des EDT. Nur auf dem
 * EDT.
 */
final class TermGraphView {

    static final String START = "Begriff eingeben und Enter drücken; ein Doppelklick auf einen Knoten macht ihn zur "
            + "Mitte.";
    static final String NO_NEIGHBOURS = "Zu diesem Begriff gibt es noch keine Beziehungen.";
    static final String TRUNCATED = "Gezeigt werden die ersten " + GraphSource.MAX_NODES + " Begriffe.";
    /** Höchstens so viele Vorschläge zeigt die Begriffssuche am Startfeld. */
    static final int SUGGESTIONS = 8;

    private final AppContext context;
    private final GraphSource source;
    private final JPanel root = new JPanel(new BorderLayout(0, 8));
    private final PlaceholderField start = new PlaceholderField(18, "Startbegriff …");
    private final JComboBox<String> depth = Comic.combo();
    private final JCheckBox proposals = Comic.checkBox("Vorschläge zeigen");
    private final ComposerButton showButton = Comic.button("Zeigen", "Den Graphen um den Startbegriff zeichnen");
    private final JLabel hint = Comic.muted(START);
    private final Canvas canvas = new Canvas();
    private final JPopupMenu suggestions = new JPopupMenu();
    private String centre;
    private int request;
    private int suggestRequest;
    private boolean quiet;

    /** @param context Laufzeit; {@code null} zeigt nur das Gerüst (Tests) */
    TermGraphView(AppContext context) {
        this(context, context == null ? null : GraphSource.ofRelations((term, withProposals) -> {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                throw new IllegalStateException(TermsView.NO_RUNTIME);
            }
            return runtime.workspace().termRelations(term, null, withProposals ? null : ReviewStatus.CONFIRMED,
                    TermRelations.MAX_LIMIT).relations();
        }));
    }

    TermGraphView(AppContext context, GraphSource source) {
        this.context = context;
        this.source = source;
        root.setOpaque(false);
        depth.addItem("Tiefe 1");
        depth.addItem("Tiefe 2");
        depth.setSelectedIndex(1);
        proposals.setOpaque(false);
        proposals.setToolTipText("Auch vorgeschlagene Beziehungen zeigen, gestrichelt");

        JLabel heading = Comic.text("Wissensgraph der Begriffe");
        heading.setFont(Comic.semiBold(15f));
        JPanel lines = new JPanel(new BorderLayout(0, 2));
        lines.setOpaque(false);
        lines.add(heading, BorderLayout.NORTH);
        lines.add(Comic.muted("Begriffe als Knoten, Beziehungen als Kanten"), BorderLayout.SOUTH);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setOpaque(false);
        controls.add(start);
        controls.add(depth);
        controls.add(proposals);
        controls.add(showButton);
        JPanel head = new JPanel(new BorderLayout(12, 0));
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
                }
            }
        });
        boolean enabled = source != null;
        start.setEnabled(enabled);
        showButton.setEnabled(enabled);
    }

    JComponent component() {
        return root;
    }

    /** Der Reiter oder Bereich wird sichtbar: ein schon gezeigter Graph lädt neu, Änderungen anderer Reiter folgen. */
    void shown() {
        reload();
    }

    private void reload() {
        if (centre != null) {
            showTerm(centre);
        }
    }

    /** Zeichnet den Graphen um {@code term}; leer räumt ihn ab. */
    void showTerm(String term) {
        suggestions.setVisible(false);
        if (source == null || term == null || term.isBlank()) {
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
        Background.run(() -> source.around(wanted, levels, withProposals), graph -> {
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
        canvas.graph(graph);
        if (graph.nodes().size() <= 1) {
            hint.setText(graph.nodes().isEmpty() ? START : NO_NEIGHBOURS);
        } else {
            hint.setText(Texts.count(graph.nodes().size(), "Begriff", "Begriffe") + " · "
                    + Texts.count(graph.edges().size(), "Beziehung", "Beziehungen")
                    + (graph.truncated() ? " · " + TRUNCATED : ""));
        }
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
        Background.run(() -> runtime.workspace().terms(new TermQueries.TermSearch(null, null, null, q, false,
                SUGGESTIONS, null)).terms(), terms -> {
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

    JCheckBox proposalsChoice() {
        return proposals;
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
        }

        void graph(GraphSource.Graph graph) {
            this.graph = graph;
            repaint();
        }

        GraphSource.Graph graph() {
            return graph;
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
            for (int i = 0; i < n; i++) {
                double angle = -Math.PI / 2 + 2 * Math.PI * i / n;
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
                Map<String, Integer> pairs = new java.util.HashMap<>();
                for (GraphSource.Edge edge : graph.edges()) {
                    pairs.merge(pairKey(edge), 1, Integer::sum);
                }
                Map<String, Integer> seen = new java.util.HashMap<>();
                for (GraphSource.Edge edge : graph.edges()) {
                    String pair = pairKey(edge);
                    int index = seen.merge(pair, 1, Integer::sum) - 1;
                    double offset = (index - (pairs.get(pair) - 1) / 2.0) * PARALLEL_GAP;
                    paintEdge(g2, edge, boxes.get(edge.from()), boxes.get(edge.to()), offset);
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

        private void paintEdge(Graphics2D g2, GraphSource.Edge edge, Rectangle2D from, Rectangle2D to,
                double offset) {
            if (from == null || to == null) {
                return;
            }
            Point2D a = exit(from, to);
            Point2D b = exit(to, from);
            if (offset != 0) {
                // Senkrecht zur Verbindung in fester Richtung (unabhängig von der Kantenrichtung) verschieben.
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
            Color color = edgeColor(edge.type());
            g2.setColor(color);
            g2.setStroke(edge.confirmed() ? new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    : new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f},
                    0f));
            g2.draw(new Line2D.Double(a, b));
            if (edge.directed()) {
                arrow(g2, a, b);
            }
            String label = Relation.typeText(edge.type());
            Font font = Comic.regular(11f);
            FontMetrics metrics = g2.getFontMetrics(font);
            double mx = (a.getX() + b.getX()) / 2;
            double my = (a.getY() + b.getY()) / 2;
            double w = metrics.stringWidth(label) + 10;
            double h = metrics.getHeight() + 2;
            RoundRectangle2D tag = new RoundRectangle2D.Double(mx - w / 2, my - h / 2, w, h, 10, 10);
            g2.setColor(Color.WHITE);
            g2.fill(tag);
            g2.setStroke(new BasicStroke(1.2f));
            g2.setColor(color);
            g2.draw(tag);
            g2.setFont(font);
            g2.setColor(Comic.INK);
            g2.drawString(label, (float) (mx - metrics.stringWidth(label) / 2.0),
                    (float) (my - h / 2 + 1 + metrics.getAscent()));
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
