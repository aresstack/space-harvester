package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.app.runtime.ModelBudget;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Wie viel Text ein Werkzeugergebnis des Chats (Seite lesen, Liste, Ansicht und Texte im Command Deck) oder die offene
 * Seite mitbringen darf: so viel, wie in der laufenden Anfrage noch Platz hat, abgeleitet aus der Kontextlänge des
 * Chat-Modells ({@link ModelBudget}: Kontextlänge abzüglich Ausgabelimit als Antwortreserve) und dem, was die Anfrage
 * schon enthält. Ersetzt die früheren festen Zeichengrenzen ({@code ChatTools.READ_CHARS}, {@code PAGE_SIZE},
 * {@code ChatTurn.OPEN_PAGE_CHARS}, {@code DeckChatTools.*_CHARS}, docs/grenzen.md „Modellkontext“). Was nicht passt,
 * holt das Modell mit Versatz im nächsten Aufruf; nichts geht verloren.
 *
 * <p>Laufen mehrere Werkzeuge einer Runde, teilen sie sich den Platz zu gleichen Teilen. Ohne bekannte Kontextlänge
 * (Einstellungen › KI-Dienst) gilt keine Grenze, wie bei der Diagrammbeschriftung. Der Platz gilt für den Thread, auf
 * dem das Werkzeug läuft ({@link #within}); außerhalb eines Turns gibt es keine Grenze.
 */
public final class ChatRoom {

    /** Keine Grenze: Kontextlänge unbekannt oder außerhalb eines Turns. */
    public static final int UNLIMITED = Integer.MAX_VALUE;
    /** Antwort eines Werkzeugs, wenn die Anfrage voll ist. */
    public static final String FULL = "Die Anfrage hat keinen Platz mehr (Kontextlänge des Chat-Modells erreicht). "
            + "Antworte mit dem bisher Gelesenen.";

    private static final ThreadLocal<Integer> CHARS = new ThreadLocal<>();

    private ChatRoom() {
    }

    /** Zeichen, die das Werkzeug auf diesem Thread gerade liefern darf. */
    public static int chars() {
        Integer chars = CHARS.get();
        return chars == null ? UNLIMITED : chars;
    }

    /** Führt {@code work} mit {@code chars} Zeichen Platz aus. */
    public static <T> T within(int chars, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        Integer before = CHARS.get();
        CHARS.set(Math.max(0, chars));
        try {
            return work.get();
        } finally {
            if (before == null) {
                CHARS.remove();
            } else {
                CHARS.set(before);
            }
        }
    }

    /**
     * Der freie Platz in Zeichen, je Teil: Kontextlänge abzüglich Antwortreserve, Nachrichten und Werkzeugbeschreibungen,
     * geteilt durch {@code parts}; ohne Budget {@link #UNLIMITED}, nie unter 0.
     */
    public static int left(ModelBudget budget, List<AiChat.Message> messages, List<AiChat.Tool> tools, int parts) {
        if (budget == null) {
            return UNLIMITED;
        }
        long used = 0;
        for (AiChat.Message message : messages == null ? List.<AiChat.Message>of() : messages) {
            used += tokens(message.text());
            for (AiChat.ToolCall call : message.toolCalls() == null ? List.<AiChat.ToolCall>of()
                    : message.toolCalls()) {
                used += tokens(call.name()) + tokens(call.argumentsJson());
            }
        }
        for (AiChat.Tool tool : tools == null ? List.<AiChat.Tool>of() : tools) {
            used += tokens(tool.name()) + tokens(tool.description()) + tokens(tool.parametersSchemaJson());
        }
        long room = budget.left((int) Math.min(Integer.MAX_VALUE, used));
        if (room <= 0) {
            return 0;
        }
        long chars = room * ModelBudget.CHARACTERS_PER_TOKEN / Math.max(1, parts);
        return (int) Math.min(UNLIMITED - 1L, chars);
    }

    private static int tokens(String text) {
        return text == null || text.isEmpty() ? 0 : ModelBudget.tokens(text);
    }
}
