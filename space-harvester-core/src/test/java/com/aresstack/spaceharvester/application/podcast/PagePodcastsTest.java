package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Material des Erklär-Podcasts einer Seite, mit erfundenen Seiten. */
class PagePodcastsTest {

    private static final PagePodcasts.Reading READING = PagePodcasts.Reading.DEFAULT;

    private static PagePodcasts.Page page(String id, String title, String text, PageText.Section... sections) {
        return new PagePodcasts.Page(id, title, "DEMO", new PageText("test/v1", text, List.of(sections)));
    }

    @TempDir
    Path root;

    @Test
    void theReadingViewReadsThePageTextOnlyWithAiReleaseAndWithTheVoiceOfThePodcast() {
        String text = "Wozu es den Nachtlauf gibt.\nAblauf\nErst Prüfung, dann Start.";
        List<String[]> opened = new ArrayList<>();
        PodcastSpeech speech = new PodcastSpeech() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<String> models() {
                return List.of("tts-unbekannt", "orpheus-3b-german");
            }

            @Override
            public List<byte[]> speak(String model, String voice, String text, boolean sentences) throws IOException {
                throw new IOException("nicht gebraucht");
            }

            @Override
            public ReadAloud readAloud(String model, String voice, ReadAloud.Playback playback) {
                opened.add(new String[]{model, voice});
                return null;
            }
        };
        PagePodcasts.Pages pages = new PagePodcasts.Pages() {
            @Override
            public Optional<PagePodcasts.Page> page(AccessContext caller, String sourceId, String pageId) {
                // nur Seite 1 darf an einen KI-Dienst (ADR 0028)
                return pageId.equals("1") ? Optional.of(PagePodcastsTest.page("1", "Nachtlauf Nordlicht", text)) : Optional.empty();
            }

            @Override
            public List<String> children(AccessContext caller, String sourceId, String pageId) {
                return List.of();
            }

            @Override
            public List<String> ancestors(AccessContext caller, String sourceId, String pageId) {
                return List.of();
            }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC);
        PagePodcasts podcasts = new PagePodcasts(new Jobs(new InMemoryJobStore(), Runnable::run,
                new JobLimits(1, 5, Duration.ofHours(1)), clock), StepModel.NONE, speech,
                new PodcastStore(root.resolve("podcasts")), PodcastVoices.bundled(), pages, () -> 2);
        AccessContext caller = AccessContext.technicalClient("app");

        assertThat(podcasts.readAloudText(caller, "wiki", "1")).contains(text);
        assertThat(podcasts.readAloudText(caller, "wiki", "2")).as("ohne KI-Freigabe nichts").isEmpty();
        assertThat(podcasts.readAloudAvailable()).isTrue();

        podcasts.readAloud(new ReadAloud.Playback() {
            @Override
            public void play(byte[] wav) {
            }

            @Override
            public void finish() {
            }

            @Override
            public void stop() {
            }
        });

        // dasselbe Modell und dieselbe Stimme wie der Gastgeber des Podcasts
        assertThat(opened).singleElement().satisfies(choice -> assertThat(choice)
                .containsExactly("orpheus-3b-german", "Jana"));
    }

    @Test
    void deletingAPagePodcastRemovesOnlyItsStoreAndTellsTheObservers() {
        // ADR 0047: lokales Artefakt löschen; erfundene Ablage zweier Seiten-Podcasts.
        PodcastStore store = new PodcastStore(root.resolve("podcasts"));
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T22:00:00Z"), ZoneOffset.UTC);
        PagePodcasts podcasts = new PagePodcasts(new Jobs(new InMemoryJobStore(), Runnable::run,
                new JobLimits(1, 5, Duration.ofHours(1)), clock), StepModel.NONE, new PodcastSpeech() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public List<String> models() {
                return List.of();
            }

            @Override
            public List<byte[]> speak(String model, String voice, String text, boolean sentences) throws IOException {
                throw new IOException("nicht gebraucht");
            }

            @Override
            public ReadAloud readAloud(String model, String voice, ReadAloud.Playback playback) {
                return null;
            }
        }, store, PodcastVoices.bundled(), new PagePodcasts.Pages() {
            @Override
            public Optional<PagePodcasts.Page> page(AccessContext who, String sourceId, String pageId) {
                return Optional.empty();
            }

            @Override
            public List<String> children(AccessContext who, String sourceId, String pageId) {
                return List.of();
            }

            @Override
            public List<String> ancestors(AccessContext who, String sourceId, String pageId) {
                return List.of();
            }
        }, () -> 2);
        AccessContext caller = AccessContext.technicalClient("app");
        String gone = PagePodcasts.key(caller, "wiki", "1");
        String kept = PagePodcasts.key(caller, "wiki", "2");
        store.saveMeta(new PodcastStore.Meta(gone, "wiki", null, "DEMO", "1", "a", List.of("1"), null, null, null));
        store.saveMeta(new PodcastStore.Meta(kept, "wiki", null, "DEMO", "2", "b", List.of("2"), null, null, null));
        java.util.concurrent.atomic.AtomicInteger notified = new java.util.concurrent.atomic.AtomicInteger();
        podcasts.subscribe(gone, notified::incrementAndGet);

        assertThat(podcasts.deletable(gone)).isTrue();
        assertThat(podcasts.delete(gone)).isTrue();

        assertThat(store.meta(gone)).isEmpty();
        assertThat(store.meta(kept)).as("nur dieser eine").isPresent();
        assertThat(notified.get()).isEqualTo(1);
        assertThat(podcasts.delete(gone)).isFalse();
    }

    @Test
    void aBookIsToldByItsHeadingsAndKeyedByFolder() {
        String markdown = "# Nachtlauf\n\nWozu es ihn gibt.\n\n## Ablauf\n\nErst Prüfung.\n\n### Schritte\n\nDrei.\n\n"
                + "## Störungen\n\nBereitschaft anrufen.\n";
        PagePodcasts.Book book = new PagePodcasts.Book("ordner-1", "Erfundenes Buch", markdown);

        PageText text = book.text();
        assertThat(text.sections()).extracting(PageText.Section::heading)
                .containsExactly("Nachtlauf", "Ablauf", "Schritte", "Störungen");
        assertThat(text.sections().get(1).end()).isEqualTo(markdown.indexOf("## Störungen"));
        PodcastWriter.Material material = book.material(PagePodcasts.Reading.DEFAULT);
        assertThat(material.overview()).startsWith("Das Buch „Erfundenes Buch“");
        // die einzige Titelüberschrift ist die Einleitung, erzählt wird nach den Kapiteln darunter
        assertThat(material.stories()).extracting(PodcastWriter.Story::title)
                .containsExactly("Einleitung", "Ablauf", "Störungen");
        assertThat(material.stories().get(0).places()).containsExactly("im Buch „Erfundenes Buch“");

        List<PagePodcasts.Span> spans = new java.util.ArrayList<>();
        for (PagePodcasts.Part part : PagePodcasts.parts(book.page(), PagePodcasts.Reading.DEFAULT,
                PagePodcasts.Kind.BOOK)) {
            spans.add(new PagePodcasts.Span(part.start(), part.end()));
        }
        // Die Teile decken das ganze Buch lückenlos ab, damit die Ansicht es in diesen Stücken zeigen kann.
        assertThat(spans.get(0).start()).isZero();
        assertThat(spans.get(spans.size() - 1).end()).isEqualTo(markdown.length());
        for (int i = 1; i < spans.size(); i++) {
            assertThat(spans.get(i).start()).isEqualTo(spans.get(i - 1).end());
        }

        AccessContext caller = AccessContext.technicalClient("app");
        assertThat(PagePodcasts.bookKey(caller, "ordner-1")).isNotEqualTo(PagePodcasts.bookKey(caller, "ordner-2"))
                .isNotEqualTo(PagePodcasts.key(caller, "wiki", "ordner-1"));
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

        PodcastWriter.Material material = PagePodcasts.material(root, List.of(child), List.of("Handbuch", "Betrieb"), READING);

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
    void eachPartKnowsWhereItStandsInThePageTextForTheMarking() {
        String text = "Wozu es den Nachtlauf gibt.\nAblauf\nErst Prüfung, dann Start.\nStörungen\nBereitschaft anrufen.";
        int ablauf = text.indexOf("Ablauf");
        int stoerungen = text.indexOf("Störungen");
        PagePodcasts.Page root = page("1", "Nachtlauf Nordlicht", text,
                new PageText.Section("Ablauf", 2, ablauf, stoerungen),
                new PageText.Section("Störungen", 2, stoerungen, text.length()));

        List<PagePodcasts.Part> parts = PagePodcasts.parts(root, READING);

        assertThat(parts).extracting(PagePodcasts.Part::title).containsExactly("Einleitung", "Ablauf", "Störungen");
        assertThat(parts).extracting(part -> text.substring(part.start(), part.end()).strip()).containsExactly(
                "Wozu es den Nachtlauf gibt.", "Ablauf\nErst Prüfung, dann Start.", "Störungen\nBereitschaft anrufen.");
        // Meldung n des Materials ist Teil n−1: dieselbe Reihenfolge, dieselben Titel
        assertThat(PagePodcasts.material(root, List.of(), List.of(), READING).stories()).extracting(PodcastWriter.Story::title)
                .containsExactly("Einleitung", "Ablauf", "Störungen");
    }

    @Test
    void theCoverageLeavesOutPartsNoTopicTellsAndTheEndThatWasCutBeforeTheModel() {
        String lang = "Zeile über den Ablauf des Nachtlaufs Nordlicht. ".repeat(100).strip();
        String text = "Wozu es den Nachtlauf gibt.\nAblauf\n" + lang + "\nHistorie\nAngelegt von Erika Beispiel.";
        int ablauf = text.indexOf("Ablauf");
        int historie = text.indexOf("Historie");
        PagePodcasts.Page root = page("1", "Nachtlauf Nordlicht", text,
                new PageText.Section("Ablauf", 2, ablauf, historie),
                new PageText.Section("Historie", 2, historie, text.length()));
        // Einstieg ohne eigene Teile (zeigt den Anfang), dann nur der Ablauf; die Historie ließ das Modell weg
        PodcastPlan plan = new PodcastPlan("Nordlicht", List.of(
                new PodcastPlan.Topic("Einstieg", "", 0.5, List.of(), List.of()),
                new PodcastPlan.Topic("Ablauf", "", 0.5, List.of(), List.of(2))), List.of());

        List<PagePodcasts.Span> covered = PagePodcasts.coverage(root, plan, READING);

        assertThat(covered).hasSize(2);
        assertThat(covered.get(0)).isEqualTo(new PagePodcasts.Span(0, ablauf));
        // Vom langen Ablauf ging nur der Anfang an das Modell: die Abdeckung endet dort, wo der gekürzte Text endet
        PagePodcasts.Span told = covered.get(1);
        assertThat(told.start()).isEqualTo(ablauf);
        assertThat(told.end()).isLessThan(historie).isGreaterThan(ablauf + READING.sectionChars() / 2);
        String sent = PagePodcasts.material(root, List.of(), List.of(), READING).stories().get(1).text();
        assertThat(sent).endsWith("…");
        assertThat(text.substring(told.start(), told.end())).isEqualTo(sent.substring(0, sent.length() - 1));
        // Gelb endet an derselben Grenze: das Laufende verdeckt nie das Grau dahinter
        assertThat(PagePodcasts.read(text, PagePodcasts.parts(root, READING).get(1),
                READING.sectionChars())).isEqualTo(told);
    }

    @Test
    void howMuchThePodcastReadsFollowsTheSettingsNotAFixedLimit() {
        String text = "Wozu es den Nachtlauf gibt.\nAblauf\nErst Prüfung, dann Start.\nStörungen\nBereitschaft anrufen.";
        int ablauf = text.indexOf("Ablauf");
        int stoerungen = text.indexOf("Störungen");
        PagePodcasts.Page root = page("1", "Nachtlauf Nordlicht", text,
                new PageText.Section("Ablauf", 2, ablauf, stoerungen),
                new PageText.Section("Störungen", 2, stoerungen, text.length()));
        // ein Hauptabschnitt als eigener Teil (der Rest hängt daran), davor die Einleitung; je Teil 12 Zeichen
        PagePodcasts.Reading small = new PagePodcasts.Reading(12, 1);

        assertThat(PagePodcasts.parts(root, small)).extracting(PagePodcasts.Part::title)
                .containsExactly("Einleitung", "Ablauf");
        PodcastWriter.Material material = PagePodcasts.material(root, List.of(), List.of(), small);
        assertThat(material.stories().get(1).text()).hasSizeLessThanOrEqualTo(12).endsWith("…");
        // dieselbe Menge gilt auch beim Planen und Schreiben, kein zweites festes Kürzen dahinter
        assertThat(material.storyChars()).isEqualTo(12);
        assertThat(PagePodcasts.material(root, List.of(), List.of(), new PagePodcasts.Reading(9000, 1)).storyChars())
                .isEqualTo(9000);
        PodcastPlan plan = new PodcastPlan("Nordlicht", List.of(
                new PodcastPlan.Topic("Ablauf", "", 0.5, List.of(), List.of(2))), List.of());
        PagePodcasts.Span told = PagePodcasts.coverage(root, plan, small).get(0);
        assertThat(told.start()).isEqualTo(ablauf);
        assertThat(told.end()).isLessThan(stoerungen);
        assertThat(PagePodcasts.Reading.of(com.aresstack.spaceharvester.ai.AiServiceSettings.Speech.AUTOMATIC
                .withPodcastReading(4500, 14))).isEqualTo(new PagePodcasts.Reading(4500, 14));
    }

    @Test
    void aPartThatWentWhollyToTheModelIsWhollyCovered() {
        String body = "Ablauf\nErst Prüfung, dann Start.";
        assertThat(PagePodcasts.reached(body, body)).isEqualTo(body.length());
        // nicht zu findende Zeile: lieber den ganzen Teil als abgedeckt zeigen als falsch grau
        assertThat(PagePodcasts.reached(body, "ganz anders…")).isEqualTo(body.length());
        assertThat(PagePodcasts.reached(body, "Ablauf\nErst…")).isEqualTo("Ablauf\nErst".length());
    }

    @Test
    void aPageWithoutHeadingsKnowsWhereEachPieceStands() {
        String paragraph = "Ein Absatz über die Testumgebung. ".repeat(60).strip();
        String text = paragraph + "\n" + paragraph + "\n" + paragraph;

        List<PagePodcasts.Part> parts = PagePodcasts.parts(page("1", "Testumgebung", text), READING);

        assertThat(parts).hasSize(3);
        assertThat(parts).allSatisfy(part -> assertThat(text.substring(part.start(), part.end())).isEqualTo(paragraph));
    }

    @Test
    void leadingSubsectionsBeforeTheFirstMainHeadingBelongToTheIntroduction() {
        String text = "Vorab\nWichtiger Hinweis zum Start.\nAblauf\nErst prüfen.";
        int vorab = text.indexOf("Vorab");
        int ablauf = text.indexOf("Ablauf");
        PodcastWriter.Material material = PagePodcasts.material(page("1", "Nordlicht", text,
                new PageText.Section("Vorab", 3, vorab, ablauf),
                new PageText.Section("Ablauf", 2, ablauf, text.length())), List.of(), List.of(), READING);

        assertThat(material.stories()).extracting(PodcastWriter.Story::title).containsExactly("Einleitung", "Ablauf");
        assertThat(material.stories().get(0).text()).contains("Wichtiger Hinweis");
    }

    @Test
    void aPageWithoutHeadingsIsSplitAtParagraphs() {
        String paragraph = "Ein Absatz über die Testumgebung. ".repeat(60);
        PodcastWriter.Material material = PagePodcasts.material(page("1", "Testumgebung",
                paragraph + "\n" + paragraph + "\n" + paragraph), List.of(), List.of(), READING);

        assertThat(material.stories()).hasSizeGreaterThan(1);
        assertThat(material.stories().get(0).title()).isEqualTo("Testumgebung (1)");
    }

    @Test
    void theInputChangesWithTheContent() {
        PodcastWriter.Material one = PagePodcasts.material(page("1", "Start", "Alt."), List.of(), List.of(), READING);
        PodcastWriter.Material two = PagePodcasts.material(page("1", "Start", "Neu."), List.of(), List.of(), READING);

        assertThat(PagePodcasts.input(one)).isNotEqualTo(PagePodcasts.input(two));
        assertThat(PagePodcasts.input(one)).isEqualTo(PagePodcasts.input(
                PagePodcasts.material(page("1", "Start", "Alt."), List.of(), List.of(), READING)));
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
