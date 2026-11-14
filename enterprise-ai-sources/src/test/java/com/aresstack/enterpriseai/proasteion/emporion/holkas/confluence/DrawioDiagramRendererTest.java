package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** draw.io-Diagramme (erfundene Inhalte) ohne Browser mit drawio-java als SVG; Unbekanntes wird gemeldet, nicht geraten. */
public class DrawioDiagramRendererTest {

    private static final String PNG_DATA = "data:image/png,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4"
            + "2mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    private static String model(String cells) {
        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>" + cells + "</root></mxGraphModel>";
    }

    private static String mxfile(String model) {
        return "<mxfile host=\"x\"><diagram id=\"d\" name=\"Seite-1\">" + model + "</diagram></mxfile>";
    }

    private static String vertex(String id, String value, String style) {
        return "<mxCell id=\"" + id + "\" value=\"" + value + "\" style=\"" + style + "\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"60\" as=\"geometry\"/></mxCell>";
    }

    private static final String TWO_BOXES = model(
            "<mxCell id=\"a\" value=\"&lt;div&gt;&amp;lt;&amp;lt;Modul&amp;gt;&amp;gt;&lt;br&gt;Alpha&amp;nbsp;Eins&lt;/div&gt;\""
                    + " style=\"rounded=0;whiteSpace=wrap;html=1;fillColor=default;strokeColor=light-dark(#333333, #ededed);\""
                    + " vertex=\"1\" parent=\"1\"><mxGeometry x=\"10\" y=\"10\" width=\"120\" height=\"60\" as=\"geometry\"/>"
                    + "</mxCell>"
                    + "<mxCell id=\"b\" value=\"Beta\" style=\"ellipse;fillColor=#fff;\" vertex=\"1\" parent=\"1\">"
                    + "<mxGeometry x=\"200\" y=\"10\" width=\"80\" height=\"60\" as=\"geometry\"/></mxCell>"
                    + "<mxCell id=\"i\" style=\"shape=image;image=" + PNG_DATA + ";\" vertex=\"1\" parent=\"1\">"
                    + "<mxGeometry x=\"10\" y=\"100\" width=\"20\" height=\"20\" as=\"geometry\"/></mxCell>"
                    + "<mxCell id=\"e\" style=\"edgeStyle=orthogonalEdgeStyle;shape=filledEdge;html=1;\" edge=\"1\""
                    + " parent=\"1\" source=\"a\" target=\"b\"><mxGeometry relative=\"1\" as=\"geometry\"/></mxCell>");

    @Test
    public void anUncompressedDiagramBecomesSvgWithPlainLabelsAndDrawioColors() throws Exception {
        String svg = DrawioDiagramRenderer.toSvg(mxfile(TWO_BOXES));

        assertTrue(svg, svg.contains("<svg"));
        assertTrue(svg, svg.contains("&lt;&lt;Modul&gt;&gt;"));
        assertTrue("&nbsp; bleibt ein geschütztes Leerzeichen wie in draw.io", svg.contains("Alpha\u00a0Eins"));
        assertTrue(svg, svg.contains("Beta"));
        assertFalse("keine HTML-Reste in der Beschriftung", svg.contains("div") || svg.contains("&amp;nbsp;"));
        assertTrue("light-dark(): helle Farbe, Batik schreibt rgb()", svg.contains("rgb(51,51,51)"));
        assertTrue("eingebettetes Bild als gültige Daten-URI", svg.contains("data:image/png;base64,iVBOR"));
    }

    @Test
    public void aCompressedPageIsUnpacked() throws Exception {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(URLEncoder.encode(TWO_BOXES, "UTF-8").replace("+", "%20").getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        String packed = "<mxfile><diagram id=\"d\">" + Base64.getEncoder().encodeToString(out.toByteArray())
                + "</diagram></mxfile>";

        assertTrue(DrawioDiagramRenderer.toSvg(packed).contains("Beta"));
    }

    @Test
    public void aBpmnGatewayAndBlockArrowAreDrawnByTheOriginalShapes() throws Exception {
        String svg = DrawioDiagramRenderer.toSvg(mxfile(model(
                vertex("g", "Prüfen?", "shape=mxgraph.bpmn.gateway2;outline=none;symbol=exclusiveGw;html=1;")
                        + vertex("p", "", "shape=mxgraph.arrows2.arrow;dy=0.65;dx=25;strokeColor=#333333;direction=south;"))));

        assertTrue(svg, svg.contains("<path"));
        assertTrue(svg, svg.contains("Prüfen?"));
        assertTrue(svg, svg.contains("rgb(51,51,51)"));
    }

    @Test
    public void anUnknownStencilIsReportedByNameInsteadOfGuessed() {
        assertEquals("nicht unterstützt: stencil:mxgraph.unbekannt.form",
                unsupported(mxfile(model(vertex("s", "Pfeil", "shape=mxgraph.unbekannt.form;")))));
        assertEquals("nicht unterstützt: shape:gibtsnicht",
                unsupported(mxfile(model(vertex("s", "Pfeil", "shape=gibtsnicht;")))));
    }

    /** drawio-java-stencils liegt im Laufzeitklassenpfad: die klassischen BPMN-XML-Schablonen werden gezeichnet. */
    @Test
    public void anXmlStencilFromTheStencilArtifactIsDrawn() throws Exception {
        String svg = DrawioDiagramRenderer.toSvg(mxfile(model(vertex("s", "Freigeben", "shape=mxgraph.bpmn.user_task;"))));

        assertTrue(svg, svg.contains("<path"));
    }

    @Test
    public void anExternalImageIsNotFetchedButReported() {
        assertEquals("nicht unterstützt: image:external", unsupported(mxfile(model(
                vertex("s", "", "shape=image;image=https://bilder.invalid/a.png;")))));
    }

    @Test
    public void aDoctypeIsRejected() {
        assertEquals("INVALID_XML",
                unsupported("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><mxfile>&e;</mxfile>"));
    }

    @Test
    public void anEmptyPageOrMissingDataIsNotDrawn() {
        assertEquals("Diagramm ohne Elemente", unsupported(mxfile(model(""))));
        assertEquals("INVALID_DIAGRAM", unsupported("<mxfile/>"));
        assertEquals("Diagrammdaten fehlen", unsupported(" "));
    }

    @Test
    public void messagesNeverContainLabelsOrIds() {
        String message = unsupported(mxfile(model(vertex("Geheim-ID-4711", "Geheimes Projekt Falke",
                "shape=mxgraph.unbekannt.form;"))));

        assertFalse(message, message.contains("Geheim") || message.contains("Falke") || message.contains("4711"));
    }

    private static String unsupported(String xml) {
        try {
            DrawioDiagramRenderer.toSvg(xml);
            fail("expected Unsupported");
            return null;
        } catch (DrawioDiagramRenderer.Unsupported e) {
            return e.getMessage();
        }
    }
}
