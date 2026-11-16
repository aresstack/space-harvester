package com.aresstack.spaceharvester.app.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ein gespeicherter Chat (nach {@code ChatRecord} des Enterprise AI Client, verschlankt): Kennung, Titel, Zeiten und
 * die Nachrichten. Die Kennung ist eine UUID und bleibt; der Titel ist anfangs die Kennung und wird, was der Benutzer
 * beim Umbenennen eingibt (Angelo, 16.11.2026).
 *
 * @param id         UUID, zugleich Dateiname
 * @param title      Anzeigename; gleich {@code id}, solange nicht umbenannt
 * @param createdAt  Epoche in Millisekunden
 * @param modifiedAt Epoche in Millisekunden der letzten Nachricht oder Umbenennung
 * @param messages   die Nachrichten in Reihenfolge
 */
public record ChatRecord(String id, String title, long createdAt, long modifiedAt, List<Message> messages) {

    public ChatRecord {
        Objects.requireNonNull(id, "id");
        title = title == null || title.isBlank() ? id : title;
        messages = List.copyOf(messages == null ? List.of() : messages);
    }

    /** Ein neuer, leerer Chat mit frischer UUID als Titel. */
    public static ChatRecord fresh(long now) {
        String id = UUID.randomUUID().toString();
        return new ChatRecord(id, id, now, now, List.of());
    }

    /** Ob der Titel vom Benutzer stammt (nicht mehr die Kennung). */
    public boolean renamed() {
        return !title.equals(id);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public ChatRecord withTitle(String newTitle, long now) {
        return new ChatRecord(id, newTitle, createdAt, now, messages);
    }

    public ChatRecord plus(Message message, long now) {
        List<Message> next = new ArrayList<>(messages);
        next.add(message);
        return new ChatRecord(id, title, createdAt, now, next);
    }

    /**
     * Eine Nachricht.
     *
     * @param role      {@code user} oder {@code assistant}
     * @param text      der Text; bei einer abgebrochenen Antwort der bis dahin empfangene
     * @param cancelled ob die Antwort abgebrochen wurde; sie bleibt sichtbar, geht aber nicht mehr an das Modell
     * @param at        Epoche in Millisekunden
     */
    public record Message(String role, String text, boolean cancelled, long at) {

        public static final String USER = "user";
        public static final String ASSISTANT = "assistant";

        public Message {
            Objects.requireNonNull(role, "role");
            text = text == null ? "" : text;
        }

        public static Message user(String text, long at) {
            return new Message(USER, text, false, at);
        }

        public static Message assistant(String text, boolean cancelled, long at) {
            return new Message(ASSISTANT, text, cancelled, at);
        }

        public boolean user() {
            return USER.equals(role);
        }

        public boolean assistant() {
            return ASSISTANT.equals(role);
        }
    }
}
