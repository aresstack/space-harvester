package com.aresstack.spaceharvester.application.standing;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wörter, an denen Labels und Titel eine Seite als überholt oder als Entwurf kennzeichnen (ADR 0032, FL6). Verglichen
 * wird je ganzes Wort ohne Groß- und Kleinschreibung; „Archivierung“ ist kein „Archiv“.
 *
 * @param outdated Wörter für überholte Seiten
 * @param draft    Wörter für Entwürfe
 */
public record StandingWords(Set<String> outdated, Set<String> draft) {

    /** Vorgabe. */
    public static final StandingWords DEFAULT = new StandingWords(
            Set.of("archiv", "archiviert", "veraltet", "obsolet", "obsolete", "deprecated", "abgelöst", "historie",
                    "historisch"),
            Set.of("entwurf", "draft", "konzept", "planung", "vorschlag"));

    public StandingWords {
        outdated = normalized(Objects.requireNonNull(outdated, "outdated"));
        draft = normalized(Objects.requireNonNull(draft, "draft"));
    }

    /** Ob das Wort eine überholte Seite kennzeichnet. */
    public boolean outdated(String word) {
        return outdated.contains(lower(word));
    }

    /** Ob das Wort einen Entwurf kennzeichnet. */
    public boolean draft(String word) {
        return draft.contains(lower(word));
    }

    private static Set<String> normalized(Set<String> words) {
        return words.stream().filter(Objects::nonNull).map(StandingWords::lower).filter(word -> !word.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    static String lower(String word) {
        return word == null ? "" : word.strip().toLowerCase(Locale.ROOT);
    }
}
