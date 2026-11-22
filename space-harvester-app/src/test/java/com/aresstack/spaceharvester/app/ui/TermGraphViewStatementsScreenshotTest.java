package com.aresstack.spaceharvester.app.ui;

import java.awt.BorderLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Bildschirmfotos der Fachsicht mit Aussagen je Kante (Paket W9) zur Sichtprüfung, je bei 960 und 900 px: ein Paar mit
 * zwei Kanten („prüfen“ / „nicht prüfen“), eines mit vier Aussagen („+ 2 weitere“), dazu das Overlay über eine Kante
 * und über „+ 2 weitere“. Erfundene Daten (Quelle „wiki“, Space „DEV“). Läuft nur mit {@code SCREENSHOTS_DIR}, allein
 * gestartet und mit Bildschirm (etwa {@code xvfb-run -a -s "-screen 0 3000x2000x24"}).
 */
class TermGraphViewStatementsScreenshotTest {

    @Test
    void renderStatements() throws Exception {
        String directory = System.getenv("SCREENSHOTS_DIR");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());
        System.setProperty("java.awt.headless", "false");
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(), "braucht einen Bildschirm (Xvfb)");
        Path out = Path.of(directory);
        Files.createDirectories(out);
        for (int width : new int[] {960, 900}) {
            shot(width, out, "graph", view -> { });
            shot(width, out, "overlay", view -> view.showEvidenceFor(view.canvas().graph().edges().get(1)));
            shot(width, out, "overlay-weitere", view -> view.showEvidenceFor(view.canvas().graph().edges().get(4)));
        }
    }

    private void shot(int width, Path out, String name, Consumer<TermGraphView> act) throws Exception {
        JFrame[] frame = new JFrame[1];
        TermGraphView[] view = new TermGraphView[1];
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(Comic.SURFACE);
            frame[0] = new JFrame("Space Harvester");
            frame[0].setUndecorated(true);
            frame[0].setContentPane(root);
            frame[0].setSize(width, 640);
            frame[0].setLocation(0, 0);
            view[0] = new TermGraphView(null, (start, depth, proposals) -> GraphSource.Graph.EMPTY,
                    GraphSource.ofDomainTerms((start, depth, checkedOnly, cancelled) ->
                            TermGraphViewStatementsTest.view(), id -> "MaltParser"));
            root.add(view[0].tools(), BorderLayout.NORTH);
            root.add(view[0].component(), BorderLayout.CENTER);
            frame[0].setVisible(true);
            view[0].domainChoice().doClick();
            view[0].show(GraphSource.ofDomainView(TermGraphViewStatementsTest.view(), "Plan", id -> "MaltParser"));
        });
        settle();
        SwingUtilities.invokeAndWait(() -> act.accept(view[0]));
        settle();
        SwingUtilities.invokeAndWait(() -> write(frame[0].getRootPane(),
                out.resolve("w9-" + name + "-" + width + ".png")));
        SwingUtilities.invokeAndWait(() -> {
            view[0].evidencePopup().setVisible(false);
            frame[0].dispose();
        });
        settle();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(200);
        }
    }

    /** Doppelt aufgelöst; das Overlay liegt als leichtes Popup in der Fensterebene und wird mitgemalt. */
    private static void write(JComponent component, Path file) {
        try {
            component.validate();
            BufferedImage image = new BufferedImage(component.getWidth() * 2, component.getHeight() * 2,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(2, 2);
            g.setColor(Comic.SURFACE);
            g.fillRect(0, 0, component.getWidth(), component.getHeight());
            component.paint(g);
            g.dispose();
            ImageIO.write(image, "png", file.toFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
