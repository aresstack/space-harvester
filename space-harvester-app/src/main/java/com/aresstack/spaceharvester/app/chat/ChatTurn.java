package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.passages.PassageLookup;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveResult;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearch;
import com.aresstack.spaceharvester.application.query.progressive.ProgressiveSearchListener;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.sources.PageMetadata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ein Turn des Chats (nach {@code ChatServiceBinding} und {@code RagChatBinding} des Enterprise AI Client, auf das
 * Nötige verkürzt): erst das Wissen aus dem Bestand holen, dann die Frage mit Verlauf und Kontext an das Chat-Modell
 * streamen. Deltas, Abschluss, Abbruch und Fehler kommen vom Adapter-Thread und gehen über {@code ui} (in der App
 * {@code SwingUtilities::invokeLater}) an den {@link Listener}; nach dem Ende kommt nichts mehr.
 *
 * <p>Wissen (ADR 0029): die links gewählten Seiten. Sind es wenige, gehen sie ganz mit; sonst sucht die progressive
 * Suche des Core (ADR 0017) nur in diesen Seiten und die besten Treffer kommen als Auszüge mit. Was nicht lokal
 * gespeichert ist, fehlt; das sagt die Statuszeile des Wissens. Die Suche blockiert und läuft deshalb nie auf dem EDT.
 */
public final class ChatTurn {

    /** Bis so viele Seiten gehen die Seiten ganz mit, statt zu suchen. */
    static final int WHOLE_PAGES = 3;
    /** Längstens so lange wartet der Turn auf die Suche; danach zählen die bis dahin bewerteten Treffer. */
    static final long SEARCH_SECONDS = 90;
    /** Die Suche nimmt höchstens so viele Zeichen Frage an ({@code KeywordSearch.MAX_TEXT_LENGTH}). */
    static final int MAX_QUERY_CHARS = 500;

    /** Was die Oberfläche vom Turn erfährt, immer auf dem UI-Thread. */
    public interface Listener {

        /** Der Kontext steht: so viele Auszüge gehen mit (0 ohne Wissen). */
        void context(ChatKnowledge.Context context);

        void delta(String text);

        void completed(String text);

        /** Mit lesbarer Meldung; die Blase zeigt sie statt einer Antwort. */
        void failed(String message);

        /** Mit dem bis dahin empfangenen Text. */
        void cancelled(String partial);
    }

    private final Workspace workspace;
    private final AiChat port;
    private final String model;
    private final String effort;
    private final List<ChatRecord.Message> history;
    private final String question;
    private final PageSelection scope;
    private final Listener listener;
    private final Executor ui;
    private final StringBuilder received = new StringBuilder();
    private volatile boolean cancelRequested;
    private volatile boolean closed;
    private volatile AiChat.Task task;
    private volatile ProgressiveSearch search;

    public ChatTurn(Workspace workspace, AiChat port, String model, String effort,
                    List<ChatRecord.Message> history, String question, PageSelection scope, Listener listener,
                    Executor ui) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.port = Objects.requireNonNull(port, "port");
        this.model = Objects.requireNonNull(model, "model");
        this.effort = effort;
        this.history = List.copyOf(history);
        this.question = Objects.requireNonNull(question, "question");
        this.scope = scope;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    /** Startet den Turn auf einem eigenen Thread; kehrt sofort zurück. */
    public void start() {
        Thread worker = new Thread(this::run, "chat-turn");
        worker.setDaemon(true);
        worker.start();
    }

    /** Bricht Suche oder Antwort ab; die Abbruchmeldung kommt über den Listener. */
    public void cancel() {
        cancelRequested = true;
        ProgressiveSearch running = search;
        if (running != null) {
            running.cancel();
        }
        AiChat.Task streaming = task;
        if (streaming != null) {
            streaming.cancel();
        }
    }

    private void run() {
        ChatKnowledge.Context context;
        try {
            context = gather();
        } catch (RuntimeException e) {
            // Ohne Wissen antwortet das Modell trotzdem; die Statuszeile sagt, dass die Suche scheiterte.
            context = ChatKnowledge.Context.EMPTY;
        }
        if (cancelRequested) {
            finish(() -> listener.cancelled(""));
            return;
        }
        ChatKnowledge.Context chosen = context;
        onUi(() -> listener.context(chosen));
        List<AiChat.Message> messages = new ArrayList<>();
        messages.add(AiChat.Message.system(context.isEmpty() ? ChatKnowledge.SYSTEM_PROMPT
                : ChatKnowledge.SYSTEM_PROMPT + "\n\n" + context.text()));
        for (ChatRecord.Message message : history) {
            if (message.text().isBlank()) {
                continue;
            }
            if (message.user()) {
                messages.add(AiChat.Message.user(message.text()));
            } else if (message.assistant() && !message.cancelled()) {
                // Abgebrochene Teilantworten bleiben, wie im EAC, aus dem Modellverlauf.
                messages.add(AiChat.Message.assistant(message.text()));
            }
        }
        messages.add(AiChat.Message.user(question));
        task = port.stream(new AiChat.Request(messages, model, effort), new AiChat.Listener() {
            @Override
            public void delta(String text) {
                synchronized (received) {
                    received.append(text);
                }
                onUi(() -> listener.delta(text));
            }

            @Override
            public void completed(String text) {
                finish(() -> listener.completed(text));
            }

            @Override
            public void failed(AiChatException error) {
                String message = describe(error);
                finish(() -> listener.failed(message));
            }

            @Override
            public void cancelled() {
                String partial;
                synchronized (received) {
                    partial = received.toString();
                }
                finish(() -> listener.cancelled(partial));
            }
        });
        if (cancelRequested) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------ Wissen

    private ChatKnowledge.Context gather() {
        if (scope == null || scope.pages().isEmpty()) {
            return ChatKnowledge.Context.EMPTY;
        }
        List<ChatKnowledge.Excerpt> excerpts = scope.size() <= WHOLE_PAGES ? wholePages() : searched();
        return ChatKnowledge.assemble(excerpts);
    }

    private List<ChatKnowledge.Excerpt> wholePages() {
        List<ChatKnowledge.Excerpt> excerpts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> source : scope.pages().asMap().entrySet()) {
            for (String pageId : source.getValue()) {
                ChatKnowledge.Excerpt excerpt = page(source.getKey(), pageId, null, ChatKnowledge.MAX_PAGE_CHARS);
                if (excerpt != null) {
                    excerpts.add(excerpt);
                }
            }
        }
        return excerpts;
    }

    private List<ChatKnowledge.Excerpt> searched() {
        String text = question.strip().replaceAll("\\s+", " ");
        if (text.length() > MAX_QUERY_CHARS) {
            text = text.substring(0, MAX_QUERY_CHARS);
        }
        KeywordSearch.Query query = new KeywordSearch.Query(text, null, Set.of(), Set.of()).within(scope.pages());
        AtomicReference<List<ProgressiveResult.RankedHit>> latest = new AtomicReference<>(List.of());
        ProgressiveSearch running = workspace.progressiveSearch(query, new ProgressiveSearchListener() {
            @Override
            public void ranked(List<ProgressiveResult.RankedHit> hits) {
                latest.set(hits);
            }
        });
        search = running;
        List<ProgressiveResult.RankedHit> hits;
        try {
            hits = running.result().get(SEARCH_SECONDS, TimeUnit.SECONDS).hits();
        } catch (TimeoutException slow) {
            running.cancel();
            hits = latest.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.cancel();
            hits = latest.get();
        } catch (ExecutionException | CancellationException failed) {
            hits = latest.get();
        } finally {
            search = null;
        }
        List<ChatKnowledge.Excerpt> excerpts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ProgressiveResult.RankedHit ranked : hits) {
            KeywordSearch.Hit hit = ranked.hit();
            if (!seen.add(hit.sourceId() + "\u0000" + hit.pageId())) {
                continue;
            }
            ChatKnowledge.Excerpt excerpt = passage(hit);
            if (excerpt == null) {
                excerpt = page(hit.sourceId(), hit.pageId(), hit.snippet(), ChatKnowledge.MAX_HIT_CHARS);
            }
            if (excerpt != null) {
                excerpts.add(excerpt);
            }
            if (excerpts.size() >= ChatKnowledge.MAX_SOURCES) {
                break;
            }
        }
        return excerpts;
    }

    /** Der Absatz des Treffers samt Nachbarn (ADR 0020), wenn der Treffer einen kennt; sonst {@code null}. */
    private ChatKnowledge.Excerpt passage(KeywordSearch.Hit hit) {
        if (hit.passageId() == null) {
            return null;
        }
        try {
            PassageLookup lookup = workspace.passage(hit.passageId());
            StringBuilder text = new StringBuilder();
            for (PagePassages.Passage passage : lookup.passages()) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(passage.text());
            }
            return text.isEmpty() ? null : new ChatKnowledge.Excerpt(hit.title(), hit.sourceId(),
                    hit.url() == null ? null : hit.url().toString(),
                    ChatKnowledge.excerpt(text.toString(), ChatKnowledge.MAX_HIT_CHARS));
        } catch (RuntimeException gone) {
            return null;
        }
    }

    /**
     * Der Lesetext der Seite nur aus dem Cache, gekürzt; {@code null}, wenn sie nicht lokal liegt. Nie ein Abruf bei
     * der Quelle: An den KI-Dienst geht nur, was lokal gespeichert und damit beim Senden freigegeben ist (ADR 0028).
     */
    private ChatKnowledge.Excerpt page(String sourceId, String pageId, String fallback, int maxChars) {
        try {
            DeliveredPage page = workspace.cachedPage(sourceId, pageId).orElse(null);
            if (page == null) {
                return null;
            }
            PageText text = workspace.pageText(page.snapshot());
            PageMetadata metadata = page.header().metadata();
            String body = text == null || text.text().isBlank() ? fallback : text.text();
            if (body == null || body.isBlank()) {
                return null;
            }
            return new ChatKnowledge.Excerpt(metadata.title(), sourceId,
                    metadata.url() == null ? null : metadata.url().toString(), ChatKnowledge.excerpt(body, maxChars));
        } catch (RuntimeException notLocal) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Zustellung

    private void finish(Runnable terminal) {
        onUi(() -> {
            if (!closed) {
                closed = true;
                terminal.run();
            }
        });
    }

    private void onUi(Runnable update) {
        ui.execute(() -> {
            if (!closed) {
                update.run();
            }
        });
    }

    /**
     * Für Menschen lesbare Fehlermeldung wie {@code ChatServiceBinding.headline} des Enterprise AI Client: erste Zeile
     * die Fehlerart, dann die technische Ursache des Adapters (laut Port-Vertrag ohne Zugangsdaten).
     */
    public static String describe(AiChatException error) {
        String reason = switch (error.kind()) {
            case AUTHENTICATION -> "Anmeldung am KI-Dienst fehlgeschlagen.";
            case CREDENTIALS_UNAVAILABLE -> "Der API-Key des KI-Dienstes ist nicht verfügbar.";
            case RATE_LIMITED -> "Der KI-Dienst ist ausgelastet. Bitte später erneut versuchen.";
            case INVALID_REQUEST -> "Der KI-Dienst hat die Anfrage abgelehnt.";
            case PROVIDER_ERROR -> "Fehler im KI-Dienst.";
            case TRANSPORT -> "Der KI-Dienst ist nicht erreichbar.";
            default -> "Unerwartete Antwort des KI-Dienstes.";
        };
        if (error.statusCode() > 0) {
            reason = reason + " (Status " + error.statusCode() + ")";
        }
        String detail = error.getMessage();
        return detail == null || detail.isBlank() ? reason : reason + "\nTechnische Ursache: " + detail;
    }
}
