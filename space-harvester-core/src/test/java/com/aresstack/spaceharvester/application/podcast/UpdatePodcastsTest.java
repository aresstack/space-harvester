package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.SummaryPage;
import com.aresstack.spaceharvester.application.changelog.SummaryStatement;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Erfundene Daten: Quelle „wiki“, Space „DEMO“, Aussagen über ein fiktives Projekt „Nordlicht“. */
class UpdatePodcastsTest {

    @TempDir
    Path root;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T18:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final AccessContext caller = AccessContext.technicalClient("app");

    @Test
    void plansWritesAndSpeaksEverySegmentWithTwoFixedVoices() throws Exception {
        FakeModel model = new FakeModel();
        FakeSpeech speech = new FakeSpeech(voice -> false);
        UpdatePodcasts podcasts = podcasts(model, speech);
        AtomicInteger notified = new AtomicInteger();
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        podcasts.subscribe(UpdatePodcasts.key(summary), notified::incrementAndGet);

        JobStatus status = podcasts.start(caller, summary);

        assertThat(jobs.status(status.id()).state()).isEqualTo(JobState.COMPLETED);
        Podcast podcast = podcasts.podcast(summary).orElseThrow();
        assertThat(podcast.title()).isEqualTo("Neues bei Nordlicht");
        assertThat(podcast.complete()).isTrue();
        // Einstieg und Abschluss je 1 Abschnitt, das mittlere Thema 2
        assertThat(podcast.parts()).extracting(Podcast.Part::topic).containsExactly(0, 1, 1, 2);
        assertThat(podcast.parts()).allSatisfy(part -> assertThat(Files.size(part.audio())).isGreaterThan(44));
        assertThat(speech.voices).containsOnly("Jana", "Thomas");
        assertThat(notified.get()).isGreaterThanOrEqualTo(1 + 1 + 3 + 4);
    }

    @Test
    void theTranscriptListsEveryTurnUnderItsTopicWithItsSpeaker() throws Exception {
        UpdatePodcasts podcasts = podcasts(new FakeModel(), new FakeSpeech(voice -> false));
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        assertThat(podcasts.transcript(summary)).as("ohne Podcast kein Transkript").isEmpty();

        podcasts.start(caller, summary);

        String transcript = podcasts.transcript(summary).orElseThrow();
        assertThat(transcript).startsWith("# Neues bei Nordlicht\n");
        // je Thema eine Überschrift, darunter nur Zeilen „Sprecher: Text“
        assertThat(transcript.lines().filter(line -> line.startsWith("## "))).hasSize(3);
        assertThat(transcript.lines().filter(line -> !line.isBlank() && !line.startsWith("#")))
                .isNotEmpty()
                .allMatch(line -> line.startsWith(PodcastProduction.HOST_LABEL + ": ")
                        || line.startsWith(PodcastProduction.COHOST_LABEL + ": "));
    }

    @Test
    void everyTopicGetsAPictureBesideTheAudio() throws Exception {
        UpdatePodcasts podcasts = podcasts(new FakeModel(), new FakeSpeech(voice -> false));
        AtomicInteger drawn = new AtomicInteger();
        podcasts.pictures(DiagramComposerTest.model(DiagramComposerTest.PLAN, DiagramComposerTest.ICON, drawn),
                PodcastPictures.PageImageSource.NONE);
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");

        assertThat(jobs.status(podcasts.start(caller, summary).id()).state()).isEqualTo(JobState.COMPLETED);

        Podcast podcast = podcasts.podcast(summary).orElseThrow();
        assertThat(podcast.parts()).allSatisfy(part -> {
            assertThat(part.picture()).isNotNull();
            assertThat(part.picture().vector()).isTrue();
            assertThat(part.picture().file()).exists();
        });
        // je Thema ein Plan und drei Symbole
        assertThat(drawn.get()).isEqualTo(3 * 4);
        // für den Bericht: je Thema mit Bild ein Eintrag in Themenreihenfolge
        List<UpdatePodcasts.TopicPicture> report = podcasts.topicPictures(summary);
        assertThat(report).hasSize(3);
        assertThat(report).allSatisfy(entry -> assertThat(entry.picture().file()).exists());
        assertThat(report).extracting(UpdatePodcasts.TopicPicture::topicTitle).doesNotContainNull();
        assertThat(report).as("jedes Bild kennt sein Kapitel").extracting(UpdatePodcasts.TopicPicture::topic)
                .containsExactly(0, 1, 2);
        int before = drawn.get();
        podcasts.start(caller, summary);
        assertThat(drawn.get()).as("ein neuer Lauf nimmt die abgelegten Bilder").isEqualTo(before);
    }

    @Test
    void aFinishedPodcastWithoutPicturesStaysIncompleteUntilTheyAreDrawn() throws Exception {
        FakeModel model = new FakeModel();
        FakeSpeech speech = new FakeSpeech(voice -> false);
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        // erzeugt, bevor es Bilder gab: das Audio ist vollständig
        podcasts(model, speech).start(caller, summary);
        UpdatePodcasts later = podcasts(model, speech);
        assertThat(later.podcast(summary).orElseThrow().complete()).isTrue();

        AtomicInteger drawn = new AtomicInteger();
        later.pictures(DiagramComposerTest.model(DiagramComposerTest.PLAN, DiagramComposerTest.ICON, drawn),
                PodcastPictures.PageImageSource.NONE);
        Podcast before = later.podcast(summary).orElseThrow();
        assertThat(before.picturesPending()).isTrue();
        assertThat(before.complete()).as("fehlende Bilder: die Wiedergabe setzt den Auftrag fort").isFalse();

        int spoken = speech.calls.get();
        assertThat(jobs.status(later.start(caller, summary).id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(speech.calls.get()).as("Audio bleibt").isEqualTo(spoken);
        assertThat(later.podcast(summary).orElseThrow().complete()).isTrue();
        assertThat(later.podcast(summary).orElseThrow().parts()).allSatisfy(part ->
                assertThat(part.picture()).isNotNull());
    }

    @Test
    void aSecondRunReusesEverythingOnDisk() throws Exception {
        FakeModel model = new FakeModel();
        FakeSpeech speech = new FakeSpeech(voice -> false);
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        podcasts(model, speech).start(caller, summary);
        int calls = model.calls.get();
        int spoken = speech.calls.get();

        // neue Instanz wie nach einem Neustart
        podcasts(model, speech).start(caller, summary);

        assertThat(model.calls.get()).isEqualTo(calls);
        assertThat(speech.calls.get()).isEqualTo(spoken);
    }

    @Test
    void continuesWhereAnInterruptedRunStopped() throws Exception {
        FakeModel model = new FakeModel();
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        // erster Lauf: Sprachausgabe fällt ganz aus, Plan und Texte liegen danach trotzdem auf der Platte
        FakeSpeech broken = new FakeSpeech(voice -> true);
        JobStatus first = podcasts(model, broken).start(caller, summary);
        assertThat(jobs.status(first.id()).incomplete()).isTrue();
        int calls = model.calls.get();

        FakeSpeech working = new FakeSpeech(voice -> false);
        podcasts(model, working).start(caller, summary);

        assertThat(model.calls.get()).as("kein neuer Plan, kein neuer Text").isEqualTo(calls);
        assertThat(podcasts(model, working).podcast(summary).orElseThrow().complete()).isTrue();
    }

    @Test
    void healsAFailingVoiceWithTheOtherOne() throws Exception {
        FakeSpeech speech = new FakeSpeech("Thomas"::equals);
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");

        JobStatus status = podcasts(new FakeModel(), speech).start(caller, summary);

        assertThat(podcasts(new FakeModel(), speech).podcast(summary).orElseThrow().complete()).isTrue();
        // der Ausweg steht in der Diagnose des Auftrags, einmal mit Anzahl
        assertThat(jobs.events(status.id())).extracting(JobEvent::message)
                .anySatisfy(message -> assertThat(message).contains("mit der anderen Stimme"));
    }

    @Test
    void pointsEachTopicToTheStatementsOfTheSummaryItTells() throws Exception {
        UpdatePodcasts podcasts = podcasts(new FakeModel(), new FakeSpeech(voice -> false));
        // die erste Aussage ohne Text zählt nicht als Meldung: Meldung 1 ist Aussage 1
        UpdateSummary summary = new UpdateSummary("wiki", "u-1", "DEMO", "Neue Startseite",
                List.of(new SummaryStatement(" ", List.of()),
                        new SummaryStatement("Das Projekt Nordlicht hat eine neue Startseite.", List.of())),
                "test", Instant.parse("2026-11-21T17:00:00Z"), 0, false);
        assertThat(podcasts.statements(summary, 1)).as("ohne Plan").isEmpty();

        podcasts.start(caller, summary);

        assertThat(podcasts.statements(summary, 0)).as("Einstieg").isEmpty();
        assertThat(podcasts.statements(summary, 1)).containsExactly(1);
        assertThat(podcasts.statements(summary, 7)).isEmpty();
    }

    @Test
    void speaksSeveralSegmentsAtOnceSoPlaybackNeverWaits() throws Exception {
        FakeSpeech speech = new FakeSpeech(voice -> false, 150);
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");

        podcasts(new FakeModel(), speech).start(caller, summary);

        // ai.parallel = 2: Sprachausgabe läuft nicht mehr Abschnitt für Abschnitt
        assertThat(speech.mostAtOnce.get()).isEqualTo(2);
        assertThat(podcasts(new FakeModel(), speech).podcast(summary).orElseThrow().complete()).isTrue();
    }

    @Test
    void aSpeechCallWithItsOwnWorkersCountsAgainstTheParallelBudget() throws Exception {
        FakeSpeech speech = new FakeSpeech(voice -> false, 100);
        speech.workers = 2;
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");

        podcasts(new FakeModel(), speech).start(caller, summary);

        // ai.parallel = 2, je Aufruf 2 Anfragen: nur ein Abschnitt zugleich
        assertThat(speech.mostAtOnce.get()).isEqualTo(1);
    }

    @Test
    void reportsSegmentsWithoutAudioInTheDiagnosis() throws Exception {
        FakeSpeech speech = new FakeSpeech(voice -> true);
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");

        JobStatus status = podcasts(new FakeModel(), speech).start(caller, summary);

        assertThat(jobs.events(status.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo("PODCAST_AUDIO_MISSING");
            assertThat(event.message()).startsWith("4 Abschnitt(e) ohne Audio").contains("1.1, 2.1, 2.2, 3.1");
        });
    }

    @Test
    void healsAModelThatNeverAnswersWithAPlanFromTheSummary() throws Exception {
        StepModel silent = new StepModel() {
            @Override
            public String version() {
                return "test";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                throw new IllegalStateException("Dienst nicht erreichbar");
            }
        };
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        UpdatePodcasts podcasts = podcasts(silent, new FakeSpeech(voice -> false));

        podcasts.start(caller, summary);

        Podcast podcast = podcasts.podcast(summary).orElseThrow();
        assertThat(podcast.complete()).isTrue();
        assertThat(podcast.parts()).extracting(Podcast.Part::topicTitle).first().isEqualTo("Einstieg");
    }

    @Test
    void aNewSummaryReplacesThePodcastOfTheSameLevel() throws Exception {
        FakeModel model = new FakeModel();
        FakeSpeech speech = new FakeSpeech(voice -> false);
        UpdatePodcasts podcasts = podcasts(model, speech);
        UpdateSummary old = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        podcasts.start(caller, old);

        UpdateSummary renewed = summary("Das Projekt Nordlicht hat eine neue Startseite und ein Glossar.");
        podcasts.start(caller, renewed);

        assertThat(UpdatePodcasts.key(renewed)).isNotEqualTo(UpdatePodcasts.key(old));
        assertThat(podcasts.podcast(old)).isEmpty();
        assertThat(podcasts.podcast(renewed)).isPresent();
    }

    @Test
    void prunesPodcastsWhoseSummaryIsGone() throws Exception {
        UpdatePodcasts podcasts = podcasts(new FakeModel(), new FakeSpeech(voice -> false));
        UpdateSummary summary = summary("Das Projekt Nordlicht hat eine neue Startseite.");
        podcasts.start(caller, summary);

        assertThat(podcasts.prune(meta -> false)).isEqualTo(1);

        assertThat(podcasts.podcast(summary)).isEmpty();
    }

    @Test
    void tellsEachTopicWithItsDetailsAndWhereItStands() {
        UpdateSummary summary = new UpdateSummary("wiki", "u-1", "DEMO", "Kurzer Überblick.", List.of(
                new SummaryStatement("Neue Startseite", List.of(new SummaryPage("p-1", "Start", "DEMO"),
                        new SummaryPage("p-2", "Glossar", "OPS")), "Startseite",
                        "Die Startseite zeigt jetzt die Ansprechstellen je Team und ein Glossar."),
                new SummaryStatement("Altes Format ohne Details. Noch ein Satz.", List.of())),
                "test", Instant.parse("2026-11-21T17:00:00Z"), 0, false);

        PodcastWriter.Material material = UpdatePodcasts.material(summary, page -> "auf der Seite „" + page.title()
                + "“");

        assertThat(material.stories()).extracting(PodcastWriter.Story::title)
                .containsExactly("Startseite", "Altes Format ohne Details");
        assertThat(material.stories().getFirst().text()).startsWith("Die Startseite zeigt");
        assertThat(material.stories().getFirst().places()).containsExactly("auf der Seite „Start“",
                "auf der Seite „Glossar“");
        assertThat(material.scope()).isEqualTo("Space DEMO");
    }

    @Test
    void theScriptOfATopicCarriesItsContentAndPlaceButNotTheOverview() throws Exception {
        FakeModel model = new FakeModel();
        UpdateSummary summary = new UpdateSummary("wiki", "u-1", "DEMO", "Hundert neue Seiten im Basissystem.",
                List.of(new SummaryStatement("Neue Startseite", List.of(new SummaryPage("p-1", "Start", "DEMO")),
                        "Startseite", "Die Startseite zeigt jetzt die Ansprechstellen je Team.")),
                "test", Instant.parse("2026-11-21T17:00:00Z"), 0, false);

        podcasts(model, new FakeSpeech(voice -> false)).start(caller, summary);

        List<String> scripts = model.inputs.stream().filter(input -> input.contains("Schreibe genau diese Beiträge")
                && input.contains("Thema 2 \"Neue Startseite\"")).toList();
        assertThat(scripts).isNotEmpty().allSatisfy(input -> assertThat(input)
                .contains("Ansprechstellen je Team", "auf der Seite „Start“, unter Handbuch › Betrieb")
                .doesNotContain("Hundert neue Seiten"));
    }

    private UpdatePodcasts podcasts(StepModel model, PodcastSpeech speech) {
        return new UpdatePodcasts(jobs, model, speech, new PodcastStore(root.resolve("podcasts")),
                PodcastVoices.bundled(), (who, source, page) -> List.of("Handbuch", "Betrieb"), () -> 2,
                (context, attempt) -> { });
    }

    private UpdateSummary summary(String text) {
        return new UpdateSummary("wiki", "u-1", "DEMO", text, List.of(new SummaryStatement(text, List.of())),
                "test", Instant.parse("2026-11-21T17:00:00Z"), 0, false);
    }

    /** Antwortet je Stufe mit festem JSON. */
    private static final class FakeModel implements StepModel {

        final AtomicInteger calls = new AtomicInteger();
        final List<String> inputs = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String version() {
            return "test";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String complete(String instructions, String input) {
            calls.incrementAndGet();
            inputs.add(input);
            if (input.contains("Plane eine Folge")) {
                return "Klar! {\"title\":\"Neues bei Nordlicht\",\"topics\":[{\"title\":\"Einstieg\",\"angle\":\"Worum "
                        + "es geht\",\"energy\":0.7,\"points\":[]},{\"title\":\"Neue Startseite\",\"angle\":\"Die "
                        + "Startseite wurde neu gebaut\",\"energy\":0.6,\"stories\":[1]},"
                        + "{\"title\":\"Abschluss\",\"energy\":0.3,\"points\":[]}]}";
            }
            if (input.contains("Plane Thema 2")) {
                return "{\"segments\":[{\"title\":\"Was neu ist\",\"beats\":[{\"speaker\":\"host\",\"intent\":\"stellt "
                        + "vor\"},{\"speaker\":\"cohost\",\"intent\":\"fragt nach\"}]},{\"title\":\"Was es bringt\","
                        + "\"beats\":[{\"speaker\":\"cohost\",\"intent\":\"ordnet ein\"}]}]}";
            }
            if (input.contains("Plane Thema")) {
                return "{\"segments\":[{\"title\":\"Kurz\",\"beats\":[{\"speaker\":\"host\",\"intent\":\"sagt was\"},"
                        + "{\"speaker\":\"cohost\",\"intent\":\"antwortet\"}]},{\"title\":\"zu viel\",\"beats\":"
                        + "[{\"speaker\":\"host\",\"intent\":\"x\"}]}]}";
            }
            return "{\"turns\":[{\"speaker\":\"host\",\"text\":\"Jana: Hallo, heute gibt es Neues.\"},"
                    + "{\"speaker\":\"cohost\",\"text\":\"Ach ja? Erzähl mal.\"}]}";
        }
    }

    /** Liefert kurze Stille als WAV; scheitert für Stimmen, die {@code failing} annimmt. */
    private static final class FakeSpeech implements PodcastSpeech {

        final List<String> voices = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger mostAtOnce = new AtomicInteger();
        private final AtomicInteger atOnce = new AtomicInteger();
        private final Predicate<String> failing;
        private final long millis;
        int workers = 1;

        FakeSpeech(Predicate<String> failing) {
            this(failing, 0);
        }

        /** @param millis so lange dauert jeder Aufruf */
        FakeSpeech(Predicate<String> failing, long millis) {
            this.failing = failing;
            this.millis = millis;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<String> models() {
            return List.of("orpheus-3b-german");
        }

        @Override
        public int workers() {
            return workers;
        }

        @Override
        public List<byte[]> speak(String model, String voice, String text, boolean sentences) throws IOException {
            calls.incrementAndGet();
            mostAtOnce.accumulateAndGet(atOnce.incrementAndGet(), Math::max);
            try {
                if (millis > 0) {
                    Thread.sleep(millis);
                }
                if (failing.test(voice)) {
                    throw new IOException("HTTP 503");
                }
                voices.add(voice);
                return List.of(silence());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("unterbrochen");
            } finally {
                atOnce.decrementAndGet();
            }
        }

        private static byte[] silence() {
            AudioFormat format = new AudioFormat(24000f, 16, 1, true, false);
            byte[] pcm = new byte[2400 * 2];
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, 2400),
                        AudioFileFormat.Type.WAVE, out);
                return out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
