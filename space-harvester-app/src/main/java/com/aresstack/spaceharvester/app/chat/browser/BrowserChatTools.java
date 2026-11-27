package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.proasteion.application.agent.BrowserMcpTools;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolCall;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolParameter;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolResult;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolType;
import com.aresstack.spaceharvester.ai.AiChat;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Browser-Werkzeuge eines Chats ({@link BrowserMcpTools}) als Werkzeuge des Chat-Ports: Schema und Aufruf wie
 * {@code McpChatTool} im Enterprise AI Client. Fehler des Werkzeugs gehen als „Fehler: …“ an das Modell. Bilder
 * (Bildschirmfoto) kann der Chat-Port nicht übertragen; das Modell bekommt nur den Text des Ergebnisses.
 */
public final class BrowserChatTools {

    /** Rahmen eines Aufrufs, etwa die Rückfrage-Fläche des Chats ({@link ChatWebBrowser#asking}). */
    @FunctionalInterface
    public interface Scope {
        String within(java.util.function.Supplier<String> call);
    }

    private final Map<String, McpToolContribution> tools = new LinkedHashMap<>();
    private final Scope scope;

    public BrowserChatTools(List<McpToolContribution> tools) {
        this(tools, java.util.function.Supplier::get);
    }

    public BrowserChatTools(List<McpToolContribution> tools, Scope scope) {
        this.scope = scope == null ? java.util.function.Supplier::get : scope;
        for (McpToolContribution tool : tools) {
            this.tools.put(tool.getName(), tool);
        }
    }

    /** Die Werkzeugdefinitionen in der Reihenfolge des Enterprise AI Client. */
    public List<AiChat.Tool> definitions() {
        List<AiChat.Tool> definitions = new ArrayList<>();
        for (McpToolContribution tool : tools.values()) {
            definitions.add(new AiChat.Tool(tool.getName(), tool.getDescription(), schema(tool)));
        }
        return definitions;
    }

    /** Ob {@code name} ein Browser-Werkzeug ist. */
    public boolean handles(String name) {
        return tools.containsKey(name);
    }

    /** Führt ein Browser-Werkzeug aus und liefert den Text für das Modell. */
    public String run(String name, JsonNode arguments) {
        McpToolContribution tool = tools.get(name);
        if (tool == null) {
            return "Unbekanntes Werkzeug.";
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (McpToolParameter parameter : tool.getParameters()) {
            JsonNode value = arguments == null ? null : arguments.get(parameter.getName());
            if (value == null || value.isNull()) {
                continue;
            }
            if (parameter.getType() == McpToolType.STRING_ARRAY) {
                List<String> list = new ArrayList<>();
                if (value.isArray()) {
                    for (JsonNode element : value) {
                        if (!element.isNull()) {
                            list.add(element.asString(""));
                        }
                    }
                } else {
                    list.add(value.asString(""));
                }
                values.put(parameter.getName(), list);
            } else {
                values.put(parameter.getName(), value.isValueNode() ? value.asString("") : value.toString());
            }
        }
        return scope.within(() -> {
            McpToolResult result = tool.getHandler().invoke(new McpToolCall(name, values));
            return result.isError() ? "Fehler: " + result.getText() : result.getText();
        });
    }

    /** Was die Oberfläche während des Aufrufs zeigt. */
    public static String describe(String name) {
        return switch (name) {
            case BrowserMcpTools.SEARCH -> "Sucht im Web";
            case BrowserMcpTools.TABS -> "Verwaltet die Browser-Tabs";
            case BrowserMcpTools.NAVIGATE -> "Öffnet eine Seite im Browser";
            case BrowserMcpTools.READ -> "Liest die Seite im Browser";
            case BrowserMcpTools.LINKS -> "Listet die Links der Seite";
            case BrowserMcpTools.FOLLOW -> "Folgt einem Link";
            case BrowserMcpTools.BACK -> "Geht im Browser zurück";
            case BrowserMcpTools.DOCUMENT -> "Liest ein verlinktes Dokument";
            case BrowserMcpTools.CONTROLS -> "Sucht Bedienelemente auf der Seite";
            case BrowserMcpTools.ACT -> "Bedient die Seite";
            case BrowserMcpTools.SCREENSHOT -> "Macht ein Bildschirmfoto";
            case BrowserMcpTools.OPEN_MANY -> "Öffnet mehrere Seiten parallel";
            case BrowserMcpTools.MARK -> "Merkt sich relevante Seiten";
            case BrowserMcpTools.CLOSE -> "Schließt den Browser";
            default -> "Browser";
        };
    }

    static String schema(McpToolContribution tool) {
        StringBuilder properties = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (McpToolParameter parameter : tool.getParameters()) {
            if (properties.length() > 0) {
                properties.append(',');
            }
            properties.append(quote(parameter.getName())).append(":{\"type\":")
                    .append(quote(jsonType(parameter.getType())));
            if (parameter.getType() == McpToolType.STRING_ARRAY) {
                properties.append(",\"items\":{\"type\":\"string\"}");
            }
            if (parameter.getType() == McpToolType.ENUM && !parameter.getEnumValues().isEmpty()) {
                properties.append(",\"enum\":[");
                for (int i = 0; i < parameter.getEnumValues().size(); i++) {
                    properties.append(i == 0 ? "" : ",").append(quote(parameter.getEnumValues().get(i)));
                }
                properties.append(']');
            }
            if (parameter.getDescription() != null && !parameter.getDescription().isEmpty()) {
                properties.append(",\"description\":").append(quote(parameter.getDescription()));
            }
            properties.append('}');
            if (parameter.isRequired()) {
                required.append(required.length() == 0 ? "" : ",").append(quote(parameter.getName()));
            }
        }
        return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":[" + required + "]}";
    }

    private static String jsonType(McpToolType type) {
        return switch (type) {
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
            case STRING_ARRAY -> "array";
            default -> "string";
        };
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
