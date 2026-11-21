package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Beat;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Segment;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Speaker;
import com.aresstack.spaceharvester.application.podcast.PodcastPlan.Turn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PodcastWriterTest {

    @Test
    void cutsAnOverlongOutlineButKeepsTheClosingTopic() {
        StringBuilder topics = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            topics.append(i > 1 ? "," : "").append("{\"title\":\"Thema ").append(i).append("\"}");
        }
        List<String> repairs = new ArrayList<>();

        PodcastPlan plan = PodcastWriter.readOutline("{\"title\":\"T\",\"topics\":[" + topics + "]}", 0, repairs);

        assertThat(plan.topics()).hasSize(PodcastPlan.MAX_TOPICS);
        assertThat(plan.topics().get(PodcastPlan.MAX_TOPICS - 1).title()).isEqualTo("Thema 12");
        assertThat(repairs).hasSize(1);
    }

    @Test
    void answersWithoutTopicsCountAsMissing() {
        assertThat(PodcastWriter.readOutline("Leider nein.", 0, new ArrayList<>())).isNull();
        assertThat(PodcastWriter.readOutline("{\"topics\":[]}", 0, new ArrayList<>())).isNull();
    }

    @Test
    void fillsMissingSpeakersByAlternation() {
        List<Segment> segments = PodcastWriter.readSegments("{\"segments\":[{\"title\":\"A\",\"beats\":[{\"intent\":"
                + "\"eins\"},{\"intent\":\"zwei\"},{\"speaker\":\"B\",\"intent\":\"drei\"}]}]}", false, new ArrayList<>());

        assertThat(segments.get(0).beats()).extracting(Beat::speaker)
                .containsExactly(Speaker.HOST, Speaker.COHOST, Speaker.COHOST);
    }

    @Test
    void stripsSpeakerPrefixesMarkdownAndStageDirections() {
        assertThat(PodcastWriter.spoken("Jana: **Hallo** [lacht] zusammen!")).isEqualTo("Hallo zusammen!");
    }

    @Test
    void turnsFollowThePlannedSpeakersWhenTheAnswerOmitsThem() {
        Segment segment = new Segment("A", List.of(new Beat(Speaker.COHOST, "x"), new Beat(Speaker.HOST, "y")));

        List<Turn> turns = PodcastWriter.readTurns("{\"turns\":[{\"text\":\"Eins.\"},{\"text\":\"Zwei.\"}]}", segment);

        assertThat(turns).extracting(Turn::speaker).containsExactly(Speaker.COHOST, Speaker.HOST);
    }

    @Test
    void theFallbackScriptReadsTheStoriesWithPlaceGreetingAndGoodbye() {
        PodcastWriter.Material material = new PodcastWriter.Material("", List.of(new PodcastWriter.Story(
                "Neue Startseite", "Die Startseite zeigt die Ansprechstellen je Team.",
                List.of("auf der Seite „Start“"))), "Space DEMO");

        List<Turn> turns = PodcastWriter.fallbackScript(material, new PodcastPlan.Topic("Neue Startseite", "", 0.5,
                List.of(), List.of(1)), true, true);

        assertThat(turns).hasSize(3);
        assertThat(turns.get(1).text()).isEqualTo("Neue Startseite: Die Startseite zeigt die Ansprechstellen je Team. "
                + "Zu finden auf der Seite „Start“.");
    }

    @Test
    void everyStoryIsToldExactlyOnce() {
        List<String> repairs = new ArrayList<>();

        PodcastPlan plan = PodcastWriter.readOutline("{\"topics\":[{\"title\":\"Einstieg\",\"stories\":[]},"
                + "{\"title\":\"Nachtlauf\",\"stories\":[1,3,1,9]},{\"title\":\"Datenbank\",\"stories\":[3]},"
                + "{\"title\":\"Abschluss\",\"stories\":[]}]}", 4, repairs);

        assertThat(plan.topics()).extracting(PodcastPlan.Topic::stories).containsExactly(List.of(), List.of(1, 3),
                List.of(), List.of(2), List.of(4), List.of());
        assertThat(plan.topics().get(plan.topics().size() - 1).title()).isEqualTo("Abschluss");
        assertThat(repairs).anyMatch(repair -> repair.contains("[2, 4]"));
    }

    @Test
    void eachTopicSeesOnlyItsOwnStoriesInFull() {
        PodcastWriter.Material material = new PodcastWriter.Material("Neues bei Nordlicht und in der Testumgebung.",
                List.of(new PodcastWriter.Story("Nachtlauf", "Vor dem Start wird der Spool-Bereich geprüft.",
                                List.of("auf der Seite „Nachtlauf“, unter Handbuch")),
                        new PodcastWriter.Story("Datenbank", "Die Testumgebung zieht im nächsten Quartal um.",
                                List.of())), "Space DEMO");
        PodcastPlan plan = new PodcastPlan("Neues", List.of(new PodcastPlan.Topic("Einstieg", "", 0.6, List.of()),
                new PodcastPlan.Topic("Nachtlauf", "", 0.5, List.of(), List.of(1)),
                new PodcastPlan.Topic("Datenbank", "", 0.5, List.of(), List.of(2)),
                new PodcastPlan.Topic("Abschluss", "", 0.4, List.of())), List.of());

        String nightRun = PodcastWriter.topicMaterial(material, plan, 1);
        String intro = PodcastWriter.topicMaterial(material, plan, 0);

        assertThat(nightRun).contains("Spool-Bereich", "unter Handbuch").doesNotContain("Quartal", "Überblick");
        assertThat(intro).contains("Überblick").doesNotContain("Spool-Bereich");
    }

    @Test
    void voicesPairTheDefaultWithTheOtherGender() {
        PodcastVoices voices = PodcastVoices.bundled();

        assertThat(voices.pair("orpheus-3b-german")).isEqualTo(new PodcastVoices.Pair("Jana", "Thomas"));
        assertThat(voices.pair("Qwen3-TTS-12Hz-CustomVoice")).isEqualTo(new PodcastVoices.Pair("Vivian", "Ryan"));
        assertThat(voices.ranked(List.of("other-tts", "qwen3-tts", "orpheus-de")))
                .containsExactly("orpheus-de", "qwen3-tts", "other-tts");
    }
}
