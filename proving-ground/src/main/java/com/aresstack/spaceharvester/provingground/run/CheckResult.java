package com.aresstack.spaceharvester.provingground.run;

/**
 * The outcome of one hook entry: a before entry (only reported when it fails) or an after check.
 *
 * @param hook        where it ran, e.g. "Suite anmeldung · afterEach"
 * @param name        the entry's name
 * @param description the entry's description; may be empty
 * @param value       the evaluated value (secrets masked), or null
 * @param passed      whether it passed
 * @param error       why it failed, or null
 */
public record CheckResult(String hook, String name, String description, String value, boolean passed, String error) {

    public String render() {
        StringBuilder line = new StringBuilder(passed ? "✓ " : "✗ ").append(hook).append(" · ").append(name);
        if (!description.isEmpty()) {
            line.append(" (").append(description).append(')');
        }
        if (value != null) {
            line.append(" = \"").append(value).append('"');
        }
        if (error != null) {
            line.append(" – ").append(error);
        }
        return line.toString();
    }
}
