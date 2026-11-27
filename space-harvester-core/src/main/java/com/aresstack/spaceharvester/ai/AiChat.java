package com.aresstack.spaceharvester.ai;

import java.util.List;
import java.util.Objects;

/**
 * Streamender Chat mit dem Chat-Modell des KI-Dienstes, quellenunabhängig (eigene SPI wie {@code StatementChat}): die
 * Kopie aus enterprise-ai-sources bleibt hinter dem Quellenadapter {@code RemoteAi}. Der Chat selbst (Verlauf,
 * Wissen, Oberfläche) lebt in der App (ADR 0012); der Core gibt nur die Verbindung.
 *
 * <p>Lebenszyklus je {@link #stream}: beliebig viele {@link Listener#delta}, danach genau ein Abschluss
 * ({@code completed}, {@code failed} oder {@code cancelled}), in der Regel auf einem Thread des Adapters, bei einem
 * sofort erkannten Fehler auch schon vor der Rückkehr auf dem aufrufenden Thread.
 *
 * <p>Werkzeuge (ADR 0031): Eine Anfrage darf {@link Tool}-Definitionen mitgeben. Will das Modell eines aufrufen, endet
 * der Strom mit {@link Listener#toolCalls} statt {@code completed}; der Aufrufer führt die Werkzeuge aus und stellt
 * eine neue Anfrage mit der Assistenten-Nachricht samt Aufrufen und je einer {@link Role#TOOL}-Nachricht als Ergebnis.
 * Die Werkzeuge selbst leben beim Aufrufer; diese Schnittstelle transportiert nur Definitionen, Aufrufe und Ergebnisse.
 */
public interface AiChat {

    /** Startet die Antwort und kehrt sofort zurück; das Handle bricht sie ab. Nie {@code null}. */
    Task stream(Request request, Listener listener);

    enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        /** Ergebnis eines Werkzeugaufrufs; {@link Message#toolCallId()} nennt den Aufruf. */
        TOOL
    }

    /**
     * Ein Werkzeug, das das Modell aufrufen darf.
     *
     * @param parametersSchemaJson JSON-Schema der Argumente (ein Objekt)
     */
    record Tool(String name, String description, String parametersSchemaJson) {

        public Tool {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name");
            }
            description = description == null ? "" : description;
            parametersSchemaJson = parametersSchemaJson == null || parametersSchemaJson.isBlank()
                    ? "{\"type\":\"object\",\"properties\":{}}" : parametersSchemaJson;
        }
    }

    /**
     * Ein Aufruf des Modells.
     *
     * @param id            Kennung des Aufrufs, an der das Ergebnis hängt (bei Diensten ohne Kennung vergeben)
     * @param argumentsJson Argumente als JSON-Objekt
     */
    record ToolCall(String id, String name, String argumentsJson) {

        public ToolCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        }

        /** Ohne Argumente: Sie können Inhalte des Benutzers tragen. */
        @Override
        public String toString() {
            return "ToolCall[" + id + ", " + name + ", " + argumentsJson.length() + " Zeichen]";
        }
    }

    /**
     * @param toolCalls  nur beim Assistenten: die Aufrufe, die er in dieser Nachricht verlangt hat
     * @param toolCallId nur bei {@link Role#TOOL}: der Aufruf, dessen Ergebnis die Nachricht trägt
     */
    record Message(Role role, String text, List<ToolCall> toolCalls, String toolCallId) {

        public Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (role == Role.TOOL && (toolCallId == null || toolCallId.isEmpty())) {
                throw new IllegalArgumentException("toolCallId");
            }
        }

        public Message(Role role, String text) {
            this(role, text, List.of(), null);
        }

        public static Message system(String text) {
            return new Message(Role.SYSTEM, text);
        }

        public static Message user(String text) {
            return new Message(Role.USER, text);
        }

        public static Message assistant(String text) {
            return new Message(Role.ASSISTANT, text);
        }

        /** Die Antwort des Assistenten, die Werkzeuge aufruft (gehört vor die Ergebnisse in den Verlauf). */
        public static Message assistant(String text, List<ToolCall> calls) {
            return new Message(Role.ASSISTANT, text, calls, null);
        }

        public static Message tool(String toolCallId, String text) {
            return new Message(Role.TOOL, text, List.of(), toolCallId);
        }

        /** Ohne Text: Er kann Inhalte des Benutzers oder einer Quelle tragen. */
        @Override
        public String toString() {
            return "Message[" + role + ", " + text.length() + " Zeichen, " + toolCalls.size() + " Aufrufe]";
        }
    }

    /**
     * @param model           Modellname wie konfiguriert
     * @param reasoningEffort Denkaufwand des Modells ({@code low}, {@code medium}, {@code high}) oder {@code null}
     * @param tools           Werkzeuge, die das Modell aufrufen darf; leer: keine
     * @param temperature     Temperatur der Antwort; {@code null}: die des Dienstes
     * @param maxTokens       Ausgabelimit der Antwort; {@code null}: das des Dienstes
     */
    record Request(List<Message> messages, String model, String reasoningEffort, List<Tool> tools, Double temperature,
                   Integer maxTokens) {

        public Request {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            Objects.requireNonNull(model, "model");
            tools = tools == null ? List.of() : List.copyOf(tools);
            if (maxTokens != null && maxTokens < 1) {
                throw new IllegalArgumentException("maxTokens");
            }
        }

        public Request(List<Message> messages, String model, String reasoningEffort, List<Tool> tools) {
            this(messages, model, reasoningEffort, tools, null, null);
        }

        public Request(List<Message> messages, String model, String reasoningEffort) {
            this(messages, model, reasoningEffort, List.of());
        }
    }

    interface Listener {

        void delta(String text);

        /** Die vollständige Antwort (die gestreamten Teile oder, ohne Teile, der Inhalt der Abschlussnachricht). */
        void completed(String text);

        void failed(AiChatException error);

        void cancelled();

        /**
         * Das Modell verlangt Werkzeugaufrufe (nur bei einer Anfrage mit Werkzeugen); ersetzt {@code completed}.
         *
         * @param text was das Modell vor den Aufrufen schon geschrieben hat, oft leer
         */
        default void toolCalls(String text, List<ToolCall> calls) {
            completed(text);
        }

        /** Das Modell wird gerade geladen (etwa bei Ollama); die Antwort kommt, sobald es läuft. */
        default void loading() {
        }
    }

    interface Task {

        void cancel();
    }
}
