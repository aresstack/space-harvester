package com.aresstack.spaceharvester.provingground.expressions;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a template with embedded {@code {{...}}} placeholders into an expression tree (from WD4J). Resolves nothing.
 *
 * <pre>
 *   Hallo {{userName}}             variable
 *   {{otp()}}                      function without arguments
 *   {{wrap('x';{{userName}})}}     arguments separated by ';': 'literal', "literal", {{nested}}, bareVariable
 * </pre>
 */
public final class ExpressionParser {

    private final Lexer lexer = new Lexer();

    /** The template as one expression; several parts become a {@link CompositeExpression}. */
    public ResolvableExpression parseTemplate(String template) {
        List<ResolvableExpression> parts = new ArrayList<>();
        for (Token token : lexer.tokenize(template)) {
            parts.add(token.type() == Token.Type.TEXT
                    ? new LiteralExpression(token.content())
                    : parseSingleExpr(token.content()));
        }
        if (parts.isEmpty()) {
            return new LiteralExpression("");
        }
        return parts.size() == 1 ? parts.get(0) : new CompositeExpression(parts);
    }

    /** The body of one {@code {{ ... }}} block: a call {@code name(...)} or a variable name. */
    private ResolvableExpression parseSingleExpr(String body) {
        String trimmed = body.trim();
        int paren = indexOfTopLevelParen(trimmed);
        if (paren > 0 && trimmed.endsWith(")")) {
            String name = trimmed.substring(0, paren).trim();
            String rawArgs = trimmed.substring(paren + 1, trimmed.length() - 1);
            List<ResolvableExpression> args = new ArrayList<>();
            for (String chunk : splitArgs(rawArgs)) {
                args.add(buildArgExpression(chunk.trim()));
            }
            return new FunctionExpression(name, args);
        }
        return new VariableExpression(trimmed);
    }

    /** The first '(' outside quotes, or -1. */
    private static int indexOfTopLevelParen(String text) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == quote) {
                    inQuote = false;
                }
            } else if (c == '\'' || c == '"') {
                inQuote = true;
                quote = c;
            } else if (c == '(') {
                return i;
            }
        }
        return -1;
    }

    private ResolvableExpression buildArgExpression(String token) {
        if (token.startsWith("{{") && token.endsWith("}}")) {
            return parseSingleExpr(token.substring(2, token.length() - 2));
        }
        if (isQuoted(token)) {
            return new LiteralExpression(token.substring(1, token.length() - 1));
        }
        return new VariableExpression(token);
    }

    private static boolean isQuoted(String s) {
        if (s.length() < 2) {
            return false;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        return (first == '\'' && last == '\'') || (first == '"' && last == '"');
    }

    /** "a; b; {{wrap('x';{{u}})}}" split at top-level ';', honouring quotes and nested blocks. */
    private static List<String> splitArgs(String raw) {
        List<String> parts = new ArrayList<>();
        if (raw.trim().isEmpty()) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inQuote) {
                current.append(c);
                if (c == quote) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inQuote = true;
                quote = c;
                current.append(c);
            } else if (c == '{' && i + 1 < raw.length() && raw.charAt(i + 1) == '{') {
                depth++;
                current.append("{{");
                i++;
            } else if (c == '}' && i + 1 < raw.length() && raw.charAt(i + 1) == '}') {
                depth--;
                current.append("}}");
                i++;
            } else if (c == ';' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        return parts;
    }
}
