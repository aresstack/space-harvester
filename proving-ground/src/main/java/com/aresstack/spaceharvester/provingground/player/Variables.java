package com.aresstack.spaceharvester.provingground.player;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves {@code ${name}} references in step values; {@code $${name}} stays literal as {@code ${name}}. */
final class Variables {

    private static final Pattern REFERENCE = Pattern.compile("\\$?\\$\\{([^}]+)}");

    private Variables() {
    }

    /** {@code text} with every reference replaced; a missing variable fails with its name (never its value). */
    static String resolve(String text, Map<String, String> values) {
        if (text == null || text.indexOf("${") < 0) {
            return text;
        }
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String whole = matcher.group();
            String replacement;
            if (whole.startsWith("$$")) {
                replacement = whole.substring(1);
            } else {
                String name = matcher.group(1);
                replacement = values.get(name);
                if (replacement == null) {
                    throw new IllegalStateException("Variable fehlt: " + name);
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
