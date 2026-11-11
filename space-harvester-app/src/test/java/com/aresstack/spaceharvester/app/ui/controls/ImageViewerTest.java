package com.aresstack.spaceharvester.app.ui.controls;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Bildbetrachter der Leseansicht: eingepasst beim Öffnen und nach Größenänderung, Zoom und Einpassen, Blättern,
 * Knöpfe nur in ihrer Zone, Speichern mit Meldung in der Infozeile.
 */
class ImageViewerTest {

    @TempDir
    Path folder;

    private static ImageViewer.Picture picture(int width, int height, String label) {
        return new ImageViewer.Picture(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), label);
    }

    @Test
    void aLargeImageIsFittedIntoTheViewOnOpenAndAfterResizing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImageViewer viewer = new ImageViewer(List.of(picture(4000, 2000, "Übersicht")), 0);
            viewer.layoutForTest(800, 600);

            assertThat(viewer.isFitted()).isTrue();
            int fitted = viewer.zoomPercent();
            assertThat(fitted).as("ganzes Bild sichtbar, nicht Originalgröße").isLessThan(25);
            assertThat(viewer.infoText()).contains("1 / 1", "Übersicht", "4000 × 2000", "Zoom: " + fitted + " %");

            viewer.layoutForTest(1600, 1000);
            assertThat(viewer.zoomPercent()).as("folgt dem größeren Fenster").isGreaterThan(fitted);
        });
    }

    @Test
    void zoomKeepsItsLevelOnResizeUntilFitIsPressed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImageViewer viewer = new ImageViewer(List.of(picture(1000, 1000, "A")), 0);
            viewer.layoutForTest(500, 520);
            int fitted = viewer.zoomPercent();

            viewer.zoomIn();
            assertThat(viewer.zoomPercent()).isGreaterThan(fitted);
            assertThat(viewer.isFitted()).isFalse();
            int zoomed = viewer.zoomPercent();
            viewer.layoutForTest(900, 900);
            assertThat(viewer.zoomPercent()).isEqualTo(zoomed);

            viewer.zoomOut();
            assertThat(viewer.zoomPercent()).isLessThan(zoomed);
            viewer.fit();
            assertThat(viewer.isFitted()).isTrue();
        });
    }

    @Test
    void arrowsPageThroughTheImagesAndEachIsFittedAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImageViewer viewer = new ImageViewer(List.of(picture(100, 100, "A"), picture(300, 100, "B")), 0);
            viewer.layoutForTest(600, 400);
            viewer.zoomIn();
            assertThat(viewer.hasPrevious()).isFalse();

            viewer.next();
            assertThat(viewer.index()).isEqualTo(1);
            assertThat(viewer.isFitted()).isTrue();
            assertThat(viewer.infoText()).startsWith("2 / 2").contains("B");
            assertThat(viewer.hasNext()).isFalse();
            viewer.next();
            assertThat(viewer.index()).isEqualTo(1);
            viewer.previous();
            assertThat(viewer.index()).isZero();
        });
    }

    @Test
    void buttonsAppearOnlyInTheirZone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImageViewer viewer = new ImageViewer(List.of(picture(100, 100, "A"), picture(100, 100, "B")), 0);
            viewer.layoutForTest(600, 400);
            int height = viewer.getComponent(0).getHeight();

            viewer.hover(null);
            assertThat(viewer.visibleButtons()).containsExactly(false, false, false, false);
            viewer.hover(new Point(590, 10));
            assertThat(viewer.visibleButtons()).containsExactly(false, false, true, false);
            viewer.hover(new Point(590, height - 10));
            assertThat(viewer.visibleButtons()).containsExactly(false, false, false, true);
            viewer.hover(new Point(590, height / 2));
            assertThat(viewer.visibleButtons()).as("nächstes Bild").containsExactly(false, true, false, false);
            viewer.hover(new Point(10, height / 2));
            assertThat(viewer.visibleButtons()).as("am ersten Bild kein Zurück").containsExactly(false, false, false,
                    false);
        });
    }

    @Test
    void savingWritesThePictureAndReportsInTheInfoLine() throws Exception {
        File target = folder.resolve("diagramm").toFile();
        String[] message = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            ImageViewer viewer = new ImageViewer(List.of(picture(40, 30, "Diagramm")), 0);
            message[0] = viewer.saveTo(target);
            assertThat(viewer.infoText()).isEqualTo(message[0]);
        });
        assertThat(message[0]).isEqualTo(ImageViewer.SAVED_PREFIX + "diagramm.png");
        BufferedImage written = ImageIO.read(folder.resolve("diagramm.png").toFile());
        assertThat(written.getWidth()).isEqualTo(40);
        assertThat(ImageViewer.fileName(picture(1, 1, "a/b: c?"))).isEqualTo("a_b_ c_.png");
        assertThat(ImageViewer.fileName(picture(1, 1, "foto.jpg"))).isEqualTo("foto.jpg");
    }

    @Test
    void zoomNeverReversesDirectionForExtremeImages() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImageViewer tall = new ImageViewer(List.of(picture(100, 20000, "lang")), 0);
            tall.layoutForTest(800, 600);
            int fitted = tall.zoomPercent();
            tall.zoomOut();
            assertThat(tall.zoomPercent()).isLessThanOrEqualTo(fitted);

            ImageViewer tiny = new ImageViewer(List.of(picture(2, 2, "klein")), 0);
            tiny.layoutForTest(800, 600);
            int big = tiny.zoomPercent();
            tiny.zoomIn();
            assertThat(tiny.zoomPercent()).isGreaterThanOrEqualTo(big);
        });
    }

    @Test
    void transparentPicturesCanBeSavedAsBmp() throws Exception {
        File target = folder.resolve("bild.bmp").toFile();
        String[] message = new String[1];
        SwingUtilities.invokeAndWait(() -> message[0] = new ImageViewer(List.of(picture(10, 10, "x")), 0)
                .saveTo(target));
        assertThat(message[0]).isEqualTo(ImageViewer.SAVED_PREFIX + "bild.bmp");
        assertThat(ImageIO.read(target).getWidth()).isEqualTo(10);
    }

    private static com.aresstack.spaceharvester.application.diagrams.VectorDiagram diagram() {
        return com.aresstack.spaceharvester.application.diagrams.VectorDiagram.of(("<svg xmlns=\"http://www.w3.org/2000/svg\""
                + " width=\"3000\" height=\"1000\"><rect x=\"1000\" width=\"1000\" height=\"1000\" fill=\"#ff0000\"/></svg>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).orElseThrow();
    }

    @Test
    void aDiagramIsDrawnFromItsVectorAtEveryZoom() throws Exception {
        var vector = diagram();
        SwingUtilities.invokeAndWait(() -> {
            // Das Rasterbild ist absichtlich winzig: gezeigt wird trotzdem der Vektor in seiner Eigengröße.
            ImageViewer viewer = new ImageViewer(List.of(new ImageViewer.Picture(
                    new BufferedImage(30, 10, BufferedImage.TYPE_INT_ARGB), "Stages", vector)), 0);
            viewer.layoutForTest(600, 400);
            assertThat(viewer.infoText()).contains("3000 × 1000");
            assertThat(viewer.zoomPercent()).isEqualTo(20);
            for (int i = 0; i < 10; i++) {
                viewer.zoomIn();
            }
            java.awt.Component canvas = ((javax.swing.JLayeredPane) viewer.getComponent(0)).getComponent(
                    ((javax.swing.JLayeredPane) viewer.getComponent(0)).getComponentCount() - 1);
            BufferedImage shot = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = shot.createGraphics();
            canvas.paint(g);
            g.dispose();
            // Mitte des Bildes = Mitte des roten Quadrats: scharf rot, nicht verwaschen.
            java.awt.Color center = new java.awt.Color(shot.getRGB(shot.getWidth() / 2, shot.getHeight() / 2));
            assertThat(center).isEqualTo(java.awt.Color.RED);
        });
    }

    @Test
    void aDiagramIsSavedAsSvgOrAsPngInTheCurrentZoomSize() throws Exception {
        var vector = diagram();
        File svg = folder.resolve("stages").toFile();
        File png = folder.resolve("stages.png").toFile();
        SwingUtilities.invokeAndWait(() -> {
            ImageViewer viewer = new ImageViewer(List.of(new ImageViewer.Picture(
                    new BufferedImage(30, 10, BufferedImage.TYPE_INT_ARGB), "Stages", vector)), 0);
            viewer.layoutForTest(600, 400);
            assertThat(ImageViewer.fileName(new ImageViewer.Picture(new BufferedImage(1, 1, 1), "s.png", vector)))
                    .isEqualTo("s.svg");
            assertThat(viewer.saveTo(svg)).isEqualTo(ImageViewer.SAVED_PREFIX + "stages.svg");
            viewer.zoomIn();
            assertThat(viewer.saveTo(png)).isEqualTo(ImageViewer.SAVED_PREFIX + "stages.png");
        });
        assertThat(java.nio.file.Files.readAllBytes(folder.resolve("stages.svg"))).isEqualTo(vector.svg());
        assertThat(ImageIO.read(png).getWidth()).isEqualTo(750);
    }

    @Test
    void theChosenFormatReplacesTheExtension() {
        assertThat(ImageViewer.withExtension("stages.svg", "png")).isEqualTo("stages.png");
        assertThat(ImageViewer.withExtension("stages", "png")).isEqualTo("stages.png");
        assertThat(ImageViewer.withExtension("v1.2 plan.png", "svg")).isEqualTo("v1.2 plan.svg");
        assertThat(ImageViewer.withExtension("stages.svg", null)).isEqualTo("stages.svg");
    }
}
