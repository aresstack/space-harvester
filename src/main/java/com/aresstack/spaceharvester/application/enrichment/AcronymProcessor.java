package com.aresstack.spaceharvester.application.enrichment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Findet Akronyme (zwei bis zehn Großbuchstaben oder Ziffern, beginnend mit einem Buchstaben, mindestens zwei
 * Großbuchstaben; ein angehängtes Plural-{@code s} zählt zum selben Akronym) samt Häufigkeit und erster Fundstelle.
 * Eine Ausschreibung wird nur übernommen, wenn sie direkt daneben in Klammern steht und die Anfangsbuchstaben ihrer
 * Wörter das Akronym ergeben, z. B. {@code Application Programming Interface (API)} oder
 * {@code API (Application Programming Interface)}.
 *
 * <p>Ergebnis {@code {"acronyms":[{"acronym","count","expansion","section","charStart"}]}}, aufsteigend nach
 * Akronym, höchstens {@value #MAX_ACRONYMS} Einträge.
 */
public class AcronymProcessor implements EnrichmentProcessor {

    static final int MAX_ACRONYMS = 500;

    private static final Pattern ACRONYM = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\p{Lu}[\\p{Lu}\\p{N}]{1,9})(s?)(?![\\p{L}\\p{N}])");
    private static final Pattern WORD = Pattern.compile("[^\\s-]+");
    private static final Pattern AFTER = Pattern.compile(" *\\(([^()\\n]{3,120})\\)");

    @Override
    public String id() {
        return "acronyms";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        Map<String, Found> found = new TreeMap<>();
        Matcher matcher = ACRONYM.matcher(text.text());
        while (matcher.find()) {
            String acronym = matcher.group(1);
            if (acronym.chars().filter(Character::isUpperCase).count() < 2) {
                continue;
            }
            Found entry = found.computeIfAbsent(acronym, key -> new Found(matcher.start()));
            entry.count++;
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
                    .append(",\"charStart\":").append(value.firstOffset).append('}');
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    /** Ausschreibung in Klammern direkt nach oder direkt vor dem Akronym, wenn die Anfangsbuchstaben passen. */
    static String expansion(String text, String acronym, int start, int end) {
        Matcher after = AFTER.matcher(text).region(end, text.length());
        if (after.lookingAt() && matches(acronym, after.group(1))) {
            return after.group(1).trim();
        }
        int open = start - 1;
        while (open >= 0 && text.charAt(open) == ' ') {
            open--;
        }
        int close = end;
        while (close < text.length() && text.charAt(close) == ' ') {
            close++;
        }
        if (open >= 0 && text.charAt(open) == '(' && close < text.length() && text.charAt(close) == ')') {
            String candidate = lastWords(text.substring(Math.max(0, open - 120), open),
                    acronym.replaceAll("\\P{L}", "").length());
            if (candidate != null && matches(acronym, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Die letzten {@code count} Wörter (durch Leerzeichen oder Bindestrich getrennt) im Originalwortlaut. */
    private static String lastWords(String phrase, int count) {
        List<Integer> starts = new ArrayList<>();
        Matcher word = WORD.matcher(phrase);
        while (word.find()) {
            starts.add(word.start());
        }
        if (count == 0 || starts.size() < count) {
            return null;
        }
        return phrase.substring(starts.get(starts.size() - count)).trim();
    }

    private static boolean matches(String acronym, String phrase) {
        String letters = acronym.replaceAll("\\P{L}", "").toLowerCase(Locale.ROOT);
        StringBuilder initials = new StringBuilder();
        for (String word : phrase.trim().split("[\\s-]+")) {
            if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                initials.append(Character.toLowerCase(word.charAt(0)));
            }
        }
        return !letters.isEmpty() && initials.toString().equals(letters);
    }

    private static final class Found {

        final int firstOffset;
        int count;
        String expansion;

        Found(int firstOffset) {
            this.firstOffset = firstOffset;
        }
    }
}
