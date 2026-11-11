package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class SvgDrawingTest {

    private static final String SVG = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "width=\"3288\" height=\"1492\"><rect x=\"10\" y=\"10\" width=\"500\" height=\"200\" fill=\"#3366cc\"/>"
            + "</svg>";

    @Test
    void drawsAtAnyScaleWithoutAFixedWidth() throws Exception {
        SvgDrawing drawing = SvgDrawing.parse(SVG);

        assertThat(drawing.width()).isEqualTo(3288.0);
        assertThat(drawing.height()).isEqualTo(1492.0);
        BufferedImage large = drawing.raster(8000);
        assertThat(large.getWidth()).isEqualTo(8000);
        assertThat(large.getRGB(100, 100)).isEqualTo(0xff3366cc);
    }

    @Test
    void paintsAZoomedDetailThroughTheGraphicsTransform() throws Exception {
        SvgDrawing drawing = SvgDrawing.parse(SVG);
        BufferedImage view = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = view.createGraphics();
        // Ausschnitt bei 400 %: Verschiebung über die Transformation, gezeichnet nur im sichtbaren Bereich.
        g.translate(-1000, -100);
        drawing.paint(g, 4.0);
        g.dispose();

        assertThat(view.getRGB(100, 100)).isEqualTo(0xff3366cc);
    }

    @Test
    void rejectsTextWithoutSvg() {
        assertThatThrownBy(() -> SvgDrawing.parse("kein svg")).isInstanceOf(SvgDrawing.Unsupported.class);
    }
}
