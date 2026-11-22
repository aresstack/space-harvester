package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Echttest der inhaltlichen Update-Zusammenfassung ({@code update-summary-v2}) gegen einen Ollama-Server, nur über die
 * HTTP-API und nur mit erfundenen Seiten. Läuft nur mit {@code -PsummaryLiveOllama=http://127.0.0.1:11434}
 * [{@code -PsummaryLiveModel=gemma4:12b}]; in der CI übersprungen. Schreibt das Ergebnis nach
 * {@code build/reports/update-summary-live.md}.
 */
@EnabledIfSystemProperty(named = "summary.live.ollama", matches = "https?://.+")
class UpdateSummaryLiveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant FROM = Instant.parse("2026-11-17T17:29:00Z");
    private static final Instant TO = Instant.parse("2026-11-21T19:50:00Z");

    private final Map<String, String> texts = new HashMap<>();
    private final Map<String, List<String>> paths = new HashMap<>();
    private final List<ReportedPage> pages = new ArrayList<>();

    @Test
    void similarPagesBecomeOneTopicWithDetailsAndSpaceNames() throws Exception {
        // Sechs gleichartige Protokolle einer Arbeitsgruppe, zeitgleich eingestellt, in zwei Spaces.
        for (int i = 1; i <= 6; i++) {
            String space = i <= 4 ? "PBA" : "QFA";
            add(space, "p" + i, "Protokoll AG Schnittstelle, Sitzung " + i, List.of("Arbeitsgruppen", "AG Schnittstelle"),
                    "Teilnehmende: Fachamt Beispielstadt, Rechenzentrum Muster. Thema der Sitzung " + i + ": Abstimmung "
                            + "der Datenschnittstelle zwischen dem Beispielarchiv und dem Fachverfahren Muster. "
                            + (i % 2 == 0 ? "Beschluss: Die Übergabe erfolgt künftig als XML nach Schema 2.1. "
                            : "Offen: Wer prüft die Prüfsummen der Lieferungen? ")
                            + "Nächster Termin in zwei Wochen. Aufgabe bis dahin: Testlieferung mit 500 Datensätzen.");
        }
        // Drei Anträge auf Ausnahmegenehmigung, ähnliches Muster.
        for (int i = 1; i <= 3; i++) {
            add("PBA", "a" + i, "Antrag auf Ausnahmegenehmigung " + (i == 1 ? "Spring Cloud Config"
                            : i == 2 ? "Zugriff auf Testumgebung" : "Längere Aufbewahrung"), List.of("Anträge"),
                    "Antragsteller: Projekt Beispielarchiv. Begründung: Für den Betrieb wird eine Abweichung von der "
                            + "Architekturvorgabe benötigt. Geltungsdauer: bis Ende 2027. Prüfung durch das "
                            + "Architekturboard steht aus.");
        }
        // Eine Infoseite in einem dritten Space.
        add("BMS", "i1", "Informationen zum Meldungs-Projekt", List.of("Projekte"),
                "Das Meldungs-Projekt stellt die Bürgermeldungen auf ein neues Portal um. Start der Pilotphase im "
                        + "Februar mit zwei Bezirken. Ansprechpartner ist das Projektbüro Musterstadt.");
        // Eine geänderte Seite mit echtem Unterschied.
        ContentDiff diff = new ContentDiff(ContentDiff.Status.AVAILABLE, List.of(new ContentChange("wiki", "c1", "4", "5",
                TO, List.of(new ParagraphChange(ParagraphChange.Kind.CHANGED,
                        "Die Datensicherung läuft täglich um 22 Uhr.",
                        "Die Datensicherung läuft täglich um 2 Uhr; am Wochenende zusätzlich eine Vollsicherung.")),
                false)));
        pages.add(page("BMS", "c1", ChangeKind.CONTENT_CHANGED, "Betriebshandbuch Beispielarchiv", diff));
        paths.put("c1", List.of("Betrieb"));
        texts.put("c1", "Betriebshandbuch des Beispielarchivs. Die Datensicherung läuft täglich um 2 Uhr.");

        Map<ChangeKind, Integer> kinds = new EnumMap<>(ChangeKind.class);
        pages.forEach(page -> kinds.merge(page.kind(), 1, Integer::sum));
        Map<String, Integer> spaces = new HashMap<>();
        pages.forEach(page -> spaces.merge(page.spaceKey(), 1, Integer::sum));
        ChangeReport report = new ChangeReport(new ChangeUpdate("u1", "wiki", FROM, TO, null, false, kinds, spaces,
                pages.size(), 0, 0, false), pages);
        Map<String, String> names = Map.of("PBA", "Projekt Beispielarchiv", "QFA", "Querschnitt Fachamt",
                "BMS", "Betrieb Musterstadt");

        String model = System.getProperty("summary.live.model", "gemma4:12b");
        UpdateSummarizer.Result result = new UpdateSummarizer(
                com.aresstack.spaceharvester.source.ai.LiveSummaryModel.ollama(
                        System.getProperty("summary.live.ollama"), model), key -> true).summarize(report, page -> paths.getOrDefault(page.pageId(), List.of()),
                page -> Optional.ofNullable(texts.get(page.pageId())), names, context());

        StringBuilder out = new StringBuilder("# Update-Zusammenfassung (Echttest, ").append(model).append(")\n\n")
                .append(result.text()).append("\n");
        for (StoredSummary.Statement topic : result.statements()) {
            out.append("\n## ").append(topic.title()).append("\n").append(topic.text()).append("\n\nDetails: ")
                    .append(topic.details()).append("\n\nSeiten: ").append(topic.pageIds()).append("\n");
        }
        Path report2 = Path.of("build", "reports", "update-summary-live.md");
        Files.createDirectories(report2.getParent());
        Files.writeString(report2, out, StandardCharsets.UTF_8);
        System.out.println(out);

        assertThat(result.text()).isNotBlank().doesNotContain("PBA", "QFA", "BMS");
        assertThat(result.statements()).as("gebündelt statt Seite für Seite").hasSizeLessThan(pages.size())
                .allSatisfy(topic -> {
                    assertThat(topic.title()).isNotBlank();
                    assertThat(topic.details()).isNotBlank();
                });
        assertThat(result.statements().stream().anyMatch(topic -> topic.pageIds().containsAll(
                Set.of("p1", "p2", "p3", "p4", "p5", "p6")))).as("die Protokolle sind ein Thema").isTrue();
    }

    private void add(String space, String id, String title, List<String> path, String text) {
        pages.add(page(space, id, ChangeKind.NEW, title, null));
        paths.put(id, path);
        texts.put(id, text);
    }

    private static ReportedPage page(String space, String id, ChangeKind kind, String title, ContentDiff diff) {
        return new ReportedPage("wiki", space, id, kind, Set.of(kind), title, List.of(),
                URI.create("https://wiki.example.org/" + id), null, "1", null, title, null, null, null, space, false,
                TO, diff);
    }

    private static JobContext context() {
        return new JobContext() {
            @Override
            public String jobId() {
                return "live";
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public boolean cancelRequested() {
                return false;
            }

            @Override
            public void discovered(long count) {
            }

            @Override
            public void processed(long count) {
            }

            @Override
            public void written(long count) {
            }

            @Override
            public void failed(long count) {
            }
        };
    }

    /** Ollama über {@code /api/chat}, ohne Denken, Antwort als JSON. */
    private record Ollama(String base, String model) implements SummaryModel {

        @Override
        public String version() {
            return "live:" + model;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String complete(String instructions, String input) {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.put("stream", false);
            body.put("think", false);
            body.put("format", "json");
            body.putObject("options").put("num_ctx", 16384).put("temperature", 0.2);
            body.putArray("messages").add(JSON.createObjectNode().put("role", "system").put("content", instructions))
                    .add(JSON.createObjectNode().put("role", "user").put("content", input));
            try {
                HttpResponse<String> response = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null))
                        .connectTimeout(Duration.ofSeconds(10)).build()
                        .send(HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/api/chat"))
                                .timeout(Duration.ofMinutes(10)).header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode answer = JSON.readTree(response.body());
                return answer.path("message").path("content").asString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Ollama nicht erreichbar", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
