package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Point;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Die Lagesuche der Fachsicht (Codex P1, Architektur): Sie läuft abseits des EDT über einen austauschbaren Executor,
 * hält ein festes Budget (gezählte Arbeit, keine Uhrzeit) und wird nie beim Zeichnen oder bei der Treffersuche
 * angestoßen; bis sie fertig ist, gilt die letzte Lage, bei anderer Größe auf die Fläche gezogen. Graph mit 40
 * Knoten und 70 Kanten, Begriffe erfunden.
 */
class TermGraphViewSearchTest {

    /**
     * Klein genug, dass der dichte Graph es auf jeder Maschine erreicht (kleine Graphen brauchen schon bis zu rund
     * 480.000 Einheiten); das echte Budget hängt mit den Schriftmaßen zusammen und ist dafür kein fester Prüfstein.
     */
    private static final long SMALL_BUDGET = 100_000L;


    private static final String[] WORDS = {"Antrag", "Sachbearbeiter", "Nachweis", "Beleg", "Plan", "Ziel", "Start",
            "Akte", "Bescheid", "Prüfstelle", "Gebühr", "Vorgang", "Frist", "Kunde", "Vertrag", "Rechnung"};

    /** {@code n} Knoten, {@code m} Verb-Kanten; mit Mitte in Tiefe 0 bis 2, sonst alle gleichrangig (Ring). */
    private static GraphSource.Graph graph(int n, int m, boolean centred) {
        List<GraphSource.Node> nodes = new ArrayList<>();
        int first = Math.min(n - 1, Math.max(4, n / 4));
        for (int i = 0; i < n; i++) {
            String label = WORDS[i % WORDS.length] + (i >= WORDS.length ? " " + (i / WORDS.length) : "");
            int depth = !centred ? 0 : i == 0 ? 0 : i <= first ? 1 : 2;
            String parent = !centred || i == 0 ? null : i <= first ? "k0" : "k" + (1 + (i % first));
            nodes.add(new GraphSource.Node("k" + i, label, depth, parent));
        }
        List<GraphSource.Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < n && edges.size() < m; i++) {
            String from = nodes.get(i).parent() == null ? "k0" : nodes.get(i).parent();
            edges.add(verb(from, "k" + i, "prüfen" + edges.size() + " · " + (1 + edges.size() % 5)));
            seen.add(from + "/k" + i);
        }
        Random random = new Random(12345);
        while (edges.size() < m) {
            int a = random.nextInt(n);
            int b = random.nextInt(n);
            if (a != b && seen.add("k" + a + "/k" + b)) {
                edges.add(verb("k" + a, "k" + b, "führen" + edges.size() + " · " + (1 + edges.size() % 7)));
            }
        }
        return new GraphSource.Graph(nodes, edges, false);
    }

    private static GraphSource.Edge verb(String from, String to, String label) {
        return new GraphSource.Edge(from, to, "VERB", true, true, GraphSource.Style.VERB, label, List.of());
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    /** Führt die angestoßenen Suchen hier aus, abseits des EDT, und lässt ihre Ergebnisse auf dem EDT ankommen. */
    private static void runSearches(List<Runnable> tasks, int[] done) throws Exception {
        assertThat(SwingUtilities.isEventDispatchThread()).isFalse();
        while (done[0] < tasks.size()) {
            tasks.get(done[0]++).run();
        }
        onEdt(() -> { });
    }

    private static void paint(TermGraphView.Canvas canvas) {
        BufferedImage image = new BufferedImage(Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        canvas.paint(image.getGraphics());
    }

    /**
     * 40 Knoten, 70 Kanten seitenweit (Ring): Die Suche läuft über den Executor, nicht beim Zeichnen; sie endet am
     * Budget, und dieselbe Größe ergibt dieselbe Lage wie die synchrone Rechnung.
     */
    @Test
    void aDenseGraphIsSearchedOffTheEdtWithinItsBudgetAndPaintingNeverSearches() throws Exception {
        GraphSource.Graph graph = graph(40, 70, false);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        int[] done = {0};
        List<Boolean> busy = new CopyOnWriteArrayList<>();
        AtomicReference<TermGraphView.Canvas> ref = new AtomicReference<>();
        onEdt(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            ref.set(canvas);
            canvas.searches(tasks::add);
            canvas.budget(SMALL_BUDGET);
            canvas.onBusy(busy::add);
            canvas.setSize(980, 560);
            canvas.domain(true);
            canvas.centred(false);
            canvas.graph(graph);
            assertThat(tasks).as("angestoßen, nicht gerechnet").hasSize(1);
            assertThat(canvas.busy()).as("noch keine Lage: der Ring dreht").isTrue();
            for (int i = 0; i < 3; i++) {
                paint(canvas);
                assertThat(canvas.nodeAt(new Point(490, 280))).isNull();
                assertThat(canvas.edgeAt(new Point(490, 280))).isNull();
                canvas.getToolTipText(new java.awt.event.MouseEvent(canvas, 0, 0, 0, 490, 280, 0, false));
            }
            assertThat(canvas.searchCount()).as("Zeichnen und Treffersuche suchen nie").isZero();
            assertThat(tasks).hasSize(1);
        });

        runSearches(tasks, done);

        TermGraphView.Canvas canvas = ref.get();
        List<Rectangle2D> async = new ArrayList<>();
        onEdt(() -> {
            assertThat(canvas.searchCount()).isEqualTo(1);
            assertThat(canvas.busy()).isFalse();
            assertThat(busy).containsExactly(false, true, false);
            assertThat(canvas.lastWork()).as("das Budget ist erreicht: die Suche wurde gekürzt")
                    .isGreaterThanOrEqualTo(SMALL_BUDGET);
            // Ein Schritt (eine Ausdehnung bzw. Beschriftungsrunde) darf das Budget überschreiten, höchstens um so viel
            // wie beim echten Budget erlaubt (2 %).
            assertThat(canvas.lastWork()).as("und nur um einen Schritt überschritten")
                    .isLessThanOrEqualTo(SMALL_BUDGET + TermGraphView.Canvas.WORK_BUDGET / 50);
            assertThat(canvas.lastVariants()).as("nicht alle 180 Ausdehnungen").isBetween(1, 179);
            Map<String, Rectangle2D> boxes = canvas.layout(980, 560);
            assertThat(canvas.searchCount()).as("die fertige Lage gilt, keine neue Suche").isEqualTo(1);
            async.addAll(boxes.values());
            for (int i = 0; i < 3; i++) {
                paint(canvas);
                Rectangle2D first = boxes.get("k0");
                assertThat(canvas.nodeAt(new Point((int) first.getCenterX(), (int) first.getCenterY())).key())
                        .isEqualTo("k0");
                assertThat(canvas.segments(boxes)).hasSize(70).allSatisfy(segment ->
                        assertThat(segment.tag()).isNotNull());
            }
            assertThat(canvas.searchCount()).as("Zeichnen sucht nie").isEqualTo(1);

            // Fest: derselbe Graph auf derselben Fläche ergibt dieselbe Lage, auch synchron gerechnet.
            TermGraphView.Canvas again = new TermGraphView.Canvas();
            again.searches(tasks::add);
            again.budget(SMALL_BUDGET);
            again.setSize(980, 560);
            again.domain(true);
            again.centred(false);
            again.graph(graph);
            assertThat(new ArrayList<>(again.layout(980, 560).values())).isEqualTo(async);
            assertThat(again.lastWork()).isEqualTo(canvas.lastWork());
        });
    }

    /**
     * Mit Mitte und anderer Größe: Bis die neue Suche fertig ist, zeigt die Fläche die letzte Lage auf die neue Größe
     * gezogen, ohne zu suchen; die gebündelte Größenänderung stößt genau eine Suche an.
     */
    @Test
    void afterAResizeTheLastLayoutIsScaledUntilTheNewSearchIsDone() throws Exception {
        GraphSource.Graph graph = graph(40, 70, true);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        int[] done = {0};
        AtomicReference<TermGraphView.Canvas> ref = new AtomicReference<>();
        onEdt(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            ref.set(canvas);
            canvas.searches(tasks::add);
            canvas.setSize(1280, 720);
            canvas.domain(true);
            canvas.graph(graph);
        });
        runSearches(tasks, done);
        TermGraphView.Canvas canvas = ref.get();
        onEdt(() -> {
            assertThat(canvas.searchCount()).isEqualTo(1);
            assertThat(canvas.lastWork()).isLessThanOrEqualTo(TermGraphView.Canvas.WORK_BUDGET
                    + TermGraphView.Canvas.WORK_BUDGET / 50);
            Map<String, Rectangle2D> before = canvas.layout(1280, 720);
            canvas.setSize(900, 500);
            paint(canvas);
            // Der zuletzt gemalte Knoten liegt oben: seine Mitte, im Verhältnis der Flächen gerückt, trifft ihn.
            Rectangle2D last = before.get("k39");
            double x = Math.max(4, Math.min(900 - last.getWidth() - 8,
                    last.getCenterX() * 900 / 1280 - last.getWidth() / 2));
            double y = Math.max(4, Math.min(500 - last.getHeight() - 8,
                    last.getCenterY() * 500 / 720 - last.getHeight() / 2));
            Point scaled = new Point((int) (x + last.getWidth() / 2), (int) (y + last.getHeight() / 2));
            assertThat(canvas.nodeAt(scaled).key()).as("die alte Lage, auf die Fläche gezogen").isEqualTo("k39");
            assertThat(canvas.busy()).as("es gibt eine Lage: kein Ring").isFalse();
            assertThat(canvas.searchCount()).as("Zeichnen sucht nie").isEqualTo(1);
            canvas.sizeSettled();
            canvas.sizeSettled();
            paint(canvas);
            assertThat(canvas.searchCount()).isEqualTo(1);
        });
        runSearches(tasks, done);
        onEdt(() -> {
            assertThat(canvas.searchCount()).as("eine Suche für die neue Größe").isEqualTo(2);
            Map<String, Rectangle2D> after = canvas.layout(900, 500);
            assertThat(canvas.searchCount()).isEqualTo(2);
            for (Rectangle2D box : after.values()) {
                assertThat(new Rectangle2D.Double(0, 0, 900, 500).contains(box)).isTrue();
            }
        });
    }

    /** Eine überholte Suche verfällt: Es gilt nur das Ergebnis der zuletzt angestoßenen, auch wenn sie zuerst endet. */
    @Test
    void aStaleSearchResultIsDiscarded() throws Exception {
        GraphSource.Graph older = graph(12, 14, false);
        GraphSource.Graph newer = graph(6, 6, false);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        AtomicReference<TermGraphView.Canvas> ref = new AtomicReference<>();
        onEdt(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            ref.set(canvas);
            canvas.searches(tasks::add);
            canvas.setSize(800, 480);
            canvas.domain(true);
            canvas.centred(false);
            canvas.graph(older);
            canvas.graph(newer);
        });
        assertThat(tasks).hasSize(2);
        tasks.get(1).run();
        tasks.get(0).run();
        onEdt(() -> { });
        TermGraphView.Canvas canvas = ref.get();
        onEdt(() -> {
            assertThat(canvas.searchCount()).as("die überholte Suche beginnt gar nicht").isEqualTo(1);
            Map<String, Rectangle2D> boxes = canvas.layout(800, 480);
            assertThat(boxes).hasSize(6);
            assertThat(canvas.searchCount()).isEqualTo(1);
        });
    }

    /** Derselbe Graph, neu geholt: gleiche Werte, eine andere Instanz. */
    private static GraphSource.Graph refetched(GraphSource.Graph graph) {
        return new GraphSource.Graph(new ArrayList<>(graph.nodes()), new ArrayList<>(graph.edges()), graph.truncated(),
                graph.note(), graph.empty());
    }

    private static Point centre(Rectangle2D box) {
        return new Point((int) box.getCenterX(), (int) box.getCenterY());
    }

    /**
     * Ein neu geholter, inhaltlich gleicher Graph (jede stille Auffrischung beim Ernten): Die fertige Lage gilt weiter,
     * ohne neue Suche, ohne Ring, und ein Klick auf einen Knoten trifft ihn sofort.
     */
    @Test
    void aRefetchedEqualGraphKeepsItsLayoutWithoutSearchOrBusy() throws Exception {
        GraphSource.Graph graph = graph(12, 14, false);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        int[] done = {0};
        List<Boolean> busy = new CopyOnWriteArrayList<>();
        AtomicReference<TermGraphView.Canvas> ref = new AtomicReference<>();
        onEdt(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            ref.set(canvas);
            canvas.searches(tasks::add);
            canvas.onBusy(busy::add);
            canvas.setSize(900, 540);
            canvas.domain(true);
            canvas.centred(false);
            canvas.graph(graph);
        });
        runSearches(tasks, done);
        TermGraphView.Canvas canvas = ref.get();
        onEdt(() -> {
            Map<String, Rectangle2D> before = canvas.layout(900, 540);
            assertThat(busy).containsExactly(false, true, false);
            GraphSource.Graph again = refetched(graph);
            assertThat(again).isNotSameAs(graph).isEqualTo(graph);
            canvas.graph(again);
            assertThat(tasks).as("keine neue Suche").hasSize(1);
            assertThat(canvas.busy()).isFalse();
            assertThat(busy).as("kein Ring").containsExactly(false, true, false);
            paint(canvas);
            assertThat(canvas.nodeAt(centre(before.get("k3"))).key()).isEqualTo("k3");
            assertThat(canvas.layout(900, 540)).isEqualTo(before);
            assertThat(canvas.searchCount()).isEqualTo(1);
        });
        assertThat(tasks).hasSize(1);
    }

    /** Dieselben Begriffe unter anderen Schlüsseln: kein Knoten der alten Lage gilt mehr. */
    private static GraphSource.Graph renamed(GraphSource.Graph graph) {
        List<GraphSource.Node> nodes = new ArrayList<>();
        for (GraphSource.Node node : graph.nodes()) {
            nodes.add(new GraphSource.Node("z" + node.key(), node.label(), node.depth(),
                    node.parent() == null ? null : "z" + node.parent()));
        }
        List<GraphSource.Edge> edges = new ArrayList<>();
        for (GraphSource.Edge edge : graph.edges()) {
            edges.add(verb("z" + edge.from(), "z" + edge.to(), edge.label()));
        }
        return new GraphSource.Graph(nodes, edges, false);
    }

    /**
     * Ein inhaltlich geänderter Graph: Bis seine Suche fertig ist, bleiben die Knoten, die es noch gibt, wo sie waren,
     * und lassen sich treffen; kein Ring. Erst ein Graph ohne einen einzigen bekannten Knoten zeigt den Ring.
     */
    @Test
    void aChangedGraphKeepsShowingItsKnownNodesUntilTheNewSearchIsDone() throws Exception {
        GraphSource.Graph graph = graph(12, 14, false);
        List<GraphSource.Node> nodes = new ArrayList<>(graph.nodes().subList(0, 11));
        nodes.add(new GraphSource.Node("k99", "Widerspruch", 0, null));
        List<GraphSource.Edge> edges = new ArrayList<>();
        for (GraphSource.Edge edge : graph.edges()) {
            if (!edge.from().equals("k11") && !edge.to().equals("k11")) {
                edges.add(edge);
            }
        }
        edges.add(verb("k0", "k99", "einlegen · 1"));
        GraphSource.Graph changed = new GraphSource.Graph(nodes, edges, false);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        int[] done = {0};
        AtomicReference<TermGraphView.Canvas> ref = new AtomicReference<>();
        onEdt(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            ref.set(canvas);
            canvas.searches(tasks::add);
            canvas.setSize(900, 540);
            canvas.domain(true);
            canvas.centred(false);
            canvas.graph(graph);
        });
        runSearches(tasks, done);
        TermGraphView.Canvas canvas = ref.get();
        Map<String, Rectangle2D> before = new java.util.HashMap<>();
        onEdt(() -> {
            before.putAll(canvas.layout(900, 540));
            canvas.graph(changed);
            assertThat(tasks).as("eine Suche für den geänderten Graphen").hasSize(2);
            assertThat(canvas.busy()).as("es gibt eine Lage: kein Ring").isFalse();
            paint(canvas);
            assertThat(canvas.nodeAt(centre(before.get("k3"))).key()).isEqualTo("k3");
            GraphSource.Node gone = canvas.nodeAt(centre(before.get("k11")));
            assertThat(gone == null ? null : gone.key()).as("der entfernte Knoten ist weg").isNotEqualTo("k11");
            assertThat(canvas.searchCount()).as("Zeichnen sucht nie").isEqualTo(1);
        });
        runSearches(tasks, done);
        onEdt(() -> {
            assertThat(canvas.searchCount()).isEqualTo(2);
            assertThat(canvas.layout(900, 540)).containsKey("k99").doesNotContainKey("k11");
            assertThat(canvas.searchCount()).isEqualTo(2);
            canvas.graph(renamed(changed));
            assertThat(canvas.busy()).as("kein bekannter Knoten: der Ring dreht").isTrue();
            assertThat(canvas.nodeAt(centre(before.get("k3")))).isNull();
            assertThat(tasks).hasSize(3);
        });
        runSearches(tasks, done);
        onEdt(() -> assertThat(canvas.busy()).isFalse());
    }

    /**
     * Eine Suche, die scheitert, auch mit einem Error: Die Fläche zeigt die Grundlage ohne Suche statt weiterzudrehen,
     * und ein späterer Stand (andere Größe) wird wieder gesucht – nichts bleibt hängen.
     */
    @Test
    void aFailingSearchFallsBackToTheBaseLayoutAndNeverSpinsForever() throws Exception {
        GraphSource.Graph graph = graph(12, 14, true);
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        int[] done = {0};
        List<Boolean> busy = new CopyOnWriteArrayList<>();
        AtomicReference<TermGraphView.Canvas> ref = new AtomicReference<>();
        onEdt(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            ref.set(canvas);
            canvas.searches(tasks::add);
            canvas.searcher(geometry -> {
                throw new StackOverflowError("erfunden");
            });
            canvas.onBusy(busy::add);
            canvas.setSize(900, 540);
            canvas.domain(true);
            canvas.graph(graph);
            assertThat(canvas.busy()).isTrue();
        });
        runSearches(tasks, done);
        TermGraphView.Canvas canvas = ref.get();
        onEdt(() -> {
            assertThat(canvas.searchCount()).isEqualTo(1);
            assertThat(canvas.busy()).as("kein Ring ohne Ende").isFalse();
            assertThat(busy).containsExactly(false, true, false);
            assertThat(canvas.lastVariants()).as("die Grundlage, keine Suche").isEqualTo(1);
            paint(canvas);
            Map<String, Rectangle2D> boxes = canvas.layout(900, 540);
            assertThat(boxes).hasSize(12);
            assertThat(canvas.nodeAt(centre(boxes.get("k0"))).key()).isEqualTo("k0");
            assertThat(canvas.searchCount()).as("die Grundlage gilt für diesen Stand").isEqualTo(1);
            canvas.searcher(TermGraphView.Canvas.Geometry::search);
            canvas.setSize(800, 500);
            canvas.sizeSettled();
            assertThat(tasks).as("nichts hängt: die neue Größe wird gesucht").hasSize(2);
        });
        runSearches(tasks, done);
        onEdt(() -> {
            assertThat(canvas.searchCount()).isEqualTo(2);
            assertThat(canvas.lastVariants()).isGreaterThanOrEqualTo(1);
            assertThat(canvas.layout(800, 500)).hasSize(12);
            assertThat(canvas.searchCount()).isEqualTo(2);
        });
    }

    /** Die Bestandssicht rechnet wie bisher beim Zeichnen (billig) und stößt keine Suche an. */
    @Test
    void theOverviewNeverSearches() throws Exception {
        List<Runnable> tasks = new CopyOnWriteArrayList<>();
        onEdt(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            canvas.searches(tasks::add);
            canvas.setSize(900, 560);
            canvas.graph(graph(12, 14, true));
            paint(canvas);
            Rectangle2D centre = canvas.layout(900, 560).get("k0");
            assertThat(canvas.nodeAt(new Point((int) centre.getCenterX(), (int) centre.getCenterY())).key())
                    .isEqualTo("k0");
            assertThat(canvas.busy()).isFalse();
            assertThat(canvas.searchCount()).isZero();
        });
        assertThat(tasks).isEmpty();
    }
}
