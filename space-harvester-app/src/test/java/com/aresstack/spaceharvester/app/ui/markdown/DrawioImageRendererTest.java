package com.aresstack.spaceharvester.app.ui.markdown;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** draw.io in der Chat-Antwort (Angelo 24.11.2026): gezeichnet mit drawio-java wie Seiten und Exporte. */
class DrawioImageRendererTest {

    static final String MXFILE = "<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>";

    private final MarkdownTheme theme = MarkdownTheme.forBubble(new Color(20, 120, 110), Color.WHITE);

    @Test
    void recognizesDrawioBlocksByLanguageOrRootElement() {
        assertTrue(DrawioImageRenderer.isDrawioBlock("drawio", MXFILE));
        assertTrue(DrawioImageRenderer.isDrawioBlock("xml", "<?xml version=\"1.0\"?>\n" + MXFILE));
        assertTrue(DrawioImageRenderer.isDrawioBlock("", "<mxGraphModel><root/></mxGraphModel>"));
        assertFalse(DrawioImageRenderer.isDrawioBlock("xml", "<project/>"));
        assertFalse(DrawioImageRenderer.isDrawioBlock("java", MXFILE));
    }

    @Test
    void drawsTheDiagramWithDrawioJava() {
        BufferedImage image = new DrawioImageRenderer(new SvgImageRenderer()).render(MXFILE, 400);

        assertNotNull(image);
        assertTrue(image.getWidth() > 10 && image.getHeight() > 10);
    }

    @Test
    void aBrokenDiagramFailsWithTheReasonOfTheLibrary() {
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> new DrawioImageRenderer(new SvgImageRenderer()).render("<mxfile>kaputt", 400));

        assertTrue(failed.getMessage().startsWith("draw.io nicht darstellbar"), failed.getMessage());
    }

    @Test
    void theAnswerShowsADrawioBlockAsDiagramInsteadOfCode() {
        Container rendered = new FlexmarkSwingRenderer(theme, url -> { }, null)
                .render("Hier:\n\n```xml\n" + MXFILE + "\n```\n", true);

        assertEquals(1, count(rendered, MermaidDiagramPanel.class));
        assertEquals(0, count(rendered, CodeBlockPanel.class));
    }

    private static int count(Container root, Class<?> type) {
        int n = 0;
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                n++;
            }
            if (child instanceof Container container) {
                n += count(container, type);
            }
        }
        return n;
    }
}
