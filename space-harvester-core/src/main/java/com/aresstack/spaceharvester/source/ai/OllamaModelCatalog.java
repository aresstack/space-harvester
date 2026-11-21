package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.astu.modelcatalog.ModelCategory;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelClassification;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Die Modelle eines Ollama-Servers mit ihren Fähigkeiten, wie in AskAI: {@code GET /api/tags} nennt die installierten
 * Modelle, {@code POST /api/show} je Modell die Fähigkeiten ({@code completion}, {@code vision}, {@code embedding},
 * …). Die Einteilung in Chat, Embeddings und Bildverständnis übernimmt {@link ModelClassification} des Enterprise AI
 * Client, wie für jeden anderen Katalog. Ältere Server ohne {@code capabilities} gelten als Chat und Embeddings.
 * Meldungen nennen nur Pfad und Status.
 */
final class OllamaModelCatalog {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final OllamaConnections connections;

    OllamaModelCatalog(OllamaConnections connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * @throws AiServiceException wenn der Server nicht erreichbar ist oder nicht wie Ollama antwortet
     */
    List<AiModel> models() {
        JsonNode tags = request("GET", "/api/tags", null);
        List<AiModel> models = new ArrayList<>();
        for (JsonNode entry : tags.path("models")) {
            String name = entry.path("model").asString(entry.path("name").asString(""));
            if (name.isBlank()) {
                continue;
            }
            List<String> capabilities = capabilities(name);
            Set<String> categories = new LinkedHashSet<>();
            for (ModelCategory category : ModelClassification.categoriesOf(capabilities, List.of(), List.of())) {
                categories.add(category.key());
            }
            models.add(new AiModel(name, entry.path("name").asString(name), categories));
        }
        return List.copyOf(models);
    }

    /**
     * Die Namen der geladenen Modelle ({@code GET /api/ps}), wie AskAI den Zustand „läuft“ zeigt.
     *
     * @throws AiServiceException wenn der Server nicht erreichbar ist oder nicht wie Ollama antwortet
     */
    Set<String> loaded() {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode entry : request("GET", "/api/ps", null).path("models")) {
            String name = entry.path("model").asString(entry.path("name").asString(""));
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Lädt ein Modell und wartet, bis es bereit ist, wie AskAI: ein leeres {@code POST /api/generate} mit derselben
     * Kontextlänge wie jede Anfrage, sonst lüde die nächste Anfrage es neu. Wie lange es dauern darf, bestimmt die
     * Frist der Verbindungen.
     *
     * @throws AiServiceException wenn der Server es nicht laden kann oder die Frist abläuft
     */
    void load(String model, int contextTokens) {
        tools.jackson.databind.node.ObjectNode body = JSON.createObjectNode().put("model", model).put("prompt", "")
                .put("stream", false);
        body.putObject("options").put("num_ctx", contextTokens);
        request("POST", "/api/generate", body.toString());
    }

    /** Die gemeldeten Fähigkeiten eines Modells; leer bei Servern, die sie noch nicht melden. */
    private List<String> capabilities(String model) {
        String body = JSON.createObjectNode().put("model", model).toString();
        List<String> capabilities = new ArrayList<>();
        for (JsonNode capability : request("POST", "/api/show", body).path("capabilities")) {
            capabilities.add(capability.asString(""));
        }
        return capabilities;
    }

    private JsonNode request(String method, String path, String body) {
        String text;
        try {
            HttpURLConnection connection = connections.open(path);
            try {
                connection.setRequestMethod(method);
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status = connection.getResponseCode();
                if (status != 200) {
                    AiServiceException.Reason reason = status >= 500 ? AiServiceException.Reason.UNREACHABLE
                            : status == 401 || status == 403 ? AiServiceException.Reason.UNAUTHORIZED
                            : AiServiceException.Reason.INVALID_RESPONSE;
                    throw new AiServiceException(reason, method + " " + path + ": HTTP " + status);
                }
                text = read(connection.getInputStream());
            } finally {
                connection.disconnect();
            }
        } catch (SocketTimeoutException e) {
            throw new AiServiceException(AiServiceException.Reason.TIMEOUT, method + " " + path + ": Timeout");
        } catch (IOException e) {
            throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, method + " " + path
                    + " nicht erreichbar (" + e.getClass().getSimpleName() + ")");
        }
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE, method + " " + path
                    + ": Antwort nicht lesbar");
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (in) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_BODY_BYTES) {
                    throw new IOException("response larger than " + MAX_BODY_BYTES + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
