package com.aresstack.spaceharvester.document;

import com.aresstack.Mermaid;

import java.awt.image.BufferedImage;

/**
 * Bindet mermaid-java (GraalJS + Batik, ohne Browser, ohne Netz) an den Render-Port der Oberfläche
 * (aus askai-java8 {@code AresStackMermaidImageRenderer}).
 */
public final class AresStackMermaidImageRenderer implements MermaidImageRenderer {

    @Override
    public BufferedImage render(String diagramCode, int width) {
        return Mermaid.renderToImage(diagramCode, width);
    }
}
