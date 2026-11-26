package com.aresstack.spaceharvester.mainframe.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read logical JCL statements without interpreting in-stream data as control cards. */
final class JclStatements {
    private static final Pattern CARD = Pattern.compile("^//([A-Z@#$][A-Z0-9@#$.]{0,16})?[ \t]+([A-Z]+)(?:[ \t]+(.*))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY = Pattern.compile("[A-Z@#$][A-Z0-9@#$.]*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> KNOWN = Set.of("JOB", "EXEC", "DD", "PROC", "PEND", "SET", "INCLUDE",
            "JCLLIB", "IF", "ELSE", "ENDIF", "OUTPUT", "COMMAND", "CNTL", "ENDCNTL", "XMIT");
    record Card(String name, String operation, String operands, Map<String, String> parameters,
                List<String> positional, int first, int last, boolean complete) {
        Card {
            parameters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            positional = List.copyOf(positional);
        }
    }
    record Issue(int line, String message) { }
    record Deck(List<Card> cards, List<Issue> issues) { }

    static Deck read(String source) {
        var cards = new ArrayList<Card>();
        var issues = new ArrayList<Issue>();
        String[] lines = source.split("\\r?\\n", -1);
        String delimiter = null;
        boolean star = false;
        boolean control = false;
        for (int index = 0; index < lines.length; index++) {
            String raw = lines[index];
            if (delimiter != null) {
                if (raw.startsWith(delimiter) && (raw.length() == delimiter.length()
                        || Character.isWhitespace(raw.charAt(delimiter.length())))) {
                    delimiter = null;
                    continue;
                }
                if (!star || !raw.startsWith("//") || !"/*".equals(delimiter)) continue;
                delimiter = null;
            }
            String line = columns(raw);
            if (line.isBlank() || line.startsWith("//*")) continue;
            if (line.stripTrailing().equals("//")) {
                cards.add(new Card("", "END", "", Map.of(), List.of(), index + 1, index + 1, true));
                continue;
            }
            var match = CARD.matcher(line.stripTrailing());
            if (!match.matches()) {
                if (!control) issues.add(new Issue(index + 1, "Unbekannte oder nicht unterstützte JCL-Karte"));
                continue;
            }
            String op = upper(match.group(2));
            if (control && !"ENDCNTL".equals(op)) continue;
            String name = match.group(1) == null ? "" : upper(match.group(1));
            int first = index + 1;
            String operands = match.group(3) == null ? "" : "IF".equals(op) ? match.group(3).strip() : operandPart(match.group(3).stripLeading());
            boolean complete = true;
            while (needsContinuation(operands) || continuationIndicator(raw)) {
                int next = index + 1;
                while (next < lines.length && columns(lines[next]).startsWith("//*")) next++;
                if (next >= lines.length || !isContinuation(columns(lines[next]), operands)) {
                    issues.add(new Issue(first, "Fortsetzung fehlt oder ist nicht eindeutig"));
                    complete = false;
                    break;
                }
                index = next;
                raw = lines[index];
                String continuation = columns(raw).substring(2).stripLeading();
                if (openQuote(operands)) {
                    // Preserve the card text but do not claim an exact value for a split quoted literal.
                    complete = false;
                    issues.add(new Issue(first, "Fortgesetztes Literal nicht exakt aufgelöst"));
                    operands += continuation.stripTrailing();
                } else {
                    operands += "IF".equals(op) ? " " + continuation.strip() : operandPart(continuation);
                }
            }
            var parameters = new LinkedHashMap<String, String>();
            var positional = new ArrayList<String>();
            if (!Set.of("IF", "ELSE", "ENDIF", "COMMAND", "CNTL", "ENDCNTL", "XMIT").contains(op)) {
                for (String part : split(operands)) {
                    int equal = part.indexOf('=');
                    if (equal > 0 && KEY.matcher(part.substring(0, equal).strip()).matches()) {
                        String key = upper(part.substring(0, equal).strip());
                        if (parameters.putIfAbsent(key, part.substring(equal + 1).strip()) != null) {
                            issues.add(new Issue(first, "Mehrfacher Parameter " + key + " nicht aufgelöst"));
                            complete = false;
                        }
                    } else positional.add(part.strip());
                }
            }
            if (!KNOWN.contains(op)) issues.add(new Issue(first, "Nicht unterstützte Operation " + op));
            cards.add(new Card(name, op, operands, parameters, positional, first, index + 1, complete));
            if ("CNTL".equals(op)) control = true;
            if ("ENDCNTL".equals(op)) control = false;
            if ("DD".equals(op) && !positional.isEmpty()
                    && Set.of("*", "DATA").contains(upper(positional.getFirst()))) {
                star = "*".equals(positional.getFirst());
                delimiter = unquote(parameters.getOrDefault("DLM", "/*"));
                if (delimiter.isEmpty() || delimiter.contains("&")) {
                    issues.add(new Issue(first, "Symbolischer oder ungültiger DLM: restliche Daten nicht interpretierbar"));
                    break;
                }
            }
        }
        if (delimiter != null && !star && !"/*".equals(delimiter)) {
            issues.add(new Issue(lines.length, "Instream-Daten ohne gefundenen Abschluss"));
        }
        return new Deck(List.copyOf(cards), List.copyOf(issues));
    }

    private static String columns(String line) { return line.substring(0, Math.min(71, line.length())); }

    private static boolean continuationIndicator(String line) {
        return line.length() >= 72 && !Character.isWhitespace(line.charAt(71));
    }

    private static boolean isContinuation(String line, String previous) {
        if (!line.startsWith("//") || line.length() < 4 || !Character.isWhitespace(line.charAt(2))
                || line.startsWith("//*") || line.stripTrailing().equals("//")) return false;
        var match = CARD.matcher(line.stripTrailing());
        return openQuote(previous) || !match.matches() || !KNOWN.contains(upper(match.group(2)));
    }

    private static boolean needsContinuation(String text) {
        return openQuote(text) || text.endsWith(",") || depth(text) > 0;
    }

    /** Stop at the first unquoted, top-level whitespace (the remarks field). */
    private static String operandPart(String value) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (Character.isWhitespace(ch) && depth == 0) {
                    // IF expressions contain spaces; the caller retains the full expression.
                    return value.substring(0, i).stripTrailing();
                }
            }
        }
        return value.stripTrailing();
    }

    static List<String> split(String text) {
        var parts = new ArrayList<String>();
        boolean quoted = false;
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    parts.add(text.substring(start, i));
                    start = i + 1;
                }
            }
        }
        if (start < text.length() || !text.isEmpty()) parts.add(text.substring(start));
        return parts;
    }

    private static boolean openQuote(String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            }
        }
        return quoted;
    }

    private static int depth(String text) {
        int result = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                else quoted = !quoted;
            } else if (!quoted) {
                if (ch == '(') result++;
                else if (ch == ')') result--;
            }
        }
        return result;
    }

    static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }
    static String upper(String value) { return value.toUpperCase(Locale.ROOT); }
}
