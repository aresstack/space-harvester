package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Regeln der Begriffsableitung für das Fachglossar (ADR 0032, FL5b) unter {@code space-harvester.glossary.rules.*}:
 * Server und App lesen dieselben Schlüssel. Die Wortlisten selbst stehen fest im Core ({@code glossar-regeln.tsv});
 * hier kommen je Art Wörter hinzu oder fallen weg. Beide Loader lesen eine fehlende oder leere Liste als „nichts“.
 *
 * @param maxTerms höchstens so viele Vorschläge der Regel je Geltungsbereich und Lauf, nach Wichtigkeit; mindestens 1
 * @param added    je Art zusätzliche Wörter, klein geschrieben, ohne Doppelte
 * @param excluded je Art ausgeschlossene Wörter der festen Liste, klein geschrieben, ohne Doppelte
 */
public record GlossaryRuleSettings(int maxTerms, Map<Kind, List<String>> added, Map<Kind, List<String>> excluded) {

    /** {@code glossary.rules.max-terms}. */
    public static final String MAX_TERMS = "glossary.rules.max-terms";
    public static final int DEFAULT_MAX_TERMS = 500;
    /** Kürzester Kopf, der auch als Ende eines zusammengeschriebenen Worts gilt (Auflage des Architekten). */
    public static final int MIN_COMPOUND_HEAD = 5;

    public static final GlossaryRuleSettings DEFAULT = new GlossaryRuleSettings(DEFAULT_MAX_TERMS, Map.of(), Map.of());

    /**
     * Art einer Wortliste.
     *
     * <ul>
     *   <li>{@link #TECHNICAL_HEADS}: technischer Kopf, gilt auch als Ende eines zusammengeschriebenen Worts
     *       („Antragsdatenbank“); mindestens {@value #MIN_COMPOUND_HEAD} Buchstaben</li>
     *   <li>{@link #TECHNICAL_WORDS}: technisches Wort, nur als ganzes Wort oder nach Bindestrich/Leerzeichen</li>
     *   <li>{@link #ROLE_HEADS}, {@link #ROLE_WORDS}: dasselbe für Rollen und Stellen</li>
     * </ul>
     */
    public enum Kind {
        TECHNICAL_HEADS("technical-heads", "technik", true),
        TECHNICAL_WORDS("technical-words", "technik-wort", false),
        ROLE_HEADS("role-heads", "rolle", true),
        ROLE_WORDS("role-words", "rolle-wort", false);

        private final String key;
        private final String resourceKind;
        private final boolean compound;

        Kind(String key, String resourceKind, boolean compound) {
            this.key = key;
            this.resourceKind = resourceKind;
            this.compound = compound;
        }

        /** Teil des Schlüssels, etwa {@code technical-heads}. */
        public String key() {
            return key;
        }

        /** Art in der festen Wortliste, etwa {@code technik}. */
        public String resourceKind() {
            return resourceKind;
        }

        /** Ob ein Wort dieser Art auch als Ende eines zusammengeschriebenen Worts gilt. */
        public boolean compound() {
            return compound;
        }

        /** {@code glossary.rules.<art>.add}: Liste, durch Kommas getrennt oder als {@code key[0]}, …. */
        public String addKey() {
            return "glossary.rules." + key + ".add";
        }

        /** {@code glossary.rules.<art>.exclude}: Liste, durch Kommas getrennt oder als {@code key[0]}, …. */
        public String excludeKey() {
            return "glossary.rules." + key + ".exclude";
        }

        /** Die Art zu einer Bezeichnung der festen Wortliste oder {@code null}. */
        public static Kind ofResource(String resourceKind) {
            for (Kind kind : values()) {
                if (kind.resourceKind.equals(resourceKind)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public GlossaryRuleSettings {
        if (maxTerms < 1) {
            throw new IllegalArgumentException("Erwartet mindestens 1 Vorschlag je Lauf");
        }
        added = lists(added, true);
        excluded = lists(excluded, false);
    }

    /** Zusätzliche Wörter einer Art. */
    public List<String> added(Kind kind) {
        return added.getOrDefault(kind, List.of());
    }

    /** Ausgeschlossene Wörter einer Art. */
    public List<String> excluded(Kind kind) {
        return excluded.getOrDefault(kind, List.of());
    }

    /** Dieselben Regeln mit anderen Wörtern einer Art. */
    public GlossaryRuleSettings with(Kind kind, List<String> add, List<String> exclude) {
        Map<Kind, List<String>> nextAdded = new EnumMap<>(Kind.class);
        nextAdded.putAll(added);
        nextAdded.put(kind, add);
        Map<Kind, List<String>> nextExcluded = new EnumMap<>(Kind.class);
        nextExcluded.putAll(excluded);
        nextExcluded.put(kind, exclude);
        return new GlossaryRuleSettings(maxTerms, nextAdded, nextExcluded);
    }

    /** Dieselben Regeln mit anderer Höchstzahl. */
    public GlossaryRuleSettings withMaxTerms(int value) {
        return new GlossaryRuleSettings(value, added, excluded);
    }

    /** Ein Wort wie im Glossar normalisiert ({@link TermText#normalize}, die eine Stelle für Wortlisten und Suchwerte). */
    public static String normalize(String word) {
        return TermText.normalize(word);
    }

    /**
     * Die brauchbaren Wörter einer Liste aus der Konfiguration; jedes unbrauchbare Wort meldet {@code problem} mit
     * Begründung, die übrigen gelten.
     *
     * @param adding ob es Wörter zum Hinzufügen sind (dann gilt die Regel für kurze Köpfe)
     */
    public static List<String> accepted(Kind kind, boolean adding, List<String> words, Consumer<String> problem) {
        List<String> result = new ArrayList<>();
        for (String word : words) {
            try {
                result.addAll(lists(Map.of(kind, java.util.Collections.singletonList(word)), adding)
                        .getOrDefault(kind, List.of()));
            } catch (IllegalArgumentException e) {
                problem.accept(e.getMessage());
            }
        }
        return result;
    }

    private static Map<Kind, List<String>> lists(Map<Kind, List<String>> lists, boolean adding) {
        Map<Kind, List<String>> result = new EnumMap<>(Kind.class);
        Objects.requireNonNull(lists, "lists").forEach((kind, words) -> {
            List<String> unique = new ArrayList<>();
            for (String word : Objects.requireNonNull(words, "words")) {
                String normalized = word == null ? "" : normalize(word);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (!normalized.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == ' ' || c == '-')) {
                    throw new IllegalArgumentException("Nur Wörter aus Buchstaben, Ziffern, Leerzeichen und "
                            + "Bindestrich: " + normalized);
                }
                if (adding && kind.compound() && normalized.codePointCount(0, normalized.length())
                        < MIN_COMPOUND_HEAD) {
                    throw new IllegalArgumentException("Kopf unter " + MIN_COMPOUND_HEAD + " Buchstaben gilt nur als "
                            + "ganzes Wort, unter " + (kind == Kind.TECHNICAL_HEADS ? Kind.TECHNICAL_WORDS
                            : Kind.ROLE_WORDS).addKey() + " eintragen: " + normalized);
                }
                if (!unique.contains(normalized)) {
                    unique.add(normalized);
                }
            }
            if (!unique.isEmpty()) {
                result.put(kind, List.copyOf(unique));
            }
        });
        return Map.copyOf(result);
    }
}
