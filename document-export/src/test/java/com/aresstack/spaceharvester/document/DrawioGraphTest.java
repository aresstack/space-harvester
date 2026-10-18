package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** draw.io als Knoten und Verbindungen für KI-Systeme (ADR 0018). */
class DrawioGraphTest {

    private static final String MODEL = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
            + "<mxCell id=\"a\" value=\"Antrag &lt;b&gt;prüfen&lt;/b&gt;\" vertex=\"1\" parent=\"1\"/>"
            + "<object id=\"b\" label=\"Freigabe\"><mxCell vertex=\"1\" parent=\"1\"/></object>"
            + "<mxCell id=\"e\" value=\"\" edge=\"1\" source=\"a\" target=\"b\" parent=\"1\"/>"
            + "<mxCell id=\"l\" value=\"ok\" vertex=\"1\" connectable=\"0\" parent=\"e\"/>"
            + "</root></mxGraphModel>";

    @Test
    void nodesEdgesAndEdgeLabelsComeFromThePlainSource() throws Exception {
        DrawioGraph graph = DrawioGraph.of(("<mxfile><diagram name=\"Ablauf\">" + MODEL + "</diagram></mxfile>")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(graph.name()).isEqualTo("Ablauf");
        assertThat(graph.nodes()).extracting(DrawioGraph.Node::label).containsExactly("Antrag prüfen", "Freigabe");
        assertThat(graph.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.source()).isEqualTo("a");
            assertThat(edge.target()).isEqualTo("b");
            assertThat(edge.label()).isEqualTo("ok");
        });
        assertThat(graph.text()).isEqualTo("Diagramm: Ablauf\n- Antrag prüfen\n- Freigabe\nAntrag prüfen → Freigabe (ok)");
    }

    @Test
    void compressedPagesAreReadToo() throws Exception {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(URLEncoder.encode(MODEL, StandardCharsets.UTF_8).replace("+", "%20")
                .getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        String packed = Base64.getEncoder().encodeToString(out.toByteArray());

        DrawioGraph graph = DrawioGraph.of(("<mxfile><diagram name=\"P\">" + packed + "</diagram></mxfile>")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.edges()).hasSize(1);
    }
}
