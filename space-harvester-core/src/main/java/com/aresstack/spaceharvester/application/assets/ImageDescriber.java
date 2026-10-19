package com.aresstack.spaceharvester.application.assets;

/**
 * Beschreibt ein Bild in Worten, typischerweise mit einem Vision-Modell (#19). Optional: ohne Umsetzung liefert der
 * Dienst keine Beschreibungen. Eine Umsetzung bekommt nur die Bildbytes, nie Seiteninhalte oder Zugangsdaten des
 * Aufrufers, und meldet Fehler mit {@link ImageDescriptionException}, ohne Bildinhalte in der Meldung.
 */
public interface ImageDescriber {

    /** Kennung des Beschreibers, z. B. {@code openai-compatible}. */
    String id();

    /** Modell und Version, z. B. {@code gpt-4o-2024-08-06}; ein anderer Wert macht vorhandene Beschreibungen alt. */
    String modelVersion();

    /** @return die Beschreibung als Klartext */
    String describe(byte[] image, String mediaType);

    /**
     * Ob gerade ein Modell eingestellt ist (ADR 0021): Ein Beschreiber, der der Konfiguration zur Laufzeit folgt, gibt
     * es auch ohne Modell; dann beschreibt er nichts, und {@link #modelVersion()} hat keine Bedeutung.
     */
    default boolean available() {
        return true;
    }

    /**
     * Ob Bilder dieser Quelle an das Modell gehen dürfen: Ein Beschreiber über einen externen Dienst beschreibt nur
     * Bilder aus Quellen in {@code external-processing-sources} (ADR 0011); das Leserecht genügt dafür nicht.
     */
    default boolean mayDescribe(String sourceId) {
        return true;
    }
}
