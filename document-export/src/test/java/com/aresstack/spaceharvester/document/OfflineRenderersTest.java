package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfflineRenderersTest {

    @Test
    void mermaidBecomesAPictureWithoutBrowserOrNetwork() {
        BufferedImage image = new AresStackMermaidImageRenderer().render("graph TD\n  A[Start] --> B[Ende]", 600);

        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isPositive();
        assertThat(image.getHeight()).isPositive();
    }

    @Test
    void svgFiguresAreRasterizedAndScriptsDropped() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"60\">"
                + "<script>alert(1)</script><rect x=\"10\" y=\"10\" width=\"100\" height=\"40\" fill=\"#4472c4\"/></svg>";
        ExportGraphics graphics = ExportGraphics.rendering();
        graphics.with(List.of(ExportImage.loaded("bild.svg", "Bild", svg.getBytes(StandardCharsets.UTF_8))));

        assertThat(graphics.image("bild.svg", "Bild")).isNotNull();
        assertThat(graphics.mermaid("graph LR\n  A --> B")).isNotNull();
        assertThat(graphics.problems()).isEmpty();
    }
}
