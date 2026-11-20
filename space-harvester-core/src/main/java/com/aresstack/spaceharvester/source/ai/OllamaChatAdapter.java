package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatImage;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.astu.chat.ChatUsage;
import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;
import com.aresstack.enterpriseai.chat.api.ChatStreamListener;
import com.aresstack.enterpriseai.chat.api.ChatTask;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat mit einem Ollama-Server über seine eigene Schnittstelle ({@code POST /api/chat}), wie in AskAI. Anders als die
 * OpenAI-kompatible Schnittstelle unter {@code /v1} nimmt sie die Kontextlänge je Anfrage an ({@code num_ctx}):
 * Ohne sie lädt Ollama ein Modell mit dessen voller Kontextlänge, und schon ein 12-Mrd.-Modell passt dann nicht mehr
 * in 16 GB Grafikspeicher. Jede Anfrage an dasselbe Modell trägt dieselbe Kontextlänge, sonst lädt Ollama es neu.
 *
 * <p>Bilder gehen als Base64 in {@code images} der Nachricht, das Ausgabelimit als {@code num_predict}, ein
 * {@code reasoningEffort} als {@code think} ({@code none} schaltet das Nachdenken ab). Meldungen nennen nur Pfad,
 * Status und Ausnahmeklasse, nie Nachrichten oder Antworten.
 */
final class OllamaChatAdapter implements ChatCompletionPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/chat";

    private final OllamaConnections connections;
    private final String model;
    private final int contextTokens;

    /**
     * @param model         Modell, wenn die Anfrage keines nennt
     * @param contextTokens Kontextlänge je Anfrage ({@code num_ctx})
     */
    OllamaChatAdapter(OllamaConnections connections, String model, int contextTokens) {
        this(connections, model, contextTokens, null);
    }

    /**
     * @param thinking {@code think} für Anfragen, die selbst kein {@code reasoningEffort} nennen (etwa die
     *                 Bildbeschreibung des Enterprise AI Client); {@code null}: keine Vorgabe
     */
    OllamaChatAdapter(OllamaConnections connections, String model, int contextTokens, String thinking) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.model = model;
        this.contextTokens = contextTokens;
        this.thinking = thinking;
    }

    private final String thinking;

    @Override
    public ChatResponse complete(ChatRequest request) {
        HttpURLConnection connection = send(request, false);
        try {
            JsonNode answer;
            try (InputStream in = connection.getInputStream()) {
                answer = JSON.readTree(in);
            } catch (JacksonException e) {
                throw new ChatCompletionException(ChatErrorKind.PROTOCOL, "POST " + PATH + ": Antwort nicht lesbar");
            } catch (IOException e) {
                throw transport(e);
            }
            return response(answer, answer.path("message").path("content").asString(""));
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public ChatTask stream(ChatRequest request, ChatStreamListener listener) {
        Objects.requireNonNull(listener, "listener");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<HttpURLConnection> open = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                listener.onStart();
                HttpURLConnection connection = send(request, true);
                open.set(connection);
                StringBuilder content = new StringBuilder();
                JsonNode last = null;
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while (!cancelled.get() && (line = lines.readLine()) != null) {
                        if (line.isBlank()) {
                            continue;
                        }
                        JsonNode chunk = JSON.readTree(line);
                        if (chunk.has("error")) {
                            throw new ChatCompletionException(ChatErrorKind.PROVIDER_ERROR,
                                    "POST " + PATH + ": Fehler im Strom");
                        }
                        String delta = chunk.path("message").path("content").asString("");
                        if (!delta.isEmpty()) {
                            content.append(delta);
                            listener.onDelta(delta);
                        }
                        if (chunk.path("done").asBoolean(false)) {
                            last = chunk;
                            break;
                        }
                    }
                } finally {
                    connection.disconnect();
                }
                if (cancelled.get()) {
                    listener.onCancelled();
                } else if (last == null) {
                    throw new ChatCompletionException(ChatErrorKind.PROTOCOL, "POST " + PATH + ": Strom ohne Ende");
                } else {
                    listener.onComplete(response(last, content.toString()));
                }
            } catch (ChatCompletionException e) {
                if (cancelled.get()) {
                    listener.onCancelled();
                } else {
                    listener.onError(e);
                }
            } catch (JacksonException e) {
                listener.onError(new ChatCompletionException(ChatErrorKind.PROTOCOL,
                        "POST " + PATH + ": Antwort nicht lesbar"));
            } catch (IOException e) {
                if (cancelled.get()) {
                    listener.onCancelled();
                } else {
                    listener.onError(transport(e));
                }
            } finally {
                done.set(true);
            }
        }, "space-harvester-ollama-chat");
        worker.setDaemon(true);
        worker.start();
        return new ChatTask() {
            @Override
            public void cancel() {
                cancelled.set(true);
                HttpURLConnection connection = open.get();
                if (connection != null) {
                    connection.disconnect();
                }
            }

            @Override
            public boolean isDone() {
                return done.get();
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    /** Die Antwort ohne Nachdenken; {@code done_reason = length} heißt am Ausgabelimit abgeschnitten. */
    private ChatResponse response(JsonNode answer, String content) {
        ChatFinishReason reason = switch (answer.path("done_reason").asString("")) {
            case "stop" -> ChatFinishReason.STOP;
            case "length" -> ChatFinishReason.LENGTH;
            default -> ChatFinishReason.UNKNOWN;
        };
        int prompt = answer.path("prompt_eval_count").asInt(0);
        int completion = answer.path("eval_count").asInt(0);
        ChatUsage usage = answer.has("eval_count") ? ChatUsage.of(prompt, completion, prompt + completion)
                : ChatUsage.notReported();
        return new ChatResponse(ChatMessage.assistant(content), reason, usage,
                answer.path("model").asString(null));
    }

    /** Öffnet die Anfrage und prüft den Status; der Aufrufer liest und trennt. */
    private HttpURLConnection send(ChatRequest request, boolean stream) {
        byte[] body = body(request, stream).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection;
        try {
            connection = connections.open(PATH);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                connection.disconnect();
                ChatErrorKind kind = status == 401 || status == 403 ? ChatErrorKind.AUTHENTICATION
                        : status == 429 ? ChatErrorKind.RATE_LIMITED
                        : status >= 500 ? ChatErrorKind.PROVIDER_ERROR : ChatErrorKind.INVALID_REQUEST;
                throw new ChatCompletionException(kind, status, "POST " + PATH + ": HTTP " + status, null);
            }
        } catch (IOException e) {
            throw transport(e);
        }
        return connection;
    }

    ObjectNode body(ChatRequest request, boolean stream) {
        ChatOptions options = request.options();
        ObjectNode body = JSON.createObjectNode();
        body.put("model", options.model() != null ? options.model() : model);
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode entry = messages.addObject();
            entry.put("role", switch (message.role()) {
                case SYSTEM, DEVELOPER -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case TOOL -> "tool";
            });
            entry.put("content", message.content());
            if (!message.images().isEmpty()) {
                ArrayNode images = entry.putArray("images");
                for (ChatImage image : message.images()) {
                    images.add(Base64.getEncoder().encodeToString(image.bytes()));
                }
            }
        }
        String effort = options.reasoningEffort() != null ? options.reasoningEffort() : thinking;
        if (effort != null) {
            String level = effort.strip().toLowerCase(Locale.ROOT);
            if (level.equals("none") || level.equals("minimal")) {
                body.put("think", false);
            } else {
                body.put("think", level);
            }
        }
        ObjectNode modelOptions = body.putObject("options");
        modelOptions.put("num_ctx", contextTokens);
        if (options.maxTokens() != null) {
            modelOptions.put("num_predict", options.maxTokens());
        }
        if (options.temperature() != null) {
            modelOptions.put("temperature", options.temperature());
        }
        if (options.topP() != null) {
            modelOptions.put("top_p", options.topP());
        }
        if (options.topK() != null) {
            modelOptions.put("top_k", options.topK());
        }
        if (!options.stop().isEmpty()) {
            ArrayNode stop = modelOptions.putArray("stop");
            options.stop().forEach(stop::add);
        }
        return body;
    }

    private static ChatCompletionException transport(IOException e) {
        return new ChatCompletionException(ChatErrorKind.TRANSPORT, "POST " + PATH + " nicht erreichbar ("
                + e.getClass().getSimpleName() + ")", e);
    }
}
