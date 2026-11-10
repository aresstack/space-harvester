package com.aresstack.spaceharvester.nlp.opennlp;

import java.util.Comparator;
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
    private static final List<String> PARTICLES = List.of("ab", "an", "auf", "aus", "bei", "bereit", "dar", "ein",
            "fest", "fort", "frei", "her", "heraus", "herein", "hin", "hinaus", "hinzu", "los", "mit", "nach", "nieder",
            "sicher", "statt", "teil", "vor", "voran", "voraus", "vorbei", "weg", "weiter", "zu", "zurück", "zusammen");
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
        if (!"VERB".equals(upos) || lemma != null && !lemma.equalsIgnoreCase(form)) {
            return lemma;
        }
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
