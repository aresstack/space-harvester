package com.aresstack.enterpriseai.astu.acropolis.textmining;

import com.aresstack.enterpriseai.astu.acropolis.chat.linguistics.TermShape;

import java.util.Locale;

/**
 * The spelling rules of the acronym extractor, shared with every display that maps findings onto rendered text:
 * which token is an acronym or a technical identifier ({@link TermShape} decides the basic shape), its search form,
 * and whether a line is shouting. Spelling only; no word lists, no rules for particular acronyms.
 */
public final class AcronymForms {

    /** Most digits an acronym may carry ({@code B2B}); more make it an identifier. */
    static final int MAX_ACRONYM_DIGITS = 2;

    private AcronymForms() {
    }

    /**
     * The kind of one token without hyphen or slash parts, or {@code null} if it is neither acronym nor identifier.
     *
     * @param token      a word as cut by the scanner (letters, digits, inner dots or underscores)
     * @param maxLetters longest acronym in letters
     */
    public static TermKind classify(String token, int maxLetters) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        int letters = 0;
        int capitals = 0;
        int digits = 0;
        boolean underscore = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    capitals++;
                }
            } else if (Character.isDigit(c)) {
                digits++;
            } else if (c == '_') {
                underscore = true;
            }
        }
        if (letters == 0) {
            return null; // numbers, dates, versions
        }
        if (underscore) {
            return digits > 0 || capitals >= 2 ? TermKind.TECHNICAL_IDENTIFIER : null;
        }
        if (digits > 0) {
            if (letters >= 2 && capitals == letters && digits <= MAX_ACRONYM_DIGITS
                    && letters + digits <= maxLetters) {
                return TermKind.ACRONYM;
            }
            return TermShape.isIdentifier(token) ? TermKind.TECHNICAL_IDENTIFIER : null;
        }
        String base = withoutPlural(token);
        if (!TermShape.isAcronym(base) || TermShape.letters(base) > maxLetters) {
            return null;
        }
        return TermKind.ACRONYM;
    }

    /** The search form: upper case, dots removed, a plural {@code s} after capitals removed. */
    public static String normalize(String token) {
        return withoutPlural(token).replace(".", "").toUpperCase(Locale.ROOT);
    }

    /**
     * The token without a trailing lower-case plural {@code s} after at least two capitals ({@code IARs} →
     * {@code IAR}); otherwise unchanged.
     */
    public static String withoutPlural(String token) {
        int n = token.length();
        if (n >= 3 && token.charAt(n - 1) == 's') {
            String base = token.substring(0, n - 1);
            boolean capitalsOnly = true;
            for (int i = 0; i < base.length(); i++) {
                char c = base.charAt(i);
                if (Character.isLetter(c) && !Character.isUpperCase(c)) {
                    capitalsOnly = false;
                    break;
                }
            }
            if (capitalsOnly && TermShape.letters(base) >= 2) {
                return base;
            }
        }
        return token;
    }

    /**
     * Whether a line is written in capitals: at least {@code minWords} words with two or more letters, of which at
     * least {@code percent} percent carry no lower-case letter.
     */
    public static boolean isShouting(String line, int percent, int minWords) {
        int words = 0;
        int loud = 0;
        int letters = 0;
        boolean lower = false;
        for (int i = 0; i <= line.length(); i++) {
            char c = i < line.length() ? line.charAt(i) : ' ';
            if (Character.isLetter(c)) {
                letters++;
                lower |= Character.isLowerCase(c);
            } else if (!Character.isDigit(c) && c != '-' && c != '.' && c != '_') {
                if (letters >= 2) {
                    words++;
                    if (!lower) {
                        loud++;
                    }
                }
                letters = 0;
                lower = false;
            }
        }
        return words >= minWords && loud * 100 >= percent * words;
    }

    /** A long form for comparing: lower case, whitespace runs as single spaces. */
    public static String normalizeLongForm(String longForm) {
        return longForm.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00A0]+", " ").trim();
    }
}
