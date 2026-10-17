package com.aresstack.spaceharvester.nlp.extraction;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mögliche Kasus einer Nominalgruppe aus den Formen ihrer Artikel, Pronomen und Possessiva, für den Fall, dass die
 * Vorverarbeitung keine Kasusmerkmale liefert. Ergebnis ist eine Menge: {@code der} kann Nominativ, Dativ oder
 * Genitiv sein, nur {@code dem}, {@code den Beleg} (mit Akkusativ/Dativ) usw. grenzen ein.
 */
final class CaseHints {

    static final String NOM = "Nom";
    static final String ACC = "Acc";
    static final String DAT = "Dat";
    static final String GEN = "Gen";

    private static final Map<String, Set<String>> FORMS = Map.ofEntries(
            Map.entry("der", Set.of(NOM, DAT, GEN)),
            Map.entry("die", Set.of(NOM, ACC)),
            Map.entry("das", Set.of(NOM, ACC)),
            Map.entry("den", Set.of(ACC, DAT)),
            Map.entry("dem", Set.of(DAT)),
            Map.entry("des", Set.of(GEN)),
            Map.entry("ein", Set.of(NOM, ACC)),
            Map.entry("eine", Set.of(NOM, ACC)),
            Map.entry("einen", Set.of(ACC)),
            Map.entry("einem", Set.of(DAT)),
            Map.entry("einer", Set.of(DAT, GEN)),
            Map.entry("eines", Set.of(GEN)),
            Map.entry("ich", Set.of(NOM)),
            Map.entry("du", Set.of(NOM)),
            Map.entry("er", Set.of(NOM)),
            Map.entry("wir", Set.of(NOM)),
            Map.entry("man", Set.of(NOM)),
            Map.entry("sie", Set.of(NOM, ACC)),
            Map.entry("es", Set.of(NOM, ACC)),
            Map.entry("ihr", Set.of(NOM, DAT)),
            Map.entry("mich", Set.of(ACC)),
            Map.entry("dich", Set.of(ACC)),
            Map.entry("ihn", Set.of(ACC)),
            Map.entry("uns", Set.of(ACC, DAT)),
            Map.entry("euch", Set.of(ACC, DAT)),
            Map.entry("mir", Set.of(DAT)),
            Map.entry("dir", Set.of(DAT)),
            Map.entry("ihm", Set.of(DAT)),
            Map.entry("ihnen", Set.of(DAT)));

    /** Stämme, deren Endung wie beim bestimmten Artikel den Kasus eingrenzt. */
    private static final Set<String> STEMS = Set.of(
            "kein", "mein", "dein", "sein", "ihr", "unser", "euer", "eur", "dies", "jed", "jen", "welch", "manch",
            "all");

    private CaseHints() {
    }

    /** Mögliche Kasus der Form {@code form} oder leer, wenn sie nichts verrät. */
    static Set<String> of(String form) {
        String lower = form.toLowerCase(Locale.ROOT);
        Set<String> known = FORMS.get(lower);
        if (known != null) {
            return known;
        }
        for (String stem : STEMS) {
            if (lower.startsWith(stem)) {
                switch (lower.substring(stem.length())) {
                    case "", "e":
                        return Set.of(NOM, ACC);
                    case "en":
                        return Set.of(ACC, DAT);
                    case "em":
                        return Set.of(DAT);
                    case "er":
                        return Set.of(NOM, DAT, GEN);
                    case "es":
                        return Set.of(NOM, ACC, GEN);
                    default:
                        break;
                }
            }
        }
        return Set.of();
    }
}
