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
 * <p>Eingabe wie in der Messung: eine Zeile {@code <Nummer> <Satz>} je Zeile des Abschnitts (übrige Sätze,
 * Überschriften, Kennungszeilen und gewählte Sätze in Textreihenfolge), die Nummern je Aufruf fortlaufend ab 1; zurück
 * auf den Satzindex der Seite bildet {@link #parse}. Der Seitentext ist Material, keine Anweisung: Er geht nur als
 * Benutzernachricht hinaus, und von der Antwort zählt nur, was dem Format genau entspricht.
 *
 * <p>Lesen der Antwort:
 * <ul>
 *     <li>Eine Zeile gilt nur, wenn sie genau {@code <Nummer>|<T, B, P oder V>|<Satz>} ist und die Nummer zu einem
 *     gewählten Satz gehört. Zeilen zu nicht gewählten Nummern werden still übergangen, alle anderen gezählt und
 *     verworfen.</li>
 *     <li>Wortabgleich: Jedes Inhaltswort des vereinfachten Satzes (ab vier Buchstaben ohne Funktionswörter, dazu
 *     Kürzel mit mindestens zwei Großbuchstaben wie „IT“), jede Ziffernfolge, jede Verneinung (nicht, kein…, nie,
 *     niemals, weder) und jedes Modalverb (je Grundform) muss im Abschnitt stehen, Inhaltswörter wörtlich oder als
 *     Form desselben Stamms ({@link #sameStem}); sonst wird der Satz verworfen, weil das Modell etwas ergänzt hat.</li>
 *     <li>Weglassung: Ein Inhaltswort, eine Ziffernfolge, eine Verneinung oder ein Modalverb des Originalsatzes, das in
 *     keinem seiner vereinfachten Sätze vorkommt, macht den Satz „unvollständig“; sein Original bleibt beim
 *     Zusammenführen ohnehin maßgeblich.</li>
 * </ul>
 *
 * <p>Bekannte Grenzen, die die Messung mit echtem Modell beziffern soll:
 * <ul>
 *     <li>Der Wortabgleich verwirft vorsichtig: Mehrzahl mit Umlaut („Verträge“/„Vertrag“) und starke Formen gelten
 *     nicht als gleicher Stamm; dann bleibt das Original maßgeblich.</li>
 *     <li>Eine Verneinung oder ein Modalverb gilt als vorhanden, wenn es irgendwo im Abschnitt steht, nicht nur im
 *     Originalsatz (nötig für Stichpunkte unter einer verneinten Kennungszeile); eine verschobene Verneinung fällt hier
 *     also nicht auf. Dagegen schützt das Zusammenführen: Merkmale kommen aus dem Originalsatz, sobald er eine Aussage
 *     zum selben Verb hat.</li>
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
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    /** Verneinungen; {@code kein…} in allen Formen. */
    private static final Set<String> NEGATIONS = Set.of("nicht", "nie", "niemals", "weder");
    /** Modalverben im Präsens und Präteritum, auf die Grundform. */
    private static final Map<String, String> MODALS = modals();
    /** Was vor einem gleichen Stamm stehen darf: Verbzusatz oder untrennbare Vorsilbe, auch mit „ge“ danach. */
    private static final List<String> PREFIXES = List.of("zurück", "ab", "an", "auf", "aus", "ein", "vor", "fest",
            "be", "ver", "er", "ent", "zer", "ge");
    /**
     * Trennbare Verbzusätze: stehen auch allein am Satzende („stellt … fest“) und nehmen „ge“ und „zu“ in die Mitte
     * („festgestellt“, „vorzulegen“).
     */
    private static final List<String> PARTICLES = List.of("zurück", "ab", "an", "auf", "aus", "ein", "vor", "fest",
            "durch", "mit", "nach", "weg", "zu");
    /** Umstandswörter der Reihenfolge, die nur gelten, wenn der Abschnitt das passende Bindewort hat. */
    private static final Map<String, Set<String>> CONNECTIVES = Map.of("danach", Set.of("nachdem", "sobald"),
            "anschließend", Set.of("nachdem", "sobald"), "zuvor", Set.of("bevor"), "vorher", Set.of("bevor"));
    /** Formen von „sein“ und „haben“ im Präsens, die mit „zu“ und Infinitiv eine Pflicht ausdrücken („ist … zu prüfen“). */
    private static final Set<String> DUTY = Set.of("ist", "sind", "hat", "haben");
    /** Endungen, die beim Stammvergleich wegfallen, die längste zuerst. */
    private static final List<String> SUFFIXES = List.of("ungen", "ung", "keit", "heit", "ern", "eln", "en", "er", "el",
            "et", "st", "es", "em", "e", "n", "s", "t");
    private static final List<String> SUFFIXES_LONGEST_FIRST = SUFFIXES.stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).toList();
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

    /**
     * Die Eingabe für einen Abschnitt: alle Zeilen in Textreihenfolge, je Zeile {@code Nummer Satz}, die Nummern
     * fortlaufend ab 1.
     */
    public static String input(SimplificationSection section) {
        StringBuilder input = new StringBuilder();
        int number = 1;
        for (SimplificationSection.Sentence line : section.lines()) {
            input.append(number++).append(' ').append(line.text()).append('\n');
        }
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
        List<SimplificationSection.Sentence> lines = section.lines();
        Words vocabulary = new Words();
        lines.forEach(line -> vocabulary.add(line.text()));

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
            int number = Integer.parseInt(matcher.group(1));
            SimplifiedSentence.Flag flag = SimplifiedSentence.Flag.of(matcher.group(2));
            String text = matcher.group(3).replaceAll("\\s+", " ").strip();
            if (number < 1 || number > lines.size()) {
                rejected++;
                continue;
            }
            int index = lines.get(number - 1).index();
            if (!chosen.containsKey(index)) {
                continue;
            }
            if (flag == null || text.isEmpty() || text.length() > MAX_SENTENCE_CHARS || text.contains("|")) {
                rejected++;
                continue;
            }
            if (!vocabulary.covers(new Words().add(text))) {
                added++;
                continue;
            }
            result.get(index).add(new SimplifiedSentence(index, flag, text));
        }
        Set<Integer> incomplete = new LinkedHashSet<>();
        Set<Integer> lost = new LinkedHashSet<>();
        result.forEach((index, sentences) -> {
            Words produced = new Words();
            sentences.forEach(sentence -> produced.add(sentence.text()));
            Words original = new Words().add(chosen.get(index).text());
            if (!produced.covers(original)) {
                incomplete.add(index);
            }
            if (!sentences.isEmpty() && !produced.keepsPolarity(original)) {
                // Verneinung oder Modalverb fehlt: die Sätze sagten womöglich das Gegenteil, also keiner gilt
                lost.add(index);
                sentences.clear();
            }
        });
        Map<Integer, List<SimplifiedSentence>> frozen = new LinkedHashMap<>();
        result.forEach((index, sentences) -> frozen.put(index, List.copyOf(sentences)));
        return new Parsed(java.util.Collections.unmodifiableMap(frozen), rejected, added, Set.copyOf(incomplete),
                Set.copyOf(lost));
    }

    /**
     * Ergebnis von {@link #parse}.
     *
     * @param sentences  je gewähltem Satz die übernommenen Sätze, in Antwortreihenfolge; leer, wenn keiner galt
     * @param rejected   Zeilen außerhalb des Formats oder zu einer fremden Nummer
     * @param added      formgerechte Sätze, die verworfen wurden, weil sie Wörter außerhalb des Abschnitts enthalten
     * @param incomplete gewählte Sätze, von deren Inhaltswörtern eines in keinem ihrer vereinfachten Sätze vorkommt
     * @param lost       gewählte Sätze mit Verneinung oder Modalverb, die in keinem ihrer vereinfachten Sätze mehr
     *                   vorkommt; deren vereinfachte Sätze sind verworfen (leere Liste), damit keine Aussage mit dem
     *                   Gegenteil entsteht
     */
    public record Parsed(Map<Integer, List<SimplifiedSentence>> sentences, int rejected, int added,
                         Set<Integer> incomplete, Set<Integer> lost) {
    }

    /**
     * Wortabgleich für einen Satz, den ein Modell aus einem anderen ableitet (ADR 0032, FL3 und FL4): ob jedes
     * Inhaltswort, jede Ziffernfolge, jede Verneinung und jedes Modalverb von {@code derived} in {@code source} steht,
     * Inhaltswörter auch als Form desselben Stamms. Derselbe Abgleich wie bei {@link #parse}; der Prüfschritt nimmt ihn
     * für Korrekturen, mit dem Originalsatz als {@code source}.
     */
    public static boolean derivable(String source, String derived) {
        return new Words().add(source).covers(new Words().add(derived));
    }

    /** Ob der Text eine Verneinung enthält (nicht, kein…, nie, niemals, weder). */
    public static boolean negated(String text) {
        return !new Words().add(text).negations.isEmpty();
    }

    /**
     * Inhaltswörter: ab vier Buchstaben ohne Funktionswörter, dazu kürzere Kürzel mit mindestens zwei Großbuchstaben
     * („IT“, „ZfA“); klein geschrieben.
     */
    static List<String> contentWords(String text) {
        return rawContentWords(text).stream().map(word -> word.toLowerCase(Locale.GERMAN)).toList();
    }

    /** Wie {@link #contentWords}, in der Schreibung des Textes. */
    private static List<String> rawContentWords(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String word = raw.toLowerCase(Locale.GERMAN);
            if (word.length() >= 4 ? !FUNCTION_WORDS.contains(word) && !negation(word) && !MODALS.containsKey(word)
                    : raw.chars().filter(Character::isUpperCase).count() >= 2) {
                words.add(raw);
            }
        }
        return words;
    }

    /**
     * Ob ein Wort durch ein anderes gedeckt ist: gleich (ohne Groß- und Kleinschreibung) oder gleicher Stamm nach
     * {@link #sameStem}. Ein Verbzusatz oder eine Vorsilbe davor gilt nur zwischen klein geschriebenen Wörtern, also
     * Verbformen („stellt“/„einstellt“); bei Nomen ist das Wort davor neue Information („Teil“/„Vorteil“).
     */
    static boolean related(String word, String known) {
        String a = word.toLowerCase(Locale.GERMAN);
        String b = known.toLowerCase(Locale.GERMAN);
        if (a.equals(b)) {
            return true;
        }
        if (Character.isUpperCase(word.charAt(0)) && agent(a) && !agent(b)) {
            // „Prüfer“ nur, wenn im Abschnitt selbst eine Form auf -er steht, nicht schon bei „prüfen“/„Prüfung“
            return false;
        }
        boolean verbs = Character.isLowerCase(word.charAt(0)) && Character.isLowerCase(known.charAt(0));
        for (String x : variants(a)) {
            for (String y : variants(b)) {
                if (sameForm(x, y) || verbs && sameStem(x, y)) {
                    return true;
                }
            }
        }
        // abgetrennter Verbzusatz („stellt … fest“) zu einer Verbform mit diesem Zusatz („feststellt“)
        return verbs && PARTICLES.contains(a) && b.startsWith(a) && b.length() >= a.length() + 3;
    }

    /** Ob ein Wort auf -er, -ers, -ern, -erin oder -erinnen endet (etwa ein Handelnder: „Prüfer“, „Prüferin“). */
    private static boolean agent(String word) {
        return word.length() >= 5 && (word.endsWith("er") || word.endsWith("ers") || word.endsWith("ern")
                || word.endsWith("erin") || word.endsWith("erinnen"));
    }

    /** Das Wort und, nach einem trennbaren Verbzusatz, ohne eingeschobenes „ge“ oder „zu“ („festgestellt“ → „feststellt“). */
    private static List<String> variants(String word) {
        List<String> variants = new ArrayList<>(List.of(word));
        for (String particle : PARTICLES) {
            for (String infix : List.of("ge", "zu")) {
                String head = particle + infix;
                if (word.startsWith(head) && word.length() - head.length() >= 4) {
                    variants.add(particle + word.substring(head.length()));
                }
            }
        }
        return variants;
    }

    /** Ob ein Satz eine Pflicht mit „sein“/„haben“ und „zu“-Infinitiv ausdrückt („ist … vorzulegen“, „hat … zu zahlen“). */
    private static boolean duty(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        if (words.stream().noneMatch(word -> DUTY.contains(word.toLowerCase(Locale.GERMAN)))) {
            return false;
        }
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i).toLowerCase(Locale.GERMAN);
            if ("zu".equals(word) && i + 1 < words.size() && infinitive(words.get(i + 1))) {
                return true;
            }
            for (String particle : PARTICLES) {
                if (word.startsWith(particle + "zu") && infinitive(words.get(i))
                        && word.length() - particle.length() - 2 >= 4) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ein Infinitiv nach „zu“: klein geschrieben, kein Funktionswort, mindestens fünf Buchstaben, auf -en. */
    private static boolean infinitive(String raw) {
        String word = raw.toLowerCase(Locale.GERMAN);
        return Character.isLowerCase(raw.charAt(0)) && word.length() >= 5 && word.endsWith("en")
                && !FUNCTION_WORDS.contains(word);
    }

    private static boolean negation(String word) {
        return NEGATIONS.contains(word) || word.startsWith("kein") && word.length() <= 7;
    }

    /** Inhaltswörter, Ziffernfolgen, Verneinungen und Modalverben (Grundform) eines oder mehrerer Texte. */
    private static final class Words {
        private final List<String> content = new ArrayList<>();
        private final Set<String> numbers = new HashSet<>();
        private final Set<String> negations = new HashSet<>();
        private final Set<String> modals = new HashSet<>();
        /** Modalverben, die der Text ohne Modalverb ausdrückt („ist … vorzulegen“ = muss); decken nur, fehlen nie. */
        private final Set<String> implied = new HashSet<>();

        Words add(String text) {
            content.addAll(rawContentWords(text));
            if (duty(text)) {
                implied.add("müssen");
            }
            Matcher number = NUMBER.matcher(text);
            while (number.find()) {
                numbers.add(number.group());
            }
            Matcher word = WORD.matcher(text);
            while (word.find()) {
                String lower = word.group().toLowerCase(Locale.GERMAN);
                if (negation(lower)) {
                    negations.add(lower.startsWith("kein") ? "kein" : lower);
                }
                String modal = MODALS.get(lower);
                if (modal != null) {
                    modals.add(modal);
                }
            }
            return this;
        }

        /** Ob jede Verneinung und jedes Modalverb aus {@code other} hier vorkommt. */
        boolean keepsPolarity(Words other) {
            return negations.containsAll(other.negations) && hasModals(other.modals);
        }

        private boolean hasModals(Set<String> wanted) {
            return wanted.stream().allMatch(modal -> modals.contains(modal) || implied.contains(modal));
        }

        /** Ob alles aus {@code other} hier vorkommt, Inhaltswörter auch als Form desselben Stamms. */
        boolean covers(Words other) {
            return numbers.containsAll(other.numbers) && negations.containsAll(other.negations)
                    && hasModals(other.modals) && other.content.stream().allMatch(word -> covered(word));
        }

        private boolean covered(String word) {
            Set<String> licence = CONNECTIVES.get(word.toLowerCase(Locale.GERMAN));
            if (licence != null) {
                return content.stream().anyMatch(known -> licence.contains(known.toLowerCase(Locale.GERMAN)));
            }
            return content.stream().anyMatch(known -> related(word, known));
        }
    }

    private static Map<String, String> modals() {
        Map<String, String> modals = new java.util.HashMap<>();
        for (String[] row : new String[][] {{"müssen", "muss", "musst", "müsst", "musste", "mussten", "musstest"},
                {"sollen", "soll", "sollst", "sollt", "sollte", "sollten", "solltest"},
                {"können", "kann", "kannst", "könnt", "konnte", "konnten", "konntest"},
                {"dürfen", "darf", "darfst", "dürft", "durfte", "durften", "durftest"}}) {
            for (String form : row) {
                modals.put(form, row[0]);
            }
        }
        return Map.copyOf(modals);
    }

    /**
     * Ob zwei Wörter Formen desselben Stamms sind: nach Abschneiden einer Endung ({@code -ung}, {@code -en},
     * {@code -t} …) und eines vorangestellten {@code ge-} gleich, oder der längere Stamm ist der kürzere mit einem
     * Verbzusatz oder einer untrennbaren Vorsilbe davor, auch mit „ge“ danach („prüfung“/„prüft“,
     * „fordert“/„zurückgefordert“). Ein anderes Wort davor ist neue Information („vertrag“/„mietvertrag“), ebenso
     * starke Formen mit anderem Vokal („gibt“/„gegeben“); im Zweifel wird der Satz verworfen. Ob die Vorsilbe gilt,
     * hängt an der Wortart: {@link #related}.
     */
    static boolean sameStem(String a, String b) {
        String x = stem(a);
        String y = stem(b);
        if (x.equals(y)) {
            return true;
        }
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        if (shorter.length() < 3 || !longer.endsWith(shorter)) {
            return false;
        }
        String rest = longer.substring(0, longer.length() - shorter.length());
        for (String prefix : PREFIXES) {
            String folded = fold(prefix);
            if (rest.equals(folded) || rest.equals(folded + "ge")) {
                return true;
            }
        }
        return false;
    }

    private static String stem(String word) {
        return stems(word).get(1);
    }

    /**
     * Das Wort ohne vorangestelltes „ge“, nach einer und nach zwei abgeschnittenen Endungen („artikels“, „artikel“,
     * „artik“), Umlaute wie Grundvokale („verträg“ → „vertrag“).
     */
    private static List<String> stems(String word) {
        String stem = word.toLowerCase(Locale.GERMAN);
        if (stem.startsWith("ge") && stem.length() > 6) {
            stem = stem.substring(2);
        }
        List<String> stems = new ArrayList<>(List.of(fold(stem)));
        for (int round = 0; round < 2; round++) {
            for (String suffix : SUFFIXES_LONGEST_FIRST) {
                if (stem.endsWith(suffix) && stem.length() - suffix.length() >= 3) {
                    stem = stem.substring(0, stem.length() - suffix.length());
                    break;
                }
            }
            stems.add(fold(stem));
        }
        return stems;
    }

    private static String fold(String word) {
        return word.replace("ä", "a").replace("ö", "o").replace("ü", "u");
    }

    /** Gleicher Stamm nach einer Endung, oder ein gemeinsamer Stamm ab vier Buchstaben nach bis zu zwei Endungen. */
    private static boolean sameForm(String a, String b) {
        List<String> x = stems(a);
        List<String> y = stems(b);
        if (x.get(1).equals(y.get(1))) {
            return true;
        }
        return x.stream().anyMatch(stem -> stem.length() >= 4 && y.contains(stem));
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
