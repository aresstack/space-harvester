package com.aresstack.enterpriseai.embedding.openai;

/**
 * Wie der Adapter einen Port-Batch auf HTTP-Requests abbildet.
 */
public enum EmbeddingInputMode {

    /**
     * Default: ein Request je Text mit {@code "input": "<text>"}. Die einzige Form, die die OpenAPI-Typdefinition
     * ({@code input: string}) deckt. N Texte ergeben N Requests in Eingabereihenfolge.
     */
    SINGLE_STRING,

    /**
     * UNVERIFIED: ein Request je Teil-Batch mit {@code "input": ["a", "b", ...]}. Die OpenAPI-Beschreibung
     * erwähnt Arrays, die Typdefinition nicht; am realen Backend noch nicht getestet. Erst aktivieren, wenn ein
     * praktischer Test bestätigt, dass das Backend Arrays annimmt und je Eingabe einen Eintrag liefert.
     */
    ARRAY_UNVERIFIED
}
