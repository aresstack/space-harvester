package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Werkzeugaufrufe, die ein Modell als Antworttext schreibt statt im Feld {@code tool_calls} (Angelo 26.11.2026: Der
 * Chat zeigte „We will call the search. {"cmd":["im_bereich_suchen","High Level"]}“ und suchte nicht). Der Adapter
 * wertet nur {@code tool_calls} als Aufruf; alles in {@code content} ist für ihn Antwort. Hier erkennt der Chat solche
 * Texte für die angebotenen Werkzeuge, macht daraus echte Aufrufe und erkennt bloße Ankündigungen eines Aufrufs, damit
 * weder Rohtext noch englischer Vorspann in der Blase oder im Verlauf bleibt.
 * <p>
 * Erkannt werden nur Werkzeuge, die im Turn angeboten sind:
 * <ul>
 *     <li>{@code {"cmd":["werkzeug", "wert", …]}} – Werte der Reihe nach auf die Parameter des Schemas</li>
 *     <li>{@code {"name":"werkzeug","arguments":{…}}} (auch {@code parameters}, {@code args}, {@code tool},
 *     {@code function}), auch in {@code <tool_call>…</tool_call>}</li>
 *     <li>{@code werkzeug {…}}, {@code werkzeug({…})}, {@code functions.werkzeug … {…}}</li>
 * </ul>
 */
final class TextToolCalls {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Kennungen erkannter Aufrufe beginnen so; der Dienst bekommt sie mit dem Ergebnis zurück. */
    static final String ID_PREFIX = "text_call_";

    /** Kurze Texte, die nur einen Werkzeugaufruf ankündigen („We will perform a silent tool call.“). */
    static final int ANNOUNCE_MAX_CHARS = 300;

    private static final Pattern TOOL_WORDS = Pattern.compile(
            "(?i)\\btool[ _-]?calls?\\b|\\btoolcalls?\\b|\\bfunction[ _-]?calls?\\b|<\\|?(tool_call|call)\\|?>"
                    + "|\\bfunctions\\.[a-z_]");

    private static final Pattern ANNOUNCE = Pattern.compile(
            "(?is)^\\W*(we|i|let's|let me|now)\\b.{0,200}\\b(call|calls|calling|invoke|use|using|search|searching)\\b.*");

    private TextToolCalls() {
    }

    /**
     * Die Aufrufe angebotener Werkzeuge im Text, in der Reihenfolge des Textes; leer, wenn keiner darin steht.
     *
     * @param offered die Werkzeuge des Turns; nur ihre Namen zählen
     */
    static List<AiChat.ToolCall> parse(String text, List<AiChat.Tool> offered) {
        if (text == null || text.isBlank() || offered == null || offered.isEmpty()) {
            return List.of();
        }
        Map<String, AiChat.Tool> tools = offered.stream()
                .collect(Collectors.toMap(AiChat.Tool::name, tool -> tool, (first, second) -> first));
        List<AiChat.ToolCall> calls = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = text.indexOf('{', from);
            if (open < 0) {
                break;
            }
            int close = closing(text, open);
            if (close < 0) {
                break;
            }
            JsonNode node = read(text.substring(open, close + 1));
            AiChat.ToolCall call = node == null ? null : fromObject(node, tools, calls.size());
            if (call == null && node != null && node.isObject()) {
                // werkzeug {…}, werkzeug({…}) oder functions.werkzeug … {…}: der Name steht kurz vor dem Objekt.
                String name = nameBefore(text, open, tools.keySet());
                if (name != null) {
                    call = new AiChat.ToolCall(ID_PREFIX + calls.size(), name, node.toString());
                }
            }
            if (call != null) {
                calls.add(call);
                from = close + 1;
            } else {
                from = open + 1;
            }
        }
        return List.copyOf(calls);
    }

    /**
     * Ob der Text nur einen Werkzeugaufruf ankündigt oder über Werkzeugaufrufe spricht, ohne zu antworten: kurz und
     * entweder mit Worten wie „tool call“ oder als englischer Satz, der ein Suchen oder Aufrufen ankündigt. Ein
     * Werkzeugname allein zählt nicht: Eine kurze Antwort darf sagen, womit gesucht wurde.
     */
    static boolean announces(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > ANNOUNCE_MAX_CHARS) {
            return false;
        }
        return TOOL_WORDS.matcher(trimmed).find() || ANNOUNCE.matcher(trimmed).matches();
    }

    /**
     * Ob ein noch unfertiger Antworttext ein Werkzeugaufruf oder seine Ankündigung werden könnte: Solange das gilt, hält
     * der Chat ihn zurück, statt ihn zu zeigen.
     */
    static boolean suspicious(String text, List<AiChat.Tool> offered) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (TOOL_WORDS.matcher(text).find() || text.contains("{\"")) {
            return true;
        }
        if (announces(text) || !parse(text, offered).isEmpty()) {
            return true;
        }
        // Ein Werkzeugname im noch kurzen Anfang kann der Beginn eines Aufrufs als Text sein.
        if (offered != null && text.length() <= ANNOUNCE_MAX_CHARS) {
            for (AiChat.Tool tool : offered) {
                if (text.contains(tool.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Ob eine gespeicherte Antwort nur ein gescheiterter Text-Aufruf ist (ohne Kenntnis der Werkzeuge): Solche
     * Antworten gehen nicht zurück an das Modell, sonst ahmt es sie in jeder folgenden Frage nach.
     */
    static boolean leaked(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.strip();
        if (trimmed.contains("{\"cmd\"") || trimmed.contains("<tool_call>")) {
            return true;
        }
        return trimmed.length() <= ANNOUNCE_MAX_CHARS
                && (TOOL_WORDS.matcher(trimmed).find() || ANNOUNCE.matcher(trimmed).matches());
    }

    private static AiChat.ToolCall fromObject(JsonNode node, Map<String, AiChat.Tool> tools, int index) {
        if (!node.isObject()) {
            return null;
        }
        JsonNode cmd = node.get("cmd");
        if (cmd != null && cmd.isArray() && !cmd.isEmpty() && cmd.get(0).isString()) {
            String name = bare(cmd.get(0).asString());
            AiChat.Tool tool = tools.get(name);
            if (tool == null) {
                return null;
            }
            List<String> parameters = parameters(tool);
            ObjectNode arguments = JSON.createObjectNode();
            for (int i = 1; i < cmd.size(); i++) {
                JsonNode value = cmd.get(i);
                if (value.isObject() && cmd.size() == 2) {
                    return new AiChat.ToolCall(ID_PREFIX + index, name, value.toString());
                }
                int slot = Math.min(i - 1, parameters.size() - 1);
                if (slot < 0) {
                    break;
                }
                String key = parameters.get(slot);
                JsonNode before = arguments.get(key);
                if (before != null && before.isString() && value.isString()) {
                    // Mehr Werte als Parameter: der letzte Parameter nimmt den Rest auf.
                    arguments.put(key, before.asString() + " " + value.asString());
                } else {
                    arguments.set(key, value);
                }
            }
            return new AiChat.ToolCall(ID_PREFIX + index, name, arguments.toString());
        }
        for (String nameKey : List.of("name", "tool", "function", "tool_name", "recipient")) {
            JsonNode nameNode = node.get(nameKey);
            if (nameNode == null) {
                continue;
            }
            if (nameNode.isObject()) {
                // {"function":{"name":…,"arguments":…}} wie in tool_calls
                AiChat.ToolCall inner = fromObject(nameNode, tools, index);
                if (inner != null) {
                    return inner;
                }
                continue;
            }
            if (!nameNode.isString()) {
                continue;
            }
            String name = bare(nameNode.asString());
            if (!tools.containsKey(name)) {
                continue;
            }
            for (String argumentsKey : List.of("arguments", "parameters", "args", "input")) {
                JsonNode arguments = node.get(argumentsKey);
                if (arguments == null) {
                    continue;
                }
                if (arguments.isString()) {
                    JsonNode parsed = read(arguments.asString());
                    if (parsed != null && parsed.isObject()) {
                        return new AiChat.ToolCall(ID_PREFIX + index, name, parsed.toString());
                    }
                } else if (arguments.isObject()) {
                    return new AiChat.ToolCall(ID_PREFIX + index, name, arguments.toString());
                }
            }
            return new AiChat.ToolCall(ID_PREFIX + index, name, "{}");
        }
        return null;
    }

    /** {@code functions.werkzeug} oder {@code werkzeug}. */
    private static String bare(String name) {
        String value = name.strip();
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : value;
    }

    /** Ein angebotener Name, der höchstens wenige Zeichen (Leerraum, Klammer, Doppelpunkt, Steuerzeichen) vor dem Objekt steht. */
    private static String nameBefore(String text, int open, Set<String> names) {
        int start = Math.max(0, open - 80);
        String before = text.substring(start, open);
        String best = null;
        int bestEnd = -1;
        for (String name : names) {
            int at = before.lastIndexOf(name);
            if (at < 0) {
                continue;
            }
            String gap = before.substring(at + name.length());
            if (!gap.matches("(?s)[\\s(:=>\"'`]*(<\\|[a-z_]+\\|>[\\s\\w]*)*[\\s(:=>\"'`]*")) {
                continue;
            }
            if (at + name.length() > bestEnd) {
                best = name;
                bestEnd = at + name.length();
            }
        }
        return best;
    }

    /** Die Parameternamen des Schemas, Pflichtparameter zuerst, sonst in Schemareihenfolge. */
    private static List<String> parameters(AiChat.Tool tool) {
        JsonNode schema = read(tool.parametersSchemaJson());
        Set<String> names = new LinkedHashSet<>();
        if (schema != null) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                required.forEach(name -> names.add(name.asString()));
            }
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                Iterator<String> keys = properties.propertyNames().iterator();
                while (keys.hasNext()) {
                    names.add(keys.next());
                }
            }
        }
        return List.copyOf(names);
    }

    /** Das Ende des JSON-Objekts ab {@code open}, mit Zeichenketten und Escapes; {@code -1} ohne Ende. */
    private static int closing(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }
}
