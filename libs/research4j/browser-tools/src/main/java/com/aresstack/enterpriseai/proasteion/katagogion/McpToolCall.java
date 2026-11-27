package com.aresstack.enterpriseai.proasteion.katagogion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein Tool-Aufruf: Tool-Name und bereits geparste, flache Argumente (String-Schlüssel). Die typisierten
 * Getter sind tolerant, weil MCP-Clients Zahlen und Wahrheitswerte je nach Modell auch als Text schicken.
 *
 * <p>Herkunft: askai-java8 {@code McpToolCall}.
 */
public final class McpToolCall {

    private final String toolName;
    private final Map<String, Object> arguments;

    public McpToolCall(String toolName, Map<String, Object> arguments) {
        this.toolName = toolName == null ? "" : toolName;
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(
                arguments == null ? Collections.<String, Object>emptyMap() : arguments));
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    /** @return der Wert als Text oder {@code null}, wenn das Argument fehlt. */
    public String getString(String name) {
        Object value = arguments.get(name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * @return der Wert als Liste von Texten in Aufrufreihenfolge: eine Liste oder ein Array bleibt eine Liste
     *         ({@code null}-Elemente entfallen), ein einzelner Wert wird zur einelementigen Liste, ein fehlendes
     *         Argument zur leeren Liste. Nie {@code null}.
     */
    public List<String> getStrings(String name) {
        Object value = arguments.get(name);
        List<String> values = new ArrayList<String>();
        if (value == null) {
            return values;
        }
        if (value instanceof Collection) {
            for (Object element : (Collection<?>) value) {
                if (element != null) {
                    values.add(String.valueOf(element));
                }
            }
        } else if (value instanceof Object[]) {
            for (Object element : (Object[]) value) {
                if (element != null) {
                    values.add(String.valueOf(element));
                }
            }
        } else {
            values.add(String.valueOf(value));
        }
        return values;
    }

    /** @return der Wert als Ganzzahl, sonst {@code defaultValue} (fehlend oder nicht numerisch). */
    public long getInteger(String name, long defaultValue) {
        Object value = arguments.get(name);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException notALong) {
            try {
                return (long) Double.parseDouble(text);
            } catch (NumberFormatException notANumber) {
                return defaultValue;
            }
        }
    }

    /** @return der Wert als Wahrheitswert, sonst {@code defaultValue}. Nur "true"/"false" (beliebige Schreibweise). */
    public boolean getBoolean(String name, boolean defaultValue) {
        Object value = arguments.get(name);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return defaultValue;
    }

    /** Nennt nur Tool und Argumentnamen, nie Argumentwerte (die Nutzertext enthalten können). */
    @Override
    public String toString() {
        return "McpToolCall[" + toolName + arguments.keySet() + "]";
    }
}
