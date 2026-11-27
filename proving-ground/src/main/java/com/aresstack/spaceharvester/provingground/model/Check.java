package com.aresstack.spaceharvester.provingground.model;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How the value of an after-hook entry is checked (WD4J's validator type and value).
 *
 * <pre>
 *   regex       the pattern is found in the value         ("" always passes)
 *   fullregex   the pattern matches the whole value       ("" = the value is empty)
 *   contains / equals / starts / ends
 *   range       min:max as numbers, both included         (e.g. 0:1, -5.5:10.75)
 *   len         length exactly n, or &gt;=n, &lt;=n
 * </pre>
 *
 * A leading "!" negates the type ({@code !contains}); "||" in the expected value separates alternatives, any of which
 * may match. Value and expected are compared trimmed, as in WD4J.
 *
 * @param type     the check type, possibly with "!"
 * @param expected the expected value; may contain {@code {{...}}} expressions (resolved at run time)
 */
public record Check(String type, String expected) {

    public Check {
        type = type == null ? "" : type.trim();
        expected = expected == null ? "" : expected;
    }

    /** Whether {@code actual} passes; {@code resolvedExpected} is {@link #expected()} with its expressions resolved. */
    public boolean test(String actual, String resolvedExpected) {
        String kind = type;
        boolean negate = kind.startsWith("!");
        if (negate) {
            kind = kind.substring(1);
        }
        kind = kind.trim().toLowerCase(Locale.ROOT);
        String exp = resolvedExpected == null ? "" : resolvedExpected.trim();
        String act = actual == null ? "" : actual.trim();
        boolean any = false;
        for (String raw : exp.contains("||") ? exp.split("\\|\\|") : new String[]{exp}) {
            if (single(kind, raw.trim(), act)) {
                any = true;
                break;
            }
        }
        return negate != any;
    }

    private static boolean single(String kind, String alt, String act) {
        try {
            return switch (kind) {
                case "regex" -> alt.isEmpty() || Pattern.compile(alt).matcher(act).find();
                case "fullregex" -> alt.isEmpty() ? act.isEmpty() : Pattern.compile(alt).matcher(act).matches();
                case "contains" -> act.contains(alt);
                case "equals" -> act.equals(alt);
                case "starts" -> act.startsWith(alt);
                case "ends" -> act.endsWith(alt);
                case "range" -> {
                    String[] bounds = alt.split(":", 2);
                    double value = Double.parseDouble(act);
                    yield value >= Double.parseDouble(bounds[0]) && value <= Double.parseDouble(bounds[1]);
                }
                case "len" -> {
                    if (alt.startsWith(">=")) {
                        yield act.length() >= Integer.parseInt(alt.substring(2).trim());
                    }
                    if (alt.startsWith("<=")) {
                        yield act.length() <= Integer.parseInt(alt.substring(2).trim());
                    }
                    yield act.length() == Integer.parseInt(alt);
                }
                default -> false;
            };
        } catch (PatternSyntaxException | NumberFormatException | ArrayIndexOutOfBoundsException invalid) {
            return false;
        }
    }

    /** Readable form, e.g. {@code contains "OK"}. */
    public String describe() {
        return type + " \"" + expected + "\"";
    }
}
