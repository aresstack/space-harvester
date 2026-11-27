package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Segment;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Speaker;
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
    void plansAndWritesANewsConversationWithoutFallbacksOrMetadata() {
        StepModel model = ollama(System.getProperty("podcast.live.ollama"),
                System.getProperty("podcast.live.model", "gemma4:12b"));
        PodcastWriter writer = new PodcastWriter(model, attempt -> { });
        // Der Überblick spricht absichtlich von Seitenzahlen und Größen: genau das soll der Podcast nicht erzählen.
        PodcastWriter.Material material = new PodcastWriter.Material("""
                Im Space DEMO gibt es 100 neue Seiten mit zusammen 840 Kilobyte, dazu 37 geänderte Seiten. Im \
                Mittelpunkt stehen der Nachtlauf Nordlicht, die Umstellung der Testumgebung und die Startseite.""",
                List.of(new PodcastWriter.Story("Nachtlauf Nordlicht bricht nicht mehr ab", """
                                Das Betriebshandbuch für den fiktiven Nachtlauf Nordlicht (Job NLXD042P) wurde \
                                überarbeitet. Vor dem Start prüft der Lauf jetzt mit dem Prüfskript NLX-CHK-7 (Ticket \
                                BET-4711), ob im Spool-Bereich genug Platz ist; ist er zu voll, startet \
                                er gar nicht erst und meldet das der Bereitschaft. Bisher brach der Lauf mitten in der \
                                Nacht ab und musste von Hand neu angestoßen werden. Neu ist auch eine Seite mit drei \
                                Eskalationsstufen: erst die Bereitschaft, nach einer Stunde das Betriebsteam, ab zwei \
                                Stunden die Leitung.""",
                                List.of("auf der Seite „Nachtlauf Nordlicht“, unter Betriebshandbuch",
                                        "auf der Seite „Eskalationsstufen“, unter Betriebshandbuch")),
                        new PodcastWriter.Story("Testumgebung zieht auf neue Datenbank um", """
                                Die Testumgebung wird im nächsten Quartal auf die neue Datenbankversion umgestellt. \
                                Zwei neue Seiten beschreiben den Ablauf: Testdaten werden vorher gesichert, die \
                                Umstellung läuft an einem Wochenende, und am Montag danach prüfen die Teams ihre \
                                Anwendungen anhand einer Checkliste.""",
                                List.of("auf der Seite „Umstellung Testdatenbank“, unter Testumgebung")),
                        new PodcastWriter.Story("Startseite mit Ansprechstellen und Glossar", """
                                Auf der Startseite stehen jetzt die Ansprechstellen je Team, damit man schneller \
                                weiß, wen man fragt. Dazu kommt ein Glossar mit zwölf Fachbegriffen, etwa was ein \
                                Spool-Bereich ist.""",
                                List.of("auf der Seite „Start“")),
                        // Erfunden, aber gebaut wie echte Updates (Angelo 22.11.2026): Aktenzeichen im Titel, Kürzel
                        // im Text, Space und Seitentitel voller Kennungen. Nichts davon darf vorgelesen werden.
                        new PodcastWriter.Story("PRV 27351 Projektstatus und Jour-Fixe", """
                                Am 20.11.2026 fand das wöchentliche Jour-Fixe des Leitstand-Teams statt, bei dem die                                 Anbindung des RZ K-PLM besprochen wurde. Der veröffentlichte Projektstatus PRV 27351                                 dokumentiert den Abschluss der Anbindung und die Übergabe der Verantwortung an den                                 Betrieb.""",
                                List.of("auf der Seite „2026-11-20 - RZ K-PLM Leitstand - Jour Fixe wöchentlich“ im "
                                        + "Space PRV 27351", "auf der Seite „Sachstand Projektergebnisse“ im Space "
                                        + "Kranich Extensions"))),
                "Space DEMO").withShow(UpdatePodcasts.show("confluence"));
        PodcastWriter.Voices voices = new PodcastWriter.Voices("Jana", "Thomas");
        List<Turn> told = new ArrayList<>();

        PodcastPlan plan = writer.outline(material);
        System.out.println("[live] Titel: " + plan.title() + ", Reparaturen: " + plan.repairs());
        plan.topics().forEach(topic -> System.out.println("[live] Thema: " + topic.title() + " " + topic.stories()));
        List<String> repairs = new ArrayList<>(plan.repairs());
        List<String> spoken = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        Segment before = null;
        for (int t = 0; t < plan.topics().size(); t++) {
            List<Segment> planned = writer.segments(material, plan, t, voices, repairs);
            for (int s = 0; s < planned.size(); s++) {
                boolean last = t == plan.topics().size() - 1 && s == planned.size() - 1;
                List<Turn> turns = writer.script(material, plan, t, planned, s, before, last, voices, repairs);
                System.out.println("[live] == " + plan.topics().get(t).title() + " / " + planned.get(s).title());
                turns.forEach(turn -> System.out.println("[live] " + turn.speaker() + ": " + turn.text()));
                turns.forEach(turn -> System.out.println("[tts]  " + SpeakableText.of(turn.text())));
                turns.forEach(turn -> spoken.add(turn.text()));
                told.addAll(turns);
                ends.add(turns.get(turns.size() - 1).text().strip());
                before = planned.get(s);
            }
        }
        System.out.println("[live] Reparaturen: " + repairs);
        String all = String.join("\n", spoken);
        // Angelo 22.11.2026: Bindestriche bringen die Stimme aus dem Tritt; die Sprachausgabe bekommt keine mehr.
        assertThat(told).extracting(turn -> SpeakableText.of(turn.text())).as("Sprechtext ohne Stolpersteine")
                .allSatisfy(text -> assertThat(text).doesNotContain("\u2011", "\u2010", "\u2013", "\u2014", "/", "→")
                        .doesNotContainPattern("[\\p{L}\\p{N}]-[\\p{L}\\p{N}]"));
        assertThat(repairs).noneMatch(repair -> repair.contains("ohne Modell"));
        // Angelo 22.11.2026: Begrüßung mit der Art der Quelle, kein erdachter Sendungsname, Rollen stimmen.
        assertThat(String.join(" ", spoken.subList(0, Math.min(3, spoken.size())))).as("Begrüßung")
                .contains("Confluence-Update");
        assertThat(all).as("kein erdachter Sendungsname").doesNotContain("IT-Info", "IT‑Info");
        assertThat(told).as("Jana ist Gastgeberin, Thomas Co-Host").noneMatch(turn ->
                turn.speaker() == Speaker.COHOST && turn.text().contains("Ich bin Jana")
                        || turn.speaker() == Speaker.HOST && turn.text().contains("Ich bin Thomas"));
        assertThat(all).as("keine Metadaten").doesNotContainIgnoringCase("kilobyte")
                .doesNotContain("100 neue Seiten", "hundert neue Seiten", "37 geänderte");
        assertThat(all).as("Inhalt kommt vor").contains("Spool").containsIgnoringCase("Checkliste");
        assertThat(all).as("Keine internen Kennungen im Sprechtext").doesNotContain("NLXD042P", "NLX-CHK-7",
                "BET-4711", "4711", "27351", "K-PLM", "PRV");
        assertThat(all).as("Keine Verweise und Space-Namen: die Stelle markiert der Bericht").doesNotContain(
                "Kranich Extensions", "Space DEMO", "im Space", "auf der Seite", "Jour Fixe wöchentlich");
        assertThat(all).as("das Jour-Fixe kommt verständlich vor").containsAnyOf("Leitstand", "Jour-Fixe",
                "Jour Fixe", "Rechenzentrum");
        assertThat(all).as("Die Eskalationsstufen kommen vor").containsAnyOf("Eskalation", "Stufe", "Leitung");
        assertThat(ends).as("Kein Abschnitt endet mit einer offenen Frage").noneMatch(text -> text.endsWith("?"));
        assertThat(spoken.stream().filter(text -> text.contains("Datenbank")).count())
                .as("Die Datenbank-Meldung wird nicht in jedem Abschnitt neu erzählt").isLessThan(spoken.size() / 2);
    }

    @Test
    void explainsAPageWithItsSubpagesWithoutReadingNumberTables() {
        StepModel model = ollama(System.getProperty("podcast.live.ollama"),
                System.getProperty("podcast.live.model", "gemma4:12b"));
        PodcastWriter writer = new PodcastWriter(model, attempt -> { });
        String text = """
                Der fiktive Nachtlauf Nordlicht verdichtet jede Nacht die Buchungen des Tages zu Monatsständen, damit \
                die Auswertungen am Morgen bereitstehen.
                Ablauf
                Um 22 Uhr startet die Vorabprüfung: Ist im Spool-Bereich genug Platz, folgen Verdichtung, Abgleich und \
                Bericht. Ist er zu voll, startet der Lauf nicht und meldet das der Bereitschaft.
                Laufzeiten der letzten Monate
                Monat | Start | Ende | Dauer | Sätze
                Juli | 22:00 | 01:12 | 3:12 | 1.204.332
                August | 22:00 | 01:40 | 3:40 | 1.388.120
                September | 22:00 | 01:05 | 3:05 | 1.150.876
                Oktober | 22:00 | 02:10 | 4:10 | 1.612.004
                Wer hilft
                Bei Fragen zur Verdichtung hilft das Fachteam Buchhaltung, bei technischen Störungen die Bereitschaft.""";
        int ablauf = text.indexOf("Ablauf");
        int zeiten = text.indexOf("Laufzeiten");
        int hilfe = text.indexOf("Wer hilft");
        PagePodcasts.Page page = new PagePodcasts.Page("1", "Nachtlauf Nordlicht", "DEMO",
                new com.aresstack.spaceharvester.application.enrichment.PageText("test/v1", text, List.of(
                        new com.aresstack.spaceharvester.application.enrichment.PageText.Section("Ablauf", 2, ablauf,
                                zeiten),
                        new com.aresstack.spaceharvester.application.enrichment.PageText.Section(
                                "Laufzeiten der letzten Monate", 2, zeiten, hilfe),
                        new com.aresstack.spaceharvester.application.enrichment.PageText.Section("Wer hilft", 2,
                                hilfe, text.length()))));
        PagePodcasts.Page escalation = new PagePodcasts.Page("2", "Eskalationsstufen", "DEMO",
                new com.aresstack.spaceharvester.application.enrichment.PageText("test/v1", """
                        Bleibt eine Störung offen, gelten drei Stufen: erst die Bereitschaft, nach einer Stunde das \
                        Betriebsteam, ab zwei Stunden die Leitung.""", List.of()));
        PodcastWriter.Material material = PagePodcasts.material(page,
                List.of(new PagePodcasts.Subpage(escalation, List.of())), List.of("Betriebshandbuch"),
                PagePodcasts.Reading.DEFAULT);
        PodcastWriter.Voices voices = new PodcastWriter.Voices("Jana", "Thomas");

        PodcastPlan plan = writer.outline(material);
        System.out.println("[live-page] Titel: " + plan.title() + ", Reparaturen: " + plan.repairs());
        plan.topics().forEach(topic -> System.out.println("[live-page] Thema: " + topic.title() + " "
                + topic.stories()));
        List<String> repairs = new ArrayList<>(plan.repairs());
        List<String> spoken = new ArrayList<>();
        Segment before = null;
        for (int t = 0; t < plan.topics().size(); t++) {
            List<Segment> planned = writer.segments(material, plan, t, voices, repairs);
            for (int s = 0; s < planned.size(); s++) {
                boolean last = t == plan.topics().size() - 1 && s == planned.size() - 1;
                List<Turn> turns = writer.script(material, plan, t, planned, s, before, last, voices, repairs);
                System.out.println("[live-page] == " + plan.topics().get(t).title() + " / " + planned.get(s).title());
                turns.forEach(turn -> System.out.println("[live-page] " + turn.speaker() + ": " + turn.text()));
                turns.forEach(turn -> spoken.add(turn.text()));
                before = planned.get(s);
            }
        }
        System.out.println("[live-page] Reparaturen: " + repairs);
        String all = String.join("\n", spoken);
        assertThat(repairs).noneMatch(repair -> repair.contains("ohne Modell"));
        assertThat(all).as("keine Zahlenkolonnen").doesNotContain("1.204.332", "1.388.120", "3:12", "01:40");
        assertThat(all).as("das Wesentliche").contains("Spool").containsAnyOf("Monatsst", "Auswertung")
                .containsAnyOf("Eskalation", "Stufe", "Leitung");
        assertThat(spoken.stream().filter(turn -> turn.contains("Seite Nachtlauf Nordlicht")).count())
                .as("die erklärte Seite wird nicht in jedem Thema neu genannt").isLessThanOrEqualTo(1);
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
