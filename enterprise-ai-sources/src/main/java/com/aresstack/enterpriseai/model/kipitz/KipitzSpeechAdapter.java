package com.aresstack.enterpriseai.model.kipitz;

import com.aresstack.enterpriseai.speech.api.SpeechSynthesisException;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
import com.aresstack.enterpriseai.speech.api.SynthesizedSpeech;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * {@link SpeechSynthesisPort} der Enterprise-API für TTS-Modelle aus demselben Katalog: {@code POST
 * <baseUrl>/audio/speech} (OpenAI-kompatibel, {@code response_format=wav}) über Route, TLS und Token des Katalogs.
 * UNVERIFIED: der Endpunkt ist mit den KIPITZ-TTS-Modellen noch nicht praktisch getestet. Fehlermeldungen nennen den
 * Status und höchstens den Prüftext des JSON-Fehlers (error.message, detail[].msg), nie Token oder Antwortkörper.
 *
 * <p>Diagnose je Request (java.util.logging, Logger dieser Klasse): Modell, Stimme, Worker-Thread, Länge und
 * Fingerabdruck des Textes (SHA-256-Präfix, nie der Text), danach Status, Dauer, Größe, Content-Type und die
 * Antwort-Header, die ein Modell oder Backend benennen. Damit lässt sich belegen, welches Modell der Client je Absatz
 * angefragt hat und ob der Server trotz gleichem Request verschieden antwortet. Nie API-Key oder Text im Log.
 */
public final class KipitzSpeechAdapter implements SpeechSynthesisPort {

    /** Ein Absatz Sprache kann serverseitig dauern; das Abspielen beginnt erst mit der Antwort. */
    private static final int MIN_READ_TIMEOUT_MILLIS = 120000;
    private static final int MAX_AUDIO_BYTES = 64 * 1024 * 1024;
    private static final int MAX_HEADER_VALUE = 120;
    private static final int MAX_ERROR_BODY = 8192;
    private static final int MAX_ERROR_MESSAGE = 240;
    private static final Logger LOG = Logger.getLogger(KipitzSpeechAdapter.class.getName());

    private final KipitzModelCatalogConfig config;

    /**
     * Der Adapter hat keine eigene Stimme: welche Stimme spricht, steht allein in jeder Anfrage (eine Quelle, kein
     * versteckter Rückfall auf eine globale Einstellung).
     */
    public KipitzSpeechAdapter(KipitzModelCatalogConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Override
    public String catalogId() {
        return KipitzModelCatalogAdapter.CATALOG_ID;
    }

    /** Sendet {@code voice} unverändert (nur Leerraum am Rand fällt); ohne Stimme fehlt {@code voice} im Request. */
    @Override
    public SynthesizedSpeech synthesize(String modelId, String requestedVoice, String text)
            throws SpeechSynthesisException {
        String voice = requestedVoice == null || requestedVoice.trim().isEmpty() ? null : requestedVoice.trim();
        if (modelId == null || modelId.trim().isEmpty() || text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("modelId and text must not be empty");
        }
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        request.addProperty("input", text);
        if (voice != null) {
            request.addProperty("voice", voice);
        }
        request.addProperty("response_format", "wav");
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        URI target = config.endpoint("audio/speech");
        String worker = Thread.currentThread().getName();
        LOG.info(requestLine(modelId, voice, worker, text));
        long started = System.nanoTime();
        try {
            HttpURLConnection connection = KipitzConnections.open(config, target);
            try {
                connection.setConnectTimeout(config.connectTimeoutMillis());
                connection.setReadTimeout(Math.max(MIN_READ_TIMEOUT_MILLIS, config.readTimeoutMillis()));
                connection.setRequestMethod("POST");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String token = token();
                if (token != null && !token.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                }
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
                int status = connection.getResponseCode();
                String served = servedBy(connection.getHeaderFields());
                if (status != 200) {
                    LOG.info(responseLine(modelId, worker, status, millisSince(started), -1,
                            connection.getContentType(), served));
                    String reason = errorMessage(connection.getErrorStream());
                    throw new SpeechSynthesisException("Sprachausgabe der Enterprise-API: HTTP " + status
                            + (reason.isEmpty() ? "" : " (" + reason + ")"));
                }
                byte[] wav = read(connection.getInputStream());
                LOG.info(responseLine(modelId, worker, status, millisSince(started), wav.length,
                        connection.getContentType(), served));
                return new SynthesizedSpeech(wav);
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new SpeechSynthesisException("Sprachausgabe der Enterprise-API nicht erreichbar ("
                    + e.getClass().getSimpleName() + ")", e);
        }
    }

    /**
     * Die Requestzeile ohne Text: {@code text=} ist ein SHA-256-Präfix, damit zwei Requests desselben Absatzes
     * vergleichbar sind, ohne den Inhalt preiszugeben.
     */
    static String requestLine(String modelId, String voice, String worker, String text) {
        return "TTS HTTP request: POST audio/speech catalog=" + KipitzModelCatalogAdapter.CATALOG_ID + " model="
                + modelId + " voice=" + (voice == null ? "none" : voice) + " response_format=wav worker=" + worker
                + " chars=" + text.length() + " text=" + fingerprint(text);
    }

    static String responseLine(String modelId, String worker, int status, long millis, int bytes,
                               String contentType, String served) {
        return "TTS HTTP response: model=" + modelId + " worker=" + worker + " status=" + status + " millis="
                + millis + (bytes < 0 ? "" : " bytes=" + bytes) + " contentType=" + contentType
                + " servedBy=" + served;
    }

    /**
     * Antwort-Header, die das tatsächlich antwortende Modell oder Backend benennen könnten (Name enthält model,
     * voice, backend, upstream, deployment oder served, oder Request-ID); nie Header mit key/token/auth/cookie.
     */
    static String servedBy(Map<String, List<String>> headers) {
        Map<String, String> picked = new TreeMap<String, String>();
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                String name = header.getKey();
                if (name == null || header.getValue() == null || header.getValue().isEmpty()) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("key") || lower.contains("token") || lower.contains("auth")
                        || lower.contains("cookie")) {
                    continue;
                }
                if (lower.contains("model") || lower.contains("voice") || lower.contains("backend")
                        || lower.contains("upstream") || lower.contains("deployment") || lower.contains("served")
                        || lower.equals("x-request-id")) {
                    String value = String.valueOf(header.getValue().get(0));
                    picked.put(lower, value.length() > MAX_HEADER_VALUE
                            ? value.substring(0, MAX_HEADER_VALUE) + "…" : value);
                }
            }
        }
        return picked.isEmpty() ? "none" : picked.toString();
    }

    /**
     * Extract only the validation text of a JSON error body ({@code error.message}, {@code detail[].msg} or a
     * string {@code detail}), for example "Input should be 'Jana', 'Thomas' or 'Max'". Never return the raw body or
     * the echoed {@code input}, so neither request text nor secrets reach the message.
     */
    static String errorMessage(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            try {
                int read;
                while (buffer.size() < MAX_ERROR_BODY && (read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, Math.min(read, MAX_ERROR_BODY - buffer.size()));
                }
            } finally {
                in.close();
            }
            JsonElement root = JsonParser.parseString(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return "";
            }
            JsonObject object = root.getAsJsonObject();
            StringBuilder message = new StringBuilder();
            if (object.has("error") && object.get("error").isJsonObject()) {
                appendString(message, object.getAsJsonObject("error").get("message"));
            }
            JsonElement detail = object.get("detail");
            if (detail != null && detail.isJsonArray()) {
                JsonArray items = detail.getAsJsonArray();
                for (JsonElement item : items) {
                    if (item.isJsonObject()) {
                        appendString(message, item.getAsJsonObject().get("msg"));
                    }
                }
            } else {
                appendString(message, detail);
            }
            String text = message.toString().replaceAll("\\s+", " ").trim();
            return text.length() > MAX_ERROR_MESSAGE ? text.substring(0, MAX_ERROR_MESSAGE) + "…" : text;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static void appendString(StringBuilder message, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            if (message.length() > 0) {
                message.append("; ");
            }
            message.append(value.getAsString());
        }
    }

    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private static long millisSince(long started) {
        return (System.nanoTime() - started) / 1000000L;
    }

    private String token() throws SpeechSynthesisException {
        if (config.bearerToken() == null) {
            return null;
        }
        try {
            return config.bearerToken().get();
        } catch (RuntimeException e) {
            // Nur der Klassenname: eine fremde Meldung könnte Secret-Material enthalten.
            throw new SpeechSynthesisException("API-Key nicht verfügbar (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("empty response");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        try {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_AUDIO_BYTES) {
                    throw new IOException("audio larger than " + MAX_AUDIO_BYTES + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        return buffer.toByteArray();
    }

    @Override
    public String toString() {
        return "KipitzSpeechAdapter[" + config.endpoint("audio/speech") + "]";
    }
}
