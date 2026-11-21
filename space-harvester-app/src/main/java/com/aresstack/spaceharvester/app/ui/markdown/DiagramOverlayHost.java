package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.JComponent;

/**
 * Eine Fläche, die Inhalte als schließbares Comic-Overlay über sich legt (wie askai-java8
 * {@code OllamaChatPanel.showTranscriptOverlay}/{@code showDiagramOverlay}). Mermaid-Diagramme der Antwortblasen
 * öffnen ihren Betrachter darüber; Werkzeugknöpfe (etwa Mindmaps des Agent-Modus) nutzen denselben Weg.
 */
public interface DiagramOverlayHost {

    /** Zeigt {@code content} mit Titel als Overlay; ein vorheriges Overlay wird ersetzt. Nur auf dem EDT. */
    void showTranscriptOverlay(JComponent content, String title);

    /** Zeigt einen Mermaid-Quelltext im vollen Betrachter ({@link MermaidViewerPanel}) als Overlay. */
    void showDiagramOverlay(String mermaidSource, String title);

    /** Schließt das offene Overlay (ohne Wirkung, wenn keines offen ist). */
    void closeTranscriptOverlay();
}
