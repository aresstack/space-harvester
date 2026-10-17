package com.aresstack.spaceharvester.nlp.opennlp;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verschmelzungen aus Präposition und Artikel, die UD German GSD als Mehrworttoken in zwei syntaktische Wörter
 * teilt ({@code am} = {@code an dem}); die Liste umfasst alle Fälle im GSD-Trainingsteil. Das Parsermodell erwartet
 * die geteilten Wörter.
 */
final class GermanContractions {

    private static final Map<String, List<String>> PARTS = Map.ofEntries(
            Map.entry("im", List.of("in", "dem")),
            Map.entry("zum", List.of("zu", "dem")),
            Map.entry("zur", List.of("zu", "der")),
            Map.entry("am", List.of("an", "dem")),
            Map.entry("vom", List.of("von", "dem")),
            Map.entry("beim", List.of("bei", "dem")),
            Map.entry("ins", List.of("in", "das")),
            Map.entry("ans", List.of("an", "das")),
            Map.entry("ums", List.of("um", "das")),
            Map.entry("aufs", List.of("auf", "das")),
            Map.entry("übers", List.of("über", "das")));

    private GermanContractions() {
    }

    /** Die Teile von {@code form} in der Schreibung des Texts ({@code Am} → {@code An}, {@code dem}) oder {@code null}. */
    static List<String> split(String form) {
        List<String> parts = PARTS.get(form.toLowerCase(Locale.ROOT));
        if (parts == null) {
            return null;
        }
        if (Character.isUpperCase(form.charAt(0))) {
            String first = parts.get(0);
            return List.of(Character.toUpperCase(first.charAt(0)) + first.substring(1), parts.get(1));
        }
        return parts;
    }
}
