package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.knowledge.DomainTermGraph;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.RelationScope;
import com.aresstack.spaceharvester.application.terms.RelationType;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermRelation;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.awt.Point;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der Reiter „Graph“ unter Begriffe: Nachbarschaft aus den Beziehungen, Knoten im Kreis um die Mitte. */
class TermGraphViewTest {

    private static final List<TermRelation> RELATIONS = List.of(
            relation("API", "Application Programming Interface", RelationType.ABBREVIATION, ReviewStatus.CONFIRMED),
            relation("API", "Schnittstelle", RelationType.SYNONYM, ReviewStatus.PROPOSED),
            relation("API", "Altes System", RelationType.RELATED, ReviewStatus.REJECTED),
            relation("Schnittstelle", "Interface", RelationType.VARIANT, ReviewStatus.CONFIRMED),
            relation("Application Programming Interface", "REST", RelationType.RELATED, ReviewStatus.CONFIRMED));

    /** Wie der Core: nur Beziehungen mit dem Begriff an einem Ende, ohne abgelehnte, auf Wunsch nur bestätigte. */
    private static final GraphSource SOURCE = GraphSource.ofRelations((term, proposals) -> RELATIONS.stream()
            .filter(r -> r.touches(term) && r.status() != ReviewStatus.REJECTED
                    && (proposals || r.status() == ReviewStatus.CONFIRMED))
            .toList());

    @Test
    void depthOneShowsConfirmedNeighboursAndDepthTwoTheirNeighbours() throws Exception {
        GraphSource.Graph one = SOURCE.around("api", 1, false);
        assertThat(one.nodes()).extracting(GraphSource.Node::label)
                .containsExactly("API", "Application Programming Interface");
        assertThat(one.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.type()).isEqualTo("ABBREVIATION");
            assertThat(edge.confirmed()).isTrue();
            assertThat(edge.directed()).isTrue();
        });

        GraphSource.Graph two = SOURCE.around("API", 2, true);
        assertThat(two.nodes()).extracting(GraphSource.Node::label).containsExactly("API",
                "Application Programming Interface", "Schnittstelle", "REST", "Interface");
        assertThat(two.nodes()).extracting(GraphSource.Node::depth).containsExactly(0, 1, 1, 2, 2);
        assertThat(two.edges()).hasSize(4);
        assertThat(two.edges()).filteredOn(e -> e.type().equals("SYNONYM")).singleElement()
                .satisfies(edge -> assertThat(edge.confirmed()).isFalse());
        assertThat(SOURCE.around("  ", 2, true).nodes()).isEmpty();
    }

    @Test
    void theGraphIsCappedAtMaxNodes() throws Exception {
        List<TermRelation> many = new ArrayList<>();
        for (int i = 0; i < GraphSource.MAX_NODES + 5; i++) {
            many.add(relation("Kern", "Begriff " + i, RelationType.RELATED, ReviewStatus.CONFIRMED));
        }
        GraphSource.Graph graph = GraphSource.ofRelations((term, proposals) -> many).around("Kern", 1, false);
        assertThat(graph.nodes()).hasSize(GraphSource.MAX_NODES);
        assertThat(graph.truncated()).isTrue();
    }

    @Test
    void theCanvasDrawsANodeForEachTermAndADoubleClickTargetIsFound() throws Exception {
        GraphSource.Graph graph = SOURCE.around("API", 2, true);
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null, SOURCE);
            view.show(graph);
            TermGraphView.Canvas canvas = view.canvas();
            canvas.setSize(800, 500);
            Map<String, Rectangle2D> boxes = canvas.layout(800, 500);
            seen.add(String.valueOf(boxes.size()));
            for (Rectangle2D box : boxes.values()) {
                assertThat(new Rectangle2D.Double(0, 0, 800, 500).contains(box)).isTrue();
            }
            List<Rectangle2D> list = new ArrayList<>(boxes.values());
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    assertThat(list.get(i).intersects(list.get(j))).as("Knoten %d und %d", i, j).isFalse();
                }
            }
            Rectangle2D centre = boxes.get(TermText.normalize("API"));
            seen.add(canvas.nodeAt(new Point((int) centre.getCenterX(), (int) centre.getCenterY())).label());
            seen.add(String.valueOf(canvas.nodeAt(new Point(1, 1))));
            BufferedImage image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            canvas.paint(g);
            g.dispose();
            // Die Mitte ist gelb gefüllt.
            seen.add(String.valueOf(image.getRGB((int) centre.getCenterX() - (int) centre.getWidth() / 2 + 6,
                    (int) centre.getY() + 6) == Comic.YELLOW.getRGB()));
            seen.add(view.hint());
        });
        assertThat(seen).containsExactly("5", "API", "null", "true", "5 Begriffe · 4 Beziehungen");
    }

    @Test
    void parallelRelationsShareOnePairKeyInBothDirections() {
        GraphSource.Edge there = new GraphSource.Edge("api", "rest", "SYNONYM", true, false);
        GraphSource.Edge back = new GraphSource.Edge("rest", "api", "ABBREVIATION", false, true);
        assertThat(TermGraphView.Canvas.pairKey(there)).isEqualTo(TermGraphView.Canvas.pairKey(back));
        assertThat(TermGraphView.Canvas.pairKey(there))
                .isNotEqualTo(TermGraphView.Canvas.pairKey(new GraphSource.Edge("api", "http", "RELATED", true, false)));
    }

    @Test
    void withoutRuntimeTheStartFieldIsDisabledAndHintsHowToStart() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null);
            seen.add(String.valueOf(view.startField().isEnabled()));
            seen.add(view.hint());
            view.show(new GraphSource.Graph(List.of(new GraphSource.Node("x", "X", 0, null)), List.of(), false));
            seen.add(view.hint());
        });
        assertThat(seen).containsExactly("false", " ", TermGraphView.NO_NEIGHBOURS);
    }

    /** Fachsicht wie vom Core: Antragsteller —stellt→ Antrag, Antrag ~ Gesuch (Begriffe erfunden). */
    private static final DomainTermGraph.View FACHSICHT = new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND,
            List.of(new DomainTermGraph.Node("antragsteller", "Antragsteller", DomainTermGraph.NodeKind.TERM, 0, null),
                    new DomainTermGraph.Node("antrag", "Antrag", DomainTermGraph.NodeKind.TERM, 1, "antragsteller"),
                    new DomainTermGraph.Node("gesuch", "Gesuch", DomainTermGraph.NodeKind.SYNONYM, 2, "antrag")),
            List.of(new DomainTermGraph.Edge("antragsteller", "antrag", DomainTermGraph.EdgeKind.VERB, "stellen", 3,
                            List.of(evidence("1", "Förderantrag"), evidence("2", "Leitfaden"))),
                    new DomainTermGraph.Edge("antrag", "gesuch", DomainTermGraph.EdgeKind.SYNONYM, null, 0,
                            List.of())),
            false, 2, Set.of("maltparser-de"));

    @Test
    void theDomainViewMapsTermsSynonymsAndVerbEdgesAndSaysWhereTheStatementsComeFrom() throws Exception {
        GraphSource source = GraphSource.ofDomainTerms((start, depth, checkedOnly) -> FACHSICHT, id -> "MaltParser");

        GraphSource.Graph graph = source.around("Antragsteller", 2, false);

        assertThat(graph.nodes()).extracting(GraphSource.Node::label, GraphSource.Node::synonym)
                .containsExactly(Tuple.tuple("Antragsteller", false), Tuple.tuple("Antrag", false),
                        Tuple.tuple("Gesuch", true));
        assertThat(graph.edges()).extracting(GraphSource.Edge::style, GraphSource.Edge::label,
                GraphSource.Edge::directed).containsExactly(
                Tuple.tuple(GraphSource.Style.VERB, "stellen · 3", true),
                Tuple.tuple(GraphSource.Style.SYNONYM, null, false));
        assertThat(graph.note()).isEqualTo("Aussagen aus MaltParser · 2 veraltete Aussagen ausgelassen");
        assertThat(GraphSource.ofDomainTerms((start, depth, checkedOnly) -> new DomainTermGraph.View(
                DomainTermGraph.Outcome.NOT_A_TERM, List.of(), List.of(), false, 0, Set.of()), id -> id)
                .around("Kaffee", 1, false).empty()).isEqualTo("„Kaffee“ " + GraphSource.NOT_A_TERM);
    }

    @Test
    void onlyDomainTermsSwitchesTheSourceAndAClickOnAVerbEdgeShowsItsEvidence() throws Exception {
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        GraphSource relations = (start, depth, proposals) -> {
            asked.add("relations");
            return GraphSource.Graph.EMPTY;
        };
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, checkedOnly) -> {
            asked.add("domain");
            return FACHSICHT;
        }, id -> "MaltParser");
        List<Object> seen = new ArrayList<>();
        List<EvidencePopup.Target> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null, relations, domain);
            assertThat(view.domainChoice().isSelected()).as("Standard aus").isFalse();
            view.showTerm("Antragsteller");
        });
        awaitTrue(() -> asked.contains("relations"));
        assertThat(asked).as("ohne Haken bleibt die bisherige Sicht").containsExactly("relations");
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null, relations, domain);
            view.onOpen(opened::add);
            assertThat(view.optionsButton().marked()).isFalse();
            view.domainChoice().doClick();
            assertThat(view.optionsButton().marked()).as("Punkt, sobald eine Option abweicht").isTrue();
            assertThat(view.optionsButton().getToolTipText()).isEqualTo("Optionen – aktiv: Nur Fachbegriffe");
            try {
                view.show(domain.around("Antragsteller", 2, false));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            seen.add(view.hint());
            TermGraphView.Canvas canvas = view.canvas();
            canvas.setSize(900, 560);
            TermGraphView.Canvas.Segment verb = canvas.segments(canvas.layout(900, 560)).getFirst();
            Point onLabel = new Point((int) verb.tag().getCenterX(), (int) verb.tag().getCenterY());
            seen.add(canvas.edgeAt(onLabel).style());
            view.showEvidenceFor(canvas.edgeAt(onLabel));
            EvidencePopup popup = view.evidencePopup();
            seen.add(popup.headingText());
            seen.add(popup.countValue());
            seen.add(popup.linkTexts());
            seen.add(popup.stageTexts());
            List<java.awt.geom.Rectangle2D> tags = canvas.segments(canvas.layout(900, 560)).stream()
                    .map(TermGraphView.Canvas.Segment::tag).filter(java.util.Objects::nonNull).toList();
            for (int i = 0; i < tags.size(); i++) {
                for (int j = i + 1; j < tags.size(); j++) {
                    assertThat(tags.get(i).intersects(tags.get(j))).as("Beschriftungen berühren sich").isFalse();
                }
            }
            popup.clickLink(1);
            BufferedImage image = new BufferedImage(900, 560, BufferedImage.TYPE_INT_RGB);
            canvas.paint(image.getGraphics());
        });
        assertThat(seen).containsExactly(
                "3 Fachbegriffe · 2 Verbindungen · Aussagen aus MaltParser · 2 veraltete Aussagen ausgelassen",
                GraphSource.Style.VERB, "Antragsteller stellen Antrag", 3,
                List.of("Förderantrag (wiki / FOE)", "Leitfaden (wiki / FOE)"), List.of("nur MaltParser", "nur MaltParser"));
        assertThat(opened).containsExactly(new EvidencePopup.Target("wiki", "2", -1, -1));
    }

    @Test
    void trustShowsAsLineStyleTooltipAndPillAndOnlyCheckedAsksTheCoreForCheckedEvidence() throws Exception {
        DomainTermGraph.Evidence checkedEvidence = evidence("1", "Förderantrag", DomainTermGraph.Trust.GEPRUEFT);
        DomainTermGraph.Evidence open = evidence("2", "Leitfaden", DomainTermGraph.Trust.UNGEPRUEFT);
        DomainTermGraph.Evidence other = evidence("3", "Merkblatt", DomainTermGraph.Trust.ABWEICHEND);
        DomainTermGraph.View view = new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND,
                List.of(new DomainTermGraph.Node("antragsteller", "Antragsteller", DomainTermGraph.NodeKind.TERM, 0,
                                null),
                        new DomainTermGraph.Node("antrag", "Antrag", DomainTermGraph.NodeKind.TERM, 1, "antragsteller"),
                        new DomainTermGraph.Node("frist", "Frist", DomainTermGraph.NodeKind.TERM, 1, "antragsteller")),
                List.of(new DomainTermGraph.Edge("antragsteller", "antrag", DomainTermGraph.EdgeKind.VERB, "stellen",
                                2, List.of(checkedEvidence, other)),
                        new DomainTermGraph.Edge("antragsteller", "frist", DomainTermGraph.EdgeKind.VERB, "wahren", 2,
                                List.of(open, other))),
                false, 0, Set.of("maltparser-de"));
        List<Boolean> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, checkedOnly) -> {
            asked.add(checkedOnly);
            return view;
        }, id -> "MaltParser");

        List<GraphSource.Edge> edges = domain.around("Antragsteller", 2, false).edges();
        assertThat(edges).extracting(GraphSource.Edge::label, GraphSource.Edge::trust, GraphSource.Edge::differs)
                .containsExactly(Tuple.tuple("stellen · 2", DomainTermGraph.Trust.GEPRUEFT, false),
                        Tuple.tuple("wahren ≠ · 2", DomainTermGraph.Trust.UNGEPRUEFT, true));
        assertThat(edges.getFirst().tip()).as("genaue Stufen wie die Pillen")
                .isEqualTo("Vom Prüfschritt bestätigt 1 · abweichend 1 – Klick zeigt die Belege");
        java.awt.BasicStroke strong = TermGraphView.Canvas.trustStroke(edges.getFirst());
        java.awt.BasicStroke dotted = TermGraphView.Canvas.trustStroke(edges.get(1));
        assertThat(strong.getDashArray()).as("geprüft: durchgezogen").isNull();
        assertThat(strong.getLineWidth()).isGreaterThan(3f);
        assertThat(dotted.getDashArray()).as("abweichend: gepunktet").containsExactly(0.5f, 5f);
        java.awt.BasicStroke dashed = TermGraphView.Canvas.trustStroke(new GraphSource.Edge("a", "b", "VERB", true,
                true, GraphSource.Style.VERB, "x · 1", List.of(open), DomainTermGraph.Trust.UNGEPRUEFT, false, null));
        assertThat(dashed.getDashArray()).as("ungeprüft: gestrichelt").containsExactly(7f, 5f);

        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView graph = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY,
                    domain);
            seen.add(graph.checkedChoice().isEnabled());
            seen.add(graph.checkedChoice().getToolTipText());
            graph.domainChoice().doClick();
            seen.add(graph.checkedChoice().isEnabled());
            graph.checkedChoice().doClick();
            seen.add(graph.optionsButton().getToolTipText());
            graph.showTerm("Antragsteller");
        });
        awaitTrue(() -> !asked.isEmpty() && asked.getLast());
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView graph = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY,
                    domain);
            graph.domainChoice().doClick();
            graph.checkedChoice().doClick();
            graph.show(new GraphSource.Graph(List.of(new GraphSource.Node("antragsteller", "Antragsteller", 0, null),
                    new GraphSource.Node("antrag", "Antrag", 1, "antragsteller")), List.of(edges.getFirst()), false));
            seen.add(graph.hint());
            graph.showEvidenceFor(edges.getFirst());
            seen.add(graph.evidencePopup().stageTexts());
        });
        assertThat(seen).containsExactly(false, TermGraphView.CHECKED_LOCKED, true,
                "Optionen – aktiv: Nur Fachbegriffe, Nur geprüft", "2 Fachbegriffe · 1 Verbindung · nur geprüft",
                List.of("vom Prüfschritt bestätigt", "abweichend"));
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("Bedingung nicht erfüllt");
            }
            SwingUtilities.invokeAndWait(() -> { });
            Thread.onSpinWait();
        }
    }

    private static DomainTermGraph.Evidence evidence(String pageId, String title) {
        return evidence(pageId, title, DomainTermGraph.Trust.UNGEPRUEFT);
    }

    private static DomainTermGraph.Evidence evidence(String pageId, String title, DomainTermGraph.Trust trust) {
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/" + pageId + "@x#s0.v1", 0, 4,
                10, "stellt", "stellen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(),
                Set.of(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED);
        return new DomainTermGraph.Evidence(new KnowledgeGraph.PageRef(new PageKey("wiki", pageId), "FOE", title,
                PageStatements.State.CURRENT, null), statement, "maltparser-de", "Antragsteller", "Antrag", trust);
    }

    private static TermRelation relation(String from, String to, RelationType type, ReviewStatus status) {
        String id = TermRelation.idOf(TermRelation.key(from, to, type, RelationScope.GLOBAL));
        return new TermRelation(id, from, to, type, RelationScope.GLOBAL, RelationOrigin.MANUAL, status, List.of(),
                null, null, false, "test", Instant.EPOCH, null, null, 1);
    }
}
