package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paket W9 (UX Form A): eine Kante je Aussage mit vollem Wortlaut, nebeneinander gebogen, höchstens drei Bögen je
 * Knotenpaar und Richtung („+ n weitere“), Querstrich für Verneinung, blass für früher/verworfen, Overlay je Aussage.
 * Erfundene Daten (Quelle „wiki“, Space „DEV“).
 */
class TermGraphViewStatementsTest {

    static final DomainTermGraph.Qualifiers NOT = new DomainTermGraph.Qualifiers(true,
            ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT);
    static final DomainTermGraph.Qualifiers MUST = new DomainTermGraph.Qualifiers(false,
            ParsedStatements.Modality.MUSS, ParsedStatements.Temporality.GILT);
    static final DomainTermGraph.Qualifiers EARLIER = new DomainTermGraph.Qualifiers(false,
            ParsedStatements.Modality.NONE, ParsedStatements.Temporality.FRUEHER);

    /**
     * Plan → Ziel mit zwei Aussagen („prüfen“, „nicht prüfen“), Plan → Start mit vier (zwei sichtbar und „+ 2
     * weitere“), Plan → Nachtlauf mit einer.
     */
    static DomainTermGraph.View view() {
        int[] page = {0};
        java.util.function.BiFunction<Integer, DomainTermGraph.Trust, List<DomainTermGraph.Evidence>> evidence =
                (n, trust) -> {
                    List<DomainTermGraph.Evidence> items = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        items.add(evidence(String.valueOf(++page[0]), trust));
                    }
                    return items;
                };
        return new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND, List.of(
                new DomainTermGraph.Node("plan", "Plan", DomainTermGraph.NodeKind.TERM, 0, null),
                new DomainTermGraph.Node("ziel", "Ziel", DomainTermGraph.NodeKind.TERM, 1, "plan"),
                new DomainTermGraph.Node("start", "Start", DomainTermGraph.NodeKind.TERM, 1, "plan"),
                new DomainTermGraph.Node("nachtlauf", "Nachtlauf", DomainTermGraph.NodeKind.TERM, 1, "plan")),
                List.of(edge("plan", "ziel", "prüfen", evidence.apply(3, DomainTermGraph.Trust.UNGEPRUEFT),
                                DomainTermGraph.Trust.UNGEPRUEFT, null),
                        edge("plan", "ziel", "prüfen", evidence.apply(2, DomainTermGraph.Trust.GEPRUEFT),
                                DomainTermGraph.Trust.GEPRUEFT, NOT),
                        edge("plan", "start", "prüfen", evidence.apply(1, DomainTermGraph.Trust.EINIG),
                                DomainTermGraph.Trust.EINIG, EARLIER),
                        edge("plan", "start", "prüfen", evidence.apply(4, DomainTermGraph.Trust.EINIG),
                                DomainTermGraph.Trust.EINIG, null),
                        edge("plan", "start", "prüfen", evidence.apply(2, DomainTermGraph.Trust.UNGEPRUEFT),
                                DomainTermGraph.Trust.UNGEPRUEFT, MUST),
                        edge("plan", "start", "prüfen", evidence.apply(3, DomainTermGraph.Trust.UNGEPRUEFT),
                                DomainTermGraph.Trust.UNGEPRUEFT, NOT),
                        edge("plan", "nachtlauf", "starten", evidence.apply(1, DomainTermGraph.Trust.UNGEPRUEFT),
                                DomainTermGraph.Trust.UNGEPRUEFT, null)),
                false, 0, Set.of("maltparser-de"));
    }

    @Test
    void everyStatementIsItsOwnEdgeWithTheFullWordingAndAtMostThreeArcsPerPair() {
        GraphSource.Graph graph = GraphSource.ofDomainView(view(), "Plan", id -> "MaltParser");

        assertThat(graph.edges()).extracting(GraphSource.Edge::to, GraphSource.Edge::label, GraphSource.Edge::tip)
                .containsExactly(
                        Tuple.tuple("ziel", "prüfen · 3", "Plan – prüfen – Ziel · 3 Belege"),
                        Tuple.tuple("ziel", "nicht prüfen · 2", "Plan – nicht prüfen – Ziel · 2 Belege"),
                        Tuple.tuple("start", "prüfen · 4", "Plan – prüfen – Start · 4 Belege"),
                        Tuple.tuple("start", "nicht prüfen · 3", "Plan – nicht prüfen – Start · 3 Belege"),
                        Tuple.tuple("start", "+ 2 weitere", "Plan – 2 weitere Aussagen – Start · 3 Belege"),
                        Tuple.tuple("nachtlauf", "starten · 1", "Plan – starten – Nachtlauf · 1 Beleg"));
        GraphSource.Edge more = graph.edges().get(4);
        assertThat(more.bundle()).isTrue();
        assertThat(more.hidden()).extracting(GraphSource.Edge::label)
                .as("die zwei schwächsten Aussagen, die stärkere zuerst")
                .containsExactly("muss prüfen · 2", "prüfen (früher) · 1");
        assertThat(more.evidence()).hasSize(3);
        assertThat(graph.edges().get(1).qualifiers().negated()).isTrue();
        assertThat(more.hidden().get(1).qualifiers().past()).isTrue();
        assertThat(TermGraphView.connections(graph)).as("jede Aussage zählt als Verbindung").isEqualTo(7);
    }

    @Test
    void theOverlayShowsOneHeadingPerStatementWithTheClickedOneOnTopAndExpanded() throws Exception {
        GraphSource.Graph graph = GraphSource.ofDomainView(view(), "Plan", id -> "MaltParser");
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY,
                    GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> view(), id -> "MaltParser"));
            view.domainChoice().doClick();
            view.show(graph);
            EvidencePopup popup = view.evidencePopup();
            view.showEvidenceFor(graph.edges().get(1));
            seen.add(popup.headingText());
            seen.add(popup.countValue());
            seen.add(popup.statementHeadings());
            seen.add(popup.linkTexts().size());
            popup.clickStatement(1);
            seen.add(popup.statementHeadings());
            view.showEvidenceFor(graph.edges().get(4));
            seen.add(popup.statementHeadings());
            view.showEvidenceFor(graph.edges().get(5));
            seen.add(popup.headingText());
            seen.add(popup.statementHeadings());
        });
        assertThat(seen).containsExactly(
                "Plan – Ziel", 5,
                List.of("▾ nicht prüfen", "▸ prüfen"), 5,
                List.of("▾ nicht prüfen", "▾ prüfen"),
                List.of("▾ muss prüfen", "▸ prüfen (früher)", "▸ prüfen", "▸ nicht prüfen"),
                "Plan – starten – Nachtlauf", List.of());
    }

    @Test
    void severalStatementsBendSideBySideEachLabelOnItsOwnArcAndNoneOverlaps() throws Exception {
        GraphSource.Graph graph = GraphSource.ofDomainView(view(), "Plan", id -> "MaltParser");
        for (int width : new int[] {900, 960}) {
            List<TermGraphView.Canvas.Segment> segments = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                TermGraphView view = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY,
                        GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> view(), id -> id));
                view.domainChoice().doClick();
                view.show(graph);
                TermGraphView.Canvas canvas = view.canvas();
                canvas.setSize(width, 560);
                segments.addAll(canvas.segments(canvas.layout(width, 560)));
                BufferedImage image = new BufferedImage(width, 560, BufferedImage.TYPE_INT_ARGB);
                canvas.paint(image.getGraphics());
            });
            assertThat(segments).hasSize(6);
            List<TermGraphView.Canvas.Segment> ziel = segments.stream()
                    .filter(segment -> segment.edge().to().equals("ziel")).toList();
            assertThat(ziel).as("zwei Aussagen: beide gebogen, auf verschiedenen Seiten").hasSize(2)
                    .allSatisfy(segment -> assertThat(segment.control()).isNotNull());
            assertThat(side(ziel.get(0))).isNotEqualTo(side(ziel.get(1)));
            List<TermGraphView.Canvas.Segment> start = segments.stream()
                    .filter(segment -> segment.edge().to().equals("start")).toList();
            assertThat(start).as("höchstens drei Bögen je Paar und Richtung").hasSize(3);
            for (int i = 0; i < segments.size(); i++) {
                Rectangle2D tag = segments.get(i).tag();
                assertThat(tag).isNotNull();
                assertThat(onOwnArc(segments.get(i))).as("Beschriftung auf ihrem Bogen: %s",
                        segments.get(i).edge().label()).isTrue();
                for (int j = i + 1; j < segments.size(); j++) {
                    assertThat(tag.intersects(segments.get(j).tag())).as("%s berührt %s bei %d px",
                            segments.get(i).edge().label(), segments.get(j).edge().label(), width).isFalse();
                }
            }
            TermGraphView.Canvas.Segment negated = ziel.stream().filter(segment -> segment.edge().qualifiers()
                    .negated()).findFirst().orElseThrow();
            double t = TermGraphView.Canvas.negationAt(negated, negated.tag());
            Point2D cross = negated.at(t);
            Rectangle2D tag = negated.tag();
            Rectangle2D kept = new Rectangle2D.Double(tag.getX() - TermGraphView.Canvas.NEGATION_GAP + 0.5,
                    tag.getY() - TermGraphView.Canvas.NEGATION_GAP + 0.5,
                    tag.getWidth() + 2 * TermGraphView.Canvas.NEGATION_GAP - 1,
                    tag.getHeight() + 2 * TermGraphView.Canvas.NEGATION_GAP - 1);
            double half = TermGraphView.Canvas.NEGATION_LENGTH / 2;
            for (double angle = 0; angle < 2 * Math.PI; angle += Math.PI / 12) {
                assertThat(kept.contains(cross.getX() + Math.cos(angle) * half, cross.getY() + Math.sin(angle) * half))
                        .as("Querstrich hält 8 px Abstand zur Beschriftung").isFalse();
            }
            assertThat(cross.distance(tag.getCenterX(), tag.getCenterY()))
                    .as("kurz vor der Beschriftung").isLessThan(Math.hypot(tag.getWidth() / 2, tag.getHeight() / 2)
                            + TermGraphView.Canvas.NEGATION_GAP + TermGraphView.Canvas.NEGATION_LENGTH + 4);
            assertThat(t).as("in Richtung der Kante vor der Beschriftung").isLessThan(tagPosition(negated));
        }
        assertThat(TermGraphView.Canvas.PALE.getAlpha()).as("blasser, nicht farbig").isLessThan(255);
        assertThat(TermGraphView.Canvas.PALE.getRGB() & 0xFFFFFF).isEqualTo(Comic.INK.getRGB() & 0xFFFFFF);
    }

    /** Auf welcher Seite der Verbindung der Knotenmitten der Scheitel liegt. */
    private static double side(TermGraphView.Canvas.Segment segment) {
        Point2D a = segment.a();
        Point2D b = segment.b();
        Point2D apex = segment.at(0.5);
        return Math.signum((b.getX() - a.getX()) * (apex.getY() - a.getY())
                - (b.getY() - a.getY()) * (apex.getX() - a.getX()));
    }

    private static boolean onOwnArc(TermGraphView.Canvas.Segment segment) {
        Rectangle2D tag = segment.tag();
        for (int i = 0; i <= 100; i++) {
            Point2D point = segment.at(i / 100.0);
            if (point.distance(tag.getCenterX(), tag.getCenterY()) < 2.5) {
                return true;
            }
        }
        return false;
    }

    private static double tagPosition(TermGraphView.Canvas.Segment segment) {
        Rectangle2D tag = segment.tag();
        double best = 0.5;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i <= 200; i++) {
            Point2D point = segment.at(i / 200.0);
            double d = point.distance(tag.getCenterX(), tag.getCenterY());
            if (d < distance) {
                distance = d;
                best = i / 200.0;
            }
        }
        return best;
    }

    static DomainTermGraph.Edge edge(String from, String to, String verb, List<DomainTermGraph.Evidence> evidence,
                                     DomainTermGraph.Trust trust, DomainTermGraph.Qualifiers qualifiers) {
        return new DomainTermGraph.Edge(from, to, DomainTermGraph.EdgeKind.VERB, verb, evidence.size(), evidence,
                trust, false, qualifiers);
    }

    static DomainTermGraph.Evidence evidence(String pageId, DomainTermGraph.Trust trust) {
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/" + pageId + "@x#s0.v1", 0, 4,
                10, "prüft", "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(),
                Set.of(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), trust == DomainTermGraph.Trust.GEPRUEFT
                ? ParsedStatements.Review.CONFIRMED : ParsedStatements.Review.UNREVIEWED);
        return new DomainTermGraph.Evidence(new KnowledgeGraph.PageRef(new PageKey("wiki", pageId), "DEV",
                "Nachtlauf " + pageId, PageStatements.State.CURRENT, null), statement, "maltparser-de", "Plan", "Ziel",
                trust);
    }
}
