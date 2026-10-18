package com.aresstack.enterpriseai.astu.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministische, regelbasierte Satzzerlegung für deutsche und englische Texte.
 *
 * <p>Ein Satz endet nach {@code . ! ? …} (samt folgender schließender Anführungszeichen/Klammern), wenn danach
 * Leerraum und ein Satzanfang folgen (Großbuchstabe inkl. Umlaut, Ziffer, öffnendes Zeichen). Kein Satzende
 * nach bekannten Abkürzungen ({@code z. B.}, {@code bzw.}, {@code Dr.} ...), einzelnen Buchstaben (Initialen)
 * und reinen Zahlen (Ordinalzahlen wie {@code 1. Januar}).
 *
 * <p>Bewusst nicht {@code java.text.BreakIterator} und keine {@code Character}-Klassen: deren Regeln und
 * Unicode-Daten unterscheiden sich zwischen JDK-Versionen, die Chunks sollen aber unter JDK 8 und neueren JDKs
 * identisch sein; Buchstaben, Ziffern und Großbuchstaben kommen aus {@link UnicodeClasses}. Prinzip übernommen aus askai-java8
 * {@code RegexSentenceSegmenter}, um Abkürzungen, Ordinalzahlen und Unicode erweitert.
 */
public final class SentenceSplitter {

    private static final Set<String> ABBREVIATIONS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "abb", "abs", "abschn", "allg", "anm", "art", "bd", "bspw", "bzgl", "bzw", "ca", "d.h", "dgl", "dr",
            "e.g", "etc", "evtl", "f", "ff", "fr", "ggf", "hr", "i.d.r", "i.e", "inkl", "jh", "jr", "kap", "max",
            "min", "mio", "mr", "mrs", "ms", "mrd", "nr", "o.ä", "prof", "s", "sog", "sr", "st", "str", "tel",
            "u.a", "u.ä", "usw", "vgl", "vs", "z.b", "z.t", "zb", "zzgl")));

    /** Die Sätze des Textes in Reihenfolge, ohne leere. */
    public List<String> split(String text) {
        List<String> sentences = new ArrayList<String>();
        if (text == null) {
            return sentences;
        }
        int start = 0;
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (!isTerminal(c)) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < length && (isTerminal(text.charAt(end)) || isClosing(text.charAt(end)))) {
                end++;
            }
            int next = end;
            while (next < length && KnowledgeText.isSpace(text.charAt(next))) {
                next++;
            }
            boolean boundary = next > end && next < length && isSentenceStart(text.codePointAt(next))
                    && !(c == '.' && onlyClosing(text, i + 1, end) && isAbbreviation(text, i));
            if (boundary) {
                add(sentences, text.substring(start, end));
                start = next;
            }
            i = end;
        }
        add(sentences, text.substring(start));
        return sentences;
    }

    private static void add(List<String> sentences, String sentence) {
        String trimmed = sentence.trim();
        if (!trimmed.isEmpty()) {
            sentences.add(trimmed);
        }
    }

    /** Zwischen dem Punkt und {@code end} stehen nur schließende Zeichen, kein weiteres Satzzeichen. */
    private static boolean onlyClosing(String text, int from, int end) {
        for (int k = from; k < end; k++) {
            if (!isClosing(text.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTerminal(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static boolean isClosing(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '“' || c == '”' || c == '’'
                || c == '»' || c == '«';
    }

    private static boolean isSentenceStart(int codePoint) {
        return UnicodeClasses.isUpperCase(codePoint) || UnicodeClasses.isDigit(codePoint)
                || codePoint == '"' || codePoint == '\'' || codePoint == '(' || codePoint == '['
                || codePoint == '„' || codePoint == '“' || codePoint == '»' || codePoint == '«';
    }

    /** Wort unmittelbar vor dem Punkt bei {@code periodIndex}, bis zum vorigen Leerraum. */
    private static boolean isAbbreviation(String text, int periodIndex) {
        int wordStart = periodIndex;
        while (wordStart > 0 && !KnowledgeText.isSpace(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = text.substring(wordStart, periodIndex);
        while (!word.isEmpty() && isOpening(word.charAt(0))) {
            word = word.substring(1);
        }
        if (word.isEmpty()) {
            return false;
        }
        if (word.codePointCount(0, word.length()) == 1 && UnicodeClasses.isLetter(word.codePointAt(0))) {
            return true; // Initiale oder Teil von "z. B."
        }
        if (isAllDigits(word)) {
            return true; // Ordinalzahl: "am 1. Januar"
        }
        return ABBREVIATIONS.contains(word.toLowerCase(Locale.ROOT));
    }

    private static boolean isOpening(char c) {
        return c == '(' || c == '[' || c == '"' || c == '\'' || c == '„' || c == '“' || c == '»'
                || c == '«';
    }

    private static boolean isAllDigits(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (!UnicodeClasses.isDigit(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
