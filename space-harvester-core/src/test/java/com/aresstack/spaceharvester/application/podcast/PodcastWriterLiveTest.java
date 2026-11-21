package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Segment;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echttest der drei Planungsstufen gegen einen Ollama-Server (nur HTTP-API, {@code /api/chat}, Temperatur 0, ohne
 * Nachdenken wie die Analyseschritte), mit einer erfundenen Zusammenfassung. Läuft nur mit
 * {@code -PpodcastLiveOllama=http://127.0.0.1:11434}; in der CI übersprungen. Gezählt wird, ob die Stufen ohne Ersatz
 * auskommen; der Text wird ausgegeben, damit man ihn lesen kann.
 */
@EnabledIfSystemProperty(named = "podcast.live.ollama", matches = "https?://.+")
class PodcastWriterLiveTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void plansAndWritesAConversationWithoutFallbacks() {
        StepModel model = ollama(System.getProperty("podcast.live.ollama"),
                System.getProperty("podcast.live.model", "gemma4:12b"));
        PodcastWriter writer = new PodcastWriter(model, attempt -> { });
        PodcastWriter.Material material = new PodcastWriter.Material("""
                Im Space DEMO wurde das Betriebshandbuch für den fiktiven Nachtlauf „Nordlicht“ überarbeitet: Der \
                Abbruch bei vollem Spool-Bereich wird jetzt mit einer Prüfung vor dem Start verhindert, und es gibt \
                eine neue Seite mit den Eskalationsstufen.

                Zwei neue Seiten beschreiben die Umstellung der Testumgebung auf die neue Datenbankversion; der \
                Termin ist für das nächste Quartal geplant.

                Auf der Startseite stehen neu die Ansprechstellen je Team und ein Glossar mit zwölf Begriffen.""",
                List.of("Nachtlauf Nordlicht: Prüfung vor dem Start verhindert Abbruch bei vollem Spool-Bereich",
                        "Umstellung der Testumgebung auf neue Datenbankversion im nächsten Quartal",
                        "Startseite mit Ansprechstellen und Glossar"), "Space DEMO");
        PodcastWriter.Voices voices = new PodcastWriter.Voices("Jana", "Thomas");

        PodcastPlan plan = writer.outline(material);
        System.out.println("[live] Titel: " + plan.title() + ", Themen: " + plan.topics().size()
                + ", Reparaturen: " + plan.repairs());
        List<String> repairs = new ArrayList<>(plan.repairs());
        Segment before = null;
        int segments = 0;
        for (int t = 0; t < plan.topics().size(); t++) {
            List<Segment> planned = writer.segments(plan, t, voices, repairs);
            for (int s = 0; s < planned.size(); s++) {
                boolean last = t == plan.topics().size() - 1 && s == planned.size() - 1;
                List<Turn> turns = writer.script(plan, t, planned.get(s), before, last, voices, repairs);
                System.out.println("[live] == " + plan.topics().get(t).title() + " / " + planned.get(s).title());
                turns.forEach(turn -> System.out.println("[live] " + turn.speaker() + ": " + turn.text()));
                assertThat(turns).allSatisfy(turn -> assertThat(turn.text().length())
                        .isLessThanOrEqualTo(PodcastWriter.MAX_TURN_CHARS * 2));
                before = planned.get(s);
                segments++;
            }
        }
        System.out.println("[live] Abschnitte: " + segments + ", Reparaturen: " + repairs);
        assertThat(plan.topics().size()).isBetween(2, PodcastPlan.MAX_TOPICS);
        assertThat(repairs).noneMatch(repair -> repair.contains("ohne Modell"));
    }

    private static StepModel ollama(String base, String name) {
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
                body.putObject("options").put("temperature", 0);
                body.putArray("messages").addObject().put("role", "system").put("content", instructions);
                ((tools.jackson.databind.node.ArrayNode) body.get("messages")).addObject().put("role", "user")
                        .put("content", input);
                try {
                    HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                            .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                            HttpResponse.BodyHandlers.ofString());
                    JsonNode answer = JSON.readTree(response.body());
                    return answer.path("message").path("content").asString();
                } catch (Exception e) {
                    throw new IllegalStateException(e.getClass().getSimpleName());
                }
            }
        };
    }
}
