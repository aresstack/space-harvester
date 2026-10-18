package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.application.query.progressive.QueryVariantModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /chat/completions} des OpenAI-kompatiblen Dienstes für die Suchvarianten (ADR 0017), über dieselbe Route,
 * dasselbe Vertrauen und denselben Bearer-Token wie die übrigen Aufrufe. Der Enterprise AI Client bringt hier keinen
 * Chat-Adapter mit; dieser ist bewusst schmal: eine Anweisung, eine Frage, kein Streaming, Antwortgröße begrenzt.
 * Meldungen nennen nur Status und Ausnahmeklasse, nie Frage, Antwort oder Key.
 */
final class ChatCompletions implements QueryVariantModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final URI endpoint;
    private final String model;
    private final Supplier<String> apiKey;
    private final HttpRoutePort routes;
    private final SSLSocketFactory tls;
    private final String userAgent;
    private final int timeoutMillis;

    ChatCompletions(URI baseUrl, String model, Supplier<String> apiKey, HttpRoutePort routes, SSLSocketFactory tls,
                    String userAgent, int timeoutMillis) {
        String base = Objects.requireNonNull(baseUrl, "baseUrl").toString();
        this.endpoint = URI.create((base.endsWith("/") ? base : base + "/") + "chat/completions");
        this.model = Objects.requireNonNull(model, "model");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.tls = tls;
        this.userAgent = userAgent;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String complete(String instructions, String question) {
        byte[] body = request(instructions, question);
        HttpURLConnection connection = null;
        try {
            connection = open();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            String key = apiKey.get();
            if (key != null && !key.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + key);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new AiServiceException(AiServiceException.Reason.UNAUTHORIZED, "Chat-Dienst: HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Chat-Dienst: HTTP " + status);
            }
            return content(read(connection.getInputStream()));
        } catch (SocketTimeoutException e) {
            throw new AiServiceException(AiServiceException.Reason.TIMEOUT, "Chat-Dienst: Timeout");
        } catch (IOException e) {
            throw new AiServiceException(AiServiceException.Reason.UNREACHABLE,
                    "Chat-Dienst: " + e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] request(String instructions, String question) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);
        root.put("stream", false);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", instructions);
        messages.addObject().put("role", "user").put("content", question);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String content(byte[] response) {
        try {
            JsonNode content = JSON.readTree(response).path("choices").path(0).path("message").path("content");
            if (!content.isString()) {
                throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE,
                        "Chat-Dienst: Antwort ohne Inhalt");
            }
            return content.asString();
        } catch (JacksonException e) {
            throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE, "Chat-Dienst: Antwort nicht lesbar");
        }
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE,
                            "Chat-Dienst: Antwort zu groß");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private HttpURLConnection open() throws IOException {
        URLConnection raw = endpoint.toURL().openConnection(proxy(routes.routeFor(endpoint)));
        if (!(raw instanceof HttpURLConnection connection)) {
            throw new IOException("kein HTTP-Endpunkt");
        }
        if (tls != null && connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(tls);
        }
        if (userAgent != null) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        return connection;
    }

    /** Wie die Adapter des Enterprise AI Client: Ohne Routenentscheidung geht nichts hinaus (fail-closed). */
    private Proxy proxy(HttpRoute route) throws IOException {
        if (route == null) {
            throw new IOException("keine Route");
        }
        return switch (route.kind()) {
            case DIRECT -> Proxy.NO_PROXY;
            case PROXY -> new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(route.proxyHost(), route.proxyPort()));
            default -> throw new IOException("Proxy-Route nicht verfügbar");
        };
    }
}
