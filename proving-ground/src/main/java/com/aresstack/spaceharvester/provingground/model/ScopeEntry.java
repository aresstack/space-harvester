package com.aresstack.spaceharvester.provingground.model;

/**
 * One entry of a hook (WD4J's parallel maps of value, enabled, description and validator, as one record).
 *
 * <ul>
 *   <li>before hooks and templates: {@code name} is a variable, {@code expression} its value ({@code {{...}}} allowed).
 *       A before entry is resolved once when its hook runs; a template on every use (a fresh one-time code).</li>
 *   <li>after hooks: {@code expression} is evaluated after the steps (e.g. {@code {{js('document.title')}}}) and its
 *       value checked with {@code check}; without a check the value only goes into the result.</li>
 * </ul>
 *
 * @param name        variable name (before, templates) or label (after)
 * @param expression  the value or expression
 * @param enabled     disabled entries are kept but not run
 * @param description what the entry is for, shown in the editor and the result; may be empty
 * @param check       the check of an after entry, or null
 */
public record ScopeEntry(String name, String expression, boolean enabled, String description, Check check) {

    public ScopeEntry {
        name = name == null ? "" : name.trim();
        expression = expression == null ? "" : expression;
        description = description == null ? "" : description;
    }

    /** An enabled variable without description. */
    public static ScopeEntry variable(String name, String expression) {
        return new ScopeEntry(name, expression, true, "", null);
    }

    /** An enabled check without description. */
    public static ScopeEntry check(String name, String expression, Check check) {
        return new ScopeEntry(name, expression, true, "", check);
    }

    public ScopeEntry withEnabled(boolean value) {
        return new ScopeEntry(name, expression, value, description, check);
    }

    public ScopeEntry withDescription(String value) {
        return new ScopeEntry(name, expression, enabled, value, check);
    }
}
