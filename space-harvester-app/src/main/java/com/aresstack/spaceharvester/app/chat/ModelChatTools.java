package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Werkzeuge, mit denen der Chat die Spezialmodelle aus Einstellungen › KI-Dienst › Modelle nutzt (Angelo 27.11.2026:
 * „Der Chat soll die Einstellungen ruhig alle nutzen“). Jedes Werkzeug ruft die Funktion auf, die das Modell seiner
 * Zeile schon für die App nutzt; der Chat erfindet nichts neu.
 *
 * <p>{@value #DIAGRAM}: ein Schaubild wie im Podcast (Zeilen „Bildgenerierung – Plan“ und „– Einzelteile“). Das SVG
 * geht nicht durch das Chat-Modell, sondern steht unter der Antwort ({@code appendix}).
 */
public final class ModelChatTools {

    public static final String DIAGRAM = "schaubild_zeichnen";

    static final String DIAGRAM_DESCRIPTION = "Zeichnet ein schlichtes Schaubild (Karten mit Symbolen, Pfeilen und "
            + "kurzen Beschriftungen) mit dem Modell der Bildgenerierung. Nutze es, wenn der Benutzer ein Bild, "
            + "Schaubild, eine Skizze oder Übersicht als Grafik möchte. Gib als inhalt die Fakten, die das Bild zeigen "
            + "soll (höchstens fünf Bestandteile). Das Bild erscheint von selbst unter deiner Antwort; gib kein SVG "
            + "wieder, sondern erkläre kurz, was es zeigt.";
    static final String DIAGRAM_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"thema\":{\"type\":\"string\",\"description\":\"Titel des Schaubilds\"},"
            + "\"inhalt\":{\"type\":\"string\",\"description\":\"Fakten und Zusammenhänge, die das Bild zeigen soll\"}"
            + "},\"required\":[\"thema\",\"inhalt\"]}";

    static final String DIAGRAM_DRAWN = "Schaubild gezeichnet; es steht unter deiner Antwort. Erkläre kurz, was es zeigt.";
    static final String DIAGRAM_NONE = "Kein Schaubild: Unter Einstellungen › KI-Dienst › Modelle ist keine "
            + "Bildgenerierung eingerichtet, oder das Modell lieferte keinen brauchbaren Plan. Sag das dem Benutzer.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Die Modelle der App, wie der Workspace sie anbietet. */
    @FunctionalInterface
    public interface Diagrams {
        /** Ein Schaubild als SVG; leer ohne Modell oder brauchbaren Plan. */
        Optional<String> compose(String topic, String material);
    }

    private ModelChatTools() {
    }

    /** Die Definition von {@value #DIAGRAM}. */
    static AiChat.Tool diagramDefinition() {
        return new AiChat.Tool(DIAGRAM, DIAGRAM_DESCRIPTION, DIAGRAM_SCHEMA);
    }

    /**
     * {@code base} samt den Modellwerkzeugen; ohne {@code diagrams} unverändert.
     *
     * @param appendix nimmt Markdown entgegen, das unter der Antwort stehen soll (das Schaubild als SVG-Block)
     */
    public static ChatToolset with(ChatToolset base, Diagrams diagrams, Consumer<String> appendix) {
        if (diagrams == null) {
            return base;
        }
        return new ChatToolset() {
            @Override
            public List<AiChat.Tool> definitions() {
                List<AiChat.Tool> all = new ArrayList<>(base.definitions());
                all.add(diagramDefinition());
                return all;
            }

            @Override
            public String run(AiChat.ToolCall call) {
                return DIAGRAM.equals(call.name()) ? diagram(call, diagrams, appendix) : base.run(call);
            }

            @Override
            public String describe(AiChat.ToolCall call) {
                return DIAGRAM.equals(call.name()) ? "Schaubild wird gezeichnet …" : base.describe(call);
            }

            @Override
            public String viewContext() {
                return base.viewContext();
            }

            @Override
            public String viewIdentity() {
                return base.viewIdentity();
            }

            @Override
            public void modelSelected(String model) {
                base.modelSelected(model);
            }

            @Override
            public void cancel() {
                base.cancel();
            }
        };
    }

    static String diagram(AiChat.ToolCall call, Diagrams diagrams, Consumer<String> appendix) {
        JsonNode arguments;
        try {
            arguments = JSON.readTree(call.argumentsJson());
        } catch (JacksonException e) {
            return "Die Argumente sind kein gültiges JSON.";
        }
        String topic = arguments.path("thema").asString("").strip();
        String material = arguments.path("inhalt").asString("").strip();
        if (material.isEmpty()) {
            return "Gib im Feld inhalt an, was das Schaubild zeigen soll.";
        }
        Optional<String> svg;
        try {
            svg = diagrams.compose(topic.isEmpty() ? material : topic, material);
        } catch (RuntimeException e) {
            ChatTools.diagnosis.accept("Schaubild gescheitert: " + e.getClass().getSimpleName());
            return "Fehler: Die Bildgenerierung ist gescheitert (" + e.getClass().getSimpleName() + ").";
        }
        if (svg.isEmpty() || svg.get().isBlank()) {
            return DIAGRAM_NONE;
        }
        appendix.accept(block(svg.get()));
        return DIAGRAM_DRAWN;
    }

    /** Das SVG als Markdown-Block, den die Antwortblase als Bild zeigt. */
    static String block(String svg) {
        return "\n\n````svg\n" + svg.strip() + "\n````\n";
    }

    private static final java.util.regex.Pattern DIAGRAM_BLOCK = java.util.regex.Pattern.compile(
            "\\n*````svg\\n.*?\\n````\\n?", java.util.regex.Pattern.DOTALL);

    /** Eine gespeicherte Antwort ohne die Schaubilder, für den Verlauf an das Modell. */
    static String withoutDiagrams(String answer) {
        return answer == null || !answer.contains("````svg") ? answer
                : DIAGRAM_BLOCK.matcher(answer).replaceAll("\n\n[Schaubild]\n").strip();
    }
}
