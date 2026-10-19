package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Abgelegte Anhänge gehen als Strom an den Client: Eine große Datei hält ihren einzigen Platz an der Schranke nur bis
 * zum Ende der Antwort, auch wenn der Client mitten im Lesen abbricht, und ein veränderter Inhalt bricht die Antwort
 * ab, statt als vollständig zu gelten.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssetsStreamApiTest {

    private static final String PATH = "/api/v1/sources/wiki/pages/101/assets/att1/content";
    private static final String CONTENT = "x".repeat(12 * 1024 * 1024);
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;
    private static final Path STORE;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>S</p>");
        CONFLUENCE.page("101", "Groß", "DEV", "<p>G</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.attachment("att1", "101", "gross.txt", "text/plain", CONTENT);
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
            STORE = Files.createTempDirectory("assets-stream");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
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
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        registry.add("space-harvester.assets.directory", STORE::toString);
        registry.add("space-harvester.assets.max-size", () -> "16MB");
        registry.add("space-harvester.assets.large-file-threshold", () -> "1MB");
        registry.add("space-harvester.assets.max-concurrent-large-files", () -> "1");
        registry.add("space-harvester.assets.large-file-wait", () -> "200ms");
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.close();
        try (Stream<Path> files = Files.walk(STORE)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    private Environment environment;

    @Test
    @Order(1)
    void aClientThatStopsReadingGivesTheOnlyPlaceBack() throws Exception {
        // Erster Abruf lädt und legt ab, zweiter kommt als Strom aus der Ablage.
        assertThat(http.send(request(), HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(CONTENT);
        HttpResponse<String> whole = http.send(request(), HttpResponse.BodyHandlers.ofString());
        assertThat(whole.statusCode()).isEqualTo(200);
        assertThat(whole.body()).isEqualTo(CONTENT);
        assertThat(whole.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("attachment"));
        assertThat(whole.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("text/plain"));

        HttpResponse<InputStream> started = http.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(started.statusCode()).isEqualTo(200);
        try (InputStream in = started.body()) {
            assertThat(in.read()).isEqualTo('x');
        } // Abbruch mitten im Strom

        int status = 0;
        for (int attempt = 0; attempt < 50 && status != 200; attempt++) {
            status = http.send(request(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200) {
                Thread.sleep(100);
            }
        }
        assertThat(status).isEqualTo(200);
    }

    @Test
    @Order(2)
    void aChangedStoredFileAbortsTheResponse() throws Exception {
        try (Stream<Path> blobs = Files.walk(STORE.resolve("blobs"))) {
            Path blob = blobs.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.writeString(blob, "y" + CONTENT.substring(1), StandardCharsets.UTF_8);
        }

        assertThatThrownBy(() -> http.send(request(), HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);
    }

    private HttpRequest request() {
        String port = environment.getRequiredProperty("local.server.port");
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header("Authorization", "Bearer " + TestClient.TOKEN)
                .GET()
                .build();
    }
}
