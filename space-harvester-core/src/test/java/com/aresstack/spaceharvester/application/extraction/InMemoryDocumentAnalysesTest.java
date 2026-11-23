package com.aresstack.spaceharvester.application.extraction;

import com.aresstack.spaceharvester.application.observe.Subscription;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.aresstack.spaceharvester.application.extraction.ExtractionOrigin.AI;
import static com.aresstack.spaceharvester.application.extraction.ExtractionOrigin.REGEX;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.CONFLICT;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.EXPLICIT;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDocumentAnalysesTest {

    private static final DocumentRef DOC = new DocumentRef("eakte", "/fsc/webdav/akte/erlass.msg", "\"e1\"");
    private static final String WORDING = "Fristformulierung";

    private final InMemoryDocumentAnalyses store = new InMemoryDocumentAnalyses();

    @Test
    void unknownDocumentIsNotAnalyzed() {
        assertThat(store.result(DOC).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
        assertThat(store.result(DOC).sections()).isEmpty();
    }

    @Test
    void resultsBelongToOneVersionOnly() {
        store.record(completed(DOC, field("Kontotyp", "Einzelvorschuss")));

        DocumentRef newer = new DocumentRef("eakte", DOC.id(), "\"e2\"");
        assertThat(store.result(newer).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
        assertThat(store.result(DOC).fieldCount()).isEqualTo(1);
    }

    @Test
    void differentValueKeepsBothAsConflict() {
        store.record(completed(DOC, field(WORDING, "„bis zum 31.12.2026 abzurechnen“")));
        DocumentAnalysis merged = store.record(completed(DOC,
                ExtractedField.of(WORDING, "„spätestens zum 30.06.2027 abzurechnen“", EXPLICIT, AI)));

        List<ExtractedField> fields = merged.sections().get(0).fields();
        assertThat(fields).extracting(ExtractedField::value)
                .containsExactly("„bis zum 31.12.2026 abzurechnen“", "„spätestens zum 30.06.2027 abzurechnen“");
        assertThat(fields).extracting(ExtractedField::status).containsOnly(CONFLICT);
    }

    @Test
    void sameValueIsNotDuplicated() {
        store.record(completed(DOC, field("Geschäftszeichen", "GZD-H 1234")));
        DocumentAnalysis merged = store.record(completed(DOC, field("Geschäftszeichen", "GZD-H 1234")));

        assertThat(merged.fieldCount()).isEqualTo(1);
        assertThat(merged.count(EXPLICIT)).isEqualTo(1);
    }

    @Test
    void notFoundYieldsToFoundValueAndNeverReplacesOne() {
        store.record(completed(DOC, ExtractedField.of("Aussteller", "", NOT_FOUND, REGEX)));
        store.record(completed(DOC, field("Aussteller", "Referat 4")));
        DocumentAnalysis merged = store.record(completed(DOC, ExtractedField.of("Aussteller", "", NOT_FOUND, REGEX)));

        assertThat(merged.sections().get(0).fields()).extracting(ExtractedField::value).containsExactly("Referat 4");
    }

    @Test
    void qualifierKeepsRequestedAndGrantedExtensionsApart() {
        DocumentAnalysis merged = store.record(completed(DOC,
                field("Fristverlängerung", "bis 31.03.2027").qualified(Qualifier.REQUESTED),
                field("Fristverlängerung", "bis 31.01.2027").qualified(Qualifier.GRANTED)));
        merged = store.record(merged);

        assertThat(merged.sections().get(0).fields()).extracting(ExtractedField::status).containsOnly(EXPLICIT);
        assertThat(merged.fieldCount()).isEqualTo(2);
    }

    @Test
    void mattersAreKeyedByIdNeverByName() {
        DocumentAnalysis merged = store.record(DocumentAnalysis.completed(DOC, List.of(
                new AnalysisSection("bwn-0001/ok-4711", "Konto 1", "Haushalt", List.of(field("Objektkonto", "4711"))),
                new AnalysisSection("bwn-0002/ok-0815", "Konto 1", "Haushalt", List.of(field("Objektkonto", "0815"))))));
        merged = store.record(merged);

        assertThat(merged.sections()).extracting(AnalysisSection::matterId)
                .containsExactly("bwn-0001/ok-4711", "bwn-0002/ok-0815");
        assertThat(merged.count(CONFLICT)).isZero();
    }

    @Test
    void groupsKeepStatementsApartAndNotesAccumulate() {
        store.record(new DocumentAnalysis(DOC, AnalysisState.COMPLETED, List.of(new AnalysisSection("Fristen",
                List.of(field("Fristbeginn", "Zahlung").inGroup("frist-1")))), "", false,
                List.of(new AnalysisNote(AnalysisNote.Code.AI_UNAVAILABLE, "KI nicht erreichbar")), List.of()));
        DocumentAnalysis merged = store.record(completed(DOC, field("Fristbeginn", "Monatsende").inGroup("frist-2")));

        assertThat(merged.count(CONFLICT)).isZero();
        assertThat(merged.fieldCount()).isEqualTo(2);
        assertThat(merged.notes()).extracting(AnalysisNote::code).containsExactly(AnalysisNote.Code.AI_UNAVAILABLE);
    }

    @Test
    void newerProcessorVersionReplacesItsOldFields() {
        Processor ai1 = new Processor("ai", "gemma4:12b/prompt-1");
        Processor ai2 = new Processor("ai", "gemma4:12b/prompt-2");
        Processor regex = new Processor("regex", "1");
        store.record(run(List.of(ai1, regex), field(WORDING, "„alt“").from(ai1),
                field("Geschäftszeichen", "GZD-H 1").from(regex)));

        DocumentAnalysis merged = store.record(run(List.of(ai2), field(WORDING, "„neu“").from(ai2)));

        assertThat(merged.sections().get(0).fields()).extracting(ExtractedField::value)
                .containsExactly("GZD-H 1", "„neu“");
        assertThat(merged.count(CONFLICT)).isZero();
        assertThat(merged.processors()).containsExactly(regex, ai2);
    }

    @Test
    void sameProcessorVersionStillMergesWithConflict() {
        Processor ai1 = new Processor("ai", "1");
        store.record(run(List.of(ai1), field(WORDING, "„a“").from(ai1)));
        DocumentAnalysis merged = store.record(run(List.of(ai1), field(WORDING, "„b“").from(ai1)));

        assertThat(merged.count(CONFLICT)).isEqualTo(2);
    }

    @Test
    void observersHearEveryChangeUntilClosed() {
        List<DocumentRef> heard = new ArrayList<>();
        Subscription subscription = store.observe(heard::add);
        store.record(completed(DOC, field("Dokumentart", "Erlass")));
        store.forget(DOC);
        subscription.close();
        store.record(completed(DOC, field("Dokumentart", "Erlass")));

        assertThat(heard).containsExactly(DOC, DOC);
        assertThat(store.result(DOC).state()).isEqualTo(AnalysisState.COMPLETED);
    }

    @Test
    void noneProviderCannotAnalyze() throws Exception {
        assertThat(DocumentAnalysisProvider.NONE.canAnalyze(DOC)).isFalse();
        assertThat(DocumentAnalysisProvider.NONE.result(DOC).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
        assertThat(DocumentAnalysisProvider.NONE.observe(ref -> { })).isSameAs(Subscription.NONE);
    }

    @Test
    void sourceReferenceIsOptional() {
        assertThat(SourceReference.NONE.isEmpty()).isTrue();
        assertThat(new SourceReference("", 2, 0, "").isEmpty()).isFalse();
        assertThat(new SourceReference("", 0, 0, 5, 2, "").hasRange()).isFalse();
    }

    private static ExtractedField field(String key, String value) {
        return ExtractedField.of(key, value, EXPLICIT, REGEX);
    }

    private static DocumentAnalysis completed(DocumentRef doc, ExtractedField... fields) {
        return DocumentAnalysis.completed(doc, List.of(new AnalysisSection("Dokumentinformationen", List.of(fields))));
    }

    private static DocumentAnalysis run(List<Processor> processors, ExtractedField... fields) {
        return new DocumentAnalysis(DOC, AnalysisState.COMPLETED,
                List.of(new AnalysisSection("Dokumentinformationen", List.of(fields))), "", false, List.of(), processors);
    }
}
