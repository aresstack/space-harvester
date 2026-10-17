package com.aresstack.spaceharvester.nlp.syntax;

import java.util.Objects;

/**
 * Ein Wort im Text, das aus mehreren syntaktischen Tokens besteht ({@code am} = {@code an} + {@code dem}); CoNLL-U
 * schreibt es als Bereichszeile {@code 6-7}.
 *
 * @param first erstes Token
 * @param last  letztes Token
 * @param form  Form im Text
 * @param start Zeichenposition im Dokumenttext (inklusive)
 * @param end   Zeichenposition im Dokumenttext (exklusive)
 */
public record MultiwordToken(int first, int last, String form, int start, int end) {

    public MultiwordToken {
        Objects.requireNonNull(form, "form");
        if (first < 1 || last <= first) {
            throw new IllegalArgumentException("Ungültiger Bereich " + first + "-" + last);
        }
    }

    public boolean covers(int tokenId) {
        return tokenId >= first && tokenId <= last;
    }
}
