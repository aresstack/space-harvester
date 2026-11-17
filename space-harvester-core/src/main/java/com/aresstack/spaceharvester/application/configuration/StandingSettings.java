package com.aresstack.spaceharvester.application.configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stand und Status-quo-Sicht (ADR 0032, FL6) unter {@code space-harvester.statements.standing}: Server und App lesen
 * dieselben Schlüssel. Die Wörter, an denen Labels und Titel (eigener Titel und Titel der Vorfahren) eine Seite als
 * überholt oder als Entwurf kennzeichnen; verglichen wird je ganzes Wort ohne Groß- und Kleinschreibung. Das ist die
 * einzige Stelle der Wortlisten. Beide Loader lesen eine fehlende oder leere Liste als Vorgabe; {@value #NONE} allein
 * heißt ausdrücklich „keine Wörter“ ({@link #words}).
 *
 * @param outdatedWords Wörter für überholte Seiten, klein geschrieben, ohne Doppelte
 * @param draftWords    Wörter für Entwürfe, klein geschrieben, ohne Doppelte
 */
public record StandingSettings(List<String> outdatedWords, List<String> draftWords) {

    /** {@code statements.standing.outdated-words}: Liste, durch Kommas getrennt oder als {@code key[0]}, …. */
    public static final String OUTDATED_WORDS = "statements.standing.outdated-words";
    /** {@code statements.standing.draft-words}: Liste, durch Kommas getrennt oder als {@code key[0]}, …. */
    public static final String DRAFT_WORDS = "statements.standing.draft-words";

    public static final List<String> DEFAULT_OUTDATED_WORDS = List.of("archiv", "archiviert", "veraltet", "obsolet",
            "obsolete", "deprecated", "abgelöst", "historie", "historisch");
    public static final List<String> DEFAULT_DRAFT_WORDS = List.of("entwurf", "draft", "konzept", "planung",
            "vorschlag");

    /** Steht allein in einer Liste für „keine Wörter“; leer oder fehlend heißt dagegen Vorgabe. */
    public static final String NONE = "-";

    public static final StandingSettings DEFAULT = new StandingSettings(DEFAULT_OUTDATED_WORDS, DEFAULT_DRAFT_WORDS);

    public StandingSettings {
        outdatedWords = normalized(outdatedWords, "outdatedWords");
        draftWords = normalized(draftWords, "draftWords");
    }

    /**
     * Die gelesene Liste, wie beide Loader sie deuten: fehlend oder leer heißt {@code fallback}, {@value #NONE} allein
     * heißt keine Wörter, sonst die Wörter.
     *
     * @throws IllegalArgumentException wenn {@value #NONE} neben anderen Wörtern steht
     */
    public static List<String> words(List<String> raw, List<String> fallback) {
        List<String> words = raw == null ? List.of() : raw.stream().filter(Objects::nonNull).map(String::strip)
                .filter(word -> !word.isEmpty()).toList();
        if (words.isEmpty()) {
            return fallback;
        }
        if (words.contains(NONE)) {
            if (words.size() > 1) {
                throw new IllegalArgumentException("„" + NONE + "“ steht allein für keine Wörter");
            }
            return List.of();
        }
        return words;
    }

    /** Die Liste, wie sie in die Konfiguration geschrieben wird: keine Wörter als {@value #NONE}. */
    public static List<String> written(List<String> words) {
        return words.isEmpty() ? List.of(NONE) : words;
    }

    private static List<String> normalized(List<String> words, String name) {
        List<String> unique = new ArrayList<>();
        for (String word : Objects.requireNonNull(words, name)) {
            String lower = word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) {
                continue;
            }
            if (!lower.chars().allMatch(Character::isLetterOrDigit)) {
                throw new IllegalArgumentException("Nur ganze Wörter aus Buchstaben und Ziffern: " + lower);
            }
            if (!unique.contains(lower)) {
                unique.add(lower);
            }
        }
        return List.copyOf(unique);
    }
}
