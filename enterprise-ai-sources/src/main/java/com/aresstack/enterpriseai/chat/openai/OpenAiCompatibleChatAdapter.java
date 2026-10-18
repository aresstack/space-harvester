package com.aresstack.enterpriseai.chat.openai;

import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;
import com.aresstack.enterpriseai.chat.api.ChatStreamListener;
import com.aresstack.enterpriseai.chat.api.ChatTask;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.astu.chat.ChatToolCall;
import com.aresstack.enterpriseai.astu.chat.ChatUsage;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Einziger produktiver {@link ChatCompletionPort}: die interne GPT-/OpenAI-kompatible Enterprise-API
 * ({@code POST <baseUrl>/chat/completions}). Kapselt alle beobachteten Besonderheiten dieses Backends
 * (siehe {@code package-info}); Application und Domain sehen davon nichts.
 *
 * <p>Threadsicher und zustandslos zwischen Anfragen. Jede Streaming-Anfrage läuft als eigener Auftrag im
 * übergebenen {@link Executor} (Standard: ein Daemon-Thread je Anfrage); ein Abbruch trennt die Verbindung.
 *
 * <p>Herkunft: Implementierungserfahrung aus MainframeMate {@code CloudChatManager} (SSE-Zeilen normalisieren,
 * Null-{@code delta.content}, {@code [DONE]}, Bearer-Header), ohne dessen Provider-Weiche, Settings-Zugriff und
 * Tool-Call-Akkumulation; OkHttp ist durch {@link HttpURLConnection} ersetzt, damit der Adapter nur Gson braucht.
 * Wo der alte Client anderes erwartet als die realen Tests der Enterprise-API, gelten die Tests.
 */
public final class OpenAiCompatibleChatAdapter implements ChatCompletionPort {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String JSON_UTF_8 = "application/json; charset=utf-8";

    private final OpenAiCompatibleChatConfig config;
    private final Executor executor;
    private final OpenAiRequestWriter writer;
    private final OpenAiResponseParser parser = new OpenAiResponseParser();

    public OpenAiCompatibleChatAdapter(OpenAiCompatibleChatConfig config) {
        this(config, new Executor() {
            @Override
            public void execute(Runnable command) {
                Thread thread = new Thread(command, "openai-chat-stream");
                thread.setDaemon(true);
                thread.start();
            }
        });
    }

    public OpenAiCompatibleChatAdapter(OpenAiCompatibleChatConfig config, Executor executor) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        this.config = config;
        this.executor = executor;
        this.writer = new OpenAiRequestWriter(config.defaultModel(), config.developerRolePolicy());
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        byte[] body = writer.write(request, false).getBytes(UTF_8);
        String token = token();
        HttpURLConnection connection = null;
        try {
            connection = open("application/json", token);
            send(connection, body);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw OpenAiErrors.forStatus(status, readError(connection), token);
            }
            return parser.parseCompletion(readAll(connection.getInputStream()));
        } catch (ChatCompletionException e) {
            throw OpenAiErrors.redacted(e, token);
        } catch (IOException e) {
            throw transport(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public ChatTask stream(final ChatRequest request, final ChatStreamListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        final StreamTask task = new StreamTask(listener);
        final byte[] body;
        try {
            body = writer.write(request, true).getBytes(UTF_8);
        } catch (ChatCompletionException e) {
            task.fail(e);
            return task;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runStream(body, task);
                }
            });
        } catch (RejectedExecutionException e) {
            task.fail(new ChatCompletionException(ChatErrorKind.TRANSPORT, "no thread available for streaming", e));
        }
        return task;
    }

    private void runStream(byte[] body, StreamTask task) {
        if (task.isCancelRequested()) {
            task.cancelled();
            return;
        }
        String token = null;
        HttpURLConnection connection = null;
        try {
            token = token();
            // Kein "Accept: text/event-stream": der getestete Server lehnt es ab. Streaming steuert nur der Body.
            connection = open("*/*", token);
            if (!task.attach(connection)) {
                task.cancelled();
                return;
            }
            send(connection, body);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw OpenAiErrors.forStatus(status, readError(connection), token);
            }
            if (task.isCancelRequested()) {
                task.cancelled();
                return;
            }
            task.started();
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                // Server, die stream=true ignorieren, liefern eine vollständige Antwort am Stück.
                ChatResponse response = parser.parseCompletion(readAll(connection.getInputStream()));
                task.delta(response.content());
                task.complete(response);
                return;
            }
            readEvents(connection.getInputStream(), task);
        } catch (ChatCompletionException e) {
            if (task.isCancelRequested()) {
                task.cancelled();
            } else {
                task.fail(OpenAiErrors.redacted(e, token));
            }
        } catch (IOException e) {
            if (task.isCancelRequested()) {
                task.cancelled();
            } else {
                task.fail(transport(e));
            }
        } catch (RuntimeException e) {
            if (task.isCancelRequested()) {
                task.cancelled();
            } else {
                task.fail(new ChatCompletionException(ChatErrorKind.PROTOCOL, "unexpected stream content", e));
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void readEvents(InputStream input, StreamTask task) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF_8));
        StringBuilder text = new StringBuilder();
        OpenAiResponseParser.ToolCallAccumulator toolCalls = new OpenAiResponseParser.ToolCallAccumulator();
        String finishReason = null;
        String model = null;
        ChatUsage usage = ChatUsage.notReported();
        boolean done = false;
        String line;
        while (!task.isCancelRequested() && (line = reader.readLine()) != null) {
            String payload = payloadOf(line);
            if (payload == null) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                done = true;
                break;
            }
            OpenAiResponseParser.Chunk chunk = parser.parseChunk(payload);
            if (chunk.content != null && !chunk.content.isEmpty()) {
                text.append(chunk.content);
                task.delta(chunk.content);
            }
            toolCalls.add(chunk.toolCalls);
            if (chunk.finishReason != null) {
                finishReason = chunk.finishReason;
            }
            if (chunk.model != null) {
                model = chunk.model;
            }
            if (chunk.usage != null && chunk.usage.isReported()) {
                usage = chunk.usage;
            }
        }
        if (task.isCancelRequested()) {
            task.cancelled();
        } else if (done || finishReason != null) {
            List<ChatToolCall> calls = toolCalls.calls();
            ChatMessage message = calls.isEmpty() ? ChatMessage.assistant(text.toString())
                    : ChatMessage.assistantToolCalls(text.toString(), calls);
            task.complete(new ChatResponse(message, OpenAiResponseParser.finishReason(finishReason), usage, model));
        } else {
            task.fail(new ChatCompletionException(ChatErrorKind.TRANSPORT, "stream ended before [DONE]"));
        }
    }

    /** SSE-Zeile auf ihren Datenanteil reduzieren; {@code null} für Leerzeilen, Kommentare und Event-Namen. */
    static String payloadOf(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")
                || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
            return null;
        }
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring("data:".length()).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private HttpURLConnection open(String accept, String token) throws IOException {
        // Route, TLS-Vertrauen und User-Agent kommen je Anfrage aus der Konfiguration; nichts davon ist
        // prozessweit gesetzt (kein ProxySelector, keine Standard-SSLSocketFactory).
        HttpURLConnection connection = RouteConnections.open(config.endpoint(), config.routes(),
                config.sslSocketFactory(), config.userAgent());
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(config.connectTimeoutMillis());
        connection.setReadTimeout(config.readTimeoutMillis());
        // Kein Fixed-Length-Streaming: HttpURLConnection puffert den (kleinen) Body und liefert so auch bei 401
        // den Fehler-Body des Servers.
        connection.setRequestProperty("Content-Type", JSON_UTF_8);
        connection.setRequestProperty("Accept", accept);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static void send(HttpURLConnection connection, byte[] body) throws IOException {
        OutputStream output = connection.getOutputStream();
        try {
            output.write(body);
        } finally {
            output.close();
        }
    }

    private static String readError(HttpURLConnection connection) {
        try {
            InputStream error = connection.getErrorStream();
            return error == null ? "" : readAll(error);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF_8);
        } finally {
            input.close();
        }
    }

    /** Holt das Token; ein Fehler der Quelle (z. B. Credential-Store nicht erreichbar) wird zum Port-Fehler. */
    private String token() {
        OpenAiCompatibleChatConfig.TokenSource source = config.tokenSource();
        if (source == null) {
            return null;
        }
        String token;
        try {
            token = source.token();
        } catch (RuntimeException e) {
            // Ursache bewusst nicht anhängen: ihre Meldung könnte Secret-Material enthalten.
            if (source.isTransient(e)) {
                throw new ChatCompletionException(ChatErrorKind.CREDENTIALS_UNAVAILABLE,
                        "token source temporarily unavailable: " + e.getClass().getSimpleName());
            }
            throw new ChatCompletionException(ChatErrorKind.AUTHENTICATION,
                    "token source failed: " + e.getClass().getSimpleName());
        }
        return token == null || token.trim().isEmpty() ? null : token.trim();
    }

    private ChatCompletionException transport(IOException e) {
        return new ChatCompletionException(ChatErrorKind.TRANSPORT,
                "connection to " + config.endpoint().getHost() + " failed: " + e.getClass().getSimpleName(), e);
    }

    /**
     * Handle einer Streaming-Anfrage; garantiert genau einen Abschluss-Callback. Abbruch und Abschluss
     * entscheiden sich unter demselben Lock: Ein {@link #cancel()} vor dem Abschluss endet immer mit
     * {@code onCancelled}, eines danach ist wirkungslos.
     */
    private static final class StreamTask implements ChatTask {

        private final ChatStreamListener listener;
        private boolean finished;
        private volatile boolean cancelRequested;
        private boolean cancelled;
        private HttpURLConnection connection;

        StreamTask(ChatStreamListener listener) {
            this.listener = listener;
        }

        @Override
        public void cancel() {
            HttpURLConnection toClose;
            synchronized (this) {
                if (finished || cancelRequested) {
                    return;
                }
                cancelRequested = true;
                toClose = connection;
            }
            if (toClose != null) {
                // Schließt den Socket; der lesende Thread bricht mit einer IOException ab.
                toClose.disconnect();
            }
        }

        @Override
        public synchronized boolean isDone() {
            return finished;
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        boolean isCancelRequested() {
            return cancelRequested;
        }

        /** @return {@code false}, wenn schon abgebrochen wurde und die Verbindung nicht genutzt werden soll */
        synchronized boolean attach(HttpURLConnection newConnection) {
            this.connection = newConnection;
            return !cancelRequested;
        }

        void started() {
            if (!cancelRequested) {
                listener.onStart();
            }
        }

        void delta(String text) {
            if (!cancelRequested && text != null && !text.isEmpty()) {
                listener.onDelta(text);
            }
        }

        void complete(ChatResponse response) {
            finish(response, null);
        }

        void fail(ChatCompletionException error) {
            finish(null, error);
        }

        void cancelled() {
            finish(null, null);
        }

        private void finish(ChatResponse response, ChatCompletionException error) {
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                cancelled = cancelRequested || (response == null && error == null);
            }
            if (cancelled) {
                listener.onCancelled();
            } else if (error != null) {
                listener.onError(error);
            } else {
                listener.onComplete(response);
            }
        }
    }
}
