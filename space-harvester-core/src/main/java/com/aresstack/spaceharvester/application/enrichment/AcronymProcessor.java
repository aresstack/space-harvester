package com.aresstack.spaceharvester.application.enrichment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Findet Akronyme (zwei bis zehn Großbuchstaben oder Ziffern, beginnend mit einem Buchstaben, mindestens zwei
 * Großbuchstaben; ein angehängtes Plural-{@code s} zählt zum selben Akronym) samt Häufigkeit und erster Fundstelle.
 * Eine Ausschreibung wird nur übernommen, wenn sie direkt daneben in Klammern steht und das Akronym nach der Regel
 * des EAC in ihr ausgerichtet werden kann ({@link #expansion}), z. B. {@code Application Programming Interface (API)},
 * {@code API (Application Programming Interface)} oder {@code Zentrale Datenverarbeitung (ZDV)}. Seit Version 3 (wie
 * EAC) statt nur Wortanfängen.
 *
 * <p>Ergebnis {@code {"acronyms":[{"acronym","count","expansion","section","charStart","occurrences":[{"start",
 * "end"}]}]}}, aufsteigend nach Akronym, höchstens {@value #MAX_ACRONYMS} Einträge. {@code charStart} ist die erste
 * Fundstelle, {@code occurrences} (seit Version 2, #115) nennt jede Fundstelle in Textreihenfolge; {@code end} schließt
 * ein Plural-{@code s} ein. Alle Positionen sind Zeichenpositionen (UTF-16 wie {@link String#substring}) in
 * {@link PageText#text()} derselben Eingabe ({@code inputSha256} des Ergebnisses). Über alle Akronyme zusammen werden
 * nur die ersten {@value #MAX_OCCURRENCES} Fundstellen im Text behalten und geschrieben; {@code count} zählt weiter
 * alle Vorkommen, eine Warnung nennt die Kürzung. Gelesen wird das Ergebnis mit {@link PageAcronyms}.
 */
public class AcronymProcessor implements EnrichmentProcessor {

    static final int MAX_ACRONYMS = 500;
    static final int MAX_OCCURRENCES = 5000;

    private static final Pattern ACRONYM = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\p{Lu}[\\p{Lu}\\p{N}]{1,9})(s?)(?![\\p{L}\\p{N}])");
    private static final Pattern AFTER = Pattern.compile(" *\\(([^()\\n]{3,120})\\)");

    @Override
    public String id() {
        return "acronyms";
    }

    @Override
    public String version() {
        return "3";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        Map<String, Found> found = new TreeMap<>();
        int retained = 0;
        boolean truncated = false;
        Matcher matcher = ACRONYM.matcher(text.text());
        while (matcher.find()) {
            String acronym = matcher.group(1);
            if (acronym.chars().filter(Character::isUpperCase).count() < 2) {
                continue;
            }
            Found entry = found.computeIfAbsent(acronym, key -> new Found(matcher.start()));
            entry.count++;
            // Nur so viele Fundstellen behalten, wie geschrieben werden; gezählt wird weiter alles.
            if (retained < MAX_OCCURRENCES) {
                entry.occurrences.add(new int[] {matcher.start(), matcher.end()});
                retained++;
            } else {
                truncated = true;
            }
            if (entry.expansion == null) {
                entry.expansion = expansion(text.text(), acronym, matcher.start(), matcher.end());
            }
        }
        List<String> warnings = new ArrayList<>();
        StringBuilder json = new StringBuilder("{\"acronyms\":[");
        int written = 0;
        for (Map.Entry<String, Found> entry : found.entrySet()) {
            if (written == MAX_ACRONYMS) {
                warnings.add("Nur die ersten " + MAX_ACRONYMS + " von " + found.size() + " Akronymen");
                break;
            }
            Found value = entry.getValue();
            PageText.Section section = text.sectionAt(value.firstOffset);
            json.append(written++ == 0 ? "" : ",")
                    .append("{\"acronym\":").append(JsonText.quote(entry.getKey()))
                    .append(",\"count\":").append(value.count)
                    .append(",\"expansion\":").append(value.expansion == null ? "null" : JsonText.quote(value.expansion))
                    .append(",\"section\":").append(section == null ? "null" : JsonText.quote(section.heading()))
                    .append(",\"charStart\":").append(value.firstOffset).append(",\"occurrences\":[");
            for (int i = 0; i < value.occurrences.size(); i++) {
                int[] occurrence = value.occurrences.get(i);
                json.append(i == 0 ? "" : ",").append("{\"start\":").append(occurrence[0])
                        .append(",\"end\":").append(occurrence[1]).append('}');
            }
            json.append("]}");
        }
        if (truncated) {
            warnings.add("Fundstellen nur für die ersten " + MAX_OCCURRENCES + " Vorkommen im Text");
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    /**
     * Jedes Vorkommen eines Akronyms in Textreihenfolge, nach denselben Regeln wie {@link #process}; für den
     * Begriffsindex ({@code terms}, #119), der alle Fundstellen ohne Kürzung braucht.
     */
    public static List<Mention> mentions(String text) {
        List<Mention> mentions = new ArrayList<>();
        Matcher matcher = ACRONYM.matcher(text);
        while (matcher.find()) {
            String acronym = matcher.group(1);
            if (acronym.chars().filter(Character::isUpperCase).count() >= 2) {
                mentions.add(new Mention(acronym, matcher.start(), matcher.end()));
            }
        }
        return mentions;
    }

    /**
     * Ein Vorkommen eines Akronyms.
     *
     * @param acronym Schreibweise ohne Plural-{@code s}
     * @param start   erste Zeichenposition
     * @param end     Position nach dem Wort, einschließlich eines Plural-{@code s}
     */
    public record Mention(String acronym, int start, int end) {
    }

    /**
     * Ausschreibung in Klammern direkt nach oder direkt vor dem Akronym, ausgerichtet wie im EAC
     * ({@code LongFormMatcher}, Schwartz und Hearst 2003): Jeder Buchstabe und jede Ziffer des Akronyms kommt in der
     * Ausschreibung in derselben Reihenfolge vor, das erste Zeichen an einem Wortanfang; so passen auch Komposita
     * („Zentrale Datenverarbeitung (ZDV)“) und Füllwörter („Bundesamt für Sicherheit in der Informationstechnik
     * (BSI)“). In der Klammer zählt nur der Text bis zum ersten Komma oder Semikolon, gesucht wird nur im selben Satz.
     * Die Ausschreibung ist immer ein wörtliches Stück des Textes, länger als das Akronym, mit Kleinbuchstaben und ohne
     * das Akronym selbst.
     */
    public static String expansion(String text, String acronym, int start, int end) {
        Matcher after = AFTER.matcher(text).region(end, text.length());
        if (after.lookingAt()) {
            String candidate = firstChunk(after.group(1));
            if (candidate != null && words(candidate) <= maxWords(characters(acronym))
                    && longFormStart(acronym, candidate) == 0 && isLongForm(candidate, acronym)) {
                return collapse(candidate);
            }
        }
        int open = start - 1;
        while (open >= 0 && text.charAt(open) == ' ') {
            open--;
        }
        int close = end;
        while (close < text.length() && text.charAt(close) == ' ') {
            close++;
        }
        if (open < 0 || text.charAt(open) != '(' || close >= text.length()
                || "),;".indexOf(text.charAt(close)) < 0) {
            return null;
        }
        int candidateEnd = open;
        while (candidateEnd > 0 && Character.isWhitespace(text.charAt(candidateEnd - 1))) {
            candidateEnd--;
        }
        int sentence = sentenceStart(text, candidateEnd);
        int candidateStart = wordsBack(text, sentence, candidateEnd, maxWords(characters(acronym)));
        String candidate = text.substring(candidateStart, candidateEnd);
        int at = longFormStart(acronym, candidate);
        if (at < 0) {
            return null;
        }
        String longForm = candidate.substring(at);
        return isLongForm(longForm, acronym) ? collapse(longForm) : null;
    }

    /** Höchstens so viele Wörter hat eine Ausschreibung: {@code min(n + 5, 2n)} für {@code n} Buchstaben und Ziffern. */
    static int maxWords(int characters) {
        return Math.min(characters + 5, characters * 2);
    }

    /**
     * Anfang des kürzesten Endstücks von {@code candidate}, das eine Ausschreibung von {@code shortForm} ist, oder
     * {@code -1}; von hinten gelesen wie im EAC.
     */
    static int longFormStart(String shortForm, String candidate) {
        int s = shortForm.length() - 1;
        int l = candidate.length() - 1;
        boolean any = false;
        for (; s >= 0; s--) {
            char c = Character.toLowerCase(shortForm.charAt(s));
            if (!Character.isLetterOrDigit(c)) {
                continue;
            }
            any = true;
            while (l >= 0 && (Character.toLowerCase(candidate.charAt(l)) != c
                    || isFirst(shortForm, s) && l > 0 && Character.isLetterOrDigit(candidate.charAt(l - 1)))) {
                l--;
            }
            if (l < 0) {
                return -1;
            }
            l--;
        }
        return any ? l + 1 : -1;
    }

    private static boolean isFirst(String shortForm, int s) {
        for (int i = 0; i < s; i++) {
            if (Character.isLetterOrDigit(shortForm.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int characters(String shortForm) {
        int count = 0;
        for (int i = 0; i < shortForm.length(); i++) {
            if (Character.isLetterOrDigit(shortForm.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** Der Klammerinhalt bis zum ersten Komma oder Semikolon, getrimmt; {@code null}, wenn leer. */
    private static String firstChunk(String inner) {
        int stop = inner.length();
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == ',' || inner.charAt(i) == ';') {
                stop = i;
                break;
            }
        }
        String chunk = inner.substring(0, stop).trim();
        return chunk.isEmpty() ? null : chunk;
    }

    /** Nach dem letzten Satzende ({@code .!?} vor Leerraum) oder Zeilenumbruch vor {@code end}, höchstens 120 zurück. */
    private static int sentenceStart(String text, int end) {
        int limit = Math.max(0, end - 120);
        for (int i = end - 1; i >= limit; i--) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '.' || c == '!' || c == '?') && i + 1 < end
                    && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return limit;
    }

    /** Anfang der letzten {@code words} Wörter vor {@code end}, nicht vor {@code limit}. */
    private static int wordsBack(String text, int limit, int end, int words) {
        int i = end;
        int count = 0;
        while (i > limit && count < words) {
            while (i > limit && Character.isWhitespace(text.charAt(i - 1))) {
                i--;
            }
            while (i > limit && !Character.isWhitespace(text.charAt(i - 1))) {
                i--;
            }
            count++;
        }
        return i;
    }

    private static int words(String value) {
        return value.trim().split("\\s+").length;
    }

    /** Länger als das Akronym, mit Kleinbuchstaben, ohne Klammern und ohne das Akronym selbst. */
    private static boolean isLongForm(String longForm, String shortForm) {
        String value = longForm.trim();
        if (value.length() <= shortForm.length() || value.indexOf('(') >= 0 || value.indexOf(')') >= 0) {
            return false;
        }
        return value.chars().anyMatch(Character::isLowerCase) && !value.contains(shortForm);
    }

    private static String collapse(String value) {
        return value.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static final class Found {

        final int firstOffset;
        final List<int[]> occurrences = new ArrayList<>();
        int count;
        String expansion;

        Found(int firstOffset) {
            this.firstOffset = firstOffset;
        }
    }
}
