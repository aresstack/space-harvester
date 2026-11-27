package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Füllt Felder der Buch-Eingabe mit der KI: je Feld eine Anfrage, höchstens {@code capacity} zugleich (Parallelität
 * des KI-Dienstes), jede gestreamt. Jedes Teilstück geht sofort an {@link Listener#delta}; scheitert ein Feld, laufen die
 * übrigen weiter. {@link #cancel} bricht alle laufenden ab und startet keine weiteren. Nichts wird protokolliert: die
 * Stichpunkte sind Inhalt des Lesers.
 */
public final class BookFieldFill {

    /** Meldungen je Feld, aus beliebigem Thread. */
    public interface Listener {
        /** Das Feld beginnt; sein alter Inhalt kann weg. */
        void started(BookFields.Field field);

        /** Ein Teilstück der Antwort. */
        void delta(BookFields.Field field, String text);

        /** Die bereinigte, vollständige Antwort ({@link BookFields#clean}). */
        void completed(BookFields.Field field, String text);

        /** Das Feld ist gescheitert oder abgebrochen; {@code reason} in Worten. */
        void failed(BookFields.Field field, String reason);

        /** Alle Felder sind fertig, gescheitert oder abgebrochen. */
        void finished();
    }

    private final AiChat chat;
    private final String model;
    private final int capacity;
    private final Deque<BookFields.Field> waiting = new ArrayDeque<>();
    private final List<AiChat.Task> running = new ArrayList<>();
    private final java.util.Set<BookFields.Field> ended = java.util.EnumSet.noneOf(BookFields.Field.class);
    private Map<BookFields.Field, List<AiChat.Message>> requests;
    private Listener listener;
    private int open;
    private boolean cancelled;

    /**
     * @param model    Chat-Modell laut KI-Einstellungen; ohne Nachdenken (kein reasoning_effort) wie die übrigen
     *                 Analyseschritte
     * @param capacity gleichzeitige Anfragen
     */
    public BookFieldFill(AiChat chat, String model, int capacity) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.model = Objects.requireNonNull(model, "model");
        this.capacity = Math.max(1, capacity);
    }

    /** Startet die Anfragen je Feld in der Reihenfolge der Map und kehrt sofort zurück. */
    public synchronized void start(Map<BookFields.Field, List<AiChat.Message>> requests, Listener listener) {
        this.requests = Map.copyOf(requests);
        this.listener = Objects.requireNonNull(listener, "listener");
        waiting.addAll(requests.keySet());
        open = waiting.size();
        if (open == 0) {
            listener.finished();
            return;
        }
        for (int i = 0; i < capacity; i++) {
            next();
        }
    }

    /** Bricht alle laufenden Anfragen ab; wartende starten nicht mehr. */
    public void cancel() {
        List<AiChat.Task> tasks;
        List<BookFields.Field> dropped;
        synchronized (this) {
            cancelled = true;
            tasks = new ArrayList<>(running);
            dropped = new ArrayList<>(waiting);
            waiting.clear();
        }
        for (BookFields.Field field : dropped) {
            done(field, null, "abgebrochen");
        }
        tasks.forEach(AiChat.Task::cancel);
    }

    private void next() {
        BookFields.Field field;
        synchronized (this) {
            field = cancelled ? null : waiting.poll();
        }
        if (field == null) {
            return;
        }
        listener.started(field);
        AiChat.Task[] task = new AiChat.Task[1];
        try {
            task[0] = chat.stream(new AiChat.Request(requests.get(field), model, null, List.of(), null, null),
                    new AiChat.Listener() {
                        @Override
                        public void delta(String text) {
                            if (text != null && !text.isEmpty()) {
                                listener.delta(field, text);
                            }
                        }

                        @Override
                        public void completed(String text) {
                            finish(task[0], field, BookFields.clean(field, text), null);
                        }

                        @Override
                        public void failed(AiChatException error) {
                            finish(task[0], field, null, error.getMessage() == null || error.getMessage().isBlank()
                                    ? String.valueOf(error.kind()) : error.getMessage());
                        }

                        @Override
                        public void cancelled() {
                            finish(task[0], field, null, "abgebrochen");
                        }
                    });
        } catch (RuntimeException failed) {
            finish(null, field, null, failed.getMessage() == null ? failed.getClass().getSimpleName()
                    : failed.getMessage());
            return;
        }
        synchronized (this) {
            // Eine sofort beendete Anfrage (etwa ohne Netz) kommt nicht mehr in die Liste der laufenden.
            if (task[0] != null && !ended.contains(field)) {
                running.add(task[0]);
            }
        }
    }

    private void finish(AiChat.Task task, BookFields.Field field, String text, String problem) {
        synchronized (this) {
            running.remove(task);
            ended.add(field);
        }
        done(field, text, problem);
        next();
    }

    private void done(BookFields.Field field, String text, String problem) {
        if (problem == null) {
            listener.completed(field, text);
        } else {
            listener.failed(field, problem);
        }
        boolean last;
        synchronized (this) {
            last = --open == 0;
        }
        if (last) {
            listener.finished();
        }
    }
}
