package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.ExtractionOrigin;
import com.aresstack.spaceharvester.application.extraction.FieldStatus;
import com.aresstack.spaceharvester.application.extraction.Qualifier;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Regex-Stufe an einem erfundenen Erlass; alle Nummern, Namen und Daten sind ausgedacht. */
class IdentifierExtractorTest {

    private static final SentenceSegmenter SENTENCES = OpenNlpSentenceSegmenter.german(
            SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()));
    private static final DocumentRef DOC = new DocumentRef("test", "erlass-1", "v1");

    private static final String DECREE = """
            Bundesministerium für Beispielwesen
            Referat Z 9
            Geschäftszeichen: Z 9 - H 4711/26/10001 :001
            DOK 2026/0123456
            Musterstadt, 27. Juli 2026
            Betreff: Abwicklung von Vorschuss- und Verwahrungskonten
            Bezug: Erlass vom 12.03.2025 – Z 9 - H 4711/25/10003
            Bezug: Rundschreiben vom 02.01.2026

            1. Vorschussbuch, Abschnitt 1, Unterabschnitt 2
            Bewirtschafternummer: 0314 0420
            Objektkonto: 8817 2201
            Buchungsstelle: 0101 3711
            Die offenen Beträge sind jeweils für jede einzelne Zahlung spätestens bis zum 31.03.2027
            auszugleichen. Eine Verlängerung der Frist wurde beantragt.

            2. Verwahrungsbuch, Abschnitt 3
            Bewirtschafternummer: 03140421
            Objektkonto: 8817 2299
            Es handelt sich um durchlaufende Gelder.
            Die Verwahrung darf nicht über das Ende des zweiten folgenden Haushaltsjahres hinaus bestehen.
            Die Verlängerung wird hiermit genehmigt.
            """;

    private final IdentifierExtractor extractor = new IdentifierExtractor(SENTENCES);

    @Test
    void readsDocumentFactsOnlyFromTheHeader() {
        DocumentAnalysis result = extract(DECREE);

        AnalysisSection document = section(result, "", SECTION_DOCUMENT);
        assertThat(value(document, FILE_REFERENCE)).contains("Z 9 - H 4711/26/10001 :001");
        assertThat(value(document, DOK_ID)).contains("2026/0123456");
        assertThat(value(document, DOCUMENT_DATE)).contains("2026-07-27");
        assertThat(value(document, DIVISION)).contains("Z 9");
        assertThat(document.fields()).filteredOn(f -> f.key().equals(REFERENCED_DOCUMENT)).hasSize(2)
                .allMatch(f -> f.status() == FieldStatus.EXPLICIT)
                .extracting(ExtractedField::group).containsExactly("bezug-1", "bezug-2");
        assertThat(result.processors()).containsExactly(IdentifierExtractor.PROCESSOR);
    }

    @Test
    void separatesMattersAndNeverMixesTheirValues() {
        DocumentAnalysis result = extract(DECREE);

        AnalysisSection first = section(result, "sachverhalt-1", SECTION_ACCOUNT);
        AnalysisSection second = section(result, "sachverhalt-2", SECTION_ACCOUNT);
        assertThat(first.matter()).isEqualTo("1. Vorschussbuch, Abschnitt 1, Unterabschnitt 2");
        assertThat(value(first, BOOK)).contains(BOOK_ADVANCE);
        assertThat(value(first, MANAGER_NUMBER)).contains("03140420");
        assertThat(value(first, OBJECT_ACCOUNT)).contains("88172201");
        assertThat(value(first, BOOKING_POINT)).contains("01013711");
        assertThat(value(first, SECTION)).contains("1");
        assertThat(value(first, SUBSECTION)).contains("2");
        assertThat(value(first, ACCOUNT_CATEGORY)).isEmpty();

        assertThat(value(second, BOOK)).contains(BOOK_CUSTODY);
        assertThat(value(second, MANAGER_NUMBER)).contains("03140421");
        assertThat(value(second, SECTION)).contains("3");
        assertThat(value(second, BOOKING_POINT)).isEmpty();
        assertThat(value(second, ACCOUNT_CATEGORY)).contains(CATEGORY_PASS_THROUGH);

        ExtractedField manager = field(first, MANAGER_NUMBER).orElseThrow();
        assertThat(manager.rawValue()).isEqualTo("0314 0420");
        assertThat(DECREE.substring(manager.source().start(), manager.source().end())).isEqualTo("0314 0420");
        assertThat(manager.origin()).isEqualTo(ExtractionOrigin.REGEX);
        assertThat(manager.verified()).isTrue();
    }

    @Test
    void readsDeadlineStatementsWithModalityNegationAndQualifier() {
        DocumentAnalysis result = extract(DECREE);

        AnalysisSection first = section(result, "sachverhalt-1", SECTION_DEADLINES);
        ExtractedField statement = field(first, DEADLINE_STATEMENT).orElseThrow();
        assertThat(statement.group()).isEqualTo("frist-1");
        assertThat(statement.value()).startsWith("Die offenen Beträge").endsWith("auszugleichen.");
        assertThat(value(first, DEADLINE_END)).contains("2027-03-31");
        assertThat(value(first, DEADLINE_REFERENCE)).contains(REFERS_TO_PAYMENT);
        ExtractedField requested = field(first, DEADLINE_EXTENSION).orElseThrow();
        assertThat(requested.qualifier()).isEqualTo(Qualifier.REQUESTED);
        assertThat(requested.group()).isEqualTo("frist-2");

        AnalysisSection second = section(result, "sachverhalt-2", SECTION_DEADLINES);
        ExtractedField custody = field(second, DEADLINE_STATEMENT).orElseThrow();
        assertThat(custody.note()).contains("Modalverb duerfen").contains("verneint");
        assertThat(value(second, DEADLINE_END)).as("nie berechnet").isEmpty();
        assertThat(field(second, DEADLINE_EXTENSION).orElseThrow().qualifier()).isEqualTo(Qualifier.GRANTED);
    }

    @Test
    void contradictoryBooksInOneMatterStayVisible() {
        DocumentAnalysis result = extract("""
                Abschnitt 4
                Bewirtschafternummer: 1111
                Die Beträge werden im Vorschussbuch und im Verwahrungsbuch geführt.
                """);

        AnalysisSection matter = section(result, "sachverhalt-1", SECTION_ACCOUNT);
        assertThat(matter.fields()).filteredOn(f -> f.key().equals(BOOK)).hasSize(2)
                .allMatch(f -> f.status() == FieldStatus.CONFLICT);
    }

    @Test
    void negatedPassThroughAndUnclearExtensionAreNotRead() {
        DocumentAnalysis result = extract("""
                Vorschussbuch
                Objektkonto: 2222
                Es handelt sich nicht um durchlaufende Gelder.
                Die beantragte Verlängerung der Frist wurde genehmigt.
                """);

        assertThat(value(section(result, "sachverhalt-1", SECTION_ACCOUNT), ACCOUNT_CATEGORY)).isEmpty();
        ExtractedField extension = field(section(result, "sachverhalt-1", SECTION_DEADLINES), DEADLINE_EXTENSION)
                .orElseThrow();
        assertThat(extension.qualifier()).as("Antrag ist nie Genehmigung").isEqualTo(Qualifier.NONE);
        assertThat(extension.note()).contains("bitte prüfen");
    }

    @Test
    void aBindingExtensionCountsAsGrantedAPossibilityDoesNot() {
        DocumentAnalysis result = extract("""
                1. Vorschussbuch
                Objektkonto: 3333
                Die Frist wird bis zum 30.06.2028 verlängert.

                2. Verwahrungsbuch
                Objektkonto: 4444
                Eine Verlängerung der Frist ist möglich.
                """);

        assertThat(field(section(result, "sachverhalt-1", SECTION_DEADLINES), DEADLINE_EXTENSION).orElseThrow()
                .qualifier()).as("verbindlich ausgesprochen").isEqualTo(Qualifier.GRANTED);
        assertThat(field(section(result, "sachverhalt-2", SECTION_DEADLINES), DEADLINE_EXTENSION).orElseThrow()
                .qualifier()).as("nur möglich").isEqualTo(Qualifier.NONE);
    }

    @Test
    void secondAccountStartsANewMatterWithoutHeading() {
        DocumentAnalysis result = extract("""
                Betreff: Konten im Vorschussbuch
                Bewirtschafternummer: 1111
                Objektkonto: 1234
                Bewirtschafternummer: 2222
                Objektkonto: 5678
                """);

        assertThat(value(section(result, "", SECTION_DOCUMENT), SUBJECT)).contains("Konten im Vorschussbuch");
        assertThat(value(section(result, "sachverhalt-1", SECTION_ACCOUNT), OBJECT_ACCOUNT)).contains("1234");
        assertThat(value(section(result, "sachverhalt-2", SECTION_ACCOUNT), OBJECT_ACCOUNT)).contains("5678");
        assertThat(section(result, "sachverhalt-1", SECTION_ACCOUNT).matter()).isEqualTo("Sachverhalt 1");
    }

    @Test
    void sentencesWithoutRuleAreNoDeadlineStatements() {
        assertThat(IdentifierExtractor.isDeadlineSentence("Die Frist endete im letzten Jahr.")).isFalse();
        assertThat(IdentifierExtractor.isDeadlineSentence("Die Abrechnung ist bis zum 30.06.2027 vorzulegen."))
                .isTrue();
        assertThat(IdentifierExtractor.isDeadlineSentence("Das Konto muss innerhalb eines Monats ausgeglichen werden."))
                .isTrue();
    }

    @Test
    void truncatedTextIsNoted() {
        DocumentAnalysis result = extractor.extract(new AnalysisInput(DOC, "", "", "Abschnitt 1", true),
                DocumentAnalysis.notAnalyzed(DOC));

        assertThat(result.notes()).singleElement().satisfies(note -> assertThat(note.code().name())
                .isEqualTo("TEXT_TRUNCATED"));
    }

    private DocumentAnalysis extract(String text) {
        return extractor.extract(new AnalysisInput(DOC, "Erlass", "text/plain", text, false),
                DocumentAnalysis.notAnalyzed(DOC));
    }

    private static AnalysisSection section(DocumentAnalysis result, String matterId, String title) {
        return result.sections().stream().filter(s -> s.matterId().equals(matterId) && s.title().equals(title))
                .findFirst().orElseThrow(() -> new AssertionError("kein Abschnitt " + matterId + "/" + title
                        + " in " + result.sections()));
    }

    private static Optional<ExtractedField> field(AnalysisSection section, String key) {
        return section.fields().stream().filter(f -> f.key().equals(key)).findFirst();
    }

    private static Optional<String> value(AnalysisSection section, String key) {
        return field(section, key).map(ExtractedField::value);
    }
}
