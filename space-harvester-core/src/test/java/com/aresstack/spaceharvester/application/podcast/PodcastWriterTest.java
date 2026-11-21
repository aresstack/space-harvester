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

        PodcastPlan plan = PodcastWriter.readOutline("{\"title\":\"T\",\"topics\":[" + topics + "]}", repairs);

        assertThat(plan.topics()).hasSize(PodcastPlan.MAX_TOPICS);
        assertThat(plan.topics().get(PodcastPlan.MAX_TOPICS - 1).title()).isEqualTo("Thema 12");
        assertThat(repairs).hasSize(1);
    }

    @Test
    void answersWithoutTopicsCountAsMissing() {
        assertThat(PodcastWriter.readOutline("Leider nein.", new ArrayList<>())).isNull();
        assertThat(PodcastWriter.readOutline("{\"topics\":[]}", new ArrayList<>())).isNull();
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
    void theFallbackScriptReadsThePointsWithGreetingAndGoodbye() {
        List<Turn> turns = PodcastWriter.fallbackScript(new PodcastPlan.Topic("Neue Startseite", "", 0.5,
                List.of("Die Startseite wurde neu gebaut.")), true, true);

        assertThat(turns).hasSize(4);
        assertThat(turns.get(2).text()).isEqualTo("Die Startseite wurde neu gebaut.");
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
