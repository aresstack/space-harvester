package com.aresstack.spaceharvester.app.chat.artifact;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.util.XMLResourceDescriptor;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Color;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Änderungen der Zeichenfläche am SVG (ADR 0045 §6), ohne Oberfläche. */
class SvgEditingTest {

    private static Document empty() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().newDocument();
        document.appendChild(document.createElementNS("http://www.w3.org/2000/svg", "svg"));
        return document;
    }

    @Test
    void movingAgainFoldsIntoTheLeadingTranslateAndKeepsTheRest() throws Exception {
        Document document = empty();
        Element shape = SvgEditing.add(document, document.getDocumentElement(), "rect");
        SvgEditing.translate(shape, "translate(5 5) rotate(10)", 2.5, -5);
        assertThat(shape.getAttribute("transform")).isEqualTo("translate(7.5 0) rotate(10)");
        SvgEditing.translate(shape, null, 0, 0);
        assertThat(shape.hasAttribute("transform")).isFalse();
    }

    @Test
    void resizingScalesAroundTheTopLeftCorner() throws Exception {
        Document document = empty();
        Element shape = SvgEditing.add(document, document.getDocumentElement(), "rect");
        SvgEditing.scale(shape, "", 0, 0, 1.5, 2);
        assertThat(shape.getAttribute("transform")).isEqualTo("scale(1.5 2)");
        SvgEditing.scale(shape, "rotate(10)", 10, 20, 2, 2);
        assertThat(shape.getAttribute("transform"))
                .isEqualTo("translate(10 20) scale(2 2) translate(-10 -20) rotate(10)");
    }

    @Test
    void newShapesSpanTheDraggedBoxInAnyDirection() throws Exception {
        Document document = empty();
        Element root = document.getDocumentElement();
        Element rect = SvgEditing.add(document, root, "rect");
        SvgEditing.span(rect, 30, 40, 10, 20);
        assertThat(rect.getAttribute("x") + " " + rect.getAttribute("y") + " " + rect.getAttribute("width") + " "
                + rect.getAttribute("height")).isEqualTo("10 20 20 20");
        Element ellipse = SvgEditing.add(document, root, "ellipse");
        SvgEditing.span(ellipse, 0, 0, 10, 5);
        assertThat(ellipse.getAttribute("cx") + " " + ellipse.getAttribute("ry")).isEqualTo("5 2.5");
        Element line = SvgEditing.add(document, root, "line");
        SvgEditing.span(line, 30, 40, 10, 20);
        assertThat(line.getAttribute("x1") + " " + line.getAttribute("x2")).isEqualTo("30 10");
        assertThat(SvgEditing.color(new Color(0x12, 0xab, 0xff))).isEqualTo("#12abff");
        assertThat(SvgEditing.number(1.0 / 3)).isEqualTo("0.33");
    }

    @Test
    void writtenFromBatikOnlyWhatTheGraphicReallySays() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"4\""
                + " height=\"4\"/></svg>";
        Document batik = new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName())
                .createSVGDocument("file:///G1.svg", new StringReader(svg));
        assertThat(SvgElements.write(batik)).contains("zoomAndPan");
        String written = SvgElements.writeSpecified(batik);
        assertThat(written).doesNotContain("zoomAndPan", "contentScriptType", "preserveAspectRatio", "version=")
                .contains("viewBox=\"0 0 10 10\"", "<rect", "width=\"4\"");
    }
}
