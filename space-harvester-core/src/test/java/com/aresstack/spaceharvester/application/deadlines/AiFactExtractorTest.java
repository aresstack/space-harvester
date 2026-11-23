package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.ModelRetry;
import com.aresstack.spaceharvester.application.extraction.AnalysisNote;
import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.ExtractionOrigin;
import com.aresstack.spaceharvester.application.extraction.Qualifier;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** KI-Stufe und Validierung mit einem Modell-Fake; der Erlass ist erfunden. */
class AiFactExtractorTest {

    private static final DocumentRef DOC = new DocumentRef("test", "erlass-2", "v1");
    private static final String TEXT = """
            Geschäftszeichen: Z 9 - H 4711/26/10001
            1. Verwahrungsbuch
            Bewirtschafternummer: 0314 0421
            Die Beträge sind spätestens bis zum 31. März 2028 auszugleichen.
            Eine Verlängerung wurde beantragt.
            2. Vorschussbuch
            Bewirtschafternummer: 0314 0422
            Die Verlängerung bis zum 30.06.2028 wird genehmigt.
            """;
    private static final List<DocumentPart> PARTS = List.of(
            new DocumentPart("sachverhalt-1", "1. Verwahrungsbuch", TEXT.indexOf("1. Verwahrungsbuch"),
                    TEXT.indexOf("2. Vorschussbuch")),
            new DocumentPart("sachverhalt-2", "2. Vorschussbuch", TEXT.indexOf("2. Vorschussbuch"), TEXT.length()));

    private long backoff;

    @BeforeEach
    void fastRetries() {
        backoff = ModelRetry.defaultBackoffMillis;
        ModelRetry.defaultBackoffMillis = 0;
    }

    @AfterEach
    void restore() {
        ModelRetry.defaultBackoffMillis = backoff;
    }

    @Test
    void factsLandInTheirMatterAndAreValidatedAgainstTheText() {
        FakeModel model = new FakeModel(
                """
                {"facts":[
                 {"key":"konto.buch","value":"verwahrung","quote":"1. Verwahrungsbuch","qualifier":"none","group":null},
                 {"key":"konto.bewirtschafternummer","value":"0314 0421","quote":"Bewirtschafternummer: 0314 0421"},
                 {"key":"frist.ende","value":"31. März 2028","quote":"spätestens bis zum 31. März 2028","group":1},
                 {"key":"frist.verlaengerung","value":"Verlängerung","quote":"Eine Verlängerung wurde beantragt.",
                  "qualifier":"granted","group":2},
                 {"key":"dokument.geschaeftszeichen","value":"Z 9 - H 4711/26/10001",
                  "quote":"Geschäftszeichen: Z 9 - H 4711/26/10001"},
                 {"key":"frist.aussage","value":"erfunden","quote":"Die Frist beträgt zehn Jahre."},
                 {"key":"unbekannt","value":"x","quote":"y"}
                ]}""",
                """
                Hier ist das Ergebnis: {"facts":[
                 {"key":"frist.verlaengerung","value":"bis 30.06.2028","quote":"Die Verlängerung bis zum 30.06.2028 wird genehmigt.",
                  "qualifier":"granted","group":1},
                 {"key":"frist.ende","value":"30.06.2029","quote":"bis zum 30.06.2028","group":1}
                ]}""");
        AiFactExtractor ai = new AiFactExtractor(model, ModelRetry.Listener.LOG_ONLY, 12_000, input -> PARTS);

        DocumentAnalysis raw = ai.extract(input(), DocumentAnalysis.notAnalyzed(DOC));
        assertThat(model.inputs).hasSize(2);
        assertThat(model.inputs.get(0)).contains("1. Verwahrungsbuch").doesNotContain("2. Vorschussbuch");
        assertThat(raw.processors()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(AiFactExtractor.ID);
            assertThat(p.version()).isEqualTo("fake-1/" + AiFactExtractor.PROMPT_VERSION);
        });
        assertThat(raw.sections()).flatExtracting(AnalysisSection::fields)
                .allMatch(f -> f.origin() == ExtractionOrigin.AI && !f.verified())
                .noneMatch(f -> f.key().equals("unbekannt"));

        DocumentAnalysis checked = new ExtractionValidator(input -> PARTS).extract(input(), raw);

        AnalysisSection first = section(checked, "sachverhalt-1", SECTION_ACCOUNT);
        ExtractedField manager = field(first, MANAGER_NUMBER);
        assertThat(manager.value()).isEqualTo("03140421");
        assertThat(manager.verified()).isTrue();
        assertThat(TEXT.substring(manager.source().start(), manager.source().end()))
                .isEqualTo("Bewirtschafternummer: 0314 0421");

        AnalysisSection firstDeadlines = section(checked, "sachverhalt-1", SECTION_DEADLINES);
        ExtractedField end = field(firstDeadlines, DEADLINE_END);
        assertThat(end.value()).isEqualTo("2028-03-31");
        assertThat(end.verified()).isTrue();
        assertThat(end.group()).isEqualTo("frist-ki-1");
        ExtractedField onlyRequested = field(firstDeadlines, DEADLINE_EXTENSION);
        assertThat(onlyRequested.verified()).as("Antrag ist nie Genehmigung").isFalse();
        assertThat(onlyRequested.note()).contains("nur einen Antrag");
        assertThat(firstDeadlines.fields()).noneMatch(f -> f.value().equals("erfunden"));

        assertThat(field(section(checked, "", SECTION_DOCUMENT), FILE_REFERENCE).verified()).isTrue();

        AnalysisSection secondDeadlines = section(checked, "sachverhalt-2", SECTION_DEADLINES);
        assertThat(field(secondDeadlines, DEADLINE_EXTENSION).verified()).isTrue();
        assertThat(field(secondDeadlines, DEADLINE_EXTENSION).qualifier()).isEqualTo(Qualifier.GRANTED);
        ExtractedField wrongDate = field(secondDeadlines, DEADLINE_END);
        assertThat(wrongDate.verified()).isFalse();
        assertThat(wrongDate.note()).contains("Wert steht so nicht im Zitat");

        assertThat(checked.notes()).extracting(AnalysisNote::text).contains("Eine KI-Angabe ohne Beleg im Text wurde "
                + "verworfen.");
        assertThat(checked.processors()).contains(ExtractionValidator.PROCESSOR);
    }

    @Test
    void anUnreadableAnswerIsRepairedOnce() {
        FakeModel model = new FakeModel("Ich kann das nicht als JSON.", """
                {"facts":[{"key":"konto.buch","value":"verwahrung","quote":"1. Verwahrungsbuch"}]}""");
        AiFactExtractor ai = new AiFactExtractor(model, null, 12_000, input -> List.of(PARTS.get(0)));

        DocumentAnalysis result = ai.extract(input(), DocumentAnalysis.notAnalyzed(DOC));

        assertThat(model.inputs).hasSize(2);
        assertThat(model.inputs.get(1)).contains("Deine vorige Antwort").contains("Ich kann das nicht als JSON.");
        assertThat(field(section(result, "sachverhalt-1", SECTION_ACCOUNT), BOOK).value()).isEqualTo("verwahrung");
        assertThat(result.state()).isEqualTo(AnalysisState.COMPLETED);
    }

    @Test
    void aFailedPartGivesAPartialResultWithItsReason() {
        FakeModel model = new FakeModel(new AiServiceException(AiServiceException.Reason.UNREACHABLE, "weg"),
                new AiServiceException(AiServiceException.Reason.UNREACHABLE, "weg"),
                new AiServiceException(AiServiceException.Reason.UNREACHABLE, "weg"),
                "{\"facts\":[{\"key\":\"konto.buch\",\"value\":\"vorschuss\",\"quote\":\"2. Vorschussbuch\"}]}");
        AiFactExtractor ai = new AiFactExtractor(model, null, 12_000, input -> PARTS);

        DocumentAnalysis result = ai.extract(input(), DocumentAnalysis.notAnalyzed(DOC));

        assertThat(model.calls).as("zwei Wiederholungen, dann der nächste Teil").isEqualTo(4);
        assertThat(result.state()).isEqualTo(AnalysisState.COMPLETED);
        assertThat(result.notes()).singleElement().satisfies(note -> {
            assertThat(note.code()).isEqualTo(AnalysisNote.Code.AI_PARTIAL);
            assertThat(note.text()).contains("1. Verwahrungsbuch").contains("UNREACHABLE");
        });
        assertThat(field(section(result, "sachverhalt-2", SECTION_ACCOUNT), BOOK).value()).isEqualTo("vorschuss");
    }

    @Test
    void cancellationIsNoPartialResult() {
        FakeModel model = new FakeModel(new CancellationException("abgebrochen"));
        AiFactExtractor ai = new AiFactExtractor(model, null, 12_000, input -> PARTS);

        assertThatThrownBy(() -> ai.extract(input(), DocumentAnalysis.notAnalyzed(DOC)))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void longPartsAreSplitWithoutLosingText() {
        String text = "Absatz eins.\n\n" + "wort ".repeat(400) + "\n\nAbsatz drei.";

        List<String> chunks = AiFactExtractor.chunks(text, 1000);

        assertThat(String.join("", chunks)).isEqualTo(text);
        assertThat(chunks).allMatch(chunk -> chunk.length() <= 1000).hasSizeGreaterThan(2);
    }

    @Test
    void theInstructionNamesEveryKeyAndTheRules() {
        assertThat(AiFactExtractor.INSTRUCTION).contains(DEADLINE_EXTENSION, BOOK, MANAGER_NUMBER, LEGAL_REFERENCE)
                .contains("Ein Antrag ist keine Genehmigung").contains("nie selbst ausrechnen");
        assertThat(AiFactExtractor.normalize(BOOK, " Verwahrung ")).isEqualTo("verwahrung");
        assertThat(AiFactExtractor.normalize(DEADLINE_END, "keine Angabe")).isEqualTo("keine Angabe");
    }

    private static AnalysisInput input() {
        return new AnalysisInput(DOC, "Erlass", "text/plain", TEXT, false);
    }

    private static AnalysisSection section(DocumentAnalysis result, String matterId, String title) {
        return result.sections().stream().filter(s -> s.matterId().equals(matterId) && s.title().equals(title))
                .findFirst().orElseThrow(() -> new AssertionError("kein Abschnitt " + matterId + "/" + title
                        + " in " + result.sections()));
    }

    private static ExtractedField field(AnalysisSection section, String key) {
        return section.fields().stream().filter(f -> f.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("kein Feld " + key + " in " + section.fields()));
    }

    /** Antwortet der Reihe nach; eine Ausnahme in der Liste wird geworfen. */
    private static final class FakeModel implements StepModel {

        final Deque<Object> answers;
        final List<String> inputs = new ArrayList<>();
        int calls;

        FakeModel(Object... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public String version() {
            return "fake-1";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String complete(String instructions, String input) {
            calls++;
            inputs.add(input);
            Object next = answers.isEmpty() ? "{\"facts\":[]}" : answers.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (String) next;
        }
    }
}
