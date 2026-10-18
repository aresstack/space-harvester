package com.aresstack.enterpriseai.model.kipitz;

import com.aresstack.enterpriseai.model.api.RerankException;
import com.aresstack.enterpriseai.model.api.RerankPort;
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
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@link RerankPort} der Enterprise-API für RERANK-Modelle aus demselben Katalog: {@code POST <baseUrl>/rerank} im
 * verbreiteten Format (vLLM, llama.cpp, Jina, Cohere): Anfrage {@code model}, {@code query}, {@code documents},
 * {@code top_n} = alle; Antwort {@code results[]} mit {@code index} und {@code relevance_score} (oder {@code score}).
 * Route, TLS und Token wie die übrigen Aufrufe des Katalogs. UNVERIFIED: mit den Re-Rankern der Enterprise-API noch
 * nicht praktisch getestet. Meldungen nennen nie Token, Anfrage oder Dokumenttext.
 */
public final class KipitzRerankAdapter implements RerankPort {

    static final String ENDPOINT = "rerank";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Logger LOG = Logger.getLogger(KipitzRerankAdapter.class.getName());

    private final KipitzModelCatalogConfig config;

    public KipitzRerankAdapter(KipitzModelCatalogConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Override
    public double[] rerank(String modelId, String query, List<String> documents) throws RerankException {
        if (modelId == null || modelId.trim().isEmpty() || query == null || documents == null) {
            throw new IllegalArgumentException("modelId, query and documents must be given");
        }
        if (documents.isEmpty()) {
            return new double[0];
        }
        byte[] body = request(modelId, query, documents).getBytes(StandardCharsets.UTF_8);
        URI target = config.endpoint(ENDPOINT);
        long started = System.nanoTime();
        try {
            HttpURLConnection connection = KipitzConnections.open(config, target);
            try {
                connection.setConnectTimeout(config.connectTimeoutMillis());
                connection.setReadTimeout(config.readTimeoutMillis());
                connection.setRequestMethod("POST");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
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
                if (status != 200) {
                    String reason = KipitzSpeechAdapter.errorMessage(connection.getErrorStream());
                    throw new RerankException("Re-Ranker der Enterprise-API: HTTP " + status
                            + (reason.isEmpty() ? "" : " (" + reason + ")"));
                }
                double[] scores = parse(read(connection.getInputStream()), documents.size());
                LOG.info("Rerank: model=" + modelId + " documents=" + documents.size() + " millis="
                        + (System.nanoTime() - started) / 1000000L);
                return scores;
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new RerankException("Re-Ranker der Enterprise-API nicht erreichbar ("
                    + e.getClass().getSimpleName() + ")", e);
        }
    }

    static String request(String modelId, String query, List<String> documents) {
        JsonObject request = new JsonObject();
        request.addProperty("model", modelId);
        request.addProperty("query", query);
        JsonArray array = new JsonArray();
        for (String document : documents) {
            array.add(document == null ? "" : document);
        }
        request.add("documents", array);
        request.addProperty("top_n", documents.size());
        return request.toString();
    }

    /** Map the {@code results} rows back to document order; every document needs exactly one finite score. */
    static double[] parse(String json, int count) throws RerankException {
        JsonArray rows;
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonObject object = root.isJsonObject() ? root.getAsJsonObject() : null;
            JsonElement results = object == null ? null
                    : object.has("results") ? object.get("results") : object.get("data");
            if (results == null || !results.isJsonArray()) {
                throw new RerankException("Re-Ranker: Antwort ohne results");
            }
            rows = results.getAsJsonArray();
        } catch (RuntimeException e) {
            throw new RerankException("Re-Ranker: Antwort unlesbar (" + e.getClass().getSimpleName() + ")");
        }
        double[] scores = new double[count];
        Arrays.fill(scores, Double.NaN);
        for (JsonElement row : rows) {
            if (!row.isJsonObject()) {
                throw new RerankException("Re-Ranker: unlesbare Zeile");
            }
            JsonObject item = row.getAsJsonObject();
            JsonElement index = item.get("index");
            JsonElement score = item.has("relevance_score") ? item.get("relevance_score") : item.get("score");
            if (index == null || score == null || !index.isJsonPrimitive() || !score.isJsonPrimitive()) {
                throw new RerankException("Re-Ranker: Zeile ohne index oder Score");
            }
            int at;
            double value;
            try {
                at = index.getAsInt();
                value = score.getAsDouble();
            } catch (RuntimeException e) {
                throw new RerankException("Re-Ranker: Zeile ohne Zahl");
            }
            if (at < 0 || at >= count || !Double.isNaN(scores[at])) {
                throw new RerankException("Re-Ranker: index außerhalb oder doppelt");
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new RerankException("Re-Ranker: Score nicht endlich");
            }
            scores[at] = value;
        }
        for (double value : scores) {
            if (Double.isNaN(value)) {
                throw new RerankException("Re-Ranker: nicht jedes Dokument bewertet");
            }
        }
        return scores;
    }

    private String token() throws RerankException {
        if (config.bearerToken() == null) {
            return null;
        }
        try {
            return config.bearerToken().get();
        } catch (RuntimeException e) {
            // Only the class name: a foreign message might contain secret material.
            throw new RerankException("API-Key nicht verfügbar (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("empty response");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("response larger than " + MAX_RESPONSE_BYTES + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "KipitzRerankAdapter[" + config.endpoint(ENDPOINT) + "]";
    }
}
