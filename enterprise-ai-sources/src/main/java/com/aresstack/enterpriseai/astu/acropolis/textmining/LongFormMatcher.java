package com.aresstack.enterpriseai.astu.acropolis.textmining;

/**
 * Finds the long form of a short form in the words next to it, after Schwartz and Hearst ("A simple algorithm for
 * identifying abbreviation definitions in biomedical text", 2003): every letter and digit of the short form must
 * appear in the long form in the same order, read from the end, and its first character must start a word. The
 * shortest such tail of the candidate text wins. Nothing is completed or invented: the result is always a literal
 * piece of the candidate text.
 */
public final class LongFormMatcher {

    private LongFormMatcher() {
    }

    /**
     * Most words a long form may have for a short form with {@code characters} letters and digits: the smaller of
     * {@code characters + 5} and {@code 2 * characters} (Schwartz and Hearst).
     */
    public static int maxWords(int characters) {
        return Math.min(characters + 5, characters * 2);
    }

    /**
     * The start index in {@code candidate} of the shortest tail that is a long form of {@code shortForm}, or
     * {@code -1} if there is none.
     */
    public static int longFormStart(String shortForm, String candidate) {
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
        if (!any) {
            return -1;
        }
        return l + 1;
    }

    /** Whether position {@code s} holds the first letter or digit of the short form. */
    private static boolean isFirst(String shortForm, int s) {
        for (int i = 0; i < s; i++) {
            if (Character.isLetterOrDigit(shortForm.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Letters and digits of a short form. */
    public static int characters(String shortForm) {
        int count = 0;
        for (int i = 0; i < shortForm.length(); i++) {
            if (Character.isLetterOrDigit(shortForm.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
