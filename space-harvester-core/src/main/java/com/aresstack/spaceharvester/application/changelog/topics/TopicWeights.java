package com.aresstack.spaceharvester.application.changelog.topics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gewichte und Schwellen der Themenbildung. Werden am Prüfstand eingestellt; jede Änderung erhöht {@link #version()},
 * damit gespeicherte Themen als veraltet gelten.
 *
 * @param version    Kennung der Einstellung, geht in {@link TopicSet#inputSha256()} ein
 * @param weights    Gewicht je Signal, nicht negativ; 0 schaltet ein Signal ab
 * @param cutoff     Bündel verschmelzen bis zu dieser kombinierten Distanz (Average Linkage)
 * @param maxBundles höchstens so viele Bündel werden gruppiert, die übrigen (leichtesten) stehen als Rest da
 */
public record TopicWeights(String version, Map<Signal, Double> weights, double cutoff, int maxBundles) {

    /**
     * Anfangswerte bis zum Prüfstand: Vektor vorn, Abschnitt und Begriffe danach; Aussagen experimentell mit 0, bis
     * der Prüfstand eine Verbesserung zeigt.
     */
    public static final TopicWeights DEFAULT = new TopicWeights("w1", Map.of(Signal.VECTOR, 0.5, Signal.SECTION, 0.2,
            Signal.TERMS, 0.2, Signal.LABELS, 0.1, Signal.REFERENCES, 0.1, Signal.STATEMENTS, 0.0), 0.55, 2_000);

    public TopicWeights {
        Objects.requireNonNull(version, "version");
        Map<Signal, Double> copy = new EnumMap<>(Signal.class);
        for (Signal signal : Signal.values()) {
            double weight = weights.getOrDefault(signal, 0.0);
            if (!(weight >= 0) || Double.isInfinite(weight)) {
                throw new IllegalArgumentException("Gewicht muss endlich und nicht negativ sein: " + signal);
            }
            copy.put(signal, weight);
        }
        weights = Map.copyOf(copy);
        if (!(cutoff >= 0 && cutoff <= 1)) {
            throw new IllegalArgumentException("cutoff muss in [0,1] liegen");
        }
        if (maxBundles < 1) {
            throw new IllegalArgumentException("maxBundles muss positiv sein");
        }
    }

    public double weight(Signal signal) {
        return weights.get(signal);
    }
}
