package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.browser.search.inference.CancellationSignal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A5c/A5-hardening: the STRICT HTTP client for the local reranker's {@code POST <baseUrl>/api/rerank}
 * dialect. It scores the submitted documents and returns a validated {@link RerankResponse}; it never
 * sorts, thresholds or truncates (the selection policy's job) and never guesses around a malformed
 * answer. Every non-usable outcome surfaces as a typed {@link RerankerClientException}:
 * <ul>
 *   <li>connect/read failure → {@code TRANSPORT}; a socket timeout → {@code TIMEOUT};</li>
 *   <li>non-2xx status → {@code HTTP_STATUS};</li>
 *   <li>a body that is not contract-valid → {@code INVALID_RESPONSE}: not JSON, missing results, a
 *       response {@code model} that differs from the requested model, a non-integer / out-of-range /
 *       duplicated index, a non-finite score, or an INCOMPLETE result set (with {@code top_n} equal to
 *       the document count every index must be present exactly once);</li>
 *   <li>cancellation before the call or after the response but before the scores are used →
 *       {@code CANCELLED}.</li>
 * </ul>
 */
public final class HttpRerankerClient {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final RerankerEndpointDescriptor descriptor;

    public HttpRerankerClient(RerankerEndpointDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    /** Score {@code documents} for {@code query} with no cancellation hook. */
    public RerankResponse rerank(String query, List<String> documents)
            throws RerankerClientException {
        return rerank(query, documents, CancellationSignal.NONE);
    }

    /**
     * Confirm the endpoint is reachable and serving the configured model — used at session start so an
     * unreachable or wrong-model runtime fails the session, not the first run. Performs a GET on
     * {@code /api/tags} and requires a 2xx response that lists the configured model name.
     */
    public void probeReadiness() throws RerankerClientException {
        URL url;
        try {
            url = new URL(joinUrl(descriptor.baseUrl, api() ? "/models" : "/api/tags"));
        } catch (IOException e) {
            throw new RerankerClientException(RerankerClientFailure.TRANSPORT,
                    "reranker base URL is unusable: " + e.getMessage(), e);
        }
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            authorize(connection, url.toString());
            int timeout = (int) Math.min(Integer.MAX_VALUE, descriptor.requestTimeoutMillis);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new RerankerClientException(RerankerClientFailure.HTTP_STATUS,
                        "reranker readiness probe returned HTTP " + status);
            }
            String body = readAll(connection.getInputStream());
            if (!body.contains(descriptor.modelName)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker endpoint does not serve the configured model "
                                + descriptor.modelName);
            }
        } catch (RerankerClientException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new RerankerClientException(RerankerClientFailure.TIMEOUT,
                    "reranker readiness probe timed out", e);
        } catch (IOException e) {
            throw new RerankerClientException(RerankerClientFailure.TRANSPORT,
                    "reranker readiness probe failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Score {@code documents} for {@code query}, honouring cancellation before and after the HTTP call.
     * Returns validated rows in response order (NOT sorted).
     */
    @SuppressWarnings("unchecked")
    public RerankResponse rerank(String query, List<String> documents, CancellationSignal cancellation)
            throws RerankerClientException {
        if (documents.isEmpty()) {
            return new RerankResponse(descriptor.modelName, new ArrayList<RerankScore>(), 0L, 0L);
        }
        if (cancellation != null && cancellation.isCancelled()) {
            throw new RerankerClientException(RerankerClientFailure.CANCELLED,
                    "cancelled before the reranker call");
        }
        if (api()) {
            String answer = post(OpenAiRerankWire.request(descriptor.modelName, query, documents));
            if (cancellation != null && cancellation.isCancelled()) {
                throw new RerankerClientException(RerankerClientFailure.CANCELLED,
                        "cancelled after the reranker response, before applying scores");
            }
            return OpenAiRerankWire.parse(descriptor.modelName, answer, documents.size());
        }
        RerankRequest request = new RerankRequest(descriptor.modelName, query, documents);
        String responseBody = post(request.toJson());

        // Cancellation observed after the HTTP round-trip but before the scores are applied.
        if (cancellation != null && cancellation.isCancelled()) {
            throw new RerankerClientException(RerankerClientFailure.CANCELLED,
                    "cancelled after the reranker response, before applying scores");
        }

        Object root;
        try {
            root = RerankJson.parse(responseBody);
        } catch (RerankJson.JsonParseException e) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker response is not JSON: " + e.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker response root is not a JSON object");
        }
        Map<String, Object> object = (Map<String, Object>) root;

        // The served model must be the one we asked for — a mismatch means the endpoint is serving a
        // different (or reloaded) model than the snapshot promised.
        Object modelValue = object.get("model");
        if (!(modelValue instanceof String) || !descriptor.modelName.equals(modelValue)) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker response model '" + modelValue + "' does not match requested '"
                            + descriptor.modelName + "'");
        }

        Object resultsValue = object.get("results");
        if (!(resultsValue instanceof List)) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker response has no 'results' array");
        }

        List<RerankScore> scores = new ArrayList<RerankScore>();
        Set<Integer> seen = new HashSet<Integer>();
        for (Object element : (List<Object>) resultsValue) {
            if (!(element instanceof Map)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result entry is not an object");
            }
            Map<String, Object> entry = (Map<String, Object>) element;
            Object indexValue = entry.get("index");
            Object scoreValue = entry.get("score");
            if (!(indexValue instanceof Double) || !(scoreValue instanceof Double)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result entry must carry numeric 'index' and 'score'");
            }
            double rawIndex = (Double) indexValue;
            if (rawIndex != Math.floor(rawIndex) || Double.isInfinite(rawIndex)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result index is not an integer: " + rawIndex);
            }
            int index = (int) rawIndex;
            if (index < 0 || index >= documents.size()) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker result index " + index + " is outside 0.." + (documents.size() - 1));
            }
            if (!seen.add(index)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker returned duplicate index " + index);
            }
            double score = (Double) scoreValue;
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                        "reranker score for index " + index + " is not finite");
            }
            scores.add(new RerankScore(index, score));
        }
        // We requested top_n = documents.size(); the endpoint must therefore score EVERY document exactly
        // once. An incomplete ranking would silently drop candidates from consideration.
        if (scores.size() != documents.size()) {
            throw new RerankerClientException(RerankerClientFailure.INVALID_RESPONSE,
                    "reranker returned " + scores.size() + " of " + documents.size()
                            + " requested document scores");
        }
        return new RerankResponse((String) modelValue, scores, longField(object, "total_duration"),
                longField(object, "load_duration"));
    }

    private static long longField(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof Double ? (long) (double) (Double) value : 0L;
    }

    private String post(String requestBody) throws RerankerClientException {
        URL url;
        try {
            url = new URL(joinUrl(descriptor.baseUrl, api() ? "/rerank" : "/api/rerank"));
        } catch (IOException e) {
            throw new RerankerClientException(RerankerClientFailure.TRANSPORT,
                    "reranker base URL is unusable: " + e.getMessage(), e);
        }
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            authorize(connection, url.toString());
            int timeout = (int) Math.min(Integer.MAX_VALUE, descriptor.requestTimeoutMillis);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);

            byte[] payload = requestBody.getBytes(UTF_8);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorBody = readAll(connection.getErrorStream());
                if (api()) { // an API's error text may echo the documents: status and error code only
                    throw new RerankerClientException(RerankerClientFailure.HTTP_STATUS,
                            "reranker returned HTTP " + status + OpenAiRerankWire.errorCode(errorBody));
                }
                throw new RerankerClientException(RerankerClientFailure.HTTP_STATUS,
                        "reranker returned HTTP " + status
                                + (errorBody.isEmpty() ? "" : ": " + errorBody));
            }
            return readAll(connection.getInputStream());
        } catch (RerankerClientException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new RerankerClientException(RerankerClientFailure.TIMEOUT,
                    "reranker call timed out after " + descriptor.requestTimeoutMillis + "ms", e);
        } catch (IOException e) {
            throw new RerankerClientException(RerankerClientFailure.TRANSPORT,
                    "reranker call failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean api() {
        return descriptor.provider == com.aresstack.askai.agent.model.reranker.RerankerProvider.OPENAI_COMPATIBLE;
    }

    /** The API through the host's network settings (proxy, certificate trust); a local reranker plainly. */
    private HttpURLConnection open(URL url) throws IOException {
        return api() ? com.aresstack.askai.agent.model.EndpointAuthorization.open(url.toString())
                : (HttpURLConnection) url.openConnection();
    }

    /** The API's key for its own URLs (per request, never kept); no redirect may carry it elsewhere. */
    private void authorize(HttpURLConnection connection, String url) throws IOException {
        if (!api()) {
            return;
        }
        connection.setInstanceFollowRedirects(false);
        com.aresstack.askai.agent.model.EndpointAuthorization.authorize(connection,
                com.aresstack.askai.agent.model.EndpointAuthorization.bearerFor(url));
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try {
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            stream.close();
        }
        return new String(buffer.toByteArray(), UTF_8);
    }

    private static String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        return baseUrl + path;
    }
}
