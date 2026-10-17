package com.aresstack.spaceharvester.application.enrichment.language;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verbformen nach Schreibung: was wie ein Verb aussieht, ob es ein Partizip ist, und eine vorgeschlagene Grundform.
 * Ohne Wörterbuch bleiben starke und umgelautete Formen oft falsch ({@code läuft} wird {@code läufen}); die Grundform
 * ist darum immer ein ungeprüfter Vorschlag.
 */
final class Verbs {

    /** Formen von werden, mit denen ein Partizip ein Vorgangspassiv bildet. */
    static final Set<String> PASSIVE_AUXILIARIES = Set.of(
            "werde", "wirst", "wird", "werden", "werdet", "wurde", "wurdest", "wurden", "würde", "würden", "worden");

    /** Formen von haben; ohne Vollverb ist haben selbst das Prädikat ({@code Der Dienst hat Zugriff}). */
    static final Set<String> HABEN = Set.of("habe", "hast", "hat", "haben", "habt", "hatte", "hattest", "hatten",
            "hattet", "hätte", "hätten");

    private static final Pattern SEPARABLE_PARTICIPLE = Pattern.compile(
            "(ab|an|auf|aus|bei|ein|fest|mit|nach|vor|weg|zu|zurück|her|hin|los|um|frei|zusammen|weiter|"
                    + "hoch|herunter|hinzu|heraus|teil|statt|dar)?ge(\\p{L}{2,}?)(et|t|en)");
    private static final Pattern INSEPARABLE_PARTICIPLE = Pattern.compile(
            "((?:be|ver|ent|er|zer|miss)\\p{L}{2,}?)(et|t)");

    private Verbs() {
    }

    /** Sieht aus wie eine Verbform: Infinitiv, Partizip oder finite Form auf {@code -t}, {@code -et}, {@code -te}. */
    static boolean verbLike(String lower) {
        return lower.length() >= 3 && lower.chars().allMatch(Character::isLetter)
                && (lower.endsWith("t") || lower.endsWith("en") || lower.endsWith("ern") || lower.endsWith("eln")
                || lower.endsWith("te") || lower.endsWith("ten"));
    }

    /** Infinitiv auf {@code -en}, {@code -ern} oder {@code -eln}. */
    static boolean infinitive(String lower) {
        return lower.length() >= 4 && (lower.endsWith("en") || lower.endsWith("ern") || lower.endsWith("eln"));
    }

    /** Partizip II nach Schreibung ({@link Terms#participle(String)}). */
    static boolean participle(String lower) {
        return Terms.participle(lower);
    }

    /** Vorgeschlagene Grundform; {@code particle} ist eine abgetrennte Verbpartikel oder {@code null}. */
    static String lemma(String word, String particle) {
        String lower = Terms.normalize(word);
        String prefix = particle == null ? "" : Terms.normalize(particle);
        if (HABEN.contains(lower)) {
            return prefix + "haben";
        }
        if (lower.endsWith("iert")) {
            return prefix + lower.substring(0, lower.length() - 1) + "en";
        }
        if (Terms.participle(lower)) {
            Matcher separable = SEPARABLE_PARTICIPLE.matcher(lower);
            if (separable.matches()) {
                String particlePart = separable.group(1) == null ? "" : separable.group(1);
                return prefix + particlePart + infinitiveOf(separable.group(2));
            }
            Matcher inseparable = INSEPARABLE_PARTICIPLE.matcher(lower);
            if (inseparable.matches()) {
                return prefix + infinitiveOf(inseparable.group(1));
            }
        }
        if (infinitive(lower)) {
            return prefix + lower;
        }
        String stem = lower;
        for (String ending : new String[] {"et", "ten", "te", "st", "t"}) {
            if (stem.endsWith(ending) && stem.length() - ending.length() >= 2) {
                stem = stem.substring(0, stem.length() - ending.length());
                break;
            }
        }
        return prefix + infinitiveOf(stem);
    }

    private static String infinitiveOf(String stem) {
        return stem.endsWith("er") || stem.endsWith("el") ? stem + "n" : stem + "en";
    }
}
