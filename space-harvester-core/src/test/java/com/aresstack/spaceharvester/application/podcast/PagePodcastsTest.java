package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Material des Erklär-Podcasts einer Seite, mit erfundenen Seiten. */
class PagePodcastsTest {

    private static PagePodcasts.Page page(String id, String title, String text, PageText.Section... sections) {
        return new PagePodcasts.Page(id, title, "DEMO", new PageText("test/v1", text, List.of(sections)));
    }

    @Test
    void sectionsBecomePartsAndSubpagesAreStackedBelow() {
        String text = "Wozu es den Nachtlauf gibt.\nAblauf\nErst Prüfung, dann Start.\nSchritte\nDrei Schritte.\n"
                + "Störungen\nBereitschaft anrufen.";
        int ablauf = text.indexOf("Ablauf");
        int schritte = text.indexOf("Schritte");
        int stoerungen = text.indexOf("Störungen");
        PagePodcasts.Page root = page("1", "Nachtlauf Nordlicht", text,
                new PageText.Section("Ablauf", 2, ablauf, stoerungen),
                new PageText.Section("Schritte", 3, schritte, stoerungen),
                new PageText.Section("Störungen", 2, stoerungen, text.length()));
        PagePodcasts.Subpage child = new PagePodcasts.Subpage(page("2", "Eskalation", "Drei Stufen bis zur Leitung."),
                List.of(page("3", "Rufnummern", "Wen man nachts erreicht.")));

        PodcastWriter.Material material = PagePodcasts.material(root, List.of(child), List.of("Handbuch", "Betrieb"));

        assertThat(material.format()).isEqualTo(PodcastWriter.Format.EXPLAINER);
        assertThat(material.stories()).extracting(PodcastWriter.Story::title)
                .containsExactly("Einleitung", "Ablauf", "Störungen", "Eskalation");
        assertThat(material.stories().get(1).text()).contains("Drei Schritte");
        assertThat(material.stories().get(3).text()).contains("Drei Stufen", "Darunter", "„Rufnummern“",
                "nachts erreicht");
        assertThat(material.stories().get(3).place()).isEqualTo("auf der Unterseite „Eskalation“");
        assertThat(material.pageIds()).containsExactly("1", "2", "3");
        assertThat(material.overview()).contains("„Nachtlauf Nordlicht“", "Space DEMO", "Handbuch › Betrieb",
                "Unterseiten: „Eskalation“");
    }

    @Test
    void leadingSubsectionsBeforeTheFirstMainHeadingBelongToTheIntroduction() {
        String text = "Vorab\nWichtiger Hinweis zum Start.\nAblauf\nErst prüfen.";
        int vorab = text.indexOf("Vorab");
        int ablauf = text.indexOf("Ablauf");
        PodcastWriter.Material material = PagePodcasts.material(page("1", "Nordlicht", text,
                new PageText.Section("Vorab", 3, vorab, ablauf),
                new PageText.Section("Ablauf", 2, ablauf, text.length())), List.of(), List.of());

        assertThat(material.stories()).extracting(PodcastWriter.Story::title).containsExactly("Einleitung", "Ablauf");
        assertThat(material.stories().get(0).text()).contains("Wichtiger Hinweis");
    }

    @Test
    void aPageWithoutHeadingsIsSplitAtParagraphs() {
        String paragraph = "Ein Absatz über die Testumgebung. ".repeat(60);
        PodcastWriter.Material material = PagePodcasts.material(page("1", "Testumgebung",
                paragraph + "\n" + paragraph + "\n" + paragraph), List.of(), List.of());

        assertThat(material.stories()).hasSizeGreaterThan(1);
        assertThat(material.stories().get(0).title()).isEqualTo("Testumgebung (1)");
    }

    @Test
    void theInputChangesWithTheContent() {
        PodcastWriter.Material one = PagePodcasts.material(page("1", "Start", "Alt."), List.of(), List.of());
        PodcastWriter.Material two = PagePodcasts.material(page("1", "Start", "Neu."), List.of(), List.of());

        assertThat(PagePodcasts.input(one)).isNotEqualTo(PagePodcasts.input(two));
        assertThat(PagePodcasts.input(one)).isEqualTo(PagePodcasts.input(
                PagePodcasts.material(page("1", "Start", "Alt."), List.of(), List.of())));
    }

    @Test
    void dullTablesAreLeftOutAndInformativeOnesStay() {
        String text = """
                Kosten je Monat
                Monat | Betrag | Anteil
                Januar | 1.200 € | 12 %
                Februar | 980 € | 9,5 %
                März | 1.050 € | 10 %
                Zuständigkeiten
                Thema | Wer | Wann
                Spool voll | Bereitschaft anrufen | sofort
                Datenbank langsam | Betriebsteam informieren | am nächsten Morgen""";

        String pruned = PodcastTables.prune(text);

        assertThat(pruned).doesNotContain("1.200").contains("(Tabelle mit Zahlen ausgelassen, Spalten: Monat, "
                + "Betrag, Anteil)");
        assertThat(pruned).contains("Spool voll | Bereitschaft anrufen | sofort", "Kosten je Monat",
                "Zuständigkeiten");
    }

    @Test
    void longInformativeTablesAreCut() {
        StringBuilder text = new StringBuilder("Begriff | Bedeutung\n");
        for (int i = 0; i < 30; i++) {
            text.append("Begriff ").append((char) ('A' + i % 26)).append(" | erklärt etwas Wichtiges\n");
        }

        String pruned = PodcastTables.prune(text.toString().strip());

        assertThat(pruned.lines().count()).isEqualTo(PodcastTables.MAX_ROWS + 2);
        assertThat(pruned).endsWith("(… weitere Zeilen der Tabelle ausgelassen)");
    }
}
