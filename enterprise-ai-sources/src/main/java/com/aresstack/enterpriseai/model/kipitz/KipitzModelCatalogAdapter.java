package com.aresstack.enterpriseai.model.kipitz;

import com.aresstack.enterpriseai.astu.modelcatalog.ModelDescriptor;
import com.aresstack.enterpriseai.model.api.ModelCatalogException;
import com.aresstack.enterpriseai.model.api.ModelCatalogPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@link ModelCatalogPort} über {@code GET <baseUrl>/models} der Enterprise-API. Blockiert (Route, TLS, HTTP);
 * Aufrufer laufen im Hintergrund. Antwortet der Dienst nicht mit 200, ist er nicht erreichbar oder antwortet er
 * unlesbar, kommt eine {@link ModelCatalogException} mit Status bzw. Ursache, nie mit Token oder Antwortkörper.
 */
public final class KipitzModelCatalogAdapter implements ModelCatalogPort {

    /** Kennung dieses Katalogs, Präfix der Auswahl in der Konfiguration ({@code model.chat=kipitz:<modell>}). */
    public static final String CATALOG_ID = "kipitz";
    static final String DISPLAY_NAME = "KIPITZ";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final KipitzModelCatalogConfig config;

    public KipitzModelCatalogAdapter(KipitzModelCatalogConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    @Override
    public String catalogId() {
        return CATALOG_ID;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public List<ModelDescriptor> models() throws ModelCatalogException {
        URI target = config.modelsEndpoint();
        String body;
        int status;
        try {
            HttpURLConnection connection = KipitzConnections.open(config, target);
            try {
                connection.setConnectTimeout(config.connectTimeoutMillis());
                connection.setReadTimeout(config.readTimeoutMillis());
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                String token = token();
                if (token != null && !token.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                }
                status = connection.getResponseCode();
                if (status != 200) {
                    throw new ModelCatalogException("GET /models: HTTP " + status);
                }
                body = read(connection.getInputStream());
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new ModelCatalogException("GET /models nicht erreichbar (" + e.getClass().getSimpleName() + ")", e);
        }
        try {
            return KipitzModelListParser.parse(body, CATALOG_ID, DISPLAY_NAME);
        } catch (RuntimeException e) {
            throw new ModelCatalogException("GET /models: Antwort nicht lesbar (" + e.getClass().getSimpleName()
                    + ")");
        }
    }

    private String token() throws ModelCatalogException {
        if (config.bearerToken() == null) {
            return null;
        }
        try {
            return config.bearerToken().get();
        } catch (RuntimeException e) {
            // Nur der Klassenname: eine fremde Meldung könnte Secret-Material enthalten.
            throw new ModelCatalogException("API-Key nicht verfügbar (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_BODY_BYTES) {
                    throw new IOException("model list larger than " + MAX_BODY_BYTES + " bytes");
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
        return "KipitzModelCatalogAdapter[" + config + "]";
    }
}
