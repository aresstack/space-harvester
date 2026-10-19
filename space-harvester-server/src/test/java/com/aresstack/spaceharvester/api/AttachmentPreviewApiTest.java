package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.TestClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Vorschau und PDF-Konvertierung von Anhängen über echtes HTTP (#183): die Fake-Confluence der Kopie, davor ein
 * Server, der zusätzlich die Dokumentkonvertierung beantwortet. Rechte wie beim Abruf des Anhangs; abgelegt wird nur
 * mit sync.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AttachmentPreviewApiTest {

    private static final String CONTENT_TOKEN = "preview-content-token-31c9";
    private static final String METADATA_TOKEN = "preview-metadata-token-8e02";
    private static final String CONVERSION = "/wiki/rest/documentConversion/latest/conversion/";
    private static final byte[] PDF = "%PDF-1.7\n%Vorschau Planung\n".getBytes(StandardCharsets.US_ASCII);
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final AtomicInteger CONVERSIONS = new AtomicInteger();
    private static final HttpServer SERVER;
    private static final Path STORE;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>S</p>");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.page("101", "Planung", "DEV", "<p>P</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("103", "Gesperrt", "DEV", "<p>X</p>");
        CONFLUENCE.child("100", "103");
        CONFLUENCE.attachment("att7", "101", "Planung.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        CONFLUENCE.attachment("att8", "103", "geheim.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        CONFLUENCE.restrict("103");
        try {
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            SERVER.createContext("/", AttachmentPreviewApiTest::serve);
            SERVER.start();
            STORE = Files.createTempDirectory("previews");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Dokumentkonvertierung für beide Anhänge (Confluence liefert sie auch für gesperrte), sonst die Fake-Confluence. */
    private static void serve(HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        ConfluenceHttpResponse response;
        if (incoming.getRawPath().startsWith(CONVERSION)) {
            CONVERSIONS.incrementAndGet();
            String rest = incoming.getRawPath().substring(CONVERSION.length());
            response = rest.equals("convert/7/1") || rest.equals("convert/8/1")
                    ? new ConfluenceHttpResponse(200, "application/pdf", PDF)
                    : new ConfluenceHttpResponse(404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        } else {
            String query = incoming.getRawQuery();
            URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                    + incoming.getRawPath() + (query == null ? "" : "?" + query));
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                if (!header.getValue().isEmpty()) {
                    headers.put(header.getKey(), header.getValue().getFirst());
                }
            }
            synchronized (CONFLUENCE) {
                response = CONFLUENCE.get(mapped, headers, Integer.MAX_VALUE);
            }
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        String client = "space-harvester.security.clients[0].";
        registry.add(client + "id", () -> TestClient.ID);
        registry.add(client + "token-sha256", () -> TestClient.TOKEN_SHA256);
        registry.add(client + "grants[0].source", () -> "wiki");
        registry.add(client + "grants[0].spaces[0]", () -> "*");
        registry.add(client + "grants[0].access", () -> "metadata,content,sync");
        String reader = "space-harvester.security.clients[1].";
        registry.add(reader + "id", () -> "nur-lesen");
        registry.add(reader + "token-sha256", () -> "sha256:" + sha256(CONTENT_TOKEN));
        registry.add(reader + "grants[0].source", () -> "wiki");
        registry.add(reader + "grants[0].spaces[0]", () -> "*");
        registry.add(reader + "grants[0].access", () -> "metadata,content");
        String metadata = "space-harvester.security.clients[2].";
        registry.add(metadata + "id", () -> "nur-metadaten");
        registry.add(metadata + "token-sha256", () -> "sha256:" + sha256(METADATA_TOKEN));
        registry.add(metadata + "grants[0].source", () -> "wiki");
        registry.add(metadata + "grants[0].spaces[0]", () -> "*");
        registry.add(metadata + "grants[0].access", () -> "metadata");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + FakeConfluence.BASE.getPath());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        registry.add("space-harvester.assets.directory", STORE::toString);
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.stop(0);
        try (Stream<Path> files = Files.walk(STORE)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    void theConvertedDocumentIsADownloadAndStoredOnlyWithSync() throws Exception {
        int before = CONVERSIONS.get();
        HttpResponse<String> read = send("/api/v1/sources/wiki/pages/101/assets/att7/preview", CONTENT_TOKEN);

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(new String(PDF, StandardCharsets.US_ASCII));
        assertThat(read.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v)
                .startsWith("application/pdf"));
        assertThat(read.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v)
                .startsWith("attachment").contains("Planung.xlsx.pdf"));
        assertThat(read.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(read.headers().firstValue("Content-Security-Policy")).hasValue("sandbox; default-src 'none'");
        // Ohne sync nicht abgelegt: ein zweiter Leser fragt die Quelle erneut.
        send("/api/v1/sources/wiki/pages/101/assets/att7/preview", CONTENT_TOKEN);
        assertThat(CONVERSIONS.get()).isEqualTo(before + 2);

        HttpResponse<String> stored = send("/api/v1/sources/wiki/pages/101/assets/att7/preview?kind=document",
                TestClient.TOKEN);
        HttpResponse<String> again = send("/api/v1/sources/wiki/pages/101/assets/att7/preview", CONTENT_TOKEN);

        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("ETag")).isEqualTo(stored.headers().firstValue("ETag"));
        assertThat(CONVERSIONS.get()).isEqualTo(before + 3);
        assertThat(CONFLUENCE.requestCount("/download/")).isZero();
    }

    @Test
    void aMissingKindIsNotFoundAndAnUnknownKindIsInvalid() throws Exception {
        assertThat(send("/api/v1/sources/wiki/pages/101/assets/att7/preview?kind=poster", TestClient.TOKEN)
                .statusCode()).isEqualTo(404);
        assertThat(send("/api/v1/sources/wiki/pages/101/assets/att7/preview?kind=gross", TestClient.TOKEN)
                .statusCode()).isEqualTo(400);
    }

    @Test
    void previewsNeedTheContentGrantAndHiddenPagesStayHidden() throws Exception {
        int before = CONVERSIONS.get();
        HttpResponse<String> metadataOnly = send("/api/v1/sources/wiki/pages/101/assets/att7/preview",
                METADATA_TOKEN);
        HttpResponse<String> hidden = send("/api/v1/sources/wiki/pages/103/assets/att8/preview", TestClient.TOKEN);
        HttpResponse<String> foreign = send("/api/v1/sources/wiki/pages/101/assets/att8/preview", TestClient.TOKEN);

        assertThat(metadataOnly.statusCode()).isIn(403, 404);
        assertThat(metadataOnly.body()).doesNotContain("PDF");
        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(hidden.body()).doesNotContain("PDF").doesNotContain("geheim");
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(CONVERSIONS.get()).isEqualTo(before);
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
