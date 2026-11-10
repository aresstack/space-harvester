package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerUnavailableException;
import com.aresstack.spaceharvester.application.knowledge.KnowledgeGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Der Reiter „Aussagen“ ohne Parser: Anzeige, Knopf, Abbruch, Seitenwechsel, Sprung (#499). */
class StatementsTabTest {

    static final String TEXT = "Der Sachbearbeiter übermittelt dem Vorgesetzten am Montag den Beleg des Kunden.";
    private static final String DOC = "wiki/1@0123456789ab";

    private final FakeActions actions = new FakeActions();
    private final DeferredRunner runner = new DeferredRunner();
    private final StatementsTab tab = new StatementsTab(() -> actions, runner);

    @Test
    void openingAPageShowsTheStoredStateAndNeverParses() {
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(tab.hintText()).isEqualTo(StatementsTab.NOT_ANALYSED);
        assertThat(tab.analyzeOffered()).isTrue();
        assertThat(actions.analyses).isZero();
    }

    @Test
    void theButtonAnalysesAndShowsCoreStatementsFirstWithRolesAndEvidence() {
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickAnalyze();
        assertThat(tab.cancelOffered()).isTrue();
        runner.runAll();

        assertThat(actions.analyses).isEqualTo(1);
        assertThat(tab.stateLabel()).isEqualTo("Aktuell");
        assertThat(tab.cancelOffered()).isFalse();
        List<String> lines = tab.lines();
        assertThat(lines.getFirst()).isEqualTo("Kernaussagen");
        assertThat(lines.get(1)).contains("übermittelt (übermitteln)", "Handelnder: Der Sachbearbeiter",
                "Betroffen: den Beleg", "Empfänger: dem Vorgesetzten", "Zeit: Montag (am, unsicher)",
                "Aktiv · Hauptsatz", "Nominaler Bezug: den Beleg → des Kunden (Genitiv; kein Besitz behauptet)",
                "Zeitangabe hing am Empfänger", "Beleg: „" + TEXT + "“");
        assertThat(lines).contains("Verbindungen");
        assertThat(lines.getLast()).contains("„beleg“ → Belege (handbuch)",
                "Kandidat, keine bestätigte Identität");
    }

    @Test
    void aClickOnAStatementJumpsToItsSentenceInTheReaderText() {
        List<List<TermsTab.Span>> jumps = new ArrayList<>();
        tab.onJump(jumps::add);
        actions.stored = analysed();
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickFirstEntry();

        assertThat(jumps).containsExactly(List.of(new TermsTab.Span(0, TEXT.length())));
    }

    @Test
    void aLateResultOfALeftPageIsDiscardedAndTheRunIsCancelled() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();
        tab.page(page("2"), text());
        runner.runAll();

        assertThat(actions.lastCancelled.getAsBoolean()).isTrue();
        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(tab.lines()).isEmpty();
        assertThat(tab.analyzeOffered()).isTrue();
    }

    @Test
    void aMissingModelShowsTheInstallHint() {
        actions.failure = new AnalyzerUnavailableException(AnalyzerUnavailableException.Reason.MODEL_MISSING,
                "Kein Parsermodell unter /daten/nlp-model. Zum Installieren …", null);
        tab.page(page("1"), text());
        runner.runAll();

        tab.clickAnalyze();
        runner.runAll();

        assertThat(tab.hintText()).startsWith("Kein Parsermodell unter /daten/nlp-model");
        assertThat(tab.analyzeOffered()).isTrue();
    }

    @Test
    void aStaleAnalysisIsGreyedAndExplained() {
        PageStatements current = analysed();
        actions.stored = new PageStatements(PageStatements.State.STALE, PageStatements.TEXT_CHANGED,
                current.analysis());
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.stateLabel()).isEqualTo("Veraltet");
        assertThat(tab.hintText()).startsWith("Der Text der Seite hat sich seit der Analyse geändert.")
                .endsWith(StatementsTab.NO_EVIDENCE);
        assertThat(tab.lines()).contains("Kernaussagen");
        assertThat(tab.lines()).as("alte Spannen nie aus dem neuen Text schneiden")
                .noneMatch(line -> line.contains("Beleg: „"));
        assertThat(tab.jumpCount()).isZero();
    }

    @Test
    void cancelKeepsAnalyseLockedUntilTheRunHasReallyEnded() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();

        tab.clickCancel();

        assertThat(tab.analyzeOffered()).as("kein zweiter Lauf, solange der erste noch rechnet").isFalse();
        assertThat(tab.progressText()).isEqualTo("Wird abgebrochen …");
        runner.runAll();
        assertThat(tab.analyzeOffered()).isTrue();
        assertThat(tab.progressText()).startsWith("Abgebrochen");
    }

    /** Codex zu #566: Beim Seitenwechsel kopierte der Knopf noch die Aussagen der vorigen Seite. */
    @Test
    void leavingThePageEmptiesTheClipboardText() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.render(analysed(), List.of());
        assertThat(tab.copyText()).isNotBlank();

        tab.pending(new PageKey("wiki", "2"));
        assertThat(tab.copyText()).isEmpty();
    }

    @Test
    void reloadingTheSamePageKeepsARunningAnalysis() {
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();

        tab.pending(new PageKey("wiki", "1"));
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(actions.lastCancelled.getAsBoolean()).isFalse();
        assertThat(tab.stateLabel()).isEqualTo("Aktuell");
    }

    @Test
    void aChangedTextOfTheSamePageCancelsTheRunningAnalysis() {
        List<String> analysed = new ArrayList<>();
        tab.onAnalysed(() -> analysed.add("x"));
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickAnalyze();

        tab.page(page("1"), new PageText("test/v1", "Ganz anderer Text.", List.of()));
        runner.runAll();

        assertThat(actions.lastCancelled.getAsBoolean()).isTrue();
        assertThat(tab.stateLabel()).isEqualTo("Nicht analysiert");
        assertThat(analysed).isEmpty();

        tab.clickAnalyze();
        runner.runAll();
        assertThat(analysed).as("neue Analyse gemeldet, etwa für den Graph-Reiter").hasSize(1);
    }

    @Test
    void loadingAndFailedPagesClaimNothingAboutTheAnalysis() {
        tab.pending(new PageKey("wiki", "1"));
        assertThat(tab.hintText()).isEqualTo(StatementsTab.LOADING_PAGE);
        assertThat(tab.stateLabel()).isBlank();
        assertThat(tab.analyzeOffered()).isFalse();

        tab.unavailable();
        assertThat(tab.hintText()).isEqualTo(StatementsTab.PAGE_UNAVAILABLE);
    }

    @Test
    void aNewAnalysisReplacesOpenSyntaxDetails() {
        actions.stored = analysed();
        tab.page(page("1"), text());
        runner.runAll();
        tab.clickSyntax();
        runner.runAll();
        assertThat(tab.syntaxText()).isEqualTo("1\tDer\n");

        actions.syntax = "1\tNeu\n";
        tab.clickAnalyze();
        runner.runAll();

        assertThat(tab.syntaxText()).isEqualTo("1\tNeu\n");
    }

    @Test
    void withoutAnalyzerTheButtonIsDisabled() {
        actions.available = false;
        tab.page(page("1"), text());
        runner.runAll();

        assertThat(tab.analyzeOffered()).isFalse();
        assertThat(tab.hintText()).isEqualTo(StatementsTab.NOT_AVAILABLE);
    }

    static PageStatements analysed() {
        ParsedStatements.Mention subject = mention("t2", 0, 18, "Der Sachbearbeiter", "Sachbearbeiter");
        ParsedStatements.Mention recipient = mention("t5", 31, 47, "dem Vorgesetzten", "Vorgesetzte");
        ParsedStatements.Mention time = mention("t8", 51, 57, "Montag", "Montag");
        ParsedStatements.Mention object = mention("t10", 58, 67, "den Beleg", "Beleg");
        ParsedStatements.Mention customer = mention("t12", 68, 78, "des Kunden", "Kunde");
        ParsedStatements.Statement statement = new ParsedStatements.Statement(DOC + "#s1.v3", 0, 19, 30,
                "übermittelt", "übermitteln", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true,
                List.of(argument(ParsedStatements.Role.ACTOR, subject, "nsubj", null, true),
                        argument(ParsedStatements.Role.AFFECTED_OBJECT, object, "obj", null, true),
                        argument(ParsedStatements.Role.RECIPIENT, recipient, "obl:arg", null, true),
                        new ParsedStatements.Argument(ParsedStatements.Role.TIME, time.id(), "nmod", "am",
                                ParsedStatements.Priority.CONTEXT, ParsedStatements.Basis.LEXICON, false)),
                Set.of("TIME_FROM_NOMINAL"));
        ParsedStatements result = new ParsedStatements(List.of(new ParsedStatements.Sentence(0, 0, TEXT.length())),
                List.of(subject, recipient, time, object, customer), List.of(statement),
                List.of(new ParsedStatements.Attachment(object.id() + "→t12", object.id(), customer.id(), "nmod",
                        null, ParsedStatements.AttachmentKind.GENITIVE, ParsedStatements.Basis.PARSER)),
                List.of("dependencies=maltparser 1.9.2"), null);
        return new PageStatements(PageStatements.State.CURRENT, null, new PageStatementAnalysis(
                new PageKey("wiki", "1"), "DEV", "Seite 1", "3", "sha256:0123456789abcdef", "maltparser-de",
                "1|maltparser-de-gsd-v1.0.0|237885c25519|rules-1", Instant.parse("2026-11-09T15:00:00Z"), result));
    }

    private static ParsedStatements.Mention mention(String token, int start, int end, String text, String lemma) {
        return new ParsedStatements.Mention(DOC + "#s1." + token, 0, start, end, text, lemma, lemma,
                ParsedStatements.MentionKind.NOUN, false, Set.of());
    }

    private static ParsedStatements.Argument argument(ParsedStatements.Role role, ParsedStatements.Mention mention,
                                                      String relation, String marker, boolean certain) {
        return new ParsedStatements.Argument(role, mention.id(), relation, marker,
                role == ParsedStatements.Role.RECIPIENT ? ParsedStatements.Priority.SUPPLEMENTARY
                        : ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, certain);
    }

    private static PageText text() {
        return new PageText("test/v1", TEXT, List.of());
    }

    private static PageSnapshot page(String id) {
        PageMetadata metadata = new PageMetadata(id, "confluence:wiki/page/" + id, "Seite " + id, "DEV", null, "3",
                Instant.parse("2026-11-01T10:00:00Z"), URI.create("https://wiki.example.org/" + id),
                PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload(TEXT.getBytes(StandardCharsets.UTF_8),
                "text/plain", "test-text"), Instant.parse("2026-11-09T10:00:00Z"));
    }

    private static final class FakeActions implements StatementsTab.Actions {

        PageStatements stored = PageStatements.missing(PageStatements.NOT_ANALYSED);
        boolean available = true;
        RuntimeException failure;
        int analyses;
        BooleanSupplier lastCancelled = () -> false;

        @Override
        public PageStatements statements(PageSnapshot page) {
            return stored;
        }

        @Override
        public boolean statementAnalysisAvailable() {
            return available;
        }

        @Override
        public List<KnowledgeGraph.Link> statementLinks(PageKey key) {
            if (stored.analysis() == null) {
                return List.of();
            }
            return List.of(new KnowledgeGraph.Link("beleg", KnowledgeGraph.Match.LEXICAL, List.of(DOC + "#s1.t10"),
                    new KnowledgeGraph.PageRef(new PageKey("handbuch", "7"), "OPS", "Belege",
                            PageStatements.State.CURRENT, null), List.of("handbuch/7@abc#s1.t4")));
        }

        @Override
        public PageStatements analyzeStatements(PageSnapshot page, BooleanSupplier cancelled) {
            analyses++;
            lastCancelled = cancelled;
            if (failure != null) {
                throw failure;
            }
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException(); // wie der Parser zwischen zwei Sätzen
            }
            PageStatements result = analysed();
            if (!cancelled.getAsBoolean()) {
                stored = result;
            }
            return result;
        }

        String syntax = "1\tDer\n";

        @Override
        public Optional<String> syntaxDetails(PageSnapshot page) {
            return Optional.of(syntax);
        }
    }

    /** Hält Hintergrundarbeit zurück, bis der Test sie ausführt (wie späte Ergebnisse nach Seitenwechsel). */
    private static final class DeferredRunner implements StatementsTab.Runner {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            pending.add(() -> {
                T result;
                try {
                    result = work.call();
                } catch (Throwable e) {
                    onFailure.accept(e);
                    return;
                }
                onSuccess.accept(result);
            });
        }

        void runAll() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }
    }
}
