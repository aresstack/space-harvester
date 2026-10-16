package com.aresstack.spaceharvester.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Führt die Curl-Beispiele aus docs/E2E.md gegen den Dienst mit der Demo-Konfiguration (Profil {@code demo},
 * config/application-demo.yml) und der Demo-Fake-Confluence aus: jede Antwort muss den dokumentierten Auszug enthalten.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class DocumentedExamplesTest {

    private static final Path GUIDE = Path.of("docs/E2E.md");
    /** Ein {@code bash}-Block mit einem einzelnen {@code curl}, danach bis zum nächsten Block dessen Antwortauszug. */
    private static final Pattern EXAMPLE = Pattern.compile(
            "```bash\\n(curl [^\\n]*)\\n```\\n(?:(?!```)[\\s\\S])*?```json\\n([\\s\\S]*?)\\n```");
    private static final Pattern URL = Pattern.compile("\"http://localhost:8080(/[^\"]*)\"");
    private static final Pattern TOKEN = Pattern.compile("(?m)^TOKEN=(\\S+)$");
    private static final String AUTHORIZATION = "-H \"Authorization: Bearer $TOKEN\"";

    private static final DemoConfluence CONFLUENCE;

    static {
        try {
            CONFLUENCE = DemoConfluence.start(0);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void demoConfluence(DynamicPropertyRegistry registry) {
        // Platzhalter in config/application-demo.yml; die Quellenliste selbst bleibt die dokumentierte.
        registry.add("SPACE_HARVESTER_DEMO_CONFLUENCE", () -> CONFLUENCE.baseUrl().toString());
    }

    @AfterAll
    static void stopConfluence() {
        CONFLUENCE.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void guideDocumentsEveryCoreOperation() throws IOException {
        List<Example> examples = examples();
        assertThat(examples).extracting(Example::path).contains(
                "/api/v1/sources",
                "/api/v1/sources/demo/spaces",
                "/api/v1/sources/demo/spaces/DEMO/pages/metadata?limit=3",
                "/api/v1/sources/demo/pages/1001/metadata");
    }

    @TestFactory
    Stream<DynamicTest> documentedCurlExamplesWork() throws IOException {
        String token = token();
        return examples().stream().map(example -> DynamicTest.dynamicTest(example.curl(), () -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + environment.getProperty("local.server.port") + example.path()));
            if (example.authenticated()) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode expected = json.readTree(example.expected());
            int status = expected.has("status") ? expected.get("status").asInt() : 200;
            assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
            assertContains(json.readTree(response.body()), expected, "$");
        }));
    }

    /** Jedes Feld des Auszugs steht mit gleichem Wert in der Antwort; Arrays haben dieselbe Länge. */
    private static void assertContains(JsonNode actual, JsonNode expected, String path) {
        if (expected.isObject()) {
            assertThat(actual.isObject()).as(path + " ist ein Objekt").isTrue();
            for (Map.Entry<String, JsonNode> field : expected.properties()) {
                String child = path + "." + field.getKey();
                if (!actual.has(field.getKey())) {
                    fail(child + " fehlt in der Antwort");
                }
                assertContains(actual.get(field.getKey()), field.getValue(), child);
            }
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as(path + " ist ein Array").isTrue();
            assertThat(actual.size()).as(path + " Länge").isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertContains(actual.get(i), expected.get(i), path + "[" + i + "]");
            }
        } else {
            assertThat(actual).as(path).isEqualTo(expected);
        }
    }

    private static List<Example> examples() throws IOException {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        List<Example> examples = new ArrayList<>();
        Matcher matcher = EXAMPLE.matcher(guide);
        while (matcher.find()) {
            String curl = matcher.group(1);
            Matcher url = URL.matcher(curl);
            if (!url.find()) {
                fail("Beispiel ohne URL auf http://localhost:8080: " + curl);
            }
            examples.add(new Example(curl, url.group(1), curl.contains(AUTHORIZATION), matcher.group(2)));
        }
        long curls = Pattern.compile("(?m)^curl ").matcher(guide).results().count();
        assertThat(examples).as("jedes curl in docs/E2E.md hat einen Antwortauszug").hasSize((int) curls);
        return examples;
    }

    private static String token() throws IOException {
        Matcher matcher = TOKEN.matcher(Files.readString(GUIDE, StandardCharsets.UTF_8));
        assertThat(matcher.find()).as("TOKEN=… in docs/E2E.md").isTrue();
        return matcher.group(1);
    }

    private record Example(String curl, String path, boolean authenticated, String expected) {
    }
}
