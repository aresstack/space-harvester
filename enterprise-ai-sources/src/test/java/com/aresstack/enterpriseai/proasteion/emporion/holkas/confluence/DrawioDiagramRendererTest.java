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

/** draw.io-Diagramme (erfundene Inhalte) ohne Browser als SVG; Unbekanntes wird gemeldet, nicht geraten. */
public class DrawioDiagramRendererTest {

    private static final String PNG_DATA = "data:image/png,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4"
            + "2mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    private static String model(String cells) {
        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>" + cells + "</root></mxGraphModel>";
    }

    private static String mxfile(String model) {
        return "<mxfile host=\"x\"><diagram id=\"d\" name=\"Seite-1\">" + model + "</diagram></mxfile>";
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
        assertTrue(svg, svg.contains("Alpha Eins"));
        assertTrue(svg, svg.contains("Beta"));
        assertFalse("keine HTML-Reste in der Beschriftung", svg.contains("div") || svg.contains("&amp;nbsp;"));
        assertFalse("keine blaue JGraphX-Vorgabe", svg.contains("#C3D9FF"));
        assertTrue(svg, svg.contains("#333333"));
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
    public void anUnknownStencilIsReportedByNameInsteadOfGuessed() {
        String xml = mxfile(model("<mxCell id=\"s\" value=\"Pfeil\" style=\"shape=mxgraph.unbekannt.form;\" vertex=\"1\""
                + " parent=\"1\"><mxGeometry width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"));

        assertEquals("nicht unterstützt: shape=mxgraph.unbekannt.form", unsupported(xml));
    }

    @Test
    public void aBlockArrowIsDrawnAsOutline() throws Exception {
        String svg = DrawioDiagramRenderer.toSvg(mxfile(model("<mxCell id=\"p\" value=\"\" style=\"shape=mxgraph.arrows2.arrow;"
                + "dy=0.65;dx=25;notch=0;strokeWidth=2;fillColor=#FFFFFF;strokeColor=#333333;direction=south;\""
                + " vertex=\"1\" parent=\"1\"><mxGeometry x=\"10\" y=\"10\" width=\"40\" height=\"100\" as=\"geometry\"/>"
                + "</mxCell>")));

        assertTrue(svg, svg.contains("<path"));
        assertTrue(svg, svg.contains("#333333"));
    }

    @Test
    public void aRotatedInterfaceSymbolIsDrawnAsBallAndSocket() throws Exception {
        String svg = DrawioDiagramRenderer.toSvg(mxfile(model("<mxCell id=\"s\" value=\"Schnittstelle\""
                + " style=\"shape=providedRequiredInterface;html=1;verticalLabelPosition=bottom;rotation=-90;\""
                + " vertex=\"1\" parent=\"1\"><mxGeometry x=\"10\" y=\"10\" width=\"20\" height=\"20\" as=\"geometry\"/>"
                + "</mxCell>")));

        assertTrue(svg, svg.contains("rotate(-90.0 "));
        assertTrue(svg, svg.contains(" A 10.0 10.0 0 0 1 ") && svg.contains(" A 6.0 6.0 0 1 1 "));
        assertTrue(svg, svg.contains("Schnittstelle"));
    }

    @Test
    public void theBlockArrowFollowsDrawioGeometry() {
        java.util.Map<String, Object> style = new java.util.HashMap<String, Object>();
        style.put("dy", "0.5");
        style.put("dx", "20");
        assertEquals("M 0.0 10.0 L 80.0 10.0 L 80.0 0.0 L 100.0 20.0 L 80.0 40.0 L 80.0 30.0 L 0.0 30.0 L 0.0 20.0 Z",
                DrawioDiagramRenderer.blockArrowPath(0, 0, 100, 40, style));
        style.put("direction", "south");
        assertEquals("M 30.0 0.0 L 30.0 80.0 L 40.0 80.0 L 20.0 100.0 L 0.0 80.0 L 10.0 80.0 L 10.0 0.0 L 20.0 0.0 Z",
                DrawioDiagramRenderer.blockArrowPath(0, 0, 40, 100, style));
    }

    @Test
    public void anExternalImageIsNotFetchedButReported() {
        String xml = mxfile(model("<mxCell id=\"s\" style=\"shape=image;image=https://bilder.invalid/a.png;\""
                + " vertex=\"1\" parent=\"1\"><mxGeometry width=\"20\" height=\"20\" as=\"geometry\"/></mxCell>"));

        assertEquals("nicht unterstützt: image=https:…", unsupported(xml));
    }

    @Test
    public void aDoctypeIsRejected() {
        assertTrue(unsupported("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><mxfile>&e;</mxfile>")
                .startsWith("Diagrammdaten mit DOCTYPE"));
    }

    @Test
    public void htmlLabelsBecomeLines() {
        assertEquals("Erste Zeile\nZweite & dritte", DrawioDiagramRenderer.plainText(
                "<div><b>Erste</b>&nbsp;Zeile</div><div><br></div>Zweite &amp; dritte"));
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
