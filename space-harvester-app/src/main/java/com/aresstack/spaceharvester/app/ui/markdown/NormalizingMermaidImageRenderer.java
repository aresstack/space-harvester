package com.aresstack.spaceharvester.app.ui.markdown;

import java.awt.image.BufferedImage;

/**
 * Fehlertolerante Render-Schicht für Mermaid aus Modellantworten (aus askai-java8
 * {@code NormalizingMermaidImageRenderer}).
 *
 * <p>Der ursprüngliche Diagrammtext wird nie verändert — {@link MermaidDiagramPanel} behält ihn wörtlich für
 * „Mermaid-Code kopieren“. Nur die <em>Render-Eingabe</em> wird kompatibel gemacht: Liefert
 * {@link MermaidRenderingSourceNormalizer} eine geänderte Kopie (klar reparierbare, nicht zitierte
 * Rechteck-Beschriftungen eines Flussdiagramms), wird diese gerendert.
 *
 * <p>Weil die Bibliothek einen teuren GraalJS-Lauf macht und Parserfehler als {@code null}-Bild statt als
 * Ausnahme meldet, wird vorab normalisiert (kein verschwendeter erster Lauf mit bekannt kaputtem Code).
 * Scheitert auch die normalisierte Kopie, folgt genau ein Rückfall auf das Original, damit der echte Fehler
 * in der Oberfläche erscheint. Es gibt höchstens einen Rückfall, nie eine Wiederholungsschleife.
 */
final class NormalizingMermaidImageRenderer implements MermaidImageRenderer {

    private final MermaidImageRenderer delegate;
    private final MermaidRenderingSourceNormalizer normalizer;

    NormalizingMermaidImageRenderer(MermaidImageRenderer delegate, MermaidRenderingSourceNormalizer normalizer) {
        if (delegate == null || normalizer == null) {
            throw new IllegalArgumentException("delegate and normalizer must not be null");
        }
        this.delegate = delegate;
        this.normalizer = normalizer;
    }

    @Override
    public BufferedImage render(String diagramCode, int width) {
        String normalized = normalizer.normalize(diagramCode);
        boolean unchanged = normalized == null ? diagramCode == null : normalized.equals(diagramCode);
        if (unchanged) {
            return delegate.render(diagramCode, width); // nichts zu reparieren: das Original genau einmal rendern
        }

        BufferedImage rendered = delegate.render(normalized, width);
        if (rendered != null) {
            return rendered;
        }

        // Die normalisierte Kopie scheiterte ebenfalls; einmal auf das Original zurückfallen, damit der
        // eigentliche Fehler sichtbar wird.
        return delegate.render(diagramCode, width);
    }
}
