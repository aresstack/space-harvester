package com.aresstack.enterpriseai.astu.knowledge;

/**
 * Siehe {@link KnowledgeTokenCounter#wordsAndSymbols()}. Ohne Regex, um lange Texte linear zu zählen; Zeichenklassen
 * aus {@link UnicodeClasses}, damit die Zählung auf jedem JDK gleich ausfällt. Der Unterstrich bleibt wie bisher
 * Wortbestandteil (Bezeichner wie {@code max_tokens}).
 */
final class WordAndSymbolTokenCounter implements KnowledgeTokenCounter {

    @Override
    public String id() {
        return "words-and-symbols-v2";
    }

    @Override
    public int count(String text) {
        if (text == null) {
            return 0;
        }
        int tokens = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '_' || UnicodeClasses.isWordPart(codePoint)) {
                if (!inWord) {
                    tokens++;
                    inWord = true;
                }
            } else {
                inWord = false;
                if (!UnicodeClasses.isSpace(codePoint)) {
                    tokens++;
                }
            }
        }
        return tokens;
    }

}
