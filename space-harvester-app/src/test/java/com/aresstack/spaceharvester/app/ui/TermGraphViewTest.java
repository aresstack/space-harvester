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
        GraphSource source = GraphSource.ofDomainTerms((start, depth, cancelled) -> FACHSICHT, id -> "MaltParser");

        GraphSource.Graph graph = source.around("Antragsteller", 2, false);

        assertThat(graph.nodes()).extracting(GraphSource.Node::label, GraphSource.Node::synonym)
                .containsExactly(Tuple.tuple("Antragsteller", false), Tuple.tuple("Antrag", false),
                        Tuple.tuple("Gesuch", true));
        assertThat(graph.edges()).extracting(GraphSource.Edge::style, GraphSource.Edge::label,
                GraphSource.Edge::directed).containsExactly(
                Tuple.tuple(GraphSource.Style.VERB, "stellen · 3", true),
                Tuple.tuple(GraphSource.Style.SYNONYM, null, false));
        assertThat(graph.note()).isEqualTo("Aussagen aus MaltParser · 2 veraltete Aussagen ausgelassen");
        assertThat(GraphSource.ofDomainTerms((start, depth, cancelled) -> new DomainTermGraph.View(
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
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, cancelled) -> {
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
                List.of("Förderantrag (wiki / FOE)", "Leitfaden (wiki / FOE)"));
        assertThat(opened).containsExactly(new EvidencePopup.Target("wiki", "2", -1, -1));
    }

    @Test
    void theInventoryViewStaysAsItWasAndTheStatusAlwaysNamesTheAnalyzer() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY,
                    GraphSource.ofDomainTerms((start, depth, cancelled) -> FACHSICHT, id -> "MaltParser"));
            seen.add(java.util.Arrays.asList(view.tools().getComponents()).contains(view.proposalsChoice()));
            seen.add(view.proposalsChoice().isEnabled());
            view.domainChoice().doClick();
            seen.add(view.proposalsChoice().isEnabled());
            seen.add(view.proposalsChoice().getToolTipText());
            view.domainChoice().doClick();
            // Bestandssicht: gerade Linien, Beschriftung in der Mitte, Ring nicht gedreht.
            view.show(new GraphSource.Graph(List.of(new GraphSource.Node("a", "A", 0, null),
                    new GraphSource.Node("b", "B", 1, "a"), new GraphSource.Node("c", "C", 1, "a"),
                    new GraphSource.Node("d", "D", 1, "a"), new GraphSource.Node("e", "E", 1, "a")),
                    List.of(new GraphSource.Edge("b", "d", "ABBREVIATION", true, true),
                            new GraphSource.Edge("a", "b", "ABBREVIATION", true, true)), false));
            TermGraphView.Canvas canvas = view.canvas();
            canvas.setSize(900, 560);
            Map<String, java.awt.geom.Rectangle2D> boxes = canvas.layout(900, 560);
            seen.add(boxes.get("b").getCenterX() == boxes.get("a").getCenterX());
            for (TermGraphView.Canvas.Segment segment : canvas.segments(boxes)) {
                seen.add(segment.control() == null);
                seen.add(Math.abs(segment.tag().getCenterX() - (segment.a().getX() + segment.b().getX()) / 2) < 0.5);
            }
        });
        assertThat(seen).containsExactly(true, true, false, TermGraphView.PROPOSALS_LOCKED,
                true, true, true, true, true);

        GraphSource.Graph empty = GraphSource.ofDomainTerms((start, depth, cancelled) -> new DomainTermGraph.View(
                DomainTermGraph.Outcome.FOUND, List.of(new DomainTermGraph.Node("antrag", "Antrag",
                DomainTermGraph.NodeKind.TERM, 0, null)), List.of(), false, 0, Set.of(),
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER),
                com.aresstack.spaceharvester.app.ui.content.StatementProvenance::analyzer).around("Antrag", 2, false);
        assertThat(empty.note()).as("ohne Kante und ohne Seite trotzdem die Ablage").isEqualTo("Aussagen aus MaltParser");
    }

    @Test
    void aCoreChangeReloadsQuietlyAndWaitsWhileTheEvidenceIsOpen() throws Exception {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, cancelled) -> {
            asked.incrementAndGet();
            return FACHSICHT;
        }, id -> "MaltParser");
        TermGraphView[] holder = new TermGraphView[1];
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY, domain);
            holder[0] = view;
            view.domainChoice().doClick();
            view.showTerm("Antragsteller");
        });
        awaitTrue(() -> asked.get() == 1 && !holder[0].hint().startsWith("Lädt"));
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = holder[0];
            view.startField().setText("Antr");
            view.coreChanged(false);
            seen.add(view.startField().getText());
            seen.add(view.hint().startsWith("Lädt"));
        });
        awaitTrue(() -> asked.get() == 2);
        SwingUtilities.invokeAndWait(() -> holder[0].coreChanged(true));
        SwingUtilities.invokeAndWait(() -> { });
        seen.add(asked.get());
        SwingUtilities.invokeAndWait(() -> holder[0].userDone());
        awaitTrue(() -> asked.get() == 3);
        assertThat(seen).as("Startfeld bleibt, kein „Lädt …“; während der Nutzer liest, erst danach neu rechnen")
                .containsExactly("Antr", false, 2);
    }

    @Test
    void aQuietResultWaitsWhileTheUserOpenedTheEvidenceMeanwhile() throws Exception {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        DomainTermGraph.View alone = new DomainTermGraph.View(DomainTermGraph.Outcome.FOUND,
                List.of(new DomainTermGraph.Node("antragsteller", "Antragsteller", DomainTermGraph.NodeKind.TERM, 0,
                        null)), List.of(), false, 0, Set.of(),
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER);
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, cancelled) -> {
            if (asked.incrementAndGet() == 2) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return asked.get() == 1 ? FACHSICHT : alone;
        }, id -> "MaltParser");
        TermGraphView[] holder = new TermGraphView[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY, domain);
            holder[0].domainChoice().doClick();
            holder[0].showTerm("Antragsteller");
        });
        awaitTrue(() -> asked.get() == 1 && !holder[0].hint().startsWith("Lädt"));
        String before = holder[0].hint();
        SwingUtilities.invokeAndWait(() -> holder[0].coreChangedWhileShowing());
        awaitTrue(() -> asked.get() == 2);
        SwingUtilities.invokeAndWait(() -> holder[0].userBusy(true));
        release.countDown();
        awaitTrue(() -> holder[0].coreDirty());
        assertThat(holder[0].hint()).as("Ergebnis zurückgehalten, das Overlay bleibt").isEqualTo(before);

        SwingUtilities.invokeAndWait(() -> holder[0].userBusy(false));
        awaitTrue(() -> asked.get() == 3 && !holder[0].hint().equals(before));
        assertThat(holder[0].coreDirty()).isFalse();
    }

    @Test
    void anOvertakenEvidenceAnswerKeepsTheReloadWaitingForTheLastClick() throws Exception {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, cancelled) -> {
            asked.incrementAndGet();
            return FACHSICHT;
        }, id -> "MaltParser");
        java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch second = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger read = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean firstDone = new java.util.concurrent.atomic.AtomicBoolean();
        TermGraphView[] holder = new TermGraphView[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY, domain);
            holder[0].sentenceReader(byPage -> {
                try {
                    if (read.incrementAndGet() == 1) {
                        first.await();
                        firstDone.set(true);
                    } else {
                        second.await();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Map.of();
            });
            holder[0].domainChoice().doClick();
            holder[0].showTerm("Antragsteller");
        });
        awaitTrue(() -> asked.get() == 1 && !holder[0].hint().startsWith("Lädt"));
        GraphSource.Edge verb = domain.around("Antragsteller", 2, false).edges().stream()
                .filter(edge -> edge.style() == GraphSource.Style.VERB).findFirst().orElseThrow();
        asked.set(1);
        SwingUtilities.invokeAndWait(() -> {
            holder[0].showEvidenceFor(verb);
            holder[0].showEvidenceFor(verb);
        });
        first.countDown();
        awaitTrue(firstDone::get);
        for (int i = 0; i < 20; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(10);
        }
        SwingUtilities.invokeAndWait(() -> holder[0].coreChangedWhileShowing());
        assertThat(asked).as("die überholte Antwort beendet das Lesen nicht").hasValue(1);
        assertThat(holder[0].coreDirty()).isTrue();

        second.countDown();
        awaitTrue(() -> asked.get() == 2);
    }

    @Test
    void theOverlayNeverMakesUpASentence() {
        com.aresstack.spaceharvester.application.standing.SentenceText found =
                new com.aresstack.spaceharvester.application.standing.SentenceText("Der Antragsteller stellt den Antrag.",
                        null, 0, 10, 4, 10, false);
        com.aresstack.spaceharvester.application.standing.SentenceText changed =
                new com.aresstack.spaceharvester.application.standing.SentenceText(null, null, 0, 10, 4, 10, true);
        assertThat(EvidencePopup.sentenceText(found, false)).isEqualTo("Der Antragsteller stellt den Antrag.");
        assertThat(EvidencePopup.sentenceText(changed, false)).isEqualTo(EvidencePopup.NO_SENTENCE);
        assertThat(EvidencePopup.sentenceText(null, false)).as("ohne Recht").isEqualTo(EvidencePopup.NO_ACCESS);
        assertThat(EvidencePopup.sentenceText(found, true)).as("Fehler").isEqualTo(EvidencePopup.UNREADABLE);
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
        ParsedStatements.Statement statement = new ParsedStatements.Statement("wiki/" + pageId + "@x#s0.v1", 0, 4,
                10, "stellt", "stellen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true, List.of(),
                Set.of(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED);
        return new DomainTermGraph.Evidence(new KnowledgeGraph.PageRef(new PageKey("wiki", pageId), "FOE", title,
                PageStatements.State.CURRENT, null), statement, "maltparser-de", "Antragsteller", "Antrag");
    }

    private static TermRelation relation(String from, String to, RelationType type, ReviewStatus status) {
        String id = TermRelation.idOf(TermRelation.key(from, to, type, RelationScope.GLOBAL));
        return new TermRelation(id, from, to, type, RelationScope.GLOBAL, RelationOrigin.MANUAL, status, List.of(),
                null, null, false, "test", Instant.EPOCH, null, null, 1);
    }
}
