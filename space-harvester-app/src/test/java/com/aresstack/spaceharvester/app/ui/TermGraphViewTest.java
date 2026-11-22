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
    void aDecisionThatFailsAfterTheOverlayClosedShowsRedWithRetryInTheStatusLine() throws Exception {
        EvidencePopupTest.Core core = new EvidencePopupTest.Core();
        PageKey plan = new PageKey("wiki", "1");
        List<Runnable> later = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null);
            EvidencePopup evidence = view.evidence().reviewing(core, new EvidencePopup.Runner() {
                @Override
                public <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onSuccess,
                                    java.util.function.Consumer<Throwable> onFailure) {
                    later.add(() -> {
                        T result;
                        try {
                            result = work.call();
                        } catch (Exception e) {
                            onFailure.accept(e);
                            return;
                        }
                        onSuccess.accept(result);
                    });
                }
            });
            seen.add(view.reviewNote());
            evidence.fill("Plan startet Nachtlauf", 1, List.of(EvidencePopupTest.evidence(plan, "Plan", false)),
                    EvidencePopupTest.sentences(), false);
            later.removeFirst().run();
            evidence.toggle(0).clickConfirm();
            // Das Overlay ist zu (hier nie sichtbar), als das Speichern scheitert.
            core.refuse = new IllegalStateException("kaputt");
            later.removeFirst().run();
            com.aresstack.spaceharvester.app.ui.controls.StatusNote note = view.reviewNote();
            seen.add(note.text());
            seen.add(note.tone());
            seen.add(note.icon().glyph());
            // Kreispfeil: die Meldung geht, dasselbe Schreiben läuft erneut und gelingt.
            core.refuse = null;
            note.icon().action().run();
            seen.add(view.reviewNote());
            later.removeFirst().run();
            seen.add(view.reviewNote());
        });
        assertThat(seen).containsExactly(null, "Entscheidung zu „Der Plan beschreibt den Start des Nachtlaufs und …“"
                + " nicht gespeichert", com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.ERROR,
                Glyph.REFRESH, null, null);
        assertThat(core.calls).containsExactly("reviews 1", "review " + EvidencePopupTest.id(plan) + " CONFIRMED",
                "review " + EvidencePopupTest.id(plan) + " CONFIRMED");
    }

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
        GraphSource source = GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> FACHSICHT, id -> "MaltParser");

        GraphSource.Graph graph = source.around("Antragsteller", 2, false);

        assertThat(graph.nodes()).extracting(GraphSource.Node::label, GraphSource.Node::synonym)
                .containsExactly(Tuple.tuple("Antragsteller", false), Tuple.tuple("Antrag", false),
                        Tuple.tuple("Gesuch", true));
        assertThat(graph.edges()).extracting(GraphSource.Edge::style, GraphSource.Edge::label,
                GraphSource.Edge::directed).containsExactly(
                Tuple.tuple(GraphSource.Style.VERB, "stellen · 3", true),
                Tuple.tuple(GraphSource.Style.SYNONYM, null, false));
        assertThat(graph.note()).isEqualTo("Aussagen aus MaltParser · 2 veraltete Aussagen ausgelassen");
        assertThat(GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> new DomainTermGraph.View(
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
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> {
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
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> {
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
            // Nur ungeprüfte Aussagen: Es fehlt die Prüfung, nicht die Analyse.
            graph.show(new GraphSource.Graph(List.of(new GraphSource.Node("antragsteller", "Antragsteller", 0, null)),
                    List.of(), false));
            seen.add(graph.hint());
            graph.checkedChoice().doClick();
            graph.show(new GraphSource.Graph(List.of(new GraphSource.Node("antragsteller", "Antragsteller", 0, null)),
                    List.of(), false));
            seen.add(graph.hint());
        });
        assertThat(seen).containsExactly(false, TermGraphView.CHECKED_LOCKED, true,
                "Optionen – aktiv: Nur Fachbegriffe, Nur geprüft", "2 Fachbegriffe · 1 Verbindung · nur geprüft",
                List.of("vom Prüfschritt bestätigt", "abweichend"),
                "Zu diesem Fachbegriff gibt es noch keine geprüften Aussagen mit anderen Fachbegriffen.",
                TermGraphView.NO_VERBS);
    }

    @Test
    void aDeviatingEvidenceNamesOnlyWhatTheOtherAnalyzerReadsDifferently() throws Exception {
        DomainTermGraph.Evidence plain = evidence("1", "Förderantrag", DomainTermGraph.Trust.ABWEICHEND);
        com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Comparison differs =
                new com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Comparison(
                        plain.statement().id(),
                        com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Agreement.DIFFERS,
                        new com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Reading(
                                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER,
                                plain.statement().id(), "stellen", Set.of("antragsteller"), Set.of("antrag")),
                        new com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Reading(
                                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.MODEL,
                                plain.statement().id(), "einreichen", Set.of("antragsteller"), Set.of()),
                        java.util.EnumSet.of(
                                com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference.VERB,
                                com.aresstack.spaceharvester.application.knowledge.AnalyzerComparison.Difference
                                        .AFFECTED_OBJECT));
        DomainTermGraph.Evidence deviating = new DomainTermGraph.Evidence(plain.page(), plain.statement(),
                plain.analyzerId(), plain.fromText(), plain.toText(), DomainTermGraph.Trust.ABWEICHEND, differs);
        DomainTermGraph.Evidence checked = new DomainTermGraph.Evidence(plain.page(), plain.statement(),
                plain.analyzerId(), plain.fromText(), plain.toText(), DomainTermGraph.Trust.GEPRUEFT, differs);

        assertThat(EvidencePopup.otherText(deviating))
                .isEqualTo(com.aresstack.spaceharvester.app.ui.content.StatementProvenance.LANGUAGE_MODEL
                + " liest anders: Verb „einreichen“ · keine Betroffenen");
        assertThat(EvidencePopup.otherText(checked)).as("geprüft: ohne „abweichend“").isNull();
        assertThat(EvidencePopup.otherText(plain)).as("ohne Abgleich").isNull();
        List<List<String>> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            EvidencePopup popup = new EvidencePopup();
            popup.fill("Antragsteller stellt Antrag", 2, List.of(deviating, checked), Map.of(), false);
            seen.add(popup.otherTexts());
        });
        assertThat(seen.getFirst()).containsExactly(
                com.aresstack.spaceharvester.app.ui.content.StatementProvenance.LANGUAGE_MODEL
                + " liest anders: Verb „einreichen“ · keine Betroffenen");
    }

    @Test
    void theRealFetchPassesCheckedOnlyAndTheHelpNamesEveryStage() {
        assertThat(TermGraphView.domainOptions(2, null, true).checkedOnly()).isTrue();
        assertThat(TermGraphView.domainOptions(1, null, false).checkedOnly()).isFalse();
        assertThat(TermGraphView.domainOptions(2, null, true).includeStale()).as("nie veraltete Aussagen").isFalse();
        assertThat(TermGraphView.HELP).as("mit dem Vergleich Parser/KI alle vier Stufen")
                .contains("kräftig", "geprüft", "dünn", "(einig)", "gestrichelt", "gepunktet mit „≠“", "(abweichend)")
                .contains("Empfänger und Präpositionalobjekte werden nicht verglichen")
                .doesNotContain("andere Seite");
    }

    @Test
    void theInventoryViewStaysAsItWasAndTheStatusAlwaysNamesTheAnalyzer() throws Exception {
        List<Object> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY,
                    GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> FACHSICHT, id -> "MaltParser"));
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

        GraphSource.Graph empty = GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> new DomainTermGraph.View(
                DomainTermGraph.Outcome.FOUND, List.of(new DomainTermGraph.Node("antrag", "Antrag",
                DomainTermGraph.NodeKind.TERM, 0, null)), List.of(), false, 0, Set.of(),
                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER),
                com.aresstack.spaceharvester.app.ui.content.StatementProvenance::analyzer).around("Antrag", 2, false);
        assertThat(empty.note()).as("ohne Kante und ohne Seite trotzdem die Ablage").isEqualTo("Aussagen aus MaltParser");
    }

    @Test
    void aCoreChangeReloadsQuietlyAndWaitsWhileTheEvidenceIsOpen() throws Exception {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> {
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
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> {
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
        GraphSource domain = GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) -> {
            asked.incrementAndGet();
            return FACHSICHT;
        }, id -> "MaltParser");
        java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch second = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger read = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Thread> firstReader =
                new java.util.concurrent.atomic.AtomicReference<>();
        TermGraphView[] holder = new TermGraphView[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY, domain);
            holder[0].sentenceReader(byPage -> {
                try {
                    if (read.incrementAndGet() == 1) {
                        firstReader.set(Thread.currentThread());
                        first.await();
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
        // Klicks nacheinander: Erst wenn das Lesen des ersten Klicks läuft, kommt der zweite. Sonst könnte der Thread
        // des zweiten Klicks zuerst lesen, und die „überholte“ Antwort wäre in Wahrheit die letzte.
        SwingUtilities.invokeAndWait(() -> holder[0].showEvidenceFor(verb));
        awaitTrue(() -> firstReader.get() != null);
        SwingUtilities.invokeAndWait(() -> holder[0].showEvidenceFor(verb));
        awaitTrue(() -> read.get() == 2);
        first.countDown();
        // Background läuft je Aufgabe in einem eigenen Thread; er endet erst, nachdem er die überholte Antwort an den
        // EDT übergeben hat. Danach den EDT einmal leeren: Die Antwort ist zugestellt, ganz ohne Zeitfenster.
        firstReader.get().join(5000);
        assertThat(firstReader.get().isAlive()).isFalse();
        SwingUtilities.invokeAndWait(() -> { });
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

    /** W4b: ohne Mitte (seitenweite Fachsicht, alle Knoten Tiefe 0) liegen alle Knoten auf Ringen, keiner gelb. */
    @Test
    void withoutACentreAllNodesAreLaidOutOnRingsAndNoneIsHighlighted() throws Exception {
        List<GraphSource.Node> nodes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            nodes.add(new GraphSource.Node("k" + i, "Begriff " + i, 0, null));
        }
        GraphSource.Graph graph = new GraphSource.Graph(nodes, List.of(new GraphSource.Edge("k0", "k1", "VERB", true,
                true, GraphSource.Style.VERB, "planen · 2", List.of())), false);
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            canvas.setSize(900, 560);
            canvas.graph(graph);
            seen.add("mit Mitte " + canvas.layout(900, 560).size());
            assertThat(canvas.emptyText()).as("die Übersicht zeigt keinen Text in der Fläche").isNull();
            canvas.domain(true);
            canvas.centred(false);
            Map<String, Rectangle2D> boxes = canvas.layout(900, 560);
            seen.add("ohne Mitte " + boxes.size());
            for (Rectangle2D box : boxes.values()) {
                assertThat(new Rectangle2D.Double(0, 0, 900, 560).contains(box)).isTrue();
            }
            Rectangle2D first = boxes.get("k0");
            BufferedImage image = new BufferedImage(900, 560, BufferedImage.TYPE_INT_RGB);
            canvas.paint(image.getGraphics());
            seen.add(String.valueOf(image.getRGB((int) first.getX() + 8, (int) first.getY() + 6)
                    == Comic.YELLOW.getRGB()));
            seen.add(canvas.nodeAt(new Point((int) first.getCenterX(), (int) first.getCenterY())).label());
        });
        assertThat(seen).containsExactly("mit Mitte 1", "ohne Mitte 30", "false", "Begriff 0");
    }

    /** W4b: Ein leerer Graph mit Text zeigt diesen gedämpft mitten in der Fläche. */
    @Test
    void anEmptyGraphWithATextPaintsItMutedInTheMiddle() throws Exception {
        int[] found = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, -1, 0};
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView.Canvas canvas = new TermGraphView.Canvas();
            canvas.setSize(600, 300);
            canvas.emptyText("Auf dieser Seite sind keine bestätigten Fachbegriffe durch ein Verb verbunden.");
            BufferedImage image = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
            canvas.paint(image.getGraphics());
            int darkest = Integer.MAX_VALUE;
            for (int x = 0; x < 600; x++) {
                for (int y = 0; y < 300; y++) {
                    int rgb = image.getRGB(x, y);
                    if (rgb == java.awt.Color.WHITE.getRGB()) {
                        continue;
                    }
                    found[0] = Math.min(found[0], x);
                    found[1] = Math.max(found[1], x);
                    found[2] = Math.min(found[2], y);
                    found[3] = Math.max(found[3], y);
                    if (rgb == Comic.INK.getRGB() || rgb == Comic.YELLOW.getRGB()) {
                        found[5]++;
                    }
                    java.awt.Color color = new java.awt.Color(rgb);
                    int brightness = color.getRed() + color.getGreen() + color.getBlue();
                    if (brightness < darkest) {
                        darkest = brightness;
                        found[4] = rgb;
                    }
                }
            }
        });
        assertThat(found[4]).as("Text gezeichnet").isNotEqualTo(-1);
        // Kantenglättung hellt nur auf: Der dunkelste Punkt hat die gedämpfte Farbe, nie Tinte oder Gelb.
        java.awt.Color darkest = new java.awt.Color(found[4]);
        assertThat(Math.abs(darkest.getRed() - Comic.MUTED.getRed())).as("rot").isLessThanOrEqualTo(24);
        assertThat(Math.abs(darkest.getGreen() - Comic.MUTED.getGreen())).as("grün").isLessThanOrEqualTo(24);
        assertThat(Math.abs(darkest.getBlue() - Comic.MUTED.getBlue())).as("blau").isLessThanOrEqualTo(24);
        assertThat(found[5]).as("kein Punkt in Tinte oder Gelb").isZero();
        // Mittig: die gezeichneten Punkte liegen um die Mitte der Fläche.
        assertThat(Math.abs((found[0] + found[1]) / 2 - 300)).as("waagrecht mittig").isLessThanOrEqualTo(12);
        assertThat(Math.abs((found[2] + found[3]) / 2 - 150)).as("senkrecht mittig").isLessThanOrEqualTo(12);
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
