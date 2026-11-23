package com.aresstack.spaceharvester.app.ui.deck.visual;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Diagramm-Ansicht „Visuell“: Mermaid-Quelltext wird zum Bild mit Knoten, das Mausrad blättert, Strg zoomt. */
class MermaidDiagramPanelTest {

    private static final String FLOW = "graph TD\n  A[JOB1] --> B[STEP1]\n  B --> C[STEP2]\n";

    @Test
    void rendersFlowchartWithNodes() throws Exception {
        MermaidDiagramPanel panel = renderedPanel(FLOW);

        assertThat(panel.hasDiagram()).isTrue();
        assertThat(panel.getDiagram().getNodes()).extracting(n -> n.getLabel())
                .contains("JOB1", "STEP1", "STEP2");
        assertThat(panel.getSvg()).contains("<svg");
    }

    @Test
    void wheelScrollsAndControlWheelZooms() throws Exception {
        MermaidDiagramPanel panel = renderedPanel(FLOW);
        Component image = imagePanel(panel);
        double zoom = onEdt(panel::getZoom);

        SwingUtilities.invokeAndWait(() -> image.dispatchEvent(wheel(image, 0, -1)));
        assertThat(onEdt(panel::getZoom)).isEqualTo(zoom);

        SwingUtilities.invokeAndWait(() -> image.dispatchEvent(wheel(image, InputEvent.CTRL_DOWN_MASK, -1)));
        assertThat(onEdt(panel::getZoom)).isGreaterThan(zoom);
    }

    private static MermaidDiagramPanel renderedPanel(String source) throws Exception {
        AtomicReference<MermaidDiagramPanel> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MermaidDiagramPanel panel = new MermaidDiagramPanel(false);
            panel.setSize(800, 600);
            panel.doLayout();
            panel.setMermaidSource(source);
            ref.set(panel);
        });
        MermaidDiagramPanel panel = ref.get();
        long until = System.currentTimeMillis() + 120_000;
        while (!onEdt(panel::hasDiagram) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        return panel;
    }

    private static Component imagePanel(MermaidDiagramPanel panel) throws Exception {
        java.lang.reflect.Field field = MermaidDiagramPanel.class.getDeclaredField("imagePanel");
        field.setAccessible(true);
        return (JPanel) field.get(panel);
    }

    private static MouseWheelEvent wheel(Component source, int modifiers, int rotation) {
        return new MouseWheelEvent(source, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers,
                100, 100, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> call) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref.set(call.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return ref.get();
    }
}
