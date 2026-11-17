package com.aresstack.spaceharvester.application.simplification;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anweisung, Eingabe und strenges Lesen der Antwort der Vorstufe „Sätze vereinfachen“ (ADR 0032, FL3). Rechnet nur,
 * ruft kein Modell und speichert nichts.
 *
 * <p><b>Version 1</b> ist genau die gemessene Anweisung ({@code anweisung-v1.txt}, Messung vom 17.11.2026) mit dem
 * Antwortformat {@code <Nummer>|<Kennzeichen>|<Satz>}. Jede Änderung an Anweisung, Eingabe oder Format ist eine neue
 * {@link #PROMPT_VERSION} und braucht eine neue Messung auf dem Prüfstand.
 *
 * <p>Eingabe: eine Zeile {@code <Nummer>|<Satz>} je Satz des Abschnitts, Kontext (Überschriften, Kennungszeilen) und
 * gewählte Sätze in Textreihenfolge; die Nummer ist der Satzindex der Seite plus 1. Der Seitentext ist Material, keine
 * Anweisung: Er geht nur als Benutzernachricht hinaus, und von der Antwort zählt nur, was dem Format genau entspricht.
 *
 * <p>Lesen der Antwort:
 * <ul>
 *     <li>Eine Zeile gilt nur, wenn sie genau {@code <Nummer>|<T, B, P oder V>|<Satz>} ist und die Nummer zu einem
 *     gewählten Satz gehört. Zeilen zu Kontextnummern werden still übergangen, alle anderen gezählt und verworfen.</li>
 *     <li>Wortabgleich: Jedes Inhaltswort des vereinfachten Satzes muss im Abschnitt stehen (Originalsatz, übrige Sätze,
 *     Kontext), wörtlich oder als Form desselben Stamms ({@link #sameStem}); sonst wird der Satz verworfen, weil das
 *     Modell etwas ergänzt hat.</li>
 *     <li>Weglassung: Ein Inhaltswort des Originalsatzes, das in keinem seiner vereinfachten Sätze vorkommt, macht den
 *     Satz „unvollständig“; sein Original bleibt beim Zusammenführen ohnehin maßgeblich.</li>
 * </ul>
 */
public final class SentenceSimplifier {

    /** Version von Anweisung, Eingabe und Antwortformat; Teil des Schlüssels der Ablage. */
    public static final String PROMPT_VERSION = "1";
    /** Längster vereinfachter Satz, der übernommen wird. */
    static final int MAX_SENTENCE_CHARS = 1_000;

    private static final String INSTRUCTIONS = resource("anweisung-v1.txt");
    private static final Pattern LINE = Pattern.compile("(\\d{1,6})\\|([A-Z])\\|(.+)");
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}]*");
    /** Endungen, die beim Stammvergleich wegfallen, die längste zuerst. */
    private static final List<String> SUFFIXES = List.of("ungen", "ung", "ern", "eln", "en", "er", "el", "et", "st",
            "es", "em", "e", "n", "s", "t");
    /** Funktionswörter ab vier Buchstaben: Artikel, Pronomen, Hilfs- und Modalverben, Bindewörter, Präpositionen. */
    private static final Set<String> FUNCTION_WORDS = Set.of("eine", "einen", "einem", "einer", "eines", "dieser",
            "diese", "dieses", "diesen", "diesem", "jeder", "jede", "jedes", "jeden", "jedem", "alle", "allen", "aller",
            "alles", "keine", "keinen", "keinem", "keiner", "keines", "nicht", "auch", "noch", "schon", "erst", "dann",
            "wenn", "sofern", "sobald", "falls", "dass", "weil", "oder", "aber", "sondern", "sowie", "denn", "damit",
            "nach", "über", "unter", "durch", "gegen", "ohne", "beim", "zum", "zur", "vom", "seit", "bis", "innerhalb",
            "außerhalb", "wird", "werden", "wurde", "wurden", "worden", "sind", "sein", "seine", "seinen", "seinem",
            "seiner", "seines", "ihre", "ihren", "ihrem", "ihrer", "ihres", "haben", "hatte", "hatten", "muss",
            "müssen", "musste", "soll", "sollen", "sollte", "kann", "können", "konnte", "darf", "dürfen", "durfte",
            "dies", "deren", "dessen", "welche", "welcher", "welches", "einzelne", "jeweils", "sich", "hier", "dort",
            "dabei", "dafür", "dazu", "davon", "daher", "jedoch");

    private SentenceSimplifier() {
    }

    /** Die Anweisung der aktuellen {@link #PROMPT_VERSION}, unverändert. */
    public static String instructions() {
        return INSTRUCTIONS;
    }

    /** Die Eingabe für einen Abschnitt: Kontext und gewählte Sätze in Textreihenfolge, je Zeile {@code Nummer|Satz}. */
    public static String input(SimplificationSection section) {
        Map<Integer, String> lines = new TreeMap<>();
        section.context().forEach(sentence -> lines.put(sentence.index(), sentence.text()));
        section.sentences().forEach(sentence -> lines.put(sentence.index(), sentence.text()));
        StringBuilder input = new StringBuilder();
        lines.forEach((index, text) -> input.append(index + 1).append('|').append(text).append('\n'));
        return input.toString();
    }

    /**
     * Liest die Antwort zu einem Abschnitt streng.
     *
     * @return je gewähltem Satz die übernommenen vereinfachten Sätze (auch leer) und die Zähler
     */
    public static Parsed parse(SimplificationSection section, String response) {
        Map<Integer, SimplificationSection.Sentence> chosen = new LinkedHashMap<>();
        section.sentences().forEach(sentence -> chosen.put(sentence.index(), sentence));
        Set<Integer> context = new HashSet<>();
        section.context().forEach(sentence -> context.add(sentence.index()));
        List<String> vocabulary = new ArrayList<>();
        section.context().forEach(sentence -> vocabulary.addAll(contentWords(sentence.text())));
        section.sentences().forEach(sentence -> vocabulary.addAll(contentWords(sentence.text())));

        Map<Integer, List<SimplifiedSentence>> result = new LinkedHashMap<>();
        chosen.keySet().forEach(index -> result.put(index, new ArrayList<>()));
        int rejected = 0;
        int added = 0;
        for (String raw : (response == null ? "" : response).split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                rejected++;
                continue;
            }
            int index = Integer.parseInt(matcher.group(1)) - 1;
            SimplifiedSentence.Flag flag = SimplifiedSentence.Flag.of(matcher.group(2));
            String text = matcher.group(3).replaceAll("\\s+", " ").strip();
            if (context.contains(index) && !chosen.containsKey(index)) {
                continue;
            }
            if (!chosen.containsKey(index) || flag == null || text.isEmpty() || text.length() > MAX_SENTENCE_CHARS
                    || text.contains("|")) {
                rejected++;
                continue;
            }
            if (!contentWords(text).stream().allMatch(word -> known(word, vocabulary))) {
                added++;
                continue;
            }
            result.get(index).add(new SimplifiedSentence(index, flag, text));
        }
        Set<Integer> incomplete = new LinkedHashSet<>();
        result.forEach((index, sentences) -> {
            List<String> produced = new ArrayList<>();
            sentences.forEach(sentence -> produced.addAll(contentWords(sentence.text())));
            if (!contentWords(chosen.get(index).text()).stream().allMatch(word -> known(word, produced))) {
                incomplete.add(index);
            }
        });
        Map<Integer, List<SimplifiedSentence>> frozen = new LinkedHashMap<>();
        result.forEach((index, sentences) -> frozen.put(index, List.copyOf(sentences)));
        return new Parsed(java.util.Collections.unmodifiableMap(frozen), rejected, added, Set.copyOf(incomplete));
    }

    /**
     * Ergebnis von {@link #parse}.
     *
     * @param sentences  je gewähltem Satz die übernommenen Sätze, in Antwortreihenfolge; leer, wenn keiner galt
     * @param rejected   Zeilen außerhalb des Formats oder zu einer fremden Nummer
     * @param added      formgerechte Sätze, die verworfen wurden, weil sie Wörter außerhalb des Abschnitts enthalten
     * @param incomplete gewählte Sätze, von deren Inhaltswörtern eines in keinem ihrer vereinfachten Sätze vorkommt
     */
    public record Parsed(Map<Integer, List<SimplifiedSentence>> sentences, int rejected, int added,
                         Set<Integer> incomplete) {
    }

    /** Inhaltswörter: ab vier Buchstaben, ohne Funktionswörter, klein geschrieben. */
    static List<String> contentWords(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.GERMAN);
            if (word.length() >= 4 && !FUNCTION_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static boolean known(String word, List<String> vocabulary) {
        return vocabulary.stream().anyMatch(other -> other.equals(word) || sameStem(word, other));
    }

    /**
     * Ob zwei Wörter Formen desselben Stamms sind: nach Abschneiden einer Endung ({@code -ung}, {@code -en},
     * {@code -t} …) und eines vorangestellten {@code ge-} gleich, oder der kürzere Stamm (mindestens vier Buchstaben)
     * steht am Anfang oder Ende des längeren („Prüfung“/„prüft“, „fordert“/„zurückgefordert“). Starke Formen mit
     * anderem Vokal („gibt“/„gegeben“) gelten nicht als gleich; im Zweifel wird der Satz verworfen.
     */
    static boolean sameStem(String a, String b) {
        String x = stem(a);
        String y = stem(b);
        if (x.equals(y)) {
            return true;
        }
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        return shorter.length() >= 4 && (longer.startsWith(shorter) || longer.endsWith(shorter));
    }

    private static String stem(String word) {
        String stem = word.toLowerCase(Locale.GERMAN);
        if (stem.startsWith("ge") && stem.length() > 6) {
            stem = stem.substring(2);
        }
        for (String suffix : SUFFIXES.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            if (stem.endsWith(suffix) && stem.length() - suffix.length() >= 3) {
                return stem.substring(0, stem.length() - suffix.length());
            }
        }
        return stem;
    }

    private static String resource(String name) {
        try (InputStream input = SentenceSimplifier.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException(name + " fehlt");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
