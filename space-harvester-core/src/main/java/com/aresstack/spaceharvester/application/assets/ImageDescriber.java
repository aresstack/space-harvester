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
}
