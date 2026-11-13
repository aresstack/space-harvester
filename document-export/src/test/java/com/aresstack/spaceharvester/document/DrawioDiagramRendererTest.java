package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

/** draw.io (erfundene Inhalte) offline mit drawio-java als SVG; Unbekanntes wird gemeldet, nicht geraten (drawio-java#16). */
class DrawioDiagramRendererTest {

    private static String model(String cells) {
        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>" + cells + "</root></mxGraphModel>";
    }

    private static String mxfile(String model) {
        return "<mxfile host=\"x\"><diagram id=\"d\" name=\"Seite-1\">" + model + "</diagram></mxfile>";
    }

    private static String vertex(String id, String value, String style, int x) {
        return "<mxCell id=\"" + id + "\" value=\"" + value + "\" style=\"" + style + "\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"" + x + "\" y=\"10\" width=\"80\" height=\"60\" as=\"geometry\"/></mxCell>";
    }

    @Test
    void drawsLabelsAndShapesAsSvg() throws Exception {
        String svg = DrawioDiagramRenderer.toSvg(mxfile(model(
                vertex("s", "Start", "rounded=1;whiteSpace=wrap;html=1;", 10)
                        + vertex("e", "Ende", "ellipse;", 120)
                        + vertex("g", "Prüfen?", "shape=mxgraph.bpmn.gateway2;outline=none;symbol=exclusiveGw;html=1;", 230))));

        assertThat(svg).contains("<svg", "Start", "Ende", "Prüfen?", "<path");
    }

    /** drawio-java-stencils liegt im Testklassenpfad (wie in App und Dienst): klassische BPMN-XML-Schablonen. */
    @Test
    void drawsXmlStencilsFromTheStencilArtifact() throws Exception {
        String svg = DrawioDiagramRenderer.toSvg(mxfile(model(vertex("t", "Freigeben", "shape=mxgraph.bpmn.user_task;", 10))));

        assertThat(svg).contains("<path");
    }

    @Test
    void reportsUnknownStencilsByCapability() {
        assertThat(unsupported(mxfile(model(vertex("s", "Pfeil", "shape=mxgraph.unbekannt.form;", 10)))))
                .isEqualTo("nicht unterstützt: stencil:mxgraph.unbekannt.form");
    }

    @Test
    void rejectsDoctype() {
        assertThat(unsupported("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><mxfile>&e;</mxfile>"))
                .isEqualTo("INVALID_XML");
    }

    @Test
    void anEmptyPageIsNotDrawn() {
        assertThat(unsupported(mxfile(model("")))).isEqualTo("Diagramm ohne Elemente");
        assertThat(unsupported(" ")).isEqualTo("Diagrammdaten fehlen");
    }

    @Test
    void messagesNeverContainLabelsOrIds() {
        String message = unsupported(mxfile(model(vertex("Geheim-ID-4711", "Geheimes Projekt Falke",
                "shape=mxgraph.unbekannt.form;", 10))));

        assertThat(message).doesNotContain("Geheim", "Falke", "4711");
    }

    private static String unsupported(String xml) {
        DrawioDiagramRenderer.Unsupported e = catchThrowableOfType(DrawioDiagramRenderer.Unsupported.class,
                () -> DrawioDiagramRenderer.toSvg(xml));
        assertThat(e).as("Unsupported erwartet").isNotNull();
        return e.getMessage();
    }
}
