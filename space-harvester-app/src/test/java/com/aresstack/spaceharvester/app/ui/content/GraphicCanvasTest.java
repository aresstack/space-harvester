package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Die Zeichenfläche auf Batik (ADR 0045 §6): jede fertige Mausaktion ist eine Revision in Koordinaten der Grafik. */
class GraphicCanvasTest {

    /** Erfundene Grafik: eine Gruppe mit Versatz, ein Kreis; viewBox halb so groß wie die Fläche. */
    private static final String BEET = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 100\""
            + " width=\"400\" height=\"200\"><g transform=\"translate(10 10)\"><rect width=\"40\" height=\"20\""
            + " fill=\"#8c8\"/></g><circle cx=\"150\" cy=\"50\" r=\"20\" fill=\"#c88\"/></svg>";
    private static final long WAIT = 20_000;

    @TempDir
    Path home;

    private ChatArtifactStore store;
    private Path folder;
    private String chatId;
    private GraphicCanvas drawing;
    private final List<String> status = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("Mal", 1), 1);
        chats.save(node, chat);
        store = new ChatArtifactStore(chats);
        folder = chats.folder(node);
        chatId = chat.id();
        ChatArtifact graphic = store.createGraphic(folder, chatId, "Beet", BEET, ChatArtifact.Origin.AI);
        SwingUtilities.invokeAndWait(() -> {
            drawing = new GraphicCanvas(store, folder, chatId, graphic, () -> { }, status::add);
            drawing.setSize(700, 500);
            layout(drawing);
        });
        // Wie die Artefakt-Ansicht: jeder neue Stand geht an die Fläche.
        store.addListener(event -> SwingUtilities.invokeLater(() -> drawing.external(event.artifact())));
        assertThat(drawing.settle(WAIT)).isTrue();
    }

    @Test
    void movingCreatingAndResizingAreRevisionsInGraphicUnits() throws Exception {
        AffineTransform view = drawing.canvas().getViewBoxTransform();
        double scale = view.getScaleX();

        // Kreis um 40 × 20 Pixel verschieben
        Point2D circle = view.transform(new Point2D.Double(150, 50), null);
        drag(circle, 40, 20);
        Element moved = element(svg(), "circle");
        assertThat(moved.getAttribute("transform")).startsWith("translate(");
        double[] shift = numbers(moved.getAttribute("transform"));
        assertThat(shift[0]).isCloseTo(40 / scale, within(0.02));
        assertThat(shift[1]).isCloseTo(20 / scale, within(0.02));
        assertThat(latest().revision()).isEqualTo(2);
        assertThat(latest().current().origin()).isEqualTo(ChatArtifact.Origin.USER);

        // Rechteck aufziehen: liegt in Koordinaten der Grafik, nicht in Pixeln
        drawing.tool(GraphicCanvas.Tool.RECTANGLE);
        Point2D from = view.transform(new Point2D.Double(20, 60), null);
        drag(from, 30 * scale, 20 * scale);
        Element made = elements(svg(), "rect").get(1);
        assertThat(Double.parseDouble(made.getAttribute("x"))).isCloseTo(20, within(0.5));
        assertThat(Double.parseDouble(made.getAttribute("y"))).isCloseTo(60, within(0.5));
        assertThat(Double.parseDouble(made.getAttribute("width"))).isCloseTo(30, within(0.5));
        assertThat(Double.parseDouble(made.getAttribute("height"))).isCloseTo(20, within(0.5));
        assertThat(made.getAttribute("id")).isNotEmpty();
        assertThat(latest().revision()).isEqualTo(3);

        // Rechteck in der Gruppe auswählen, am Griff unten rechts um die halbe Breite größer ziehen
        drawing.tool(GraphicCanvas.Tool.SELECT);
        Point2D inside = view.transform(new Point2D.Double(30, 20), null);
        drag(inside, 0, 0);
        Point2D corner = view.transform(new Point2D.Double(50, 30), null);
        drag(corner, 20 * scale, 10 * scale);
        Element grown = elements(svg(), "rect").get(0);
        double[] factor = numbers(grown.getAttribute("transform"));
        assertThat(grown.getAttribute("transform")).startsWith("scale(");
        assertThat(factor[0]).isCloseTo(1.5, within(0.05));
        assertThat(factor[1]).isCloseTo(1.5, within(0.05));
        assertThat(latest().revision()).isEqualTo(4);

        // Gespeichert wird nur, was im Dokument steht, nicht Batiks Vorgaben
        assertThat(latest().current().svg()).doesNotContain("zoomAndPan", "contentScriptType", "version=");
        assertThat(status).isEmpty();
    }

    @Test
    void aChangeFromTheAiIsLoadedAndTheNextStrokeBuildsOnIt() throws Exception {
        String changed = BEET.replace("#c88", "#36c");
        store.replaceSvg(folder, chatId, "G1", 1, ChatArtifact.Origin.AI, "Farbe", changed);
        // Benachrichtigung (EDT) → Prüfung (Batik) → Laden (EDT) → neue Fläche bereit
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(drawing.settle(WAIT)).isTrue();
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(drawing.revision()).isEqualTo(2);
        assertThat(drawing.settle(WAIT)).isTrue();

        AffineTransform view = drawing.canvas().getViewBoxTransform();
        drag(view.transform(new Point2D.Double(150, 50), null), 10, 0);
        assertThat(latest().revision()).isEqualTo(3);
        assertThat(element(svg(), "circle").getAttribute("fill")).isEqualTo("#36c");
    }

    // ------------------------------------------------------------------ Hilfen

    private ChatArtifact latest() {
        return store.read(folder, chatId, "G1");
    }

    private org.w3c.dom.Document svg() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                latest().current().svg().getBytes(StandardCharsets.UTF_8)));
    }

    private static Element element(org.w3c.dom.Document document, String tag) {
        return elements(document, tag).get(0);
    }

    private static List<Element> elements(org.w3c.dom.Document document, String tag) {
        var nodes = document.getElementsByTagNameNS("*", tag);
        List<Element> all = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            all.add((Element) nodes.item(i));
        }
        return all;
    }

    private static double[] numbers(String transform) {
        String inner = transform.substring(transform.indexOf('(') + 1, transform.indexOf(')'));
        String[] parts = inner.trim().split("[\\s,]+");
        return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
    }

    /** Drücken, zweimal ziehen, loslassen; danach ist alles im Aktualisierungs-Thread erledigt. */
    private void drag(Point2D start, double dx, double dy) throws Exception {
        JComponent canvas = drawing.canvas();
        int x1 = (int) Math.round(start.getX());
        int y1 = (int) Math.round(start.getY());
        int x2 = (int) Math.round(start.getX() + dx);
        int y2 = (int) Math.round(start.getY() + dy);
        SwingUtilities.invokeAndWait(() -> {
            long now = System.currentTimeMillis();
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now,
                    MouseEvent.BUTTON1_DOWN_MASK, x1, y1, 1, false, MouseEvent.BUTTON1));
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, now,
                    MouseEvent.BUTTON1_DOWN_MASK, (x1 + x2) / 2, (y1 + y2) / 2, 1, false, MouseEvent.BUTTON1));
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, now,
                    MouseEvent.BUTTON1_DOWN_MASK, x2, y2, 1, false, MouseEvent.BUTTON1));
            canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now, 0, x2, y2, 1, false,
                    MouseEvent.BUTTON1));
        });
        assertThat(drawing.settle(WAIT)).isTrue();
        // Die Benachrichtigung über den eigenen Stand kommt über den EDT; sie darf die Fläche nicht neu laden.
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(drawing.settle(WAIT)).isTrue();
    }

    private static void layout(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layout(child);
            }
        }
    }
}
