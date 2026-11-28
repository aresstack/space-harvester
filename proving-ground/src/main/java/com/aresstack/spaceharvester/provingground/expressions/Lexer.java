package com.aresstack.spaceharvester.provingground.expressions;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a template into TEXT and EXPR tokens (from WD4J): balanced {@code {{ ... }}} blocks, nested ones included
 * ({@code {{f({{x}})}}}), everything outside is text. Only slices; what a block means is the parser's business.
 */
final class Lexer {

    List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int position = 0;
        while (position < input.length()) {
            int start = input.indexOf("{{", position);
            if (start < 0) {
                tokens.add(new Token(Token.Type.TEXT, input.substring(position)));
                break;
            }
            if (start > position) {
                tokens.add(new Token(Token.Type.TEXT, input.substring(position, start)));
            }
            int end = findMatchingEnd(input, start);
            if (end < 0) {
                throw new IllegalArgumentException("Ausdruck nicht geschlossen: '{{' an Stelle " + (start + 1));
            }
            tokens.add(new Token(Token.Type.EXPR, input.substring(start + 2, end).trim()));
            position = end + 2;
        }
        return tokens;
    }

    /** The index of the "}}" that closes the "{{" at {@code start}, or -1. */
    private static int findMatchingEnd(String input, int start) {
        int depth = 0;
        for (int i = start; i < input.length() - 1; i++) {
            if (input.startsWith("{{", i)) {
                depth++;
                i++;
            } else if (input.startsWith("}}", i)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
                i++;
            }
        }
        return -1;
    }
}
