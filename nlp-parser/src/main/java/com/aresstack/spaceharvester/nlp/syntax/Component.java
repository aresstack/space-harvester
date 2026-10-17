package com.aresstack.spaceharvester.nlp.syntax;

import java.util.Objects;

/**
 * Herkunft einer Analyseschicht: welches Werkzeug mit welchem Modell Sätze, Tokens, Wortarten, Grundformen oder
 * Abhängigkeiten geliefert hat. {@code gold} steht für von Hand annotierte Daten.
 *
 * @param layer       Schicht, z. B. {@code sentences}, {@code tokens}, {@code upos}, {@code lemma}, {@code dependencies}
 * @param tool        Werkzeug mit Version, z. B. {@code maltparser 1.9.2}
 * @param model       Modellkennung mit Version oder {@code null}
 * @param modelSha256 SHA-256 der Modelldatei oder {@code null}
 */
public record Component(String layer, String tool, String model, String modelSha256) {

    public Component {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(tool, "tool");
    }

    /** Von Hand annotierte Schicht, etwa aus einer Treebank oder einem Testkorpus. */
    public static Component gold(String layer, String source) {
        return new Component(layer, "gold", source, null);
    }
}
