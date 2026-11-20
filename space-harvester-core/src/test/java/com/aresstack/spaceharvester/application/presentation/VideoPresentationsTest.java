package com.aresstack.spaceharvester.application.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
}
