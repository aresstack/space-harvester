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
 * @param projectId  das Projekt, zu dem der Chat gehört (ADR 0043, Nachtrag F2); {@code null} = Standardprojekt
 *                   „AresStack“. Eine Kennung, die es nicht (mehr) gibt, gilt ebenfalls als Standardprojekt; das
 *                   entscheidet {@code ProjectStore#resolve}, nicht der Chat. Geht mit dem Chat in den Papierkorb
 *                   und zurück, denn es steht in seiner Datei.
 * @param groupId    die farbige Gruppe innerhalb des Projekts (F2); {@code null} = ohne Gruppe, ebenso eine Kennung,
 *                   die das Projekt nicht kennt
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRecord(String id, String title, long createdAt, long modifiedAt, List<Message> messages,
                         @com.fasterxml.jackson.annotation.JsonInclude(
                                 com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         String projectId,
                         @com.fasterxml.jackson.annotation.JsonInclude(
                                 com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         String groupId) {

    public ChatRecord {
        Objects.requireNonNull(id, "id");
        title = title == null || title.isBlank() ? id : title;
        messages = List.copyOf(messages == null ? List.of() : messages);
        projectId = projectId == null || projectId.isBlank() ? null : projectId;
        groupId = groupId == null || groupId.isBlank() ? null : groupId;
    }

    /** Ein Chat des Standardprojekts ohne Gruppe. */
    public ChatRecord(String id, String title, long createdAt, long modifiedAt, List<Message> messages) {
        this(id, title, createdAt, modifiedAt, messages, null, null);
    }

    /** Ein neuer, leerer Chat mit frischer UUID als Titel im Standardprojekt. */
    public static ChatRecord fresh(long now) {
        return fresh(now, null);
    }

    /** Ein neuer, leerer Chat mit frischer UUID als Titel im Projekt {@code projectId} (ADR 0043, F6). */
    public static ChatRecord fresh(long now, String projectId) {
        return fresh(now, projectId, null);
    }

    /** Ein neuer, leerer Chat im Projekt und gleich in einer seiner Gruppen („Neuer Chat in dieser Gruppe“). */
    public static ChatRecord fresh(long now, String projectId, String groupId) {
        String id = UUID.randomUUID().toString();
        return new ChatRecord(id, id, now, now, List.of(), projectId, groupId);
    }

    /**
     * Derselbe Chat in einem anderen Projekt, ohne Gruppe („In Projekt verschieben“); {@code null} = Standardprojekt.
     * Die Änderungszeit bleibt, damit der Chat in den Listen nicht nach oben springt.
     */
    public ChatRecord inProject(String newProjectId) {
        return new ChatRecord(id, title, createdAt, modifiedAt, messages, newProjectId, null);
    }

    /** Derselbe Chat in einer Gruppe seines Projekts; {@code null} = ohne Gruppe. Die Änderungszeit bleibt. */
    public ChatRecord inGroup(String newGroupId) {
        return new ChatRecord(id, title, createdAt, modifiedAt, messages, projectId, newGroupId);
    }

    /**
     * Derselbe Chat mit umgeschriebenem Text jeder Nachricht (etwa Verweise auf umgezogene Artefakte, ADR 0043 F3);
     * Änderungszeit und alles andere bleiben.
     */
    public ChatRecord withTexts(java.util.function.UnaryOperator<String> rewrite) {
        List<Message> next = new ArrayList<>(messages.size());
        for (Message message : messages) {
            next.add(new Message(message.role(), rewrite.apply(message.text()), message.cancelled(), message.at(),
                    message.area(), message.scope(), message.sources()));
        }
        return new ChatRecord(id, title, createdAt, modifiedAt, next, projectId, groupId);
    }

    /** Ob der Titel vom Benutzer stammt (nicht mehr die Kennung). */
    public boolean renamed() {
        return !title.equals(id);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public ChatRecord withTitle(String newTitle, long now) {
        return new ChatRecord(id, newTitle, createdAt, now, messages, projectId, groupId);
    }

    public ChatRecord plus(Message message, long now) {
        List<Message> next = new ArrayList<>(messages);
        next.add(message);
        return new ChatRecord(id, title, createdAt, now, next, projectId, groupId);
    }

    /**
     * Eine Nachricht.
     *
     * @param role      {@code user} oder {@code assistant}
     * @param text      der Text; bei einer abgebrochenen Antwort der bis dahin empfangene
     * @param cancelled ob die Antwort abgebrochen wurde; sie bleibt sichtbar, geht aber nicht mehr an das Modell
     * @param at        Epoche in Millisekunden
     * @param area      bei einer Frage der Bereich in Worten, für den sie gestellt wurde (die Auswahl links beim
     *                  Senden, Angelo 21.11.2026: der Chat bleibt stehen, der Bereich wandert); {@code null} bei
     *                  Antworten und bei Chats aus der Zeit davor
     * @param scope     bei einer Frage die Kennung dieses Bereichs (Quelle, Space, Seite), an der ein Wechsel des
     *                  Bereichs erkannt wird, auch wenn zwei Bereiche gleich heißen; {@code null} wie {@code area}
     * @param sources   bei einer Antwort die nummerierten Quellen, die das Modell hatte (Position = Nummer, ab 1);
     *                  leer bei Fragen, ohne Quellen und bei Chats aus der Zeit davor
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String text, boolean cancelled, long at, String area, String scope,
                          @com.fasterxml.jackson.annotation.JsonInclude(
                                  com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                          List<Source> sources) {

        public static final String USER = "user";
        public static final String ASSISTANT = "assistant";

        public Message {
            Objects.requireNonNull(role, "role");
            text = text == null ? "" : text;
            area = area == null || area.isBlank() ? null : area;
            scope = scope == null || scope.isBlank() ? null : scope;
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public Message(String role, String text, boolean cancelled, long at, String area, String scope) {
            this(role, text, cancelled, at, area, scope, List.of());
        }

        public Message(String role, String text, boolean cancelled, long at) {
            this(role, text, cancelled, at, null, null);
        }

        public static Message user(String text, long at) {
            return new Message(USER, text, false, at, null, null);
        }

        /** Eine Frage samt dem Bereich, für den sie gestellt wurde: in Worten und als Kennung. */
        public static Message user(String text, long at, String area, String scope) {
            return new Message(USER, text, false, at, area, scope);
        }

        public static Message assistant(String text, boolean cancelled, long at) {
            return new Message(ASSISTANT, text, cancelled, at, null, null);
        }

        /** Eine Antwort samt ihren nummerierten Quellen. */
        public static Message assistant(String text, boolean cancelled, long at, List<Source> sources) {
            return new Message(ASSISTANT, text, cancelled, at, null, null, sources);
        }

        public boolean user() {
            return USER.equals(role);
        }

        public boolean assistant() {
            return ASSISTANT.equals(role);
        }
    }

    /**
     * Eine Quelle einer Antwort, wie sie das Modell als [n] zitieren konnte.
     *
     * @param title    Titel der Seite
     * @param sourceId Kennung der Wissensquelle
     * @param pageId   die Seite oder {@code null}, wenn nicht bekannt (etwa beim Überblick)
     * @param location Adresse der Seite oder {@code null}
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String title, String sourceId, String pageId, String location) {

        public Source {
            Objects.requireNonNull(sourceId, "sourceId");
        }

        /** Die Quelle eines Auszugs aus dem Kontext des Turns. */
        public static Source of(ChatKnowledge.Excerpt excerpt) {
            return new Source(excerpt.title(), excerpt.sourceId(), excerpt.pageId(), excerpt.location());
        }
    }
}
