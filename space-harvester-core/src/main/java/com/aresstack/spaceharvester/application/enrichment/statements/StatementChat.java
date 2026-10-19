package com.aresstack.spaceharvester.application.enrichment.statements;

import java.util.List;
import java.util.Objects;

/**
 * Das Chat-Modell der Satzanalyse (#108) als Port: Nachrichten hinein, der Text der Antwort heraus. Die Umsetzung über
 * den KI-Dienst ({@code POST /chat/completions}, {@code ai.chat-model}) liefert {@code RemoteAi#statementChat}; der
 * Core kennt nur diesen Port, wie {@code QueryVariantModel} der Suchvarianten (ADR 0017). Anders als dort gehen hier
 * Inhalte einer Quelle an den Dienst, darum nur für Quellen in {@code external-processing-sources}.
 */
public interface StatementChat {

    /** Kennung des Modells, z. B. {@code gpt-4o-mini}; Teil der Prozessorversion. */
    String model();

    /**
     * Ohne Token-Grenze und ohne Sampling-Optionen, wie im Enterprise AI Client (Reasoning-Modelle zählen ihr Denken
     * gegen eine Grenze); nur das Modell ist gesetzt.
     *
     * @return die Antwort des Modells, unbearbeitet
     * @throws RuntimeException wenn der Dienst nicht antwortet; die Meldung nennt nie Nachrichten, Antwort oder Schlüssel
     */
    String complete(List<Message> messages);

    /** Wer spricht. */
    enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    /**
     * Eine Nachricht.
     *
     * @param role    Rolle
     * @param content Text
     */
    record Message(Role role, String content) {

        public Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(content, "content");
        }

        public static Message system(String content) {
            return new Message(Role.SYSTEM, content);
        }

        public static Message user(String content) {
            return new Message(Role.USER, content);
        }

        public static Message assistant(String content) {
            return new Message(Role.ASSISTANT, content);
        }

        @Override
        public String toString() {
            return "Message[" + role + ", " + content.length() + " Zeichen]";
        }
    }
}
