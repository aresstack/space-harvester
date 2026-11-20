package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Angelos Beispiel aus dem Chat: nacktes SVG mit {@code <br/>} in {@code <text>} wird gezäunt und gerendert. */
public class SvgImageRendererTest {

    @Test
    public void rendersStoryOverviewFromAnswerAsImage() throws Exception {
        String svg = resource("story-overview.svg");
        String answer = "Hier die Übersicht:\n\n" + svg + "\nViel Spaß!";

        String fenced = SvgMarkdownBlocks.fence(answer);
        assertTrue(fenced.contains("````svg\n<svg width=\"900\""));
        assertTrue(fenced.contains("</svg>\n````\n"));
        assertTrue(fenced.endsWith("Viel Spaß!"));

        String sanitized = SvgImageRenderer.sanitize(svg);
        assertFalse(sanitized.contains("<br"));
        assertTrue(sanitized.contains("<tspan x=\"125\" dy=\"-0.6em\">Mira Valen</tspan>"
                + "<tspan x=\"125\" dy=\"1.2em\">(Heldin)</tspan>"));
        assertEquals("svg", DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(sanitized.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement().getNodeName());

        BufferedImage image = new SvgImageRenderer().render(svg, 900);
        assertNotNull(image);
        assertTrue(image.getWidth() > 100 && image.getHeight() > 100);
    }

    @Test
    public void dropsExternalReferencesAndScripts() {
        String sanitized = SvgImageRenderer.sanitize("<svg xmlns=\"x\"><script>alert(1)</script>"
                + "<image href=\"https://example.invalid/a.png\"/><rect fill=\"url(https://example.invalid/p)\""
                + " onclick=\"x()\"/><use href=\"#a\"/></svg>");
        assertFalse(sanitized.contains("script"));
        assertFalse(sanitized.contains("example.invalid"));
        assertFalse(sanitized.contains("onclick"));
        assertTrue(sanitized.contains("href=\"#a\""));
    }

    @Test
    public void dropsCanvasBackgroundSoTheBubbleShowsThrough() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"100\""
                + " style=\"background:#f9f9f9\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/>"
                + "<rect x=\"40\" y=\"30\" width=\"120\" height=\"40\" fill=\"#e65100\"/></svg>";

        String sanitized = SvgImageRenderer.sanitize(svg);
        assertFalse(sanitized.contains("100%"));
        assertFalse(sanitized.contains("background"));
        assertTrue(sanitized.contains("<rect x=\"40\""));

        BufferedImage image = new SvgImageRenderer().render(svg, 200);
        assertNotNull(image);
        assertEquals(0, image.getRGB(0, 0) >>> 24);
    }

    private static String resource(String name) throws IOException {
        InputStream in = SvgImageRendererTest.class.getResourceAsStream(name);
        assertNotNull(in, name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
