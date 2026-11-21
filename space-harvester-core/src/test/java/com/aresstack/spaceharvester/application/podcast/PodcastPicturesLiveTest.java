package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.document.SvgDrawing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echttest der Bilder des Podcasts gegen einen Ollama-Server (nur HTTP-API, {@code /api/chat}, Temperatur 0, ohne
 * Nachdenken, Tokenlänge wie die Bildgenerierung 16384), mit erfundenen Themen. Läuft nur mit
 * {@code -PpodcastLiveOllama=http://127.0.0.1:11434}; in der CI übersprungen. Die Schaubilder liegen danach als SVG und
 * PNG unter {@code build/reports/podcast-pictures}, damit man sie ansehen kann.
 */
@EnabledIfSystemProperty(named = "podcast.live.ollama", matches = "https?://.+")
class PodcastPicturesLiveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void choosesAPageImageByItsDescriptionAndDrawsDiagramsFromParts() throws Exception {
        String base = System.getProperty("podcast.live.ollama");
        String name = System.getProperty("podcast.live.model", "gemma4:12b");
        StepModel model = ollama(base, name, 16384);
        Path out = Path.of("build/reports/podcast-pictures");
        Files.createDirectories(out);
        ExecutorService parts = Executors.newFixedThreadPool(2);
        try {
            byte[] photo = PodcastPicturesTest.png(800, 450);
            PodcastPictures.PageImageSource source = new PodcastPictures.PageImageSource() {
                @Override
                public List<Candidate> candidates(String sourceId, List<String> pageIds) {
                    return List.of(new Candidate("p1", "a1", "logo.png", "Das Logo des Teams: ein blauer Kreis mit "
                                    + "einem stilisierten Stern.", "image/png"),
                            new Candidate("p2", "a2", "eskalation.png", "Ein Schaubild mit drei Stufen der "
                                    + "Eskalation beim Nachtlauf: Leitstand, Bereitschaft, Fachverantwortung, "
                                    + "verbunden durch Pfeile.", "image/png"),
                            new Candidate("p3", "a3", "kantine.jpg", "Ein Foto der Kantine mit Tischen.",
                                    "image/jpeg"));
                }

                @Override
                public Optional<byte[]> content(String sourceId, Candidate candidate) {
                    return Optional.of(photo);
                }
            };
            Path store = Files.createTempDirectory("podcast-pictures");
            PodcastPictures pictures = new PodcastPictures(new PodcastStore(store), model, model, source, parts,
                    attempt -> { });
            List<String> repairs = new ArrayList<>();
            java.util.Set<String> used = new java.util.HashSet<>();

            PodcastPictures.Picture escalation = pictures.make("00000000000000a1", "s", new PodcastPictures.Brief(1,
                    "Neue Eskalationsstufen für den Nachtlauf Nordlicht", "Für den fiktiven Nachtlauf Nordlicht gibt es "
                    + "eine neue Seite mit den Eskalationsstufen: zuerst der Leitstand, dann die Bereitschaft, "
                    + "zuletzt die Fachverantwortung.", List.of("p1", "p2", "p3")), used, repairs);
            System.out.println("[live] Eskalation: " + escalation.kind() + " " + escalation.pageId());
            assertThat(escalation.kind()).isEqualTo(PodcastPictures.Kind.PAGE);
            assertThat(escalation.pageId()).isEqualTo("p2");

            String[][] topics = {
                    {"Prüfung vor dem Start", "Der fiktive Nachtlauf Nordlicht bricht nicht mehr ab, wenn der "
                            + "Spool-Bereich voll ist: Vor dem Start prüft ein neuer Schritt den freien Platz, räumt "
                            + "alte Ausgaben ab und startet erst dann den Lauf."},
                    {"Testumgebung zieht um", "Die Testumgebung wird im nächsten Quartal auf die neue "
                            + "Datenbankversion umgestellt. Betroffen sind drei Fachverfahren; vorher gibt es einen "
                            + "Probelauf und eine Rückfallebene."},
                    {"Wer hilft wem", "Auf der Startseite stehen jetzt die Ansprechstellen je Team: Betrieb, "
                            + "Entwicklung, Fachbereich und Sicherheit, dazu ein Glossar mit zwölf Begriffen."}};
            int drawn = 0;
            for (int i = 0; i < topics.length; i++) {
                long start = System.nanoTime();
                PodcastPictures.Picture picture = pictures.make("00000000000000a1", "s", new PodcastPictures.Brief(
                        2 + i, topics[i][0], topics[i][1], List.of()), used, repairs);
                System.out.printf("[live] %s: %s in %d s%n", topics[i][0], picture.kind(),
                        (System.nanoTime() - start) / 1_000_000_000L);
                if (picture.kind() == PodcastPictures.Kind.DIAGRAM) {
                    drawn++;
                    String svg = Files.readString(picture.file());
                    Files.writeString(out.resolve("thema-" + (i + 1) + ".svg"), svg);
                    ImageIO.write(SvgDrawing.parse(svg).raster(1280), "png",
                            out.resolve("thema-" + (i + 1) + ".png").toFile());
                    Path folder = picture.file().getParent();
                    String planName = String.format("pic-%02d-plan", 2 + i);
                    try (var plan = Files.list(folder)) {
                        plan.filter(file -> file.getFileName().toString().startsWith(planName)).forEach(file -> {
                                    try {
                                        System.out.println("[live] Plan: " + Files.readString(file));
                                    } catch (java.io.IOException e) {
                                        throw new java.io.UncheckedIOException(e);
                                    }
                                });
                    }
                }
            }
            System.out.println("[live] Reparaturen: " + repairs);
            assertThat(drawn).isEqualTo(topics.length);
            assertThat(repairs).noneMatch(repair -> repair.contains("kein Schaubild"));
        } finally {
            parts.shutdownNow();
        }
    }

    static StepModel ollama(String base, String name, int maxTokens) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new StepModel() {
            @Override
            public String version() {
                return "live:" + name;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                ObjectNode body = JSON.createObjectNode();
                body.put("model", name);
                body.put("stream", false);
                body.put("think", false);
                body.putObject("options").put("temperature", 0).put("num_predict", maxTokens);
                body.putArray("messages").addObject().put("role", "system").put("content", instructions);
                ((tools.jackson.databind.node.ArrayNode) body.get("messages")).addObject().put("role", "user")
                        .put("content", input);
                try {
                    HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                            .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                            HttpResponse.BodyHandlers.ofString());
                    JsonNode answer = JSON.readTree(response.body());
                    if ("length".equals(answer.path("done_reason").asString(""))) {
                        throw new IllegalStateException("abgeschnitten");
                    }
                    return answer.path("message").path("content").asString();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getClass().getSimpleName());
                }
            }
        };
    }
}
