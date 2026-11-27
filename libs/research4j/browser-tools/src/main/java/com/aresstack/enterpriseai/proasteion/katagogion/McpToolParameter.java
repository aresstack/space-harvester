package com.aresstack.enterpriseai.proasteion.katagogion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ein flacher Tool-Parameter: Name, Typ, Pflichtkennzeichen, Beschreibung und (bei {@link McpToolType#ENUM})
 * die erlaubten Werte.
 *
 * <p>Herkunft: askai-java8 {@code McpToolParameter}.
 */
public final class McpToolParameter {

    private final String name;
    private final McpToolType type;
    private final boolean required;
    private final String description;
    private final List<String> enumValues;

    private McpToolParameter(String name, McpToolType type, boolean required, String description,
                             List<String> enumValues) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("parameter name must not be empty");
        }
        this.name = name.trim();
        this.type = type == null ? McpToolType.STRING : type;
        this.required = required;
        this.description = description == null ? "" : description;
        this.enumValues = Collections.unmodifiableList(new ArrayList<String>(
                enumValues == null ? Collections.<String>emptyList() : enumValues));
    }

    public static McpToolParameter string(String name, boolean required, String description) {
        return new McpToolParameter(name, McpToolType.STRING, required, description, null);
    }

    public static McpToolParameter integer(String name, boolean required, String description) {
        return new McpToolParameter(name, McpToolType.INTEGER, required, description, null);
    }

    public static McpToolParameter bool(String name, boolean required, String description) {
        return new McpToolParameter(name, McpToolType.BOOLEAN, required, description, null);
    }

    /** Eine Liste von Texten (JSON Schema {@code array} von {@code string}), nie eine kommaseparierte Zeichenkette. */
    public static McpToolParameter stringArray(String name, boolean required, String description) {
        return new McpToolParameter(name, McpToolType.STRING_ARRAY, required, description, null);
    }

    public static McpToolParameter enumeration(String name, boolean required, String description,
                                               List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("enum parameter needs at least one value");
        }
        return new McpToolParameter(name, McpToolType.ENUM, required, description, values);
    }

    public String getName() {
        return name;
    }

    public McpToolType getType() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getEnumValues() {
        return enumValues;
    }

    @Override
    public String toString() {
        return name + ":" + type + (required ? "" : "?");
    }
}
