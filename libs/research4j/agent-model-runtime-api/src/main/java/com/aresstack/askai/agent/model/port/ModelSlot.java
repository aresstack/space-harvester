package com.aresstack.askai.agent.model.port;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The name of a MODEL SLOT: the role-level handle a caller uses instead of a model name (ADR 0001, 3.2).
 * A role says "I need the {@code judge}" or "I need the {@code synthesis}"; the platform resolves the slot
 * to a concrete model on a concrete endpoint from its central configuration. Plugins never see which model
 * fills a slot, so swapping models (or moving a slot to another endpoint) never touches plugin code.
 *
 * <p>Slot names are free settings keys, not an enumeration: the platform's configuration decides which
 * slots exist and what fills them (ADR 0001, open decision 2). A name is lower-case ASCII letters, digits,
 * {@code '-'}, {@code '_'} and {@code '.'}, starts with a letter and is at most 64 characters long, so it
 * is safe as a settings key, a log token and a file-name part.</p>
 */
public final class ModelSlot {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    private final String name;

    private ModelSlot(String name) {
        this.name = name;
    }

    /**
     * @throws IllegalArgumentException when {@code name} is null or not a valid slot name
     */
    public static ModelSlot of(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid model slot name: " + name
                    + " (expected lower-case [a-z][a-z0-9._-]{0,63})");
        }
        return new ModelSlot(name);
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModelSlot && ((ModelSlot) other).name.equals(name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
