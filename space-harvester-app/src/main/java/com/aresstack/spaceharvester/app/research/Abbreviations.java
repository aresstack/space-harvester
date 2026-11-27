package com.aresstack.spaceharvester.app.research;

import com.aresstack.enterpriseai.astu.acropolis.chat.linguistics.TermShape;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Abkürzungen in den Stichpunkten zum Buch, ohne Modell aufgelöst: Wörter in Akronym-Form ({@link TermShape#isAcronym})
 * werden im Fachglossar nachgeschlagen. Als aufgelöst gilt nur ein bestätigter Eintrag mit Erläuterung, dessen Begriff
 * die Abkürzung selbst ist oder sie in Klammern nennt (etwa „Abrechnungsverkehr (ABR)“); alles andere bleibt
 * unaufgelöst, und die KI soll es unverändert lassen statt eine Bedeutung zu erfinden.
 */
public final class Abbreviations {

    private Abbreviations() {
    }

    /** Eine aufgelöste Abkürzung, wie sie in den Prompt geht. */
    public record Resolved(String abbreviation, String meaning) {

        public Resolved {
            Objects.requireNonNull(abbreviation, "abbreviation");
            Objects.requireNonNull(meaning, "meaning");
        }

        /** „Abkürzung = Erläuterung“. */
        public String line() {
            return abbreviation + " = " + meaning;
        }
    }

    /** Aufgelöste und nicht aufgelöste Abkürzungen in der Reihenfolge ihres ersten Vorkommens. */
    public record Result(List<Resolved> resolved, List<String> unresolved) {

        public static final Result NONE = new Result(List.of(), List.of());

        public Result {
            resolved = List.copyOf(resolved);
            unresolved = List.copyOf(unresolved);
        }
    }

    /** Die Wörter in Akronym-Form, jedes einmal, in der Reihenfolge ihres ersten Vorkommens. */
    public static List<String> acronyms(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null) {
            return List.of();
        }
        for (String raw : text.split("[\\s,;:!?()\\[\\]{}\"'„“”‚‘’«»<>|=+*]+")) {
            String token = trim(raw);
            String[] parts = token.split("-");
            boolean whole = parts.length > 0;
            for (String part : parts) {
                whole &= acronym(part) || !part.isEmpty() && part.chars().allMatch(Character::isDigit);
            }
            if (whole && acronym(token)) {
                found.add(token);
                continue;
            }
            // Zusammensetzungen wie „OGR-Eintrag“: die Abkürzung ist ein Teil.
            for (String part : parts) {
                if (acronym(trim(part))) {
                    found.add(trim(part));
                }
            }
        }
        return List.copyOf(found);
    }

    /** Löst die Abkürzungen in {@code text} mit {@code lookup} auf (leer = nicht aufgelöst). */
    public static Result resolve(String text, Function<String, Optional<String>> lookup) {
        List<Resolved> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String acronym : acronyms(text)) {
            Optional<String> meaning = lookup.apply(acronym).map(String::strip).filter(m -> !m.isEmpty());
            if (meaning.isPresent()) {
                resolved.add(new Resolved(acronym, meaning.get()));
            } else {
                unresolved.add(acronym);
            }
        }
        return new Result(resolved, unresolved);
    }

    /**
     * Nachschlagen im Fachglossar ({@link GlossaryAccess#search}, nur bestätigte Einträge): die Erläuterung des Eintrags,
     * der die Abkürzung selbst ist, sonst „Langform – Erläuterung“ des Eintrags, der sie in Klammern nennt (im Begriff
     * oder im Wortlaut der Fundstelle). Die Begriffssuche findet „Langform (ABK)“ unter „ABK“ nicht, weil sie nur
     * Wortanfänge des Begriffs vergleicht; deshalb liest die Funktion beim ersten Aufruf einmal alle bestätigten
     * Einträge seitenweise und schlägt danach darin nach. Ein Fehler des Glossars heißt „nicht aufgelöst“.
     */
    public static Function<String, Optional<String>> glossary(GlossaryAccess glossary) {
        Objects.requireNonNull(glossary, "glossary");
        List<GlossaryAccess.Entry> confirmed = new ArrayList<>();
        boolean[] loaded = {false};
        return acronym -> {
            try {
                synchronized (confirmed) {
                    if (!loaded[0]) {
                        String cursor = null;
                        do {
                            GlossaryAccess.Page page = glossary.search(new GlossaryAccess.Search(null,
                                    ReviewStatus.CONFIRMED, null, null, null, null, GlossaryAccess.MAX_LIMIT, cursor));
                            confirmed.addAll(page.entries());
                            cursor = page.nextCursor();
                        } while (cursor != null);
                        loaded[0] = true;
                    }
                }
            } catch (RuntimeException unavailable) {
                synchronized (confirmed) {
                    confirmed.clear();
                }
                return Optional.empty();
            }
            Optional<String> named = Optional.empty();
            for (GlossaryAccess.Entry entry : confirmed) {
                Optional<String> meaning = meaning(entry, acronym);
                if (meaning.isPresent() && entry.term().strip().equalsIgnoreCase(acronym)) {
                    return meaning;
                }
                if (named.isEmpty()) {
                    named = meaning;
                }
            }
            return named;
        };
    }

    /** Was der Eintrag zur Abkürzung sagt; leer, wenn er sie nicht meint oder nichts erläutert. */
    static Optional<String> meaning(GlossaryAccess.Entry entry, String acronym) {
        String explanation = entry.explanation() == null ? "" : entry.explanation().strip();
        if (entry.status() != ReviewStatus.CONFIRMED || explanation.isEmpty()) {
            return Optional.empty();
        }
        String term = entry.term().strip();
        if (term.equalsIgnoreCase(acronym)) {
            return Optional.of(explanation);
        }
        String inBrackets = "(" + acronym + ")";
        String wording = entry.reference() == null || entry.reference().wording() == null ? ""
                : entry.reference().wording();
        if (term.contains(inBrackets) || wording.contains(inBrackets)) {
            String longForm = term.replace(inBrackets, "").strip();
            return Optional.of(longForm.isEmpty() || longForm.toLowerCase(Locale.ROOT).equals(
                    acronym.toLowerCase(Locale.ROOT)) ? explanation : longForm + " – " + explanation);
        }
        return Optional.empty();
    }

    private static boolean acronym(String token) {
        return token.length() > 1 && TermShape.letters(token) >= 2 && TermShape.isAcronym(token);
    }

    private static String trim(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && "-./_".indexOf(raw.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && "-./_".indexOf(raw.charAt(end - 1)) >= 0) {
            end--;
        }
        return raw.substring(start, end);
    }
}
