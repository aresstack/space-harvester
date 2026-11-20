package com.aresstack.spaceharvester.application.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Korrektur, Auszüge, Dateinamen und Ablage von „In PPTX umwandeln“. */
class VideoPresentationsTest {

    private static final List<TranscriptSections.Section> CHUNK = TranscriptSections.parse(
            "[00:00:00 – 00:00:28] Heute geht es um Kuberneds.\n\n[00:00:28 – 00:00:57] Und um Helm Tschards.\n\n");

    @Test
    void aCorrectionWithTheSameSectionsKeepsTheOriginalTimes() {
        List<TranscriptSections.Section> corrected = VideoPresentations.acceptCorrection(CHUNK,
                "[00:00:00 – 00:00:28] Heute geht es um Kubernetes.\n\n[00:00:28 – 00:00:57] Und um Helm-Charts.");
        assertThat(corrected).extracting(TranscriptSections.Section::text)
                .containsExactly("Heute geht es um Kubernetes.", "Und um Helm-Charts.");
        assertThat(corrected.get(1).endMillis()).isEqualTo(57_000);
    }

    @Test
    void aCorrectionThatLosesOrMovesSectionsIsRejected() {
        assertThat(VideoPresentations.acceptCorrection(CHUNK, "[00:00:00 – 00:00:57] Alles in einem.")).isNull();
        assertThat(VideoPresentations.acceptCorrection(CHUNK,
                "[00:00:00] eins\n\n[00:00:40] zwei")).isNull();
        assertThat(VideoPresentations.acceptCorrection(CHUNK, "  ")).isNull();
        assertThat(VideoPresentations.acceptCorrection(CHUNK, "Heute geht es um Kubernetes.")).isNull();
    }

    @Test
    void aSingleSectionMayComeBackWithoutItsTime() {
        List<TranscriptSections.Section> one = CHUNK.subList(0, 1);
        assertThat(VideoPresentations.acceptCorrection(one, "Heute geht es um Kubernetes."))
                .singleElement().satisfies(section -> {
                    assertThat(section.text()).isEqualTo("Heute geht es um Kubernetes.");
                    assertThat(section.endMillis()).isEqualTo(28_000);
                });
    }

    @Test
    void theCorrectionSeesOnlySlidesShownDuringItsChunk() {
        Map<Long, String> descriptions = new java.util.LinkedHashMap<>();
        descriptions.put(0L, "Titelfolie „Kubernetes im Betrieb“.");
        descriptions.put(40_000L, "Folie „Helm-Charts“.");
        descriptions.put(120_000L, "Folie „Fragen“.");
        String input = VideoPresentations.correctionInput(CHUNK, descriptions, List.copyOf(descriptions.keySet()));
        assertThat(input).contains("[00:00:00] Titelfolie", "[00:00:40] Folie „Helm-Charts“")
                .doesNotContain("Fragen").contains("Transkript:\n[00:00:00 – 00:00:28] Heute geht es um Kuberneds.");
    }

    @Test
    void theExcerptOfASlideIsTheSpeechDuringItsTime() {
        assertThat(VideoPresentations.excerpt(CHUNK, 30_000, Long.MAX_VALUE))
                .isEqualTo("[00:00:28 – 00:00:57] Und um Helm Tschards.");
        assertThat(VideoPresentations.excerpt(CHUNK, 60_000, 90_000)).isEmpty();
    }

    @Test
    void fileNamesFollowTheVideoTitle() {
        assertThat(VideoPresentations.fileName("Vortrag Kubernetes.mp4")).isEqualTo("Vortrag Kubernetes.pptx");
        assertThat(VideoPresentations.fileName("a/b:c")).isEqualTo("a_b_c.pptx");
        assertThat(VideoPresentations.fileName(null)).isEqualTo("Video.pptx");
        assertThat(VideoPresentations.fileName("Vortrag Kubernetes.mp4", VideoPresentations.Format.PDF))
                .isEqualTo("Vortrag Kubernetes.pdf");
        assertThat(VideoPresentations.Format.parse("pdf")).isEqualTo(VideoPresentations.Format.PDF);
        assertThat(VideoPresentations.Format.parse(null)).as("Aufträge ohne Angabe sind PPTX")
                .isEqualTo(VideoPresentations.Format.PPTX);
        assertThat(VideoPresentations.Options.DEFAULT.format()).isEqualTo(VideoPresentations.Format.PPTX);
        assertThat(VideoPresentations.Options.DEFAULT.withFormat(VideoPresentations.Format.PDF).parallel())
                .isEqualTo(VideoPresentations.Options.DEFAULT.parallel());
        assertThat(VideoPresentations.transcriptName("Vortrag.mp4")).isEqualTo("Vortrag – Transkript.txt");
    }

    @Test
    void theStoreKeepsCorrectionAndNotesPerVersion() {
        InMemoryEnrichmentStore enrichments = new InMemoryEnrichmentStore();
        PresentationStore store = new PresentationStore(enrichments, Clock.systemUTC());
        PageKey page = new PageKey("bscw", "4711");
        store.saveCorrected(page, "v1", new PresentationStore.Result("korrigiert", "m", "sha:1"));
        store.saveNote(page, "v1", 3_000, new PresentationStore.Result("Vortragstext", "v", "sha:2"));
        store.saveNote(page, "v1", 1_000, new PresentationStore.Result("Begrüßung", "v", "sha:3"));
        assertThat(store.corrected(page, "v1")).map(PresentationStore.Result::text).contains("korrigiert");
        assertThat(store.notes(page, "v1")).containsOnlyKeys(1_000L, 3_000L);
        assertThat(store.notes(page, "v2")).isEmpty();
        assertThat(store.has(page)).isTrue();
        store.removeNote(page, "v1", 3_000);
        assertThat(store.notes(page, "v1")).containsOnlyKeys(1_000L);
        assertThat(store.corrected(page, "v1")).isPresent();
        store.remove(page);
        assertThat(store.corrected(page, "v1")).isEmpty();
    }

    @Test
    void anExistingTranscriptIsOnlyExtendedNeverReplacedByAShorterOne() {
        String existing = "[00:00:00 – 00:00:30] Willkommen.\n\n[00:00:30 – 00:01:00] Erste Folie.\n\n";
        assertThat(VideoPresentations.extendsTranscript(existing + "[00:01:00 – 00:01:20] Zweite.\n\n", existing))
                .isTrue();
        assertThat(VideoPresentations.extendsTranscript("[00:00:00 – 00:00:30] Neu erkannt.\n\n", existing))
                .isFalse();
        assertThat(VideoPresentations.extendsTranscript("[00:00:00 – 00:00:30] Willkommen.\n\n", existing))
                .isFalse();
        assertThat(VideoPresentations.extendsTranscript("[00:00:00 – 00:00:30] Erstes.\n\n", null)).isTrue();
        assertThat(VideoPresentations.extendsTranscript(" ", null)).isFalse();
    }

    @Test
    void aSlideWithoutDescriptionSaysWhy() {
        assertThat(VideoPresentations.slideReason("model-busy")).contains("belegt");
        assertThat(VideoPresentations.slideReason("not-ready")).contains("Standbild");
        assertThat(VideoPresentations.slideReason("model-failed")).contains("Vision-Modell");
        assertThat(VideoPresentations.slideReason("model-timeout")).contains("Frist", "ai.timeout");
        assertThat(VideoPresentations.slideReason("model-empty")).contains("leer");
        assertThat(VideoPresentations.slideReason(null)).isEqualTo("unbekannter Grund");
    }

    @Test
    void aFailedModelCallSaysTheRealReasonWithAttemptsInsteadOfEmpty() {
        JobContext context = context();
        AtomicInteger calls = new AtomicInteger();

        VideoPresentations.Outcome<String> outcome = VideoPresentations.call(2, context, () -> true, () -> {
            calls.incrementAndGet();
            throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE,
                    "Vision-Modell: Antwort am Limit abgeschnitten (3593 Token Prompt + 4599 Token Antwort)");
        });

        assertThat(calls).hasValue(3);
        assertThat(outcome.value()).isNull();
        assertThat(outcome.reason()).startsWith("unbrauchbare Antwort (Vision-Modell: Antwort am Limit abgeschnitten")
                .contains("3 Versuche").doesNotContain("nicht oder leer");
    }

    @Test
    void aModelCallThatSucceedsOnALaterAttemptKeepsItsAnswer() {
        AtomicInteger calls = new AtomicInteger();

        VideoPresentations.Outcome<String> outcome = VideoPresentations.call(2, context(), () -> true,
                () -> calls.incrementAndGet() == 1 ? VideoPresentations.Outcome.failed("leer")
                        : VideoPresentations.Outcome.of("Vortragstext"));

        assertThat(outcome.value()).isEqualTo("Vortragstext");
        assertThat(calls).hasValue(2);
    }

    @Test
    void noModelCallWithoutReleaseAndTheReasonSaysSo() {
        AtomicInteger calls = new AtomicInteger();

        VideoPresentations.Outcome<String> outcome = VideoPresentations.call(2, context(), () -> false,
                () -> VideoPresentations.Outcome.of(String.valueOf(calls.incrementAndGet())));

        assertThat(calls).hasValue(0);
        assertThat(outcome.reason()).contains("nicht für den KI-Dienst freigegeben");
    }

    @Test
    void failureReasonsNameTimeoutUnreachableAndWrappedCauses() {
        assertThat(VideoPresentations.callFailure(new AiServiceException(AiServiceException.Reason.TIMEOUT,
                "Chat-Dienst: TRANSPORT"))).startsWith("keine Antwort innerhalb der Frist");
        assertThat(VideoPresentations.callFailure(new ImageDescriptionException(
                ImageDescriptionException.Reason.FAILED, "Beschreibung fehlgeschlagen",
                new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Chat-Dienst: PROVIDER_ERROR (HTTP 500)"))))
                .isEqualTo("der KI-Dienst war nicht erreichbar (Chat-Dienst: PROVIDER_ERROR (HTTP 500))");
        assertThat(VideoPresentations.callFailure(new IllegalStateException("geheimer Text")))
                .isEqualTo("Fehler IllegalStateException");
    }

    @Test
    void theReasonOfASlideNoteNamesTheStillImage() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(1920, 1080,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", png);

        assertThat(VideoPresentations.imageFacts(png.toByteArray())).startsWith("Standbild 1920×1080, ");
        assertThat(VideoPresentations.imageFacts(new byte[0])).isEqualTo("Standbild leer");
        assertThat(VideoPresentations.imageFacts(new byte[] {1, 2, 3})).isEqualTo("Standbild kein PNG, 3 Bytes");
    }

    @Test
    void everyPhaseRunsAsManyModelCallsAtOnceAsItsOptionsSay() {
        // Angelo 20.11.2026: „Warum verwendet er nur eine Verbindung zum KI-Server?“ – vier zugleich, auch für die
        // Bildbeschreibung, die früher bei zwei abwies.
        int parallel = VideoPresentations.Options.DEFAULT.parallel();
        assertThat(parallel).isEqualTo(4);
        assertThat(com.aresstack.spaceharvester.application.assets.Assets.MAX_CONCURRENT_DESCRIPTIONS)
                .isGreaterThanOrEqualTo(parallel);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        java.util.concurrent.CountDownLatch together = new java.util.concurrent.CountDownLatch(parallel);

        VideoPresentations.inParallel(List.of(1, 2, 3, 4, 5, 6, 7, 8), parallel, context(), item -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            together.countDown();
            try {
                together.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
        });

        assertThat(most).hasValue(parallel);
    }

    private static JobContext context() {
        return org.mockito.Mockito.mock(JobContext.class);
    }

    @Test
    void aJobFromBeforeARestartRunsAgainInTheSameEntry() {
        // Angelo 20.11.2026: „Warum kann ich den Job nach einem Neustart der App nicht fortsetzen oder Fehlerhaftes
        // wiederholen lassen?“ – die Arbeit entsteht aus den Angaben des Auftrags neu.
        String owner = "TECHNICAL_CLIENT:space-harvester-app";
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = org.mockito.Mockito.mock(
                com.aresstack.spaceharvester.application.jobs.Jobs.class);
        for (com.aresstack.spaceharvester.application.jobs.JobType type : List.of(
                com.aresstack.spaceharvester.application.jobs.JobType.VIDEO_TRANSCRIPT,
                com.aresstack.spaceharvester.application.jobs.JobType.VIDEO_SLIDES,
                com.aresstack.spaceharvester.application.jobs.JobType.TRANSCRIPT_CORRECTION,
                com.aresstack.spaceharvester.application.jobs.JobType.SLIDE_NOTES,
                com.aresstack.spaceharvester.application.jobs.JobType.PRESENTATION_EXPORT)) {
            String id = "job-" + type;
            com.aresstack.spaceharvester.application.jobs.JobStatus status = status(id, type, owner,
                    Map.of(VideoPresentations.PAGE_PARAMETER, "42", VideoPresentations.FILE_PARAMETER, "/tmp/v.pdf",
                            VideoPresentations.FORMAT_PARAMETER, "pdf"));
            org.mockito.Mockito.when(jobs.resumable(id, owner)).thenReturn(false);
            org.mockito.Mockito.when(jobs.status(id, owner)).thenReturn(status);
            org.mockito.Mockito.when(jobs.rerun(org.mockito.ArgumentMatchers.eq(id),
                    org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.any())).thenReturn(status);
        }
        VideoPresentations presentations = presentations(jobs);

        for (com.aresstack.spaceharvester.application.jobs.JobType type : List.of(
                com.aresstack.spaceharvester.application.jobs.JobType.VIDEO_TRANSCRIPT,
                com.aresstack.spaceharvester.application.jobs.JobType.VIDEO_SLIDES,
                com.aresstack.spaceharvester.application.jobs.JobType.TRANSCRIPT_CORRECTION,
                com.aresstack.spaceharvester.application.jobs.JobType.SLIDE_NOTES,
                com.aresstack.spaceharvester.application.jobs.JobType.PRESENTATION_EXPORT)) {
            presentations.resume("job-" + type, owner);
            org.mockito.Mockito.verify(jobs).rerun(org.mockito.ArgumentMatchers.eq("job-" + type),
                    org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.notNull());
        }
    }

    @Test
    void aJobOfAnEndUserOrWithoutItsPageCannotBeRebuiltAndSaysWhy() {
        assertThat(VideoPresentations.caller("TECHNICAL_CLIENT:space-harvester-app"))
                .hasValueSatisfying(caller -> assertThat(caller.jobOwner())
                        .isEqualTo("TECHNICAL_CLIENT:space-harvester-app"));
        assertThat(VideoPresentations.caller("END_USER:anna@space-harvester-app")).isEmpty();
        assertThat(VideoPresentations.caller("TECHNICAL_CLIENT:")).isEmpty();
        assertThat(VideoPresentations.caller(null)).isEmpty();

        com.aresstack.spaceharvester.application.jobs.Jobs jobs = org.mockito.Mockito.mock(
                com.aresstack.spaceharvester.application.jobs.Jobs.class);
        VideoPresentations presentations = presentations(jobs);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> presentations.rebuild(status("a",
                        com.aresstack.spaceharvester.application.jobs.JobType.SLIDE_NOTES, "END_USER:anna@app",
                        Map.of(VideoPresentations.PAGE_PARAMETER, "42"))))
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobNotResumableException.class)
                .hasMessageContaining("Anmeldung");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> presentations.rebuild(status("b",
                        com.aresstack.spaceharvester.application.jobs.JobType.PRESENTATION_EXPORT,
                        "TECHNICAL_CLIENT:app", Map.of(VideoPresentations.PAGE_PARAMETER, "42"))))
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobNotResumableException.class)
                .hasMessageContaining("Datei");
    }

    private static com.aresstack.spaceharvester.application.jobs.JobStatus status(String id,
            com.aresstack.spaceharvester.application.jobs.JobType type, String owner, Map<String, String> parameters) {
        com.aresstack.spaceharvester.application.jobs.JobStatus status = org.mockito.Mockito.mock(
                com.aresstack.spaceharvester.application.jobs.JobStatus.class);
        org.mockito.Mockito.when(status.id()).thenReturn(id);
        org.mockito.Mockito.when(status.type()).thenReturn(type);
        org.mockito.Mockito.when(status.sourceId()).thenReturn("wiki");
        org.mockito.Mockito.when(status.owner()).thenReturn(owner);
        org.mockito.Mockito.when(status.parameter(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> parameters.get(call.<String>getArgument(0)));
        return status;
    }

    private static VideoPresentations presentations(com.aresstack.spaceharvester.application.jobs.Jobs jobs) {
        return new VideoPresentations(jobs, (who, sourceId, pageId) -> null,
                org.mockito.Mockito.mock(com.aresstack.spaceharvester.application.assets.Assets.class),
                org.mockito.Mockito.mock(com.aresstack.spaceharvester.application.images.PageImageAnalysis.class),
                org.mockito.Mockito.mock(com.aresstack.spaceharvester.application.marks.VideoMarks.class),
                com.aresstack.spaceharvester.application.stepmodel.StepModel.NONE,
                new PresentationStore(new InMemoryEnrichmentStore(), Clock.systemUTC()), key -> true,
                java.nio.file.Path.of("out"));
    }
}
