package com.aresstack.spaceharvester.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.application.extraction.AnalysisSection;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysis;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
import com.aresstack.spaceharvester.application.extraction.ExtractedField;
import com.aresstack.spaceharvester.application.extraction.ExtractionOrigin;
import com.aresstack.spaceharvester.application.extraction.FieldStatus;
import com.aresstack.spaceharvester.application.extraction.SourceReference;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DeadlineChatToolsTest {

    private static final DocumentRef DOCUMENT = new DocumentRef("eakte", "/akte/bescheid.pdf", "v1");

    @Test
    void theChatStartsTheDeadlineAnalysisOfTheOpenDocumentAndGetsTheTable() throws Exception {
        FakeAnalyses analyses = new FakeAnalyses();
        DeadlineChatTools tools = new DeadlineChatTools(() -> DOCUMENT, analyses);

        assertThat(tools.definitions()).extracting(AiChat.Tool::name).containsExactly(DeadlineChatTools.DEADLINES);
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> tools.run(call("{}")));
        analyses.started.get(5, TimeUnit.SECONDS);
        assertThat(answer).isNotDone();
        analyses.finish(DocumentAnalysis.completed(DOCUMENT, List.of(new AnalysisSection("Fristeninformationen",
                List.of(ExtractedField.of("Frist", "31.12.2026", FieldStatus.EXPLICIT, ExtractionOrigin.AI)
                        .withSource(new SourceReference("Abschnitt 3", 2, 4, "bis zum 31.12.2026")))))));

        assertThat(answer.get(5, TimeUnit.SECONDS))
                .contains("Fristen-Analyse", "Fristeninformationen:", "- Frist: 31.12.2026 [ausdrücklich · "
                        + "Abschnitt 3 · S. 2 · Abs. 4]");
        assertThat(analyses.starts).hasValue(1);
    }

    @Test
    void aFinishedResultIsReadWithoutANewRunUnlessAskedForAgain() throws Exception {
        FakeAnalyses analyses = new FakeAnalyses();
        analyses.results.put(DOCUMENT, DocumentAnalysis.completed(DOCUMENT, List.of()));
        DeadlineChatTools tools = new DeadlineChatTools(() -> DOCUMENT, analyses);

        assertThat(tools.run(call("{}"))).contains("keine Informationen gefunden");
        assertThat(analyses.starts).hasValue(0);

        CompletableFuture<String> again = CompletableFuture.supplyAsync(() -> tools.run(call("{\"neu\":true}")));
        analyses.started.get(5, TimeUnit.SECONDS);
        analyses.finish(DocumentAnalysis.failed(DOCUMENT, "kein Text"));
        assertThat(again.get(5, TimeUnit.SECONDS)).contains("Analyse fehlgeschlagen: kein Text");
        assertThat(analyses.starts).hasValue(1);
    }

    @Test
    void stopEndsTheWaitingCall() throws Exception {
        FakeAnalyses analyses = new FakeAnalyses();
        DeadlineChatTools tools = new DeadlineChatTools(() -> DOCUMENT, analyses);

        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> tools.run(call("{}")));
        analyses.started.get(5, TimeUnit.SECONDS);
        tools.cancel();

        assertThat(answer.get(5, TimeUnit.SECONDS)).isEqualTo("Abgebrochen.");
    }

    @Test
    void withoutOpenDocumentOrAnalysisTheChatIsToldSo() {
        assertThat(new DeadlineChatTools(() -> null, new FakeAnalyses()).run(call("{}")))
                .isEqualTo(DeadlineChatTools.NO_DOCUMENT);
        assertThat(new DeadlineChatTools(() -> DOCUMENT, DocumentAnalysisProvider.NONE).definitions()).isEmpty();
    }

    private static AiChat.ToolCall call(String arguments) {
        return new AiChat.ToolCall("1", DeadlineChatTools.DEADLINES, arguments);
    }

    /** Wie {@code DeadlineAnalysisService}: Start setzt „läuft“, das Ende meldet der Beobachter. */
    private static final class FakeAnalyses implements DocumentAnalysisProvider {
        final Map<DocumentRef, DocumentAnalysis> results = new ConcurrentHashMap<>();
        final List<Consumer<DocumentRef>> observers = new CopyOnWriteArrayList<>();
        final AtomicInteger starts = new AtomicInteger();
        volatile CompletableFuture<Void> started = new CompletableFuture<>();

        @Override
        public DocumentAnalysis result(DocumentRef document) {
            return results.getOrDefault(document, DocumentAnalysis.notAnalyzed(document));
        }

        @Override
        public boolean canAnalyze(DocumentRef document) {
            return true;
        }

        @Override
        public Subscription observe(Consumer<DocumentRef> changed) {
            observers.add(changed);
            return () -> observers.remove(changed);
        }

        @Override
        public void startAnalysis(DocumentRef document) {
            starts.incrementAndGet();
            results.put(document, DocumentAnalysis.inProgress(document));
            started.complete(null);
        }

        void finish(DocumentAnalysis result) {
            results.put(result.document(), result);
            observers.forEach(observer -> observer.accept(result.document()));
        }
    }
}
