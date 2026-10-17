package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bilder, Anhänge und Diagramme als Referenzen (#9), über echtes HTTP gegen die Fake-Confluence: die Verweise bleiben
 * unverändert in der nativen Payload, die Anhangsliste nennt alle Anhänge samt nicht eingebetteter, ohne Dateibytes.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PageAttachmentsApiTest {

    /** Ein Bild zweimal verwendet, ein externes Bild und ein draw.io-Diagramm. */
    private static final String STORAGE = "<p>Vorher</p>"
            + "<ac:image ac:width=\"300\"><ri:attachment ri:filename=\"bild.png\"/></ac:image>"
            + "<p>Dazwischen</p>"
            + "<ac:image><ri:attachment ri:filename=\"bild.png\"/></ac:image>"
            + "<ac:image><ri:url ri:value=\"https://extern.example.org/logo.svg\"/></ac:image>"
            + "<ac:structured-macro ac:name=\"drawio\" ac:schema-version=\"1\">"
            + "<ac:parameter ac:name=\"diagramName\">ablauf</ac:parameter></ac:structured-macro>";

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Ohne Bilder</p>").storage("<p>Ohne Bilder</p>");
        CONFLUENCE.page("101", "Mit Bildern", "DEV", "<p>Bilder</p>").storage(STORAGE);
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("102", "Kaputte Liste", "DEV", "<p>K</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.page("103", "Gesperrt", "DEV", "<p>G</p>");
        CONFLUENCE.child("100", "103");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.attachment("att1", "101", "bild.png", "image/png", "PNG-Bytes");
        CONFLUENCE.attachment("att2", "101", "ablauf.drawio", "application/vnd.jgraph.mxfile", "<mxfile/>");
        CONFLUENCE.attachment("att3", "101", "protokoll.pdf", "application/pdf", "PDF");
        CONFLUENCE.attachment("att4", "102", "gut.txt", "text/plain", "x");
        CONFLUENCE.attachment("kaputt", "102", "ohne-id.txt", "text/plain", "y");
        CONFLUENCE.attachment("att5", "103", "geheim.txt", "text/plain", "z");
        CONFLUENCE.restrict("103");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.connectTimeoutMillis", () -> "2000");
        registry.add("space-harvester.sources[0].options.pageSize", () -> "2");
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private PageContentFetcher snapshots;

    @Test
    void listNamesEveryAttachmentIncludingNonEmbeddedOnesWithoutLoadingBytes() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/101/attachments");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(body.get("pageId").asString()).isEqualTo("101");
        assertThat(body.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(body.get("origin").asString()).isEqualTo("live");
        assertThat(body.get("complete").asBoolean()).isTrue();
        assertThat(body.get("warnings")).isEmpty();
        assertThat(body.get("nativeFormat").asString())
                .isEqualTo("confluence-rest-attachment-list+json;expand=container,version");
        JsonNode attachments = body.get("attachments");
        assertThat(attachments).hasSize(3);
        assertThat(attachments.get(0).get("attachmentId").asString()).isEqualTo("att1");
        assertThat(attachments.get(0).get("title").asString()).isEqualTo("bild.png");
        assertThat(attachments.get(0).get("mediaType").asString()).isEqualTo("image/png");
        assertThat(attachments.get(0).get("fileSize").asLong()).isEqualTo(9);
        assertThat(attachments.get(0).get("version").asString()).isEqualTo("1");
        assertThat(attachments.get(2).get("title").asString()).isEqualTo("protokoll.pdf");
        assertThat(CONFLUENCE.requestCount("/download/")).isZero();
        assertThat(response.body()).doesNotContain("PNG-Bytes");
    }

    @Test
    void referencesStayUnchangedInTheNativePayload() {
        PageSnapshot withImages = snapshots.fetch("wiki", "101");
        PageSnapshot withoutImages = snapshots.fetch("wiki", "100");

        // Byte für Byte belegt NativeSnapshotSourceTest; hier zählt, dass alle Verweise im Original stehen.
        assertThat(storage(withImages)).isEqualTo(STORAGE);
        assertThat(storage(withoutImages)).isEqualTo("<p>Ohne Bilder</p>");
    }

    private String storage(PageSnapshot snapshot) {
        return json.readTree(new String(snapshot.payload().bytes(), StandardCharsets.UTF_8))
                .get("body").get("storage").get("value").asString();
    }

    @Test
    void pageWithoutAttachmentsHasAnEmptyCompleteList() throws Exception {
        JsonNode body = json.readTree(get("/api/v1/sources/wiki/pages/100/attachments").body());

        assertThat(body.get("attachments")).isEmpty();
        assertThat(body.get("complete").asBoolean()).isTrue();
    }

    @Test
    void entriesWithoutUsableIdMakeTheListIncompleteWithAWarning() throws Exception {
        JsonNode body = json.readTree(get("/api/v1/sources/wiki/pages/102/attachments").body());

        assertThat(body.get("complete").asBoolean()).isFalse();
        assertThat(body.get("attachments")).hasSize(1);
        assertThat(body.get("warnings")).hasSize(1);
        assertThat(body.get("warnings").get(0).asString()).doesNotContain("ohne-id.txt");
    }

    @Test
    void restrictedAndUnknownPagesAreNotFoundAndFailuresAreNeverAnEmptyList() throws Exception {
        assertThat(get("/api/v1/sources/wiki/pages/103/attachments").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/sources/wiki/pages/999/attachments").statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/sources/wiki/pages/att1/attachments").statusCode()).isEqualTo(400);

        CONFLUENCE.failNext(503, 3);
        HttpResponse<String> unavailable = get("/api/v1/sources/wiki/pages/101/attachments");

        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(unavailable.body()).contains("problems/source-unavailable").doesNotContain("\"attachments\":");
    }

    @Test
    void withoutTokenNothingIsListed() throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/sources/wiki/pages/101/attachments")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("bild.png");
    }

    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
