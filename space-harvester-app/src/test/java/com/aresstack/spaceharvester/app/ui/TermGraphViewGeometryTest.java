package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Freie Beschriftungen in der Fachsicht (UX W4b, dieselbe Regel wie W1): Keine Beschriftung berührt einen Knoten
 * oder eine andere Beschriftung, keine fremde Kante läuft durch eine Beschriftung, und keine Kante läuft unter einem
 * Knoten durch, der nicht ihr eigenes Ende ist. Der Graph ist dem Bildschirmfoto nachgebaut: Mitte Antrag, Nachbarn
 * Sachbearbeiter, Nachweis, Beleg, dahinter Bescheid (über Sachbearbeiter) und Prüfstelle (über Beleg), dazu
 * Prüfstelle → Bescheid „erlassen“. Dazu dichtere Graphen aus der Prüfung (fünf bis sieben Nachbarn mit kurzen
 * Speichen, seitenweit zwölf Knoten auf zwei Ringen), bei denen ein einzelner gieriger Durchlauf nicht reicht.
 */
class TermGraphViewGeometryTest {

    private static final int[][] SIZES = {{660, 560}, {980, 560}, {800, 480}, {720, 600}, {1100, 640}};

    private static GraphSource.Graph screenshot(boolean centred) {
        List<GraphSource.Node> nodes = List.of(
                new GraphSource.Node("antrag", "Antrag", 0, null),
                new GraphSource.Node("sachbearbeiter", "Sachbearbeiter", centred ? 1 : 0, centred ? "antrag" : null),
                new GraphSource.Node("nachweis", "Nachweis", centred ? 1 : 0, centred ? "antrag" : null),
                new GraphSource.Node("beleg", "Beleg", centred ? 1 : 0, centred ? "antrag" : null),
                new GraphSource.Node("bescheid", "Bescheid", centred ? 2 : 0, centred ? "sachbearbeiter" : null),
                new GraphSource.Node("pruefstelle", "Prüfstelle", centred ? 2 : 0, centred ? "beleg" : null));
        List<GraphSource.Edge> edges = List.of(
                verb("sachbearbeiter", "antrag", "prüfen · 4"),
                verb("antrag", "nachweis", "enthalten · 3"),
                verb("beleg", "antrag", "begründen · 2"),
                verb("sachbearbeiter", "bescheid", "erstellen · 2"),
                verb("pruefstelle", "beleg", "anfordern · 2"),
                verb("pruefstelle", "bescheid", "erlassen"));
        return new GraphSource.Graph(nodes, edges, false);
    }

    /**
     * Ein dichterer Graph (Befund der Prüfung): Mitte Antrag mit {@code neighbours} Nachbarn (5 bis 7), dahinter
     * Bescheid, Nachtlauf und Gebühr, dazu Querkanten zwischen Nachbarn und zwischen Kindern.
     */
    private static GraphSource.Graph dense(int neighbours) {
        String[][] ring = {{"sachbearbeiter", "Sachbearbeiter", "prüfen · 4", "in"},
                {"nachweis", "Nachweis", "enthalten · 3", "out"}, {"beleg", "Beleg", "begründen · 2", "in"},
                {"plan", "Plan", "festlegen", "out"}, {"ziel", "Ziel", "nennen · 2", "in"},
                {"start", "Start", "auslösen", "out"}, {"akte", "Akte", "führen · 3", "in"}};
        List<GraphSource.Node> nodes = new ArrayList<>();
        List<GraphSource.Edge> edges = new ArrayList<>();
        nodes.add(new GraphSource.Node("antrag", "Antrag", 0, null));
        for (int i = 0; i < neighbours; i++) {
            String[] n = ring[i];
            nodes.add(new GraphSource.Node(n[0], n[1], 1, "antrag"));
            edges.add("in".equals(n[3]) ? verb(n[0], "antrag", n[2]) : verb("antrag", n[0], n[2]));
        }
        nodes.add(new GraphSource.Node("bescheid", "Bescheid", 2, "sachbearbeiter"));
        nodes.add(new GraphSource.Node("nachtlauf", "Nachtlauf", 2, "nachweis"));
        nodes.add(new GraphSource.Node("gebuehr", "Gebühr", 2, "plan"));
        edges.add(verb("sachbearbeiter", "bescheid", "erstellen · 2"));
        edges.add(verb("nachweis", "nachtlauf", "auslösen"));
        edges.add(verb("plan", "gebuehr", "berechnen"));
        edges.add(verb("bescheid", "nachtlauf", "erlassen"));
        edges.add(verb("nachweis", "beleg", "anfordern · 2"));
        return new GraphSource.Graph(nodes, edges, false);
    }

    /**
     * Der seitenweite Ring des Befunds: zwölf gleichrangige Knoten (Antrag, sieben Nachbarn, vier Kinder) und elf
     * Kanten.
     */
    private static GraphSource.Graph pageWide() {
        String[][] names = {{"antrag", "Antrag"}, {"sachbearbeiter", "Sachbearbeiter"}, {"nachweis", "Nachweis"},
                {"beleg", "Beleg"}, {"plan", "Plan"}, {"ziel", "Ziel"}, {"start", "Start"}, {"akte", "Akte"},
                {"bescheid", "Bescheid"}, {"pruefstelle", "Prüfstelle"}, {"gebuehr", "Gebühr"},
                {"vorgang", "Vorgang"}};
        List<GraphSource.Node> nodes = new ArrayList<>();
        for (String[] name : names) {
            nodes.add(new GraphSource.Node(name[0], name[1], 0, null));
        }
        List<GraphSource.Edge> edges = List.of(
                verb("sachbearbeiter", "antrag", "prüfen · 4"),
                verb("antrag", "nachweis", "enthalten · 3"),
                verb("beleg", "antrag", "begründen · 2"),
                verb("antrag", "plan", "festlegen"),
                verb("ziel", "antrag", "nennen · 2"),
                verb("antrag", "start", "auslösen"),
                verb("akte", "antrag", "führen · 3"),
                verb("sachbearbeiter", "bescheid", "erstellen · 2"),
                verb("pruefstelle", "beleg", "anfordern · 2"),
                verb("ziel", "gebuehr", "berechnen"),
                verb("akte", "vorgang", "ablegen"));
        return new GraphSource.Graph(nodes, edges, false);
    }

    private static GraphSource.Edge verb(String from, String to, String label) {
        return new GraphSource.Edge(from, to, "VERB", true, true, GraphSource.Style.VERB, label, List.of());
    }

    /** Die Beschriftung mit dem sichtbaren Abstand, den sie zu einem Knoten samt Schatten hält. */
    private static Rectangle2D padded(Rectangle2D tag) {
        return new Rectangle2D.Double(tag.getX() - 2, tag.getY() - 2, tag.getWidth() + 4, tag.getHeight() + 4);
    }

    /** Alle Verstöße gegen die Regel bei dieser Größe, lesbar benannt. */
    private static List<String> violations(TermGraphView.Canvas canvas, int width, int height) {
        canvas.setSize(width, height);
        Map<String, Rectangle2D> boxes = canvas.layout(width, height);
        List<TermGraphView.Canvas.Segment> segments = canvas.segments(boxes);
        List<String> found = new ArrayList<>();
        Rectangle2D area = new Rectangle2D.Double(0, 0, width, height);
        Rectangle2D inner = new Rectangle2D.Double(8, 8, width - 16, height - 16);
        for (Map.Entry<String, Rectangle2D> box : boxes.entrySet()) {
            if (!area.contains(box.getValue())) {
                found.add("Knoten " + box.getKey() + " ragt aus der Fläche");
            } else if (!inner.contains(TermGraphView.Canvas.drawn(box.getValue()))) {
                found.add("Knoten " + box.getKey() + " klebt am Rand");
            }
            for (Map.Entry<String, Rectangle2D> other : boxes.entrySet()) {
                if (box.getKey().compareTo(other.getKey()) < 0 && box.getValue().intersects(other.getValue())) {
                    found.add("Knoten " + box.getKey() + " überdeckt " + other.getKey());
                }
            }
        }
        for (int i = 0; i < segments.size(); i++) {
            TermGraphView.Canvas.Segment segment = segments.get(i);
            String name = segment.edge().from() + "→" + segment.edge().to();
            Rectangle2D tag = segment.tag();
            assertThat(tag).as("Beschriftung von " + name).isNotNull();
            if (!area.contains(tag)) {
                found.add("Beschriftung " + name + " ragt aus der Fläche");
            }
            for (Map.Entry<String, Rectangle2D> box : boxes.entrySet()) {
                if (tag.intersects(box.getValue())) {
                    found.add("Beschriftung " + name + " berührt Knoten " + box.getKey());
                } else if (padded(tag).intersects(TermGraphView.Canvas.drawn(box.getValue()))) {
                    found.add("Beschriftung " + name + " klebt am Knoten " + box.getKey() + " (samt Schatten)");
                }
                boolean own = box.getKey().equals(segment.edge().from()) || box.getKey().equals(segment.edge().to());
                if (!own && segment.crosses(box.getValue())) {
                    found.add("Kante " + name + " läuft unter Knoten " + box.getKey());
                }
            }
            for (int j = 0; j < segments.size(); j++) {
                TermGraphView.Canvas.Segment other = segments.get(j);
                if (j == i) {
                    continue;
                }
                String otherName = other.edge().from() + "→" + other.edge().to();
                if (i < j && tag.intersects(other.tag())) {
                    found.add("Beschriftung " + name + " berührt Beschriftung " + otherName);
                }
                if (other.crosses(tag)) {
                    found.add("Kante " + otherName + " läuft durch Beschriftung " + name);
                }
            }
        }
        return found;
    }

    private static void assertFree(boolean centred) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            canvas.domain(true);
            canvas.centred(centred);
            canvas.graph(screenshot(centred));
            List<String> found = new ArrayList<>();
            for (int[] size : SIZES) {
                for (String violation : violations(canvas, size[0], size[1])) {
                    found.add(size[0] + " × " + size[1] + ": " + violation);
                }
                BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
                canvas.paint(image.getGraphics());
            }
            assertThat(found).as(centred ? "mit Mitte" : "seitenweit").isEmpty();
        });
    }

    @Test
    void inTheCentredDomainViewLabelsAndEdgesStandFree() throws Exception {
        assertFree(true);
    }

    @Test
    void inThePageWideRingLayoutLabelsAndEdgesStandFree() throws Exception {
        assertFree(false);
    }

    /** Dieselbe Regel über viele Flächengrößen, mit Mitte und seitenweit. */
    @Test
    void acrossManyCanvasSizesNothingTouches() throws Exception {
        for (boolean centred : new boolean[] {true, false}) {
            SwingUtilities.invokeAndWait(() -> {
                TermGraphView.Canvas canvas = new TermGraphView.Canvas();
                canvas.domain(true);
                canvas.centred(centred);
                canvas.graph(screenshot(centred));
                List<String> found = new ArrayList<>();
                for (int w = 560; w <= 1300; w += 30) {
                    for (int h = 400; h <= 760; h += 40) {
                        for (String violation : violations(canvas, w, h)) {
                            found.add(w + " × " + h + ": " + violation);
                        }
                    }
                }
                assertThat(found).as(centred ? "mit Mitte" : "seitenweit").isEmpty();
            });
        }
    }

    private static void assertFree(String name, GraphSource.Graph graph, boolean centred, int[][] sizes)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            canvas.domain(true);
            canvas.centred(centred);
            canvas.graph(graph);
            List<String> found = new ArrayList<>();
            for (int[] size : sizes) {
                for (String violation : violations(canvas, size[0], size[1])) {
                    found.add(size[0] + " × " + size[1] + ": " + violation);
                }
            }
            assertThat(found).as(name).isEmpty();
        });
    }

    private static final int[][] DENSE_SIZES = {{660, 560}, {980, 560}, {800, 480}, {560, 400}, {720, 600},
            {1100, 640}, {1280, 720}};

    /** Mit Mitte und fünf bis sieben Nachbarn: auch kurze Speichen tragen ihre Beschriftung frei. */
    @Test
    void denseCentredGraphsStandFree() throws Exception {
        for (int neighbours = 5; neighbours <= 7; neighbours++) {
            assertFree(neighbours + " Nachbarn", dense(neighbours), true, DENSE_SIZES);
        }
    }

    /** Seitenweit mit zwölf Knoten auf zwei Ringen: keine Kante läuft durch eine Beschriftung. */
    @Test
    void densePageWideRingStandsFree() throws Exception {
        assertFree("seitenweit, zwölf Knoten", pageWide(), false, DENSE_SIZES);
    }

    /** Die dichten Graphen über viele Flächengrößen: Die Ringe rücken auseinander, bis nichts mehr berührt. */
    @Test
    void denseGraphsAcrossManyCanvasSizes() throws Exception {
        List<int[]> sizes = new ArrayList<>();
        for (int w = 560; w <= 1300; w += 30) {
            for (int h = 400; h <= 760; h += 40) {
                sizes.add(new int[] {w, h});
            }
        }
        int[][] all = sizes.toArray(new int[0][]);
        for (int neighbours = 5; neighbours <= 7; neighbours++) {
            assertFree(neighbours + " Nachbarn", dense(neighbours), true, all);
        }
        assertFree("seitenweit, zwölf Knoten", pageWide(), false, all);
    }

    /** Das Verschieben ist fest: dieselbe Größe ergibt dieselbe Lage (Klicks treffen, was gezeichnet ist). */
    @Test
    void theLayoutIsStableForTheSameSize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            canvas.domain(true);
            canvas.graph(screenshot(true));
            canvas.setSize(660, 560);
            List<Rectangle2D> first = canvas.segments(canvas.layout(660, 560)).stream()
                    .map(TermGraphView.Canvas.Segment::tag).toList();
            List<Rectangle2D> second = canvas.segments(canvas.layout(660, 560)).stream()
                    .map(TermGraphView.Canvas.Segment::tag).toList();
            assertThat(second).isEqualTo(first);
        });
    }
}
