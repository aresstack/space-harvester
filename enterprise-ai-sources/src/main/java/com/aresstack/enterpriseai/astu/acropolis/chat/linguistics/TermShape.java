package com.aresstack.enterpriseai.astu.acropolis.chat.linguistics;

/**
 * The spelling of an acronym or identifier: at least two capitals that make up at least half of the letters, or
 * letters and digits mixed, or digits with several separators. Read from the characters only; says nothing about
 * meaning.
 */
public final class TermShape {

    private TermShape() {
    }

    /**
     * @param shouting whether the message is written mostly in capitals; then capitals mark no acronym
     */
    public static boolean isAcronymOrIdentifier(String token, boolean shouting) {
        return isIdentifier(token) || (!shouting && isAcronym(token));
    }

    public static boolean isAcronym(String token) {
        int letters = 0;
        int capitals = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    capitals++;
                }
            } else if (!Character.isDigit(c) && c != '-' && c != '.' && c != '/' && c != '_') {
                return false;
            }
        }
        return capitals >= 2 && capitals * 2 >= letters;
    }

    public static boolean isIdentifier(String token) {
        int letters = 0;
        int digits = 0;
        int separators = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            } else if (Character.isDigit(c)) {
                digits++;
            } else if (c == '-' || c == '.' || c == '/' || c == '_') {
                separators++;
            } else {
                return false;
            }
        }
        return digits > 0 && token.length() >= 3 && (letters > 0 || separators >= 2);
    }

    public static int letters(String token) {
        int letters = 0;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetter(token.charAt(i))) {
                letters++;
            }
        }
        return letters;
    }
}
