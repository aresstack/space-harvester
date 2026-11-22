package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.SubtitleLine;
import com.aresstack.spaceharvester.application.podcast.Podcast;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Erfundener Podcast über ein fiktives Projekt „Nordlicht“. */
class PodcastSubtitlesTest {

    private static final List<Podcast.Line> LINES = List.of(
            new Podcast.Line(true, "Willkommen bei Nordlicht. Heute geht es um die neue Startseite!"),
            new Podcast.Line(false, "Was ist denn neu, z. B. beim Glossar? Das würde mich interessieren."));

    @Test
    void splitsTurnsIntoSentencesAndKeepsAbbreviationsTogether() {
        assertThat(PodcastSubtitles.sentences(LINES.get(0).text()))
                .containsExactly("Willkommen bei Nordlicht.", "Heute geht es um die neue Startseite!");
        assertThat(PodcastSubtitles.sentences(LINES.get(1).text()))
                .containsExactly("Was ist denn neu, z. B. beim Glossar?", "Das würde mich interessieren.");
        assertThat(PodcastSubtitles.sentences(" ")).isEmpty();
    }

    @Test
    void picksTheSentenceByTheShareOfTheSpokenText() {
        assertThat(PodcastSubtitles.at(LINES, 0).text()).isEqualTo("Willkommen bei Nordlicht.");
        assertThat(PodcastSubtitles.at(LINES, 0.3).text()).isEqualTo("Heute geht es um die neue Startseite!");
        assertThat(PodcastSubtitles.at(LINES, 0.6).text()).isEqualTo("Was ist denn neu, z. B. beim Glossar?");
        PodcastSubtitles.Cue last = PodcastSubtitles.at(LINES, 1);
        assertThat(last.text()).isEqualTo("Das würde mich interessieren.");
        assertThat(last.share()).isEqualTo(1.0);
        // Die Sätze folgen einander, keiner wird übersprungen oder kehrt zurück.
        int changes = 0;
        String before = "";
        for (int i = 0; i <= 100; i++) {
            String text = PodcastSubtitles.at(LINES, i / 100d).text();
            if (!text.equals(before)) {
                changes++;
                before = text;
            }
        }
        assertThat(changes).isEqualTo(4);
        assertThat(PodcastSubtitles.at(List.of(), 0.5)).isEqualTo(PodcastSubtitles.Cue.NONE);
    }

    @Test
    void measuredStartsOfTheTurnsDecideTheTurnExactly() {
        // Der zweite Beitrag beginnt erst bei 12 s, obwohl der erste nach Textlänge viel früher zu Ende wäre.
        List<Podcast.Line> measured = List.of(
                new Podcast.Line(true, LINES.get(0).text(), 0),
                new Podcast.Line(false, LINES.get(1).text(), 12_000));
        assertThat(PodcastSubtitles.at(measured, 0, 20_000).text()).isEqualTo("Willkommen bei Nordlicht.");
        assertThat(PodcastSubtitles.at(measured, 11_000, 20_000).text())
                .isEqualTo("Heute geht es um die neue Startseite!");
        // In der Pause vor dem nächsten Beitrag bleibt der letzte Satz stehen.
        assertThat(PodcastSubtitles.at(measured, 11_900, 20_000).text())
                .isEqualTo("Heute geht es um die neue Startseite!");
        assertThat(PodcastSubtitles.at(measured, 12_000, 20_000).text())
                .isEqualTo("Was ist denn neu, z. B. beim Glossar?");
        assertThat(PodcastSubtitles.at(measured, 19_900, 20_000).text()).isEqualTo("Das würde mich interessieren.");
        // Fehlt eine Zeit (älteres Audio), gilt die Schätzung nach Textlänge über den ganzen Abschnitt.
        List<Podcast.Line> partly = List.of(measured.get(0), LINES.get(1));
        assertThat(PodcastSubtitles.at(partly, 11_000, 20_000).text())
                .isEqualTo(PodcastSubtitles.at(LINES, 0.55).text());
        assertThat(PodcastSubtitles.at(LINES, 5_000, 0)).isEqualTo(PodcastSubtitles.at(LINES, 0));
    }

    @Test
    void aLongSentenceIsShownInPagesOfTwoLinesThatFollowTheSpeech() {
        SubtitleLine line = new SubtitleLine();
        line.setSize(260, line.getPreferredSize().height);
        String sentence = "Jana erklärt sehr ausführlich, warum die neue Startseite von Nordlicht übersichtlicher ist, "
                + "welche Kacheln entfallen sind und wie das Glossar jetzt von jeder Seite aus erreichbar bleibt.";
        line.show(sentence, 0);
        List<String> first = line.shownLines();
        assertThat(first).hasSize(SubtitleLine.LINES);
        assertThat(first.get(0)).startsWith("Jana erklärt");
        for (String shown : first) {
            assertThat(line.getFontMetrics(line.getFont()).stringWidth(shown)).isLessThanOrEqualTo(260);
        }
        line.show(sentence, 1);
        List<String> last = line.shownLines();
        assertThat(last).isNotEqualTo(first);
        assertThat(last.getLast()).endsWith("bleibt.");
        // Die Höhe bleibt gleich, ob eine oder zwei Zeilen stehen: darunter springt nichts.
        int height = line.getPreferredSize().height;
        line.show("Kurz.", 0.5);
        assertThat(line.shownLines()).containsExactly("Kurz.");
        assertThat(line.getPreferredSize().height).isEqualTo(height);
        line.show("", 0);
        assertThat(line.shownLines()).isEmpty();
    }
}
