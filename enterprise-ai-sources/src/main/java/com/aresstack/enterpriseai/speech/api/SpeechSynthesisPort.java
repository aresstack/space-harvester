package com.aresstack.enterpriseai.speech.api;

/**
 * Sprachausgabe eines Modellkatalogs: {@link #synthesize} blockiert (HTTP, Modell laden) und läuft deshalb nie auf
 * dem Swing-EDT. Das Modell ist die Kennung innerhalb von {@link #catalogId()} (ohne Katalogpräfix).
 */
public interface SpeechSynthesisPort {

    /** Der Katalog, aus dem die Modelle dieses Ports stammen (Präfix der Auswahl {@code model.tts}). */
    String catalogId();

    /**
     * Spricht {@code text} mit genau dieser Stimme. Es gibt bewusst keine Variante ohne Stimme und keine Stimme im
     * Port selbst: die Stimme löst allein der Aufrufer auf (eine Quelle), damit kein Pfad still auf eine andere fällt.
     * Modelle mit mehreren Sprechern wählen ohne Stimme je Anfrage selbst, sodass Absätze derselben Antwort verschieden
     * klingen können. Ports ohne Sprecherwahl (eine lokale Stimme ist genau ein Sprecher) übergehen {@code voice}.
     *
     * @param modelId Kennung des TTS-Modells innerhalb von {@link #catalogId()}
     * @param voice   die Stimme des Modells, unverändert (Groß-/Kleinschreibung zählt); {@code null} = ohne Stimme
     * @param text    reiner Text ohne Markdown, nicht leer
     * @return das gesprochene Audio
     * @throws SpeechSynthesisException wenn der Dienst nicht erreichbar ist oder das Modell nicht sprechen kann
     */
    SynthesizedSpeech synthesize(String modelId, String voice, String text) throws SpeechSynthesisException;
}
