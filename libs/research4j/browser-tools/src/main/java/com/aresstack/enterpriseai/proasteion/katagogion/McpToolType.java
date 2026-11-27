package com.aresstack.enterpriseai.proasteion.katagogion;

/**
 * Die kleine, flache Menge an Parametertypen. Bewusst minimal, damit auch kleine Modelle Tools zuverlässig
 * aufrufen.
 *
 * <p>Herkunft: askai-java8 {@code McpToolType}.
 */
public enum McpToolType {
    STRING,
    INTEGER,
    BOOLEAN,
    ENUM,
    /** Eine Liste von Texten, JSON Schema {@code array} mit {@code items: string}. */
    STRING_ARRAY
}
