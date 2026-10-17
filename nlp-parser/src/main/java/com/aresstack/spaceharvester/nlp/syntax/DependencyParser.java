package com.aresstack.spaceharvester.nlp.syntax;

/**
 * Port: bestimmt für einen vorverarbeiteten Satz Kopf und UD-Relation jedes Worts. Implementierungen sind
 * threadsicher und laden ihr Modell einmal, nie je Satz.
 */
public interface DependencyParser {

    /** Derselbe Satz mit {@link Token#head()} und {@link Token#deprel()} für jedes Wort. */
    Sentence parse(Sentence tagged);

    /** Werkzeug und Modell der Schicht {@code dependencies}. */
    Component component();
}
