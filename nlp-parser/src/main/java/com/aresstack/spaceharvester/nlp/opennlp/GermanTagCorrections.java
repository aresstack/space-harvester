package com.aresstack.spaceharvester.nlp.opennlp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministische Korrekturen der OpenNLP-Wortarten und -Grundformen, nach dem Tagger und vor dem Parser (Nachtrag
 * Ursachenmatrix zu ADR 0024). Korrigiert wird nur, was nie richtig sein kann: Formen geschlossener Wortklassen
 * (Artikel, Pronomen, Konjunktionen, Postpositionen) sind im Deutschen nie ein Verb. Offene Wortklassen bleiben, wie
 * der Tagger sie liefert; kein Lexikon, kein Nachtrainieren.
 */
final class GermanTagCorrections {

    /** Artikel- und Pronomenformen: vor einem Nomen Artikel ({@code DET}), sonst Pronomen ({@code PRON}). */
    private static final Set<String> DETERMINER_OR_PRONOUN = Set.of(
            "der", "die", "das", "dem", "den", "des",
            "welcher", "welche", "welches", "welchem", "welchen",
            "dieser", "diese", "dieses", "diesem", "diesen",
            "jener", "jene", "jenes", "jenem", "jenen",
            "dessen", "deren", "derer");
    private static final Set<String> PRONOUNS = Set.of(
            "ich", "du", "er", "sie", "es", "wir", "ihr", "sich", "mich", "dich", "mir", "dir", "uns", "euch", "ihm",
            "ihn", "ihnen");
    private static final Set<String> CONJUNCTIONS = Set.of("und", "oder", "aber", "sondern", "sowie");
    /** Nachgestellte Teile zweiteiliger Präpositionen ({@code über … hinweg}, {@code durch … hindurch}). */
    private static final Set<String> POSTPOSITIONS = Set.of("hinweg", "hindurch", "entlang", "zufolge", "halber");
    private static final Set<String> VERBAL = Set.of("VERB", "AUX");
    private static final Set<String> NOMINAL_FOLLOWERS = Set.of("NOUN", "PROPN", "ADJ", "NUM");

    /** Abtrennbare Verbzusätze, die im zu-Infinitiv vor {@code zu} stehen ({@code bereitzustellen}). */
    /** Form → Grundform der starken Verben (ADR 0032). */
    private static final Map<String, String> STRONG = strongVerbs();
    private static final List<String> PARTICLES = List.of("ab", "an", "auf", "aus", "bei", "bereit", "dar", "ein",
            "fest", "fort", "frei", "her", "heraus", "herein", "hin", "hinaus", "hinzu", "los", "mit", "nach", "nieder",
            "sicher", "statt", "teil", "vor", "voran", "voraus", "vorbei", "weg", "weiter", "zu", "zurück", "zusammen");
    /** Verbzusätze und untrennbare Vorsilben vor einer starken Präsensform, längere zuerst. */
    private static final List<String> PREFIXES = java.util.stream.Stream.concat(PARTICLES.stream(),
                    java.util.stream.Stream.of("be", "emp", "ent", "er", "ge", "miss", "ver", "zer", "über", "unter",
                            "durch", "wider", "hinter", "um"))
            .distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    /**
     * Verbzusatz + {@code zu} + Infinitiv. Längere Zusätze zuerst, damit {@code hinzuzufügen} als {@code hinzu} +
     * {@code zu} + {@code fügen} gelesen wird.
     */
    private static final Pattern ZU_INFINITIVE = Pattern.compile("(" + String.join("|", PARTICLES.stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).toList()) + ")zu(\\p{L}+?)(en|ern|eln)");
    private static final Pattern VOWEL = Pattern.compile("[aeiouyäöü]");
    /**
     * Stämme der Verben auf {@code hinzu-} ({@code hinzufügen}, {@code hinzuziehen} …). Ihr Infinitiv gleicht dem
     * zu-Infinitiv eines Verbs auf {@code hin-} ({@code hinziehen}); für diese Stämme gilt das häufigere
     * {@code hinzu-}-Verb (ebenso für Stämme auf {@code -ier}: {@code hinzuaddieren}), für alle anderen der
     * zu-Infinitiv ({@code hinzuweisen} → {@code hinweisen}, {@code sich hinzusetzen} → {@code hinsetzen}).
     */
    private static final Set<String> HINZU_STEMS = Set.of("füg", "zieh", "komm", "rechn", "zähl", "kauf", "verdien",
            "lern", "gewinn", "tret", "denk", "schreib", "hol", "ruf", "schalt", "buch", "wähl", "misch", "erwerb");

    private GermanTagCorrections() {
    }

    /** Korrigiert die Wortarten an Ort und Stelle; {@code upos[i]} gehört zu {@code forms[i]}. */
    static void correctTags(String[] forms, String[] upos) {
        for (int i = 0; i < forms.length; i++) {
            if (!VERBAL.contains(upos[i])) {
                continue;
            }
            String form = forms[i].toLowerCase(Locale.ROOT);
            if (DETERMINER_OR_PRONOUN.contains(form)) {
                upos[i] = i + 1 < forms.length && NOMINAL_FOLLOWERS.contains(upos[i + 1]) ? "DET" : "PRON";
            } else if (PRONOUNS.contains(form)) {
                upos[i] = "PRON";
            } else if (CONJUNCTIONS.contains(form)) {
                upos[i] = "CCONJ";
            } else if (POSTPOSITIONS.contains(form)) {
                upos[i] = "ADP";
            }
        }
    }

    /**
     * Grundform eines zu-Infinitivs mit abtrennbarem Verbzusatz, den der Lemmatisierer unverändert lässt
     * ({@code bereitzustellen} → {@code bereitstellen}); sonst {@code lemma} unverändert. Ein gewöhnlicher Infinitiv,
     * der nur zufällig „Zusatz + zu“ enthält, bleibt: ein Verb auf {@code hinzu-} aus {@link #HINZU_STEMS}
     * ({@code hinzufügen}, dessen zu-Infinitiv {@code hinzuzufügen} ist) und ein Rest ohne Vokal im Stamm
     * ({@code anzupfen}, {@code aufzucken}: „pf“, „ck“ sind kein Verbstamm).
     */
    static String lemma(String form, String upos, String lemma) {
        return verbLemma(form, upos, lemma);
    }

    private static String verbLemma(String form, String upos, String lemma) {
        if (!"VERB".equals(upos)) {
            return lemma;
        }
        if (lemma != null && !lemma.equalsIgnoreCase(form)) {
            return participleWithParticle(form, lemma);
        }
        // Grundform aus der Form nur, wenn OpenNLP die Form unverändert als Grundform liefert (ADR 0032, FL2)
        Matcher zu = ZU_INFINITIVE.matcher(form.toLowerCase(Locale.ROOT));
        if (zu.matches()) {
            return zuInfinitive(form, lemma);
        }
        if (lemma == null) {
            return null;
        }
        String strong = strong(form);
        return strong != null ? strong : finite(form, lemma);
    }

    /**
     * Partizip mit Verbzusatz, dessen „ge“ der Lemmatisierer in der Grundform lässt („zurückgesendet“ →
     * „zurückgesenden“): Zusatz + Stamm, wenn die Form genau Zusatz + ge + Stamm + (e)t ist und der Stamm mindestens
     * drei Buchstaben hat.
     */
    static String participleWithParticle(String form, String lemma) {
        String lower = form.toLowerCase(Locale.ROOT);
        String base = lemma.toLowerCase(Locale.ROOT);
        for (String particle : PARTICLES) {
            String prefix = particle + "ge";
            if (!base.startsWith(prefix) || !lower.startsWith(prefix) || !lower.endsWith("t")) {
                continue;
            }
            String rest = base.substring(prefix.length());
            String stem = rest.endsWith("en") ? rest.substring(0, rest.length() - 2)
                    : rest.endsWith("n") ? rest.substring(0, rest.length() - 1) : null;
            String ending = lower.substring(prefix.length());
            if (stem != null && stem.length() >= 3 && (ending.equals(stem + "t") || ending.equals(stem + "et"))) {
                return particle + rest;
            }
        }
        return lemma;
    }

    /**
     * Grundform einer finiten Form der 3. Person, die der Lemmatisierer unverändert lässt („abruft“ → „abrufen“,
     * „sammelt“ → „sammeln“, „arbeitet“ → „arbeiten“). Formen mit Umlaut bleiben (starke Verben: „hält“ → „halten“
     * ist ohne Wörterbuch nicht sicher), ebenso alles, was nicht auf „t“ endet.
     */
    static String finite(String form, String lemma) {
        String lower = form.toLowerCase(Locale.ROOT);
        if (lower.length() < 4 || !lower.endsWith("t") || lower.endsWith("en") || lower.matches(".*[äöü].*")
                || !lower.matches("\\p{L}+") || lower.startsWith("ge") || strongVowel(lower)) {
            return lemma;
        }
        String stem = lower.substring(0, lower.length() - 1);
        if (stem.endsWith("er") || stem.endsWith("el")) {
            // „sammelt“, „ändert“: Konsonant vor -el/-er; „hielt“, „fiel“ (Vokal davor) sind keine solchen Formen
            char before = stem.length() > 2 ? stem.charAt(stem.length() - 3) : 'a';
            return "aeiouy".indexOf(before) >= 0 ? lemma : stem + "n";
        }
        if (stem.endsWith("de") || stem.endsWith("te")) {
            return stem.substring(0, stem.length() - 1) + "en";
        }
        return stem + "en";
    }

    /**
     * Im Zweifel die Form lassen: Partizipien auf „ge-“ („gebucht“, „gehört“) und ein einzelnes „i“ als letzter Vokal
     * vor „-t“/„-st“ (starke Verben mit e → i: „gibt“, „gilt“, „nimmt“, „misst“) außer bei „-et“/„-ert“/„-elt“
     * („bildet“, „liefert“). Lieber keine Grundform als eine erfundene („giben“).
     */
    private static boolean strongVowel(String lower) {
        if (lower.endsWith("et") || lower.endsWith("ert") || lower.endsWith("elt")) {
            return false;
        }
        Matcher vowels = Pattern.compile("[aeiouy]+").matcher(lower);
        String last = null;
        while (vowels.find()) {
            last = vowels.group();
        }
        return "i".equals(last);
    }

    /**
     * Präsens eines starken oder unregelmäßigen Verbs mit Vokalwechsel aus {@code starke-verben.tsv} („hält“ →
     * „halten“), auch mit Verbzusatz oder untrennbarer Vorsilbe davor („einhält“ → „einhalten“, „erhält“ →
     * „erhalten“); sonst {@code null}.
     */
    static String strong(String form) {
        String lower = form.toLowerCase(Locale.ROOT);
        String direct = STRONG.get(lower);
        if (direct != null) {
            return direct;
        }
        for (String prefix : PREFIXES) {
            if (lower.length() > prefix.length() + 2 && lower.startsWith(prefix)) {
                String base = STRONG.get(lower.substring(prefix.length()));
                if (base != null) {
                    return prefix + base;
                }
            }
        }
        return null;
    }

    private static Map<String, String> strongVerbs() {
        Map<String, String> forms = new HashMap<>();
        try (InputStream input = GermanTagCorrections.class.getResourceAsStream("starke-verben.tsv")) {
            if (input == null) {
                throw new IllegalStateException("starke-verben.tsv fehlt");
            }
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t");
                for (int i = 1; i < columns.length; i++) {
                    forms.putIfAbsent(columns[i].strip(), columns[0].strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(forms);
    }

    private static String zuInfinitive(String form, String lemma) {
        Matcher matcher = ZU_INFINITIVE.matcher(form.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return lemma;
        }
        String particle = matcher.group(1);
        String stem = matcher.group(2);
        if (particle.equals("hinzu") && !VOWEL.matcher(stem).find()) {
            // „hinzuzucken“: hin + zu + zuck, nicht hinzu + zu + ck.
            particle = "hin";
            stem = "zu" + stem;
        }
        if (!VOWEL.matcher(stem).find()
                || particle.equals("hin") && (HINZU_STEMS.contains(stem) || stem.endsWith("ier"))) {
            return lemma;
        }
        return particle + stem + matcher.group(3);
    }
}
