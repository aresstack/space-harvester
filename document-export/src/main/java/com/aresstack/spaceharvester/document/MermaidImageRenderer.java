package com.aresstack.spaceharvester.document;

import java.awt.image.BufferedImage;

/**
 * Rendert Mermaid-Quelltext in ein Bild, ohne die Render-Bibliothek in die Oberfläche zu tragen
 * (aus askai-java8 {@code ui.markdown.MermaidImageRenderer}).
 */
public interface MermaidImageRenderer {

    /** @return das gerenderte Diagramm oder {@code null}, wenn die Quelle nicht gerendert werden konnte */
    BufferedImage render(String diagramCode, int width);
}
