package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.model.chat.ChatMessage;
import com.aresstack.knowledge4j.model.chat.MainModelChat;
import com.aresstack.knowledge4j.model.chat.MainModelChatResult;
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Das Chat-Modell des KI-Dienstes als {@link MainModelChat} für die Bibliotheken: jeder Aufruf streamt (Proxy-Fristen)
 * und wartet auf die vollständige Antwort. Temperatur und Ausgabelänge gehen mit; ein JSON-Schema kennt der Dienst
 * nicht, die Bibliotheken prüfen die Antwort selbst und schicken eine ungültige einmal zurück. Fehler kommen als
 * Ergebnis zurück, nie als Ausnahme.
 */
public final class AiChatModel implements MainModelChat {

    private final AiChat chat;
    private final String model;
    private final String reasoningEffort;
    private final int contextTokens;
    private final Set<AiChat.Task> running = ConcurrentHashMap.newKeySet();

    /**
     * @param reasoningEffort Denkaufwand wie in den KI-Einstellungen; {@code null} = aus
     * @param contextTokens   Kontextlänge des Modells laut Einstellung; 0 = unbekannt
     */
    public AiChatModel(AiChat chat, String model, String reasoningEffort, int contextTokens) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.model = Objects.requireNonNull(model, "model");
        this.reasoningEffort = reasoningEffort;
        this.contextTokens = Math.max(0, contextTokens);
    }

    @Override
    public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
        List<AiChat.Message> sent = new ArrayList<>();
        for (ChatMessage message : messages) {
            sent.add(switch (message.getRole()) {
                case SYSTEM -> AiChat.Message.system(message.getContent());
                case ASSISTANT -> AiChat.Message.assistant(message.getContent());
                default -> AiChat.Message.user(message.getContent());
            });
        }
        CompletableFuture<MainModelChatResult> done = new CompletableFuture<>();
        AiChat.Task task = chat.stream(new AiChat.Request(sent, model, reasoningEffort, List.of(), temperature,
                maxOutputTokens > 0 ? maxOutputTokens : null), new AiChat.Listener() {
            @Override
            public void delta(String text) {
            }

            @Override
            public void completed(String text) {
                done.complete(MainModelChatResult.ok(text == null ? "" : text));
            }

            @Override
            public void failed(AiChatException error) {
                done.complete(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        error.kind() + (error.getMessage() == null ? "" : ": " + error.getMessage())));
            }

            @Override
            public void cancelled() {
                done.complete(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "abgebrochen"));
            }
        });
        running.add(task);
        try {
            return done.get();
        } catch (InterruptedException interrupted) {
            task.cancel();
            Thread.currentThread().interrupt();
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "unterbrochen");
        } catch (ExecutionException failed) {
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    failed.getCause() == null ? "Fehler" : failed.getCause().getClass().getSimpleName());
        } finally {
            running.remove(task);
        }
    }

    @Override
    public int contextTokens() {
        return contextTokens;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public void cancelInFlight() {
        running.forEach(AiChat.Task::cancel);
    }
}
