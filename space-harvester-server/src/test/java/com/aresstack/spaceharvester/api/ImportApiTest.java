package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConvertiblePage;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.output.convert.JsonPackageConverter;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Import eines JSON-Pakets über echtes HTTP: Auftrag mit Fortschritt, nur mit manage-configuration. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ImportApiTest {

    private static final String ADMIN_TOKEN = "import-admin-token-7c1f";
    private static final Path CACHE = Path.of("build", "import-api-test", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[1].id", () -> "import-admin");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(ADMIN_TOKEN));
        registry.add("space-harvester.security.clients[1].manage-configuration", () -> "true");
        registry.add("space-harvester.cache.directory", () -> CACHE.toString());
        registry.add("space-harvester.assets.directory", () -> CACHE.resolve("assets").toString());
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void anAdministratorImportsAPackageAsAJob() throws Exception {
        HttpResponse<String> started = post(ADMIN_TOKEN, "/api/v1/sources/wiki/data/imports", pkg());

        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        JsonNode job = json.readTree(started.body());
        assertThat(job.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(job.get("spaceKey").asString()).isEqualTo("DEV");
        String location = started.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/api/v1/sources/wiki/data/imports/" + job.get("id").asString());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode status = job;
        while (!List.of("completed", "failed").contains(status.get("state").asString())
                && Instant.now().isBefore(deadline)) {
            HttpResponse<String> polled = send(ADMIN_TOKEN, "GET", location);
            assertThat(polled.statusCode()).isEqualTo(200);
            status = json.readTree(polled.body());
            Thread.sleep(50);
        }
        assertThat(status.get("state").asString()).as(status.toString()).isEqualTo("completed");
        assertThat(status.get("progress").get("written").asLong()).isEqualTo(2);
    }

    @Test
    void oldPackagesOtherClientsAndUnknownSourcesAreRefused() throws Exception {
        HttpResponse<String> old = post(ADMIN_TOKEN, "/api/v1/sources/wiki/data/imports", oldPackage());
        assertThat(old.statusCode()).isEqualTo(400);
        assertThat(old.body()).contains("import-unsupported");

        HttpResponse<String> notZip = post(ADMIN_TOKEN, "/api/v1/sources/wiki/data/imports",
                "kein zip".getBytes(StandardCharsets.UTF_8));
        assertThat(notZip.statusCode()).isEqualTo(400);
        assertThat(notZip.body()).contains("import-not-a-package");

        HttpResponse<String> forbidden = post(TestClient.TOKEN, "/api/v1/sources/wiki/data/imports", pkg());
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(post(null, "/api/v1/sources/wiki/data/imports", pkg()).statusCode()).isEqualTo(401);

        HttpResponse<String> unknown = post(ADMIN_TOKEN, "/api/v1/sources/fehlt/data/imports", pkg());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("source-not-found");
        assertThat(send(ADMIN_TOKEN, "GET", "/api/v1/sources/wiki/data/imports/unbekannt").statusCode())
                .isEqualTo(404);
    }

    /** Ein Paket mit Seite 100 und ihrer Unterseite 101, wie „JSON mit Medien“ es schreibt. */
    private static byte[] pkg() throws Exception {
        List<PageSnapshot> pages = List.of(page("wiki", "DEV", "100", null), page("wiki", "DEV", "101", "100"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonPackageConverter().convert(new ConversionInput() {
            @Override
            public String sourceId() {
                return "wiki";
            }

            @Override
            public String spaceKey() {
                return "DEV";
            }

            @Override
            public PageTree tree() {
                return new PageTree(List.of(new PageTree.Node("100", List.of(new PageTree.Node("101", List.of())))),
                        List.of(), 2, 2);
            }

            @Override
            public List<PageHeader> headers() {
                return pages.stream().map(PageSnapshot::header).toList();
            }

            @Override
            public void forEachPage(Consumer<ConvertiblePage> action) {
                action.accept(new ConvertiblePage(pages.get(0).header(), pages.get(0), 0));
                action.accept(new ConvertiblePage(pages.get(1).header(), pages.get(1), 1));
            }
        }, out);
        return out.toByteArray();
    }

    private static byte[] oldPackage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("pages.json"));
            zip.write("[]".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    private HttpResponse<String> post(String token, String path, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + path))
                .header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String token, String method, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + path)).method(method,
                HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
