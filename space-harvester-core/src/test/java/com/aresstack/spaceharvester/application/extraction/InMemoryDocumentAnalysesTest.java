package com.aresstack.spaceharvester.application.extraction;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aresstack.spaceharvester.application.extraction.ExtractionOrigin.AI;
import static com.aresstack.spaceharvester.application.extraction.ExtractionOrigin.REGEX;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.CONFLICT;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.EXPLICIT;
import static com.aresstack.spaceharvester.application.extraction.FieldStatus.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDocumentAnalysesTest {

    private static final DocumentRef DOC = new DocumentRef("eakte", "/fsc/webdav/akte/erlass.msg", "\"e1\"");

    private final InMemoryDocumentAnalyses store = new InMemoryDocumentAnalyses();

    @Test
    void unknownDocumentIsNotAnalyzed() {
        assertThat(store.result(DOC).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
        assertThat(store.result(DOC).sections()).isEmpty();
    }

    @Test
    void resultsBelongToOneVersionOnly() {
        store.record(completed(DOC, field("Kontotyp", "Einzelvorschuss", EXPLICIT)));

        DocumentRef newer = new DocumentRef("eakte", DOC.id(), "\"e2\"");
        assertThat(store.result(newer).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
        assertThat(store.result(DOC).fieldCount()).isEqualTo(1);
    }

    @Test
    void differentValueKeepsBothAsConflict() {
        store.record(completed(DOC, field("Fristende", "31.12.2026", EXPLICIT)));
        DocumentAnalysis merged = store.record(completed(DOC, ExtractedField.of("Fristende", "30.06.2027", EXPLICIT, AI)));

        List<ExtractedField> fields = merged.sections().get(0).fields();
        assertThat(fields).extracting(ExtractedField::value).containsExactly("31.12.2026", "30.06.2027");
        assertThat(fields).extracting(ExtractedField::status).containsOnly(CONFLICT);
    }

    @Test
    void sameValueIsNotDuplicated() {
        store.record(completed(DOC, field("Geschäftszeichen", "GZD-H 1234", EXPLICIT)));
        DocumentAnalysis merged = store.record(completed(DOC, field("Geschäftszeichen", "GZD-H 1234", EXPLICIT)));

        assertThat(merged.fieldCount()).isEqualTo(1);
        assertThat(merged.count(EXPLICIT)).isEqualTo(1);
    }

    @Test
    void notFoundYieldsToFoundValueAndNeverReplacesOne() {
        store.record(completed(DOC, field("Aussteller", "", NOT_FOUND)));
        store.record(completed(DOC, field("Aussteller", "Referat 4", EXPLICIT)));
        DocumentAnalysis merged = store.record(completed(DOC, field("Aussteller", "", NOT_FOUND)));

        assertThat(merged.sections().get(0).fields()).extracting(ExtractedField::value).containsExactly("Referat 4");
    }

    @Test
    void notesKeepRequestedAndGrantedExtensionsApart() {
        DocumentAnalysis merged = store.record(completed(DOC,
                field("Fristverlängerung", "bis 31.03.2027", EXPLICIT).withNote("beantragt"),
                field("Fristverlängerung", "bis 31.01.2027", EXPLICIT).withNote("genehmigt")));
        merged = store.record(new DocumentAnalysis(DOC, AnalysisState.COMPLETED, merged.sections(), "", false));

        assertThat(merged.sections().get(0).fields()).extracting(ExtractedField::status).containsOnly(EXPLICIT);
        assertThat(merged.fieldCount()).isEqualTo(2);
    }

    @Test
    void mattersAreNeverMerged() {
        DocumentAnalysis merged = store.record(DocumentAnalysis.completed(DOC, List.of(
                new AnalysisSection("Konto 1", "Haushalt", List.of(field("Objektkonto", "4711", EXPLICIT))),
                new AnalysisSection("Konto 2", "Haushalt", List.of(field("Objektkonto", "0815", EXPLICIT))))));
        merged = store.record(merged);

        assertThat(merged.sections()).extracting(AnalysisSection::heading)
                .containsExactly("Konto 1 – Haushalt", "Konto 2 – Haushalt");
        assertThat(merged.count(CONFLICT)).isZero();
    }

    @Test
    void groupsKeepStatementsApartAndNotesAndProcessorsAccumulate() {
        DocumentAnalysis first = new DocumentAnalysis(DOC, AnalysisState.COMPLETED, List.of(new AnalysisSection("Fristen",
                List.of(field("Fristbeginn", "Zahlung", EXPLICIT).inGroup("frist-1")))), "", false,
                List.of(new AnalysisNote(AnalysisNote.Code.AI_UNAVAILABLE, "KI nicht erreichbar")), List.of("regex/1"));
        store.record(first);
        DocumentAnalysis merged = store.record(new DocumentAnalysis(DOC, AnalysisState.COMPLETED,
                List.of(new AnalysisSection("Fristen", List.of(field("Fristbeginn", "Monatsende", EXPLICIT)
                        .inGroup("frist-2")))), "", false, List.of(), List.of("regex/1", "ai/gemma4:12b/prompt-1")));

        assertThat(merged.count(CONFLICT)).isZero();
        assertThat(merged.fieldCount()).isEqualTo(2);
        assertThat(merged.notes()).extracting(AnalysisNote::code).containsExactly(AnalysisNote.Code.AI_UNAVAILABLE);
        assertThat(merged.processors()).containsExactly("regex/1", "ai/gemma4:12b/prompt-1");
    }

    @Test
    void forgetRemovesTheResult() {
        store.record(completed(DOC, field("Dokumentart", "Erlass", EXPLICIT)));
        store.forget(DOC);
        assertThat(store.result(DOC).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
    }

    @Test
    void noneProviderCannotAnalyze() throws Exception {
        assertThat(DocumentAnalysisProvider.NONE.canAnalyze(DOC)).isFalse();
        assertThat(DocumentAnalysisProvider.NONE.result(DOC).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
    }

    @Test
    void sourceReferenceIsOptional() {
        assertThat(SourceReference.NONE.isEmpty()).isTrue();
        assertThat(new SourceReference("", 2, 0, "").isEmpty()).isFalse();
        assertThat(new SourceReference("", 0, 0, 5, 2, "").hasRange()).isFalse();
    }

    private static ExtractedField field(String key, String value, FieldStatus status) {
        return ExtractedField.of(key, value, status, REGEX);
    }

    private static DocumentAnalysis completed(DocumentRef doc, ExtractedField... fields) {
        return DocumentAnalysis.completed(doc, List.of(new AnalysisSection("Dokumentinformationen", List.of(fields))));
    }
}
