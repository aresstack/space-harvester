package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.check.CheckScope;
import com.aresstack.spaceharvester.application.check.CheckUnavailableException;
import com.aresstack.spaceharvester.application.check.StatementCheckStore;
import com.aresstack.spaceharvester.application.check.StatementChecks;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Prüfschritt an der gespeicherten Analyse (ADR 0032, FL4), nur mit Schein-Modell und Schein-Analysator und erfundenen
 * Sätzen: Ein Urteil senkt höchstens die wirksame Stufe, Korrekturen kommen als eigene Aussagen hinzu, und nach dem
 * Entzug der Freigabe steht alles genau wie vorher da.
 */
class StatementAnalysisCheckTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    /** Satz 1 PLAUSIBLE und verneint; Satz 2 UNCERTAIN (offenes Pronomen) und verneint. */
    private static final String TEXT = "Die Stelle sendet dem Kunden den Bescheid nicht. Den Antrag prüft er nicht.";
    private static final String CORRECTION = "Die Stelle sendet dem Kunden den Bescheid nicht.";

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final CorrectionAwareAnalyzer parser = new CorrectionAwareAnalyzer();
    private final AtomicBoolean released = new AtomicBoolean(true);
    private final FakeModel model = new FakeModel();
    private final StatementCheckStore checkStore = new StatementCheckStore(new InMemoryEnrichmentStore(),
            StatementAnalysisTest.clock());
    private final StatementAnalysis analysis = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store,
            new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), () -> parser,
            StatementAnalysisTest.clock(), null, new StatementChecks(model, checkStore, page -> released.get()));
    private final PageSnapshot page = EnrichmentTestPages.text("wiki", "1", "1", TEXT);
    private static final StatementAnalysis.CheckRequest ALL = new StatementAnalysis.CheckRequest(CheckScope.ALL, 1);
    private static final StatementAnalysis.CheckRequest STANDARD = new StatementAnalysis.CheckRequest(
            CheckScope.UNCERTAIN_AND_STAGE, 1);

    @Test
    void falseLowersOnlyTheEffectiveQualityAndTrueNeverRaisesIt() {
        analysis.analyze(CALLER, page, () -> false);
        model.answer = input -> input.contains("sendet") ? "1|FALSCH" : "1|STIMMT";

        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();
        analysis.check(CALLER, page, () -> false, ALL, costs::add);

        List<ParsedStatements.Statement> statements = stored().statements();
        ParsedStatements.Statement sends = statements.get(0);
        assertThat(sends.quality()).as("Stufe des Parsers bleibt").isEqualTo(ParsedStatements.Quality.PLAUSIBLE);
        assertThat(sends.effectiveQuality()).isEqualTo(ParsedStatements.Quality.UNCERTAIN);
        assertThat(sends.effectiveQualityReasons()).contains(ParsedStatements.Check.MODEL_FALSE);
        assertThat(sends.check().verdict()).isEqualTo(ParsedStatements.Verdict.FALSCH);
        assertThat(sends.check().version()).isEqualTo("fake:m1|prompt-1");
        ParsedStatements.Statement checks = statements.get(1);
        assertThat(checks.check().verdict()).isEqualTo(ParsedStatements.Verdict.STIMMT);
        assertThat(checks.effectiveQuality()).as("STIMMT hebt nie").isEqualTo(ParsedStatements.Quality.UNCERTAIN);
        assertThat(costs).singleElement().satisfies(c -> {
            assertThat(c.run().statements()).isEqualTo(2);
            assertThat(c.run().modelCalls()).isEqualTo(2);
            assertThat(c.run().falsch()).isEqualTo(1);
            assertThat(c.run().stimmt()).isEqualTo(1);
        });
        assertThat(analysis.read(CALLER, page).state()).isEqualTo(PageStatements.State.CURRENT);
    }

    @Test
    void theStandardScopeLeavesCertainOriginalStatementsAlone() {
        analysis.analyze(CALLER, page, () -> false);
        model.answer = input -> "1|FALSCH";

        analysis.check(CALLER, page, () -> false, STANDARD, costs -> { });

        assertThat(model.inputs).singleElement().satisfies(input -> assertThat(input).contains("prüft")
                .doesNotContain("sendet").as("Original-Aussagen ohne Zusammenhang").startsWith("Satz: "));
        assertThat(stored().statements().get(0).check()).isNull();
    }

    @Test
    void aValidCorrectionBecomesAnOwnStatementAndTheCorrectedOneStaysLowered() {
        analysis.analyze(CALLER, page, () -> false);
        model.answer = input -> input.contains("sendet") ? "1|UNVOLLSTAENDIG|" + CORRECTION : "1|STIMMT";
        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();

        analysis.check(CALLER, page, () -> false, ALL, costs::add);

        ParsedStatements result = stored();
        assertThat(result.statements()).hasSize(3);
        ParsedStatements.Statement corrected = result.statements().get(0);
        assertThat(corrected.check().corrected()).isTrue();
        assertThat(corrected.effectiveQuality()).isEqualTo(ParsedStatements.Quality.UNCERTAIN);
        assertThat(corrected.effectiveQualityReasons()).contains(ParsedStatements.Check.MODEL_CORRECTED);
        ParsedStatements.Statement correction = result.statements().get(2);
        assertThat(correction.derivation().origin()).isEqualTo(ParsedStatements.Origin.MODELL);
        assertThat(correction.derivation().corrects()).isEqualTo(corrected.id());
        assertThat(correction.derivation().simplifiedSentence()).isEqualTo(CORRECTION);
        assertThat(correction.sentence()).isEqualTo(corrected.sentence());
        assertThat(correction.start()).isZero();
        assertThat(correction.withRole(ParsedStatements.Role.RECIPIENT)).hasSize(1);
        assertThat(correction.features()).as("Merkmale aus dem Original").isEqualTo(corrected.features());
        assertThat(correction.derivation().featuresFrom()).isEqualTo(ParsedStatements.Origin.ORIGINAL);
        assertThat(correction.check()).isNull();
        Set<String> mentionIds = new java.util.HashSet<>();
        result.mentions().forEach(mention -> mentionIds.add(mention.id()));
        assertThat(correction.arguments()).allSatisfy(argument -> assertThat(mentionIds).contains(argument.mention()));
        assertThat(result.mentions()).filteredOn(mention -> mention.id().contains(".m1.")).hasSize(3)
                .allSatisfy(mention -> assertThat(mention.start()).isZero());
        assertThat(costs.getFirst().corrections()).isEqualTo(1);
    }

    @Test
    void incompleteWithoutAValidCorrectionChangesNothing() {
        analysis.analyze(CALLER, page, () -> false);
        // Verneinung fehlt: besteht den Wortabgleich, taugt aber nicht als Vervollständigung
        model.answer = input -> input.contains("sendet")
                ? "1|UNVOLLSTAENDIG|Die Stelle sendet dem Kunden den Bescheid." : "1|UNVOLLSTAENDIG";
        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();

        analysis.check(CALLER, page, () -> false, ALL, costs::add);

        ParsedStatements result = stored();
        assertThat(result.statements()).hasSize(2);
        assertThat(result.statements().get(0).check().corrected()).isFalse();
        assertThat(result.statements().get(0).effectiveQuality()).isEqualTo(ParsedStatements.Quality.PLAUSIBLE);
        assertThat(costs.getFirst().rejectedCorrections()).isEqualTo(1);
    }

    @Test
    void withdrawingTheReleaseRestoresTheAnalysisExactlyWithoutMakingItStale() {
        analysis.analyze(CALLER, page, () -> false);
        ParsedStatements before = stored();
        model.answer = input -> input.contains("sendet") ? "1|UNVOLLSTAENDIG|" + CORRECTION : "1|FALSCH";
        analysis.check(CALLER, page, () -> false, ALL, costs -> { });
        int parses = parser.calls.get();

        assertThat(analysis.withdrawChecks(List.of(page.key()), key -> true)).as("Freigabe besteht").isZero();
        assertThat(analysis.withdrawChecks(List.of(page.key()), key -> false)).isEqualTo(1);

        assertThat(stored().statements()).isEqualTo(before.statements());
        assertThat(stored().mentions()).isEqualTo(before.mentions());
        assertThat(parser.calls).as("ohne neu zu parsen").hasValue(parses);
        assertThat(analysis.read(CALLER, page).state()).isEqualTo(PageStatements.State.CURRENT);
        assertThat(analysis.withdrawChecks(List.of(page.key()), key -> false)).as("nichts mehr da").isZero();
    }

    @Test
    void withdrawingTheStageAlsoRemovesTheChecks() {
        analysis.analyze(CALLER, page, () -> false);
        ParsedStatements before = stored();
        model.answer = input -> "1|FALSCH";
        analysis.check(CALLER, page, () -> false, ALL, costs -> { });

        assertThat(analysis.withdrawStage(List.of(page.key()), key -> false)).isEqualTo(1);

        assertThat(stored().statements()).isEqualTo(before.statements());
        assertThat(analysis.read(CALLER, page).state()).isEqualTo(PageStatements.State.CURRENT);
    }

    @Test
    void aNewModelReplacesAllEarlierVerdictsAndCorrections() {
        analysis.analyze(CALLER, page, () -> false);
        model.answer = input -> input.contains("sendet") ? "1|UNVOLLSTAENDIG|" + CORRECTION : "1|FALSCH";
        analysis.check(CALLER, page, () -> false, ALL, costs -> { });
        assertThat(stored().statements()).hasSize(3);

        model.version = "fake:m2";
        model.answer = input -> "1|STIMMT";
        analysis.check(CALLER, page, () -> false, ALL, costs -> { });

        assertThat(stored().statements()).hasSize(2).allSatisfy(statement -> {
            assertThat(statement.check().verdict()).isEqualTo(ParsedStatements.Verdict.STIMMT);
            assertThat(statement.check().version()).isEqualTo("fake:m2|prompt-1");
            assertThat(statement.effectiveQuality()).isEqualTo(statement.quality());
        });
    }

    @Test
    void aSecondRunResumesFromTheStoreWithoutModelCalls() {
        analysis.analyze(CALLER, page, () -> false);
        model.answer = input -> "1|FALSCH";
        analysis.check(CALLER, page, () -> false, ALL, costs -> { });
        int calls = model.calls.get();
        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();

        analysis.check(CALLER, page, () -> false, ALL, costs::add);

        assertThat(model.calls).hasValue(calls);
        assertThat(costs.getFirst().run().fromStore()).isEqualTo(2);
        assertThat(stored().statements()).allSatisfy(statement -> assertThat(statement.check().verdict())
                .isEqualTo(ParsedStatements.Verdict.FALSCH));
    }

    @Test
    void aReleaseWithdrawnDuringTheRunWritesNothing() {
        analysis.analyze(CALLER, page, () -> false);
        ParsedStatements before = stored();
        model.answer = input -> {
            released.set(false);
            return "1|FALSCH";
        };

        assertThatThrownBy(() -> analysis.check(CALLER, page, () -> false, ALL, costs -> { }))
                .isInstanceOfSatisfying(CheckUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(CheckUnavailableException.Reason.NOT_RELEASED));
        assertThat(stored().statements()).isEqualTo(before.statements());
        assertThat(checkStore.contains(page.key())).as("Antwort nach dem Entzug nicht gespeichert").isFalse();
    }

    @Test
    void withoutReleaseModelOrCurrentAnalysisNothingIsSent() {
        assertThatThrownBy(() -> analysis.check(CALLER, page, () -> false, ALL, costs -> { }))
                .isInstanceOfSatisfying(CheckUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(CheckUnavailableException.Reason.NOT_CURRENT));
        analysis.analyze(CALLER, page, () -> false);
        released.set(false);
        assertThatThrownBy(() -> analysis.check(CALLER, page, () -> false, ALL, costs -> { }))
                .isInstanceOfSatisfying(CheckUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(CheckUnavailableException.Reason.NOT_RELEASED));
        released.set(true);
        model.available = false;
        assertThatThrownBy(() -> analysis.check(CALLER, page, () -> false, ALL, costs -> { }))
                .isInstanceOfSatisfying(CheckUnavailableException.class, e -> assertThat(e.reason())
                        .isEqualTo(CheckUnavailableException.Reason.NO_MODEL));
        assertThat(model.calls).hasValue(0);
    }

    @Test
    void theGraphFollowsTheEffectiveQuality() {
        analysis.analyze(CALLER, page, () -> false);
        model.answer = input -> input.contains("sendet") ? "1|FALSCH" : "1|STIMMT";
        analysis.check(CALLER, page, () -> false, ALL, costs -> { });

        PageGraph graph = PageGraph.of(store.find(page.key()).orElseThrow(), PageGraph.DEFAULT_QUALITIES);

        assertThat(graph.nodes()).noneMatch(node -> node.kind() == PageGraph.NodeKind.STATEMENT);
    }

    /** Erfundener Stichpunkt-Abschnitt: der Handelnde steht in der Zeile über dem Stichpunkt. */
    private static final String BULLETS = "Die Prüfstelle hat zwei Aufgaben. Den Bescheid senden. Den Beleg ablegen.";

    /**
     * Die Seite {@link #BULLETS}, analysiert, mit einer Aussage der Vorstufe im zweiten Satz („senden“, Handelnder
     * „Die Prüfstelle“ aus dem ersten), PLAUSIBLE, mit Abschnitt 0..2 oder ohne (Analyse vor FL4).
     */
    private PageSnapshot bulletPage(boolean withSection) {
        PageSnapshot bullets = EnrichmentTestPages.text("wiki", "2", "1", BULLETS);
        analysis.analyze(CALLER, bullets, () -> false);
        PageStatementAnalysis analyzed = store.find(bullets.key()).orElseThrow();
        ParsedStatements.Sentence second = analyzed.result().sentences().get(1);
        String prefix = analyzed.documentId() + "#s2.p1.";
        List<ParsedStatements.Mention> mentions = List.of(
                new ParsedStatements.Mention(prefix + "t1", 1, second.start(), second.end(), "Die Prüfstelle",
                        "Prüfstelle", "Prüfstelle", ParsedStatements.MentionKind.NOUN, false, Set.of()),
                new ParsedStatements.Mention(prefix + "t3", 1, second.start(), second.end(), "den Bescheid",
                        "Bescheid", "Bescheid", ParsedStatements.MentionKind.NOUN, false, Set.of()));
        ParsedStatements.Derivation stage = ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.FACT,
                "Die Prüfstelle sendet den Bescheid.");
        ParsedStatements.Statement fromStage = new ParsedStatements.Statement(prefix + "v2", 1, second.start(),
                second.end(), "sendet", "senden", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true,
                List.of(new ParsedStatements.Argument(ParsedStatements.Role.ACTOR, prefix + "t1", "nsubj", null,
                                ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true),
                        new ParsedStatements.Argument(ParsedStatements.Role.AFFECTED_OBJECT, prefix + "t3", "obj",
                                null, ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true)),
                Set.of(), 0, ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                new ParsedStatements.Features(ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.NONE,
                        ParsedStatements.Temporality.GILT),
                withSection ? stage.withSection(new ParsedStatements.Section(0, 2)) : stage);
        store.replace(analyzed.withResult(new ParsedStatements(analyzed.result().sentences(), mentions,
                List.of(fromStage), List.of(), analyzed.result().components(), null)));
        return bullets;
    }

    @Test
    void aStageStatementIsCheckedWithItsSectionAsContext() {
        PageSnapshot bullets = bulletPage(true);
        // Ein Modell, das sich an „nur, was im Satz steht“ hält: ohne Zusammenhang fehlt ihm der Handelnde
        model.answer = input -> input.contains("> Die Prüfstelle hat zwei Aufgaben.") ? "1|STIMMT" : "1|FALSCH";
        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();

        analysis.check(CALLER, bullets, () -> false, STANDARD, costs::add);

        assertThat(model.inputs).singleElement().isEqualTo("""
                Zusammenhang:
                > Die Prüfstelle hat zwei Aufgaben.
                > Den Bescheid senden.
                > Den Beleg ablegen.
                Satz: Den Bescheid senden.
                1 Verb: senden; Handelnder: Die Prüfstelle; Betroffenes: den Bescheid; verneint: nein; \
                Verbindlichkeit: keine; Zeitbezug: gilt
                """);
        ParsedStatements.Statement checked = store.find(bullets.key()).orElseThrow().result().statements().get(0);
        assertThat(checked.check().verdict()).isEqualTo(ParsedStatements.Verdict.STIMMT);
        assertThat(checked.effectiveQuality()).as("nicht abgewertet").isEqualTo(ParsedStatements.Quality.PLAUSIBLE);
        assertThat(costs.getFirst().withoutSection()).isZero();
        assertThat(costs.getFirst().run().unanswered()).isZero();
    }

    @Test
    void aCorrectionOfAStageStatementMayUseWordsFromItsSection() {
        PageSnapshot bullets = bulletPage(true);
        // „Aufgaben“ steht nur im Zusammenhang, nicht im Stichpunkt selbst
        model.answer = input -> "1|UNVOLLSTAENDIG|Die Prüfstelle sendet den Bescheid Aufgaben.";
        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();

        analysis.check(CALLER, bullets, () -> false, STANDARD, costs::add);

        assertThat(costs.getFirst().run().correctionsRejected()).as("Wortabgleich gegen den Abschnitt").isZero();
        assertThat(parser.last.get()).isEqualTo("Die Prüfstelle sendet den Bescheid Aufgaben.\n");
    }

    @Test
    void aStageStatementWithoutStoredSectionStaysUnchecked() {
        PageSnapshot bullets = bulletPage(false);
        model.answer = input -> "1|FALSCH";
        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();

        analysis.check(CALLER, bullets, () -> false, STANDARD, costs::add);

        assertThat(model.calls).hasValue(0);
        assertThat(costs.getFirst().withoutSection()).isEqualTo(1);
        assertThat(store.find(bullets.key()).orElseThrow().result().statements().get(0).check()).isNull();
    }

    @Test
    void statementsWithoutAValidVerdictAreCountedAsUnanswered() {
        analysis.analyze(CALLER, page, () -> false);
        model.answer = input -> input.contains("sendet") ? "1|STIMMT" : "keine Ahnung";
        List<StatementAnalysis.CheckCosts> costs = new ArrayList<>();

        analysis.check(CALLER, page, () -> false, ALL, costs::add);

        assertThat(costs.getFirst().run().unanswered()).isEqualTo(1);
        assertThat(costs.getFirst().run().rejected()).isEqualTo(1);
    }

    private ParsedStatements stored() {
        return store.find(page.key()).orElseThrow().result();
    }

    /** Schein-Modell: Antwort je Eingabe, zählt Aufrufe. */
    private static final class FakeModel implements StepModel {
        volatile String version = "fake:m1";
        volatile boolean available = true;
        volatile Function<String, String> answer = input -> "";
        final AtomicInteger calls = new AtomicInteger();
        final List<String> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String complete(String instructions, String input) {
            calls.incrementAndGet();
            inputs.add(input);
            return answer.apply(input);
        }
    }

    /**
     * Der Schein-Analysator für Seiten; Korrekturen (Dokument {@code …-check}) parst er mit Empfänger: großgeschriebene
     * Wörter außer Artikeln sind Erwähnungen, die erste handelt, eine mittlere empfängt, die letzte ist betroffen;
     * Verneinung bekannt.
     */
    private static final class CorrectionAwareAnalyzer implements StatementAnalyzer {
        private static final Set<String> ARTICLES = Set.of("Der", "Die", "Das", "Dem", "Den", "Des");
        private static final Pattern WORD = Pattern.compile("\\p{L}+");
        private final FakeStatementAnalyzer pages = new FakeStatementAnalyzer();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> last = new AtomicReference<>();

        @Override
        public String id() {
            return pages.id();
        }

        @Override
        public String version() {
            return pages.version();
        }

        @Override
        public ParsedStatements analyze(String documentId, String text, BooleanSupplier cancelled) {
            calls.incrementAndGet();
            last.set(text);
            if (!documentId.endsWith("-check")) {
                return pages.analyze(documentId, text, cancelled);
            }
            List<ParsedStatements.Sentence> sentences = new ArrayList<>();
            List<ParsedStatements.Mention> mentions = new ArrayList<>();
            List<ParsedStatements.Statement> statements = new ArrayList<>();
            int start = 0;
            int index = 0;
            for (String line : text.split("\n", -1)) {
                if (line.isBlank()) {
                    start += line.length() + 1;
                    continue;
                }
                sentences.add(new ParsedStatements.Sentence(index, start, start + line.length()));
                List<ParsedStatements.Mention> own = new ArrayList<>();
                String verb = null;
                Matcher words = WORD.matcher(line);
                int token = 0;
                while (words.find()) {
                    token++;
                    String word = words.group();
                    if (FakeStatementAnalyzer.VERBS.contains(word) && verb == null) {
                        verb = word;
                    } else if (Character.isUpperCase(word.charAt(0)) && !ARTICLES.contains(word)) {
                        own.add(new ParsedStatements.Mention(documentId + "#s" + (index + 1) + ".t" + token, index,
                                start + words.start(), start + words.end(), word, word, word,
                                ParsedStatements.MentionKind.NOUN, false, Set.of()));
                    }
                }
                mentions.addAll(own);
                if (verb != null && own.size() >= 2) {
                    List<ParsedStatements.Argument> arguments = new ArrayList<>();
                    for (int i = 0; i < own.size(); i++) {
                        ParsedStatements.Role role = i == 0 ? ParsedStatements.Role.ACTOR
                                : i == own.size() - 1 ? ParsedStatements.Role.AFFECTED_OBJECT
                                : ParsedStatements.Role.RECIPIENT;
                        arguments.add(new ParsedStatements.Argument(role, own.get(i).id(), "dep", null,
                                ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true));
                    }
                    statements.add(new ParsedStatements.Statement(documentId + "#s" + (index + 1) + ".v1", index,
                            start, start + line.length(), verb, verb, ParsedStatements.Voice.ACTIVE,
                            ParsedStatements.Clause.MAIN, true, arguments, Set.of(), 0,
                            ParsedStatements.Quality.PLAUSIBLE, List.of(), ParsedStatements.Review.UNREVIEWED,
                            new ParsedStatements.Features(line.contains(" nicht")
                                    ? ParsedStatements.Negation.NEGATED : ParsedStatements.Negation.AFFIRMED,
                                    ParsedStatements.Modality.NONE, ParsedStatements.Temporality.GILT)));
                }
                start += line.length() + 1;
                index++;
            }
            return new ParsedStatements(sentences, mentions, statements, List.of(), List.of("parser=fake"), null);
        }
    }
}
