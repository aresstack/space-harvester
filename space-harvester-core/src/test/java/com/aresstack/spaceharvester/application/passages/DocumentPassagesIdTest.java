package com.aresstack.spaceharvester.application.passages;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link DocumentPassages}: Capture = Hash des Lesetextes des Dokuments, Position über alle Abschnitte in Reihenfolge,
 * keine ID ohne gemeinsamen Stand; aufgelöst wird nur der Text des einen Abschnitts.
 */
class DocumentPassagesIdTest {

    private static final PageKey PAGE = new PageKey("wiki", "101");
    private static final DocumentKey DOCUMENT = DocumentKey.attachment(PAGE, "a1");
    private static final String REVISION = "sha256:" + "d".repeat(64);
    private static final String SECTION_0 = "sha256:" + "0".repeat(64);
    private static final String SECTION_1 = "sha256:" + "1".repeat(64);
    private static final String EXTRACTION = "extraktion-1|embedding-max-chars=0";

    private final List<Integer> sectionsRead = new ArrayList<>();

    @Test
    void positionsCountThePassagesOfAllSectionsBeforeInOrder() {
        List<Enrichment> results = List.of(passages(1, SECTION_1, REVISION), passages(0, SECTION_0, REVISION));

        String first = DocumentPassages.idAt(DOCUMENT, results, 0, SECTION_0, 0, 5);
        String third = DocumentPassages.idAt(DOCUMENT, results, 1, SECTION_1, 0, 5);
        String fourth = DocumentPassages.idAt(DOCUMENT, results, 1, SECTION_1, 13, 20);

        assertThat(PassageId.parse(first).position()).isZero();
        assertThat(PassageId.parse(third).position()).isEqualTo(2);
        assertThat(PassageId.parse(fourth).position()).isEqualTo(3);
        assertThat(PassageId.parse(fourth).capture()).isEqualTo("d".repeat(64)).hasSize(PassageId.CAPTURE_LENGTH);
        assertThat(PassageId.parse(fourth).document()).contains(DOCUMENT.fileName());
        assertThat(DocumentPassages.idAt(DOCUMENT, results, 1, SECTION_0, 0, 5)).as("anderer Abschnittstext").isNull();
        assertThat(DocumentPassages.idAt(DOCUMENT, results, 1, SECTION_1, 5, 15)).as("über zwei Absätze").isNull();
    }

    @Test
    void withoutACommonStateThereIsNoId() {
        List<Enrichment> unfinished = List.of(passages(0, SECTION_0, REVISION),
                passages(1, SECTION_1, "sha256:" + "e".repeat(64)));

        assertThat(DocumentPassages.idAt(DOCUMENT, unfinished, 0, SECTION_0, 0, 5)).isNull();
        assertThat(DocumentPassages.idAt(DOCUMENT, List.of(passages(0, SECTION_0, null)), 0, SECTION_0, 0, 5))
                .isNull();
    }

    @Test
    void resolvingAPositionReadsOnlyTheTextOfItsSection() {
        List<Enrichment> results = List.of(passages(0, SECTION_0, REVISION), passages(1, SECTION_1, REVISION));
        PassageId third = PassageId.parse(DocumentPassages.idAt(DOCUMENT, results, 1, SECTION_1, 0, 5));

        PassageLookup lookup = DocumentPassages.resolve(third, results, (document, section, inputSha256, extraction) -> {
            sectionsRead.add(section);
            return SECTION_1.equals(inputSha256)
                    ? Optional.of(new PageText("document-text/v1", "Erste Zeile.\nZweite Zeile.", List.of()))
                    : Optional.empty();
        }, 1);

        assertThat(lookup.match()).isEqualTo(PassageLookup.Match.FOUND);
        assertThat(sectionsRead).containsExactly(1);
        assertThat(lookup.passages()).extracting(PagePassages.Passage::index).containsExactly(2, 3);
        assertThat(lookup.passages()).extracting(PagePassages.Passage::text)
                .containsExactly("Erste Zeile.", "Zweite Zeile.");
        assertThat(lookup.passages().getFirst().id()).isEqualTo(third.value());
    }

    @Test
    void anotherSegmentationOfTheSameTextIsChanged() {
        List<Enrichment> results = List.of(passages(0, SECTION_0, REVISION));
        PassageId id = PassageId.parse(DocumentPassages.idAt(DOCUMENT, results, 0, SECTION_0, 0, 5));
        PassageId other = PassageId.of(id.sourceId(), id.pageId(), id.capture(), id.document().orElseThrow(),
                id.position(), "00000000", id.embedding());

        PassageLookup lookup = DocumentPassages.resolve(other, results, (document, section, sha, extraction) -> Optional.empty(),
                1);

        assertThat(lookup.match()).isEqualTo(PassageLookup.Match.CHANGED);
        assertThat(lookup.passages()).isEmpty();
    }

    @Test
    void sectionsBeforeAndAfterTheEmbeddingLimitShareOneId() {
        // Vor der Grenze semantisch, danach nach Struktur: verschiedene Modellkürzel, ein vollständiger Stand.
        List<Enrichment> results = List.of(passages(0, SECTION_0, REVISION, "passages@1;model=m"),
                passages(1, SECTION_1, REVISION, "passages@1"));

        String first = DocumentPassages.idAt(DOCUMENT, results, 0, SECTION_0, 0, 5);
        String third = DocumentPassages.idAt(DOCUMENT, results, 1, SECTION_1, 0, 5);

        assertThat(first).isNotNull();
        assertThat(PassageId.parse(third).embedding()).isEqualTo(PassageId.parse(first).embedding())
                .isNotEqualTo(PassageId.parse(DocumentPassages.idAt(DOCUMENT,
                        List.of(passages(0, SECTION_0, REVISION, "passages@1;model=m")), 0, SECTION_0, 0, 5))
                        .embedding());
        PassageLookup lookup = DocumentPassages.resolve(PassageId.parse(third), results,
                (document, section, inputSha256, extraction) -> Optional.of(new PageText("document-text/v1",
                        "Erste Zeile.\nZweite Zeile.", List.of())), 0);
        assertThat(lookup.match()).isEqualTo(PassageLookup.Match.FOUND);
    }

    @Test
    void aPassageIsOnlyFoundAgainstTheExtractionItsStateWasRecordedWith() {
        List<Enrichment> results = List.of(passages(0, SECTION_0, REVISION), passages(1, SECTION_1, REVISION));
        PassageId third = PassageId.parse(DocumentPassages.idAt(DOCUMENT, results, 1, SECTION_1, 0, 5));
        List<String> asked = new ArrayList<>();

        PassageLookup lookup = DocumentPassages.resolve(third, results, (document, section, inputSha256, extraction) -> {
            asked.add(extraction);
            // Die jetzige Extraktion ist eine andere: Der Abschnitt blieb gleich, ein anderer kann sich geändert haben.
            return Optional.empty();
        }, 1);
        assertThat(asked).containsExactly(EXTRACTION);
        assertThat(lookup.match()).isEqualTo(PassageLookup.Match.UNAVAILABLE);

        List<Enrichment> unrecorded = List.of(passages(0, SECTION_0, REVISION).withBasis(REVISION, null),
                passages(1, SECTION_1, REVISION).withBasis(REVISION, null));
        assertThat(DocumentPassages.resolve(third, unrecorded, (document, section, inputSha256, extraction) ->
                Optional.of(new PageText("document-text/v1", "Erste Zeile.\nZweite Zeile.", List.of())), 1).match())
                .as("ohne vermerkte Extraktion").isEqualTo(PassageLookup.Match.UNAVAILABLE);
    }

    private static Enrichment passages(int section, String inputSha256, String revision) {
        return passages(section, inputSha256, revision, "passages@1");
    }

    /** Zwei Absätze [0, 12) und [13, 26) eines Abschnitts. */
    private static Enrichment passages(int section, String inputSha256, String revision, String version) {
        String passage = ",\"firstSentence\":0,\"sentences\":1,\"section\":null,\"kind\":\"paragraph\","
                + "\"boundary\":\"structure\",\"similarity\":null}";
        return new Enrichment(PAGE, PassageProcessor.ID, version, inputSha256, revision, Enrichment.Status.OK,
                "{\"mode\":\"structure\",\"reason\":\"no-model\",\"model\":null,\"segmentation\":\"passages;eac\","
                        + "\"sentences\":2,\"textSha256\":\"x\",\"passages\":[{\"start\":0,\"end\":12" + passage
                        + ",{\"start\":13,\"end\":26" + passage + "]}", List.of(), null,
                Instant.parse("2026-11-13T07:00:00Z"), DOCUMENT, section).withBasis(revision, EXTRACTION);
    }
}
