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
 */
public interface AiChat {

    /** Startet die Antwort und kehrt sofort zurück; das Handle bricht sie ab. Nie {@code null}. */
    Task stream(Request request, Listener listener);

    enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    record Message(Role role, String text) {

        public Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(text, "text");
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
    }

    /**
     * @param model           Modellname wie konfiguriert
     * @param reasoningEffort Denkaufwand des Modells ({@code low}, {@code medium}, {@code high}) oder {@code null}
     */
    record Request(List<Message> messages, String model, String reasoningEffort) {

        public Request {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            Objects.requireNonNull(model, "model");
        }
    }

    interface Listener {

        void delta(String text);

        /** Die vollständige Antwort (die gestreamten Teile oder, ohne Teile, der Inhalt der Abschlussnachricht). */
        void completed(String text);

        void failed(AiChatException error);

        void cancelled();
    }

    interface Task {

        void cancel();
    }
}
