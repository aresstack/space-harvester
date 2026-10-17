package com.aresstack.spaceharvester.application.enrichment.language;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Regelbasierte Zerlegung deutschen Texts in Sätze, Tokens mit grober Wortklasse und Nominalgruppen, ohne
 * Sprachmodell und ohne Wörterbuch offener Wortklassen. Gemeinsame Grundlage der Sprachprozessoren; jeder Prozessor
 * zerlegt selbst, damit er unabhängig bleibt.
 *
 * <p>Regeln:
 * <ul>
 *   <li>Sätze enden an Zeilenumbrüchen (Absätze, Überschriften, Listenpunkte), an {@code |} (Tabellenzellen) und an
 *       {@code .}, {@code !}, {@code ?}, sofern davor keine Abkürzung, kein einzelner Buchstabe und keine Zahl steht
 *       und danach ein großgeschriebenes Wort, eine Ziffer oder das Ende folgt.</li>
 *   <li>Wortklassen kommen aus {@link Lexicon}; großgeschriebene übrige Wörter sind Nomen, außer am Satzanfang, wenn
 *       {@code Sie} folgt, das Wort auf der Seite auch kleingeschrieben vorkommt oder es mit Adjektivsuffix und
 *       Flexionsendung vor einem großgeschriebenen Wort steht ({@code Fehlerhafte Seiten}).</li>
 *   <li>Eine Nominalgruppe ist {@code [Artikel] Adjektiv* Nomen+}; Adjektive (Flexionsendung {@code -e}, {@code -en},
 *       {@code -er}, {@code -es}, {@code -em}) zählen nur nach Artikel, Präposition, Zahl oder Konjunktion, damit ein
 *       Verb vor einem Nomen nicht als Eigenschaft gilt. Höchstens {@value #MAX_TERM_WORDS} Wörter je Begriff.</li>
 *   <li>Sätze mit mehr englischen als deutschen Markerwörtern (mindestens zwei) werden übersprungen und gezählt.</li>
 * </ul>
 */
final class GermanText {

    static final int MAX_TERM_WORDS = 6;

    /** Großgeschriebenes Adjektiv am Satzanfang vor einem Nomen ({@code Fehlerhafte Seiten}). */
    private static final Pattern ADJECTIVE_AT_START = Pattern.compile(
            "\\p{L}{2,}(?:ig|lich|haft|bar|isch|los|sam|iert)(?:e|en|er|es|em)");

    private final PageText page;
    private final List<Sentence> sentences;
    private final int skipped;

    private GermanText(PageText page, List<Sentence> sentences, int skipped) {
        this.page = page;
        this.sentences = sentences;
        this.skipped = skipped;
    }

    static GermanText analyze(PageText page) {
        List<List<Raw>> blocks = sentences(tokens(page.text()));
        Set<String> lowercaseWords = new HashSet<>();
        for (List<Raw> block : blocks) {
            for (Raw raw : block) {
                if (raw.word && Character.isLowerCase(raw.text.charAt(0))) {
                    lowercaseWords.add(raw.text);
                }
            }
        }
        List<Sentence> sentences = new ArrayList<>();
        int skipped = 0;
        for (List<Raw> block : blocks) {
            if (block.stream().noneMatch(raw -> raw.word)) {
                continue;
            }
            if (!german(block)) {
                skipped++;
                continue;
            }
            List<Token> tokens = classify(block, lowercaseWords);
            sentences.add(new Sentence(tokens, nounGroups(page.text(), tokens)));
        }
        return new GermanText(page, List.copyOf(sentences), skipped);
    }

    List<Sentence> sentences() {
        return sentences;
    }

    /** Anzahl übersprungener fremdsprachiger Sätze. */
    int skippedSentences() {
        return skipped;
    }

    /** Überschrift des Abschnitts an {@code offset} oder {@code null}. */
    String sectionAt(int offset) {
        PageText.Section section = page.sectionAt(offset);
        return section == null ? null : section.heading();
    }

    /** Warnung über übersprungene Sätze, sonst leer. */
    List<String> warnings() {
        return skipped == 0 ? List.of()
                : List.of(skipped + (skipped == 1 ? " Satz" : " Sätze") + " nicht deutsch, übersprungen");
    }

    // --- Zerlegung --------------------------------------------------------------------------------------------

    private record Raw(String text, int start, int end, boolean word, boolean breakBefore) {
    }

    private static List<Raw> tokens(String text) {
        List<Raw> tokens = new ArrayList<>();
        boolean lineBreak = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                lineBreak = true;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isLetterOrDigit(c)) {
                int end = i + 1;
                while (true) {
                    while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                        end++;
                    }
                    if (end + 1 < text.length() && "-_.'’".indexOf(text.charAt(end)) >= 0
                            && Character.isLetterOrDigit(text.charAt(end + 1))) {
                        end += 2;
                    } else {
                        break;
                    }
                }
                tokens.add(new Raw(text.substring(i, end), i, end, true, lineBreak));
                lineBreak = false;
                i = end;
            } else {
                int end = Character.isHighSurrogate(c) && i + 1 < text.length() ? i + 2 : i + 1;
                tokens.add(new Raw(text.substring(i, end), i, end, false, lineBreak));
                lineBreak = false;
                i = end;
            }
        }
        return tokens;
    }

    private static List<List<Raw>> sentences(List<Raw> tokens) {
        List<List<Raw>> sentences = new ArrayList<>();
        List<Raw> current = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Raw token = tokens.get(i);
            if (token.breakBefore || token.text.equals("|")) {
                close(sentences, current);
                current = new ArrayList<>();
                if (token.text.equals("|")) {
                    continue;
                }
            }
            current.add(token);
            if (!token.word && ".!?…".contains(token.text) && ends(tokens, i, current)) {
                close(sentences, current);
                current = new ArrayList<>();
            }
        }
        close(sentences, current);
        return sentences;
    }

    private static boolean ends(List<Raw> tokens, int index, List<Raw> current) {
        Raw next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
        if (next == null || next.breakBefore) {
            return true;
        }
        if (tokens.get(index).text.equals(".") && current.size() >= 2) {
            Raw before = current.get(current.size() - 2);
            String lower = before.text.toLowerCase(java.util.Locale.ROOT);
            if (before.word && (Lexicon.ABBREVIATIONS.contains(lower) || before.text.length() == 1
                    || Character.isDigit(before.text.charAt(before.text.length() - 1)))) {
                return false;
            }
        }
        char first = next.text.charAt(0);
        return Character.isUpperCase(first) || Character.isDigit(first) || "\"„“»(".indexOf(first) >= 0;
    }

    private static void close(List<List<Raw>> sentences, List<Raw> current) {
        if (!current.isEmpty()) {
            sentences.add(current);
        }
    }

    private static boolean german(List<Raw> sentence) {
        int german = 0;
        int english = 0;
        for (Raw raw : sentence) {
            if (raw.word) {
                String lower = raw.text.toLowerCase(java.util.Locale.ROOT);
                german += Lexicon.GERMAN_MARKERS.contains(lower) ? 1 : 0;
                english += Lexicon.ENGLISH_MARKERS.contains(lower) ? 1 : 0;
            }
        }
        return english < 2 || english <= german;
    }

    // --- Wortklassen ------------------------------------------------------------------------------------------

    private static List<Token> classify(List<Raw> sentence, Set<String> lowercaseWords) {
        int firstWord = -1;
        for (int i = 0; i < sentence.size() && firstWord < 0; i++) {
            if (sentence.get(i).word) {
                firstWord = i;
            }
        }
        List<Token> tokens = new ArrayList<>(sentence.size());
        for (int i = 0; i < sentence.size(); i++) {
            Raw raw = sentence.get(i);
            Raw next = i + 1 < sentence.size() ? sentence.get(i + 1) : null;
            tokens.add(new Token(raw.text, raw.start, raw.end, wordClass(raw, i == firstWord, next, lowercaseWords)));
        }
        return tokens;
    }

    private static WordClass wordClass(Raw raw, boolean sentenceStart, Raw next, Set<String> lowercaseWords) {
        if (!raw.word) {
            return WordClass.PUNCTUATION;
        }
        String text = raw.text;
        if (text.matches("[\\p{N}.,]+")) {
            return WordClass.NUMBER;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (Lexicon.DETERMINERS.contains(lower)) {
            return WordClass.DETERMINER;
        }
        if (Lexicon.PRONOUNS.contains(lower)) {
            return WordClass.PRONOUN;
        }
        if (Lexicon.PREPOSITIONS.contains(lower)) {
            return WordClass.PREPOSITION;
        }
        if (Lexicon.CONJUNCTIONS.contains(lower)) {
            return WordClass.CONJUNCTION;
        }
        if (Lexicon.AUXILIARIES.contains(lower)) {
            return WordClass.AUXILIARY;
        }
        if (Lexicon.MODALS.contains(lower)) {
            return WordClass.MODAL;
        }
        if (Lexicon.NEGATIONS.contains(lower)) {
            return WordClass.NEGATION;
        }
        if (Lexicon.ADVERBS.contains(lower)) {
            return WordClass.ADVERB;
        }
        if (identifier(text)) {
            return WordClass.IDENTIFIER;
        }
        if (acronym(text)) {
            return WordClass.ACRONYM;
        }
        if (Character.isUpperCase(text.charAt(0))) {
            if (sentenceStart && (next != null && next.text.equals("Sie") || lowercaseWords.contains(lower)
                    || ADJECTIVE_AT_START.matcher(lower).matches() && next != null && next.word
                    && Character.isUpperCase(next.text.charAt(0)))) {
                return WordClass.OTHER;
            }
            return WordClass.NOUN;
        }
        return WordClass.OTHER;
    }

    /** Buchstaben mit Ziffern, Unterstrich oder innerem Punkt, oder Binnenmajuskel nach Kleinbuchstabe. */
    static boolean identifier(String text) {
        boolean letter = text.chars().anyMatch(Character::isLetter);
        boolean digit = text.chars().anyMatch(Character::isDigit);
        return letter && (digit || text.indexOf('_') >= 0 || text.indexOf('.') >= 0
                || text.matches("\\p{Ll}+\\p{Lu}\\p{L}*"));
    }

    /** Mindestens zwei Großbuchstaben, die mindestens die Hälfte der Buchstaben ausmachen ({@code GmbH}, {@code IDs}). */
    static boolean acronym(String text) {
        if (text.indexOf('-') >= 0 && !text.matches("[\\p{Lu}-]+")) {
            return false;
        }
        long letters = text.chars().filter(Character::isLetter).count();
        long upper = text.chars().filter(Character::isUpperCase).count();
        return upper >= 2 && upper * 2 >= letters && Character.isUpperCase(text.charAt(0));
    }

    // --- Nominalgruppen ---------------------------------------------------------------------------------------

    private static List<NounGroup> nounGroups(String text, List<Token> tokens) {
        List<NounGroup> groups = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            boolean determiner = token.is(WordClass.DETERMINER);
            if (!determiner && !token.wordClass().nominal() && !adjective(tokens, i, false)) {
                i++;
                continue;
            }
            int j = determiner ? i + 1 : i;
            boolean opened = determiner || openerBefore(tokens, i);
            while (j < tokens.size() && opened && adjective(tokens, j, true)) {
                j++;
            }
            int termFrom = j;
            while (j < tokens.size() && tokens.get(j).wordClass().nominal() && j - termFrom < MAX_TERM_WORDS) {
                j++;
            }
            if (j == termFrom) {
                i++;
                continue;
            }
            String term = text.substring(tokens.get(termFrom).start(), tokens.get(j - 1).end());
            groups.add(new NounGroup(i, termFrom, j, term, kind(tokens.subList(termFrom, j))));
            i = j;
        }
        return groups;
    }

    /** Vor Token {@code index} steht etwas, nach dem ein Adjektiv ohne Artikel eine Nominalgruppe eröffnet. */
    private static boolean openerBefore(List<Token> tokens, int index) {
        if (index == 0) {
            return true;
        }
        WordClass before = tokens.get(index - 1).wordClass();
        return before == WordClass.PREPOSITION || before == WordClass.NUMBER || before == WordClass.CONJUNCTION
                || tokens.get(index - 1).punctuation(",") || tokens.get(index - 1).punctuation("(");
    }

    private static boolean adjective(List<Token> tokens, int index, boolean opened) {
        Token token = tokens.get(index);
        return token.is(WordClass.OTHER) && (Character.isLowerCase(token.text().charAt(0)) || index == 0)
                && Terms.hasAdjectiveEnding(token.lower()) && (opened || openerBefore(tokens, index));
    }

    private static String kind(List<Token> term) {
        if (term.size() == 1 && term.get(0).is(WordClass.ACRONYM)) {
            return "acronym";
        }
        if (term.size() == 1 && term.get(0).is(WordClass.IDENTIFIER)) {
            return "identifier";
        }
        return "noun";
    }
}
