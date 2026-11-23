package com.aresstack.spaceharvester.application.deadlines;

import com.aresstack.spaceharvester.ai.ModelRetry;
import com.aresstack.spaceharvester.application.assets.DocumentText;
import com.aresstack.spaceharvester.application.extraction.AnalysisNote;
import com.aresstack.spaceharvester.application.extraction.AnalysisState;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentContent;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.ExtractionOrigin;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.passages.OpenNlpSentenceSegmenter;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aresstack.spaceharvester.application.deadlines.DeadlineFieldKeys.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Der Analyseauftrag mit Fakes für Ablage, Text und Modell; der Erlass ist erfunden. */
class DeadlineAnalysisServiceTest {

    private static final SentenceSegmenter SENTENCES = OpenNlpSentenceSegmenter.german(
            SentenceSegmenter.splitter(KnowledgeTextSegmentation.defaults()));
    private static final DocumentRef DOC = new DocumentRef("eakte", "/akte/erlass-7.txt", "\"etag-1\"");
    private static final String DECREE = """
            Geschäftszeichen: Z 9 - H 4711/26/10007
            Betreff: Abwicklung erfundener Konten

            1. Verwahrungsbuch
            Bewirtschafternummer: 0314 0421
            Die Beträge sind spätestens bis zum 31. März 2028 auszugleichen.

            2. Vorschussbuch
            Bewirtschafternummer: 0314 0422
            Die Verlängerung bis zum 30.06.2028 wird genehmigt.
            """;

    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run,
            new JobLimits(2, 5, Duration.ofHours(1)), Clock.systemUTC());
    private final FakeModel model = new FakeModel();

    @Test
    void aReleasedDocumentRunsAllThreeStagesAsOneJob() {
        DeadlineAnalysisService service = service(true);
        List<DocumentRef> changes = new CopyOnWriteArrayList<>();
        service.observe(changes::add);
        service.content(content(DECREE));

        assertThat(service.canAnalyze(DOC)).isTrue();
        service.startAnalysis(DOC);

        DocumentAnalysis result = service.result(DOC);
        assertThat(result.state()).isEqualTo(AnalysisState.COMPLETED);
        assertThat(result.notes()).extracting(AnalysisNote::code).as("die verworfene Angabe wird gemeldet")
                .containsExactly(AnalysisNote.Code.AI_PARTIAL);
        assertThat(field(result, MANAGER_NUMBER, ExtractionOrigin.REGEX)).isPresent();
        ExtractedField granted = field(result, DEADLINE_EXTENSION, ExtractionOrigin.AI).orElseThrow();
        assertThat(granted.verified()).as("Zitat steht im Text").isTrue();
        assertThat(granted.source().start()).isEqualTo(DECREE.indexOf("Die Verlängerung"));
        assertThat(field(result, DEADLINE_STATEMENT, ExtractionOrigin.AI)).as("ohne Beleg verworfen").isEmpty();
        assertThat(model.calls.get()).as("Kopf und zwei Sachverhalte").isEqualTo(3);
        assertThat(changes).isNotEmpty().allMatch(DOC::equals);
        assertThat(jobs.listAll(JobType.DOCUMENT_ANALYSIS)).singleElement().satisfies(job -> {
            assertThat(job.state()).isEqualTo(JobState.COMPLETED);
            assertThat(job.sourceId()).isEqualTo("eakte");
            assertThat(job.parameter(DeadlineAnalysisService.DOCUMENT_PARAMETER)).isEqualTo(DOC.id());
        });
    }

    @Test
    void withoutReleaseNothingGoesToTheModel() {
        DeadlineAnalysisService service = service(false);
        service.content(content(DECREE));

        service.startAnalysis(DOC);

        DocumentAnalysis result = service.result(DOC);
        assertThat(model.calls.get()).isZero();
        assertThat(result.state()).isEqualTo(AnalysisState.COMPLETED);
        assertThat(field(result, MANAGER_NUMBER, ExtractionOrigin.REGEX)).isPresent();
        assertThat(result.notes()).extracting(AnalysisNote::code).containsExactly(AnalysisNote.Code.AI_NOT_RELEASED);
    }

    @Test
    void anUnreadableDocumentFailsWithItsReason() {
        DeadlineAnalysisService service = service(true);
        service.content(new DocumentContent() {
            @Override
            public boolean supports(DocumentRef document) {
                return true;
            }

            @Override
            public Content read(DocumentRef document) throws Exception {
                throw new java.io.IOException("ETag geändert");
            }
        });

        service.startAnalysis(DOC);

        assertThat(service.result(DOC).state()).isEqualTo(AnalysisState.FAILED);
        assertThat(service.result(DOC).failure()).contains("Dokument nicht lesbar").contains("ETag geändert");
        JobStatus job = jobs.listAll(JobType.DOCUMENT_ANALYSIS).get(0);
        assertThat(job.state()).isEqualTo(JobState.FAILED);
    }

    @Test
    void withoutAStoreNothingCanBeAnalyzed() {
        DeadlineAnalysisService service = service(true);

        assertThat(service.canAnalyze(DOC)).isFalse();
        assertThatThrownBy(() -> service.startAnalysis(DOC)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(service.result(DOC).state()).isEqualTo(AnalysisState.NOT_ANALYZED);
    }

    @Test
    void partsAreTheHeaderAndEachMatter() {
        List<DocumentPart> parts = IdentifierExtractor.parts(new AnalysisInput(DOC, "", "", DECREE, false));

        assertThat(parts).extracting(DocumentPart::matterId).containsExactly("", "sachverhalt-1", "sachverhalt-2");
        assertThat(parts.get(0).of(DECREE)).contains("Geschäftszeichen");
        assertThat(parts.get(2).of(DECREE)).startsWith("2. Vorschussbuch");
    }

    private DeadlineAnalysisService service(boolean released) {
        AiFactExtractor ai = new AiFactExtractor(model, ModelRetry.Listener.LOG_ONLY, 1000,
                IdentifierExtractor::parts, () -> 2);
        return new DeadlineAnalysisService(jobs, "tester", new PlainText(), new IdentifierExtractor(SENTENCES), ai,
                new ExtractionValidator(IdentifierExtractor::parts), (origin, id) -> released);
    }

    private static DocumentContent content(String text) {
        return new DocumentContent() {
            @Override
            public boolean supports(DocumentRef document) {
                return "eakte".equals(document.origin());
            }

            @Override
            public Content read(DocumentRef document) {
                return new Content("erlass-7.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static Optional<ExtractedField> field(DocumentAnalysis result, String key, ExtractionOrigin origin) {
        return result.sections().stream().flatMap(section -> section.fields().stream())
                .filter(field -> field.key().equals(key) && field.origin() == origin).findFirst();
    }

    /** Antwortet nach dem Inhalt des Teils, denn die Teile laufen parallel. */
    private static final class FakeModel implements StepModel {

        final AtomicInteger calls = new AtomicInteger();

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
            calls.incrementAndGet();
            if (input.contains("2. Vorschussbuch")) {
                return """
                        {"facts":[{"key":"frist.verlaengerung","value":"bis 30.06.2028",
                         "quote":"Die Verlängerung bis zum 30.06.2028 wird genehmigt.","qualifier":"granted","group":1}]}""";
            }
            if (input.contains("1. Verwahrungsbuch")) {
                return """
                        {"facts":[{"key":"frist.aussage","value":"erfunden","quote":"Die Frist beträgt zehn Jahre."}]}""";
            }
            return "{\"facts\":[]}";
        }
    }

    private static final class PlainText implements DocumentText {

        @Override
        public String id() {
            return "plain";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public Optional<String> detect(byte[] content) {
            return Optional.of("text/plain");
        }

        @Override
        public Extracted extract(String title, String mediaType, byte[] content) {
            return new Extracted(new String(content, StandardCharsets.UTF_8), false);
        }
    }
}
