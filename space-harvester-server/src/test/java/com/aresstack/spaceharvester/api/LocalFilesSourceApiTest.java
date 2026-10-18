package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Eine zweite Quellenart (#18) über dieselben Routen wie Confluence: lokale Dateien als eine Partition, Seiten-IDs aus
 * dem relativen Pfad, Metadaten live und das Original Byte für Byte, ohne Änderung an Controllern oder
 * Anwendungsfällen.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LocalFilesSourceApiTest {

    private static final Path DIR;
    private static final Path CACHE;

    static {
        try {
            DIR = Files.createTempDirectory("files-source");
            CACHE = Files.createTempDirectory("files-cache");
            Files.writeString(DIR.resolve("start.md"), "# Start\n", StandardCharsets.UTF_8);
            Files.createDirectories(DIR.resolve("handbuch"));
            Files.writeString(DIR.resolve("handbuch/betrieb.txt"), "Betrieb\n", StandardCharsets.UTF_8);
            Files.createDirectories(DIR.resolve(".ssh"));
            Files.writeString(DIR.resolve(".ssh/schluessel.txt"), "geheim\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        TestClient.register(registry, "docs");
        registry.add("space-harvester.sources[0].id", () -> "docs");
        registry.add("space-harvester.sources[0].type", () -> "files");
        registry.add("space-harvester.sources[0].title", () -> "Handbücher");
        registry.add("space-harvester.sources[0].options.directory", DIR::toString);
        registry.add("space-harvester.cache.directory", CACHE::toString);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Autowired
    private SourceCatalog catalog;

    private static String pageId(String relativePath) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relativePath.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSourceListShowsTypeAndCapabilities() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":\"docs\"", "\"type\":\"files\"", "\"title\":\"Handbücher\"",
                "\"capabilities\":[\"partitions\",\"page-listing\",\"page-metadata\",\"stored-page\",\"addresses\"]")
                .doesNotContain(DIR.toString());
    }

    @Test
    void theDirectoryIsOnePartitionNamedLikeTheSource() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/docs/spaces");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"key\":\"docs\"");
    }

    @Test
    void allFilesAreListedWithPathBasedPageIds() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/docs/spaces/docs/pages/metadata");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"pageId\":\"" + pageId("start.md") + "\"",
                "\"pageId\":\"" + pageId("handbuch/betrieb.txt") + "\"", "\"complete\":true")
                // Versteckte Ordner bleiben außen vor, und weder Link noch resourceId nennen den Serverpfad.
                .contains("\"url\":null", "\"resourceId\":\"files:docs/" + pageId("start.md") + "\"")
                .doesNotContain(pageId(".ssh/schluessel.txt"), "schluessel", DIR.toString(), "\"file:");
        assertThat(get("/api/v1/sources/docs/pages/" + pageId(".ssh/schluessel.txt") + "/metadata").statusCode())
                .isEqualTo(404);
    }

    @Test
    void aSingleFileHasMetadataUnderTheCanonicalRoute() throws Exception {
        HttpResponse<String> found = get("/api/v1/sources/docs/pages/" + pageId("handbuch/betrieb.txt")
                + "/metadata");
        HttpResponse<String> missing = get("/api/v1/sources/docs/pages/" + pageId("fehlt.md") + "/metadata");

        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body()).contains("\"spaceKey\":\"docs\"",
                "\"resourceId\":\"files:docs/" + pageId("handbuch/betrieb.txt") + "\"").doesNotContain(DIR.toString());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain(DIR.toString());
    }

    @Test
    void theStoredPageIsTheFileByteForByte() {
        var page = catalog.find("docs").orElseThrow().storedPage(pageId("start.md"));

        assertThat(page.payload()).isEqualTo("# Start\n".getBytes(StandardCharsets.UTF_8));
        assertThat(page.nativeFormat()).isEqualTo("file");
    }

    @Test
    void theKeywordSearchFindsCachedFilesInTheFullTextIndex() throws Exception {
        assertThat(get("/api/v1/sources/docs/pages/" + pageId("handbuch/betrieb.txt")).statusCode()).isEqualTo(200);

        HttpResponse<String> response = get("/api/v1/search?text=betrieb");

        // Dateien haben keine eigene Suche (#180): gesucht hat der Volltextindex des Caches.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"indexed\"",
                "\"pageId\":\"" + pageId("handbuch/betrieb.txt") + "\"").doesNotContain(DIR.toString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
