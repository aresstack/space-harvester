package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

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
    void withoutRuntimeTheStartFieldIsDisabledAndHintsHowToStart() throws Exception {
        List<String> seen = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TermGraphView view = new TermGraphView(null);
            seen.add(String.valueOf(view.startField().isEnabled()));
            seen.add(view.hint());
            view.show(new GraphSource.Graph(List.of(new GraphSource.Node("x", "X", 0, null)), List.of(), false));
            seen.add(view.hint());
        });
        assertThat(seen).containsExactly("false", TermGraphView.START, TermGraphView.NO_NEIGHBOURS);
    }

    private static TermRelation relation(String from, String to, RelationType type, ReviewStatus status) {
        String id = TermRelation.idOf(TermRelation.key(from, to, type, RelationScope.GLOBAL));
        return new TermRelation(id, from, to, type, RelationScope.GLOBAL, RelationOrigin.MANUAL, status, List.of(),
                null, null, false, "test", Instant.EPOCH, null, null, 1);
    }
}
