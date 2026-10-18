package com.aresstack.spaceharvester.application.diagrams;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DiagramsTest {

    static final String MXFILE = "<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>";

    @Test
    void drawsDrawioAsSvg() {
        Diagrams.Rendering rendering = Diagrams.drawio(MXFILE.getBytes(StandardCharsets.UTF_8));

        assertThat(rendering.drawn()).isTrue();
        assertThat(rendering.mediaType()).isEqualTo(Diagrams.SVG);
        assertThat(new String(rendering.content(), StandardCharsets.UTF_8)).contains("<svg", "Start");
    }

    @Test
    void reportsWhatJgraphxCannotDrawInsteadOfGuessing() {
        Diagrams.Rendering broken = Diagrams.drawio("kein Diagramm".getBytes(StandardCharsets.UTF_8));
        Diagrams.Rendering doctype = Diagrams.drawio(("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + MXFILE).getBytes(StandardCharsets.UTF_8));

        assertThat(broken.drawn()).isFalse();
        assertThat(broken.problem()).startsWith("draw.io nicht darstellbar");
        assertThat(doctype.drawn()).isFalse();
    }

    @Test
    void drawsMermaidAsPng() {
        Diagrams.Rendering rendering = Diagrams.mermaid("graph TD\n  A[Start] --> B[Ende]");

        assertThat(rendering.drawn()).isTrue();
        assertThat(rendering.mediaType()).isEqualTo(Diagrams.PNG);
        assertThat(rendering.content()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void recognisesDrawioAttachmentsAndPreviewsLikeConfluence() {
        assertThat(Diagrams.isDrawio("application/vnd.jgraph.mxfile", "ablauf")).isTrue();
        assertThat(Diagrams.isDrawio("application/octet-stream", "ablauf.drawio")).isTrue();
        assertThat(Diagrams.isDrawio("image/png", "ablauf.png")).isFalse();
        assertThat(Diagrams.drawioName("ablauf.drawio")).isEqualTo("ablauf");
        assertThat(Diagrams.isDrawioPreview("ablauf.png", "ablauf")).isTrue();
        assertThat(Diagrams.isDrawioPreview("Ablauf.drawio.png", "ablauf")).isTrue();
        assertThat(Diagrams.isDrawioPreview("anderes.png", "ablauf")).isFalse();
    }
}
