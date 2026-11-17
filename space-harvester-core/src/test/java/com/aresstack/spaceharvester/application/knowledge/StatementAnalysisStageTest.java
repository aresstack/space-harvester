package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.configuration.SimplificationSettings;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.simplification.SimplificationModel;
import com.aresstack.spaceharvester.application.simplification.SimplificationStore;
import com.aresstack.spaceharvester.application.simplification.Simplifications;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Aussagen der Vorstufe und die KI-Freigabe der Seite (ADR 0028, ADR 0032 FL3), nur mit Schein-Modell. */
class StatementAnalysisStageTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final String TEXT = "Der Sachbearbeiter übermittelt den Beleg. Die Stelle prüft den Antrag.";

    private final InMemoryKnowledgeGraphStore store = new InMemoryKnowledgeGraphStore();
    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();
    private final AtomicBoolean released = new AtomicBoolean(true);
    private final SimplificationModel model = new SimplificationModel() {
        @Override
        public String version() {
            return "fake:m1";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String complete(String instructions, String input) {
            StringBuilder out = new StringBuilder();
            for (String line : input.split("\n")) {
                String[] parts = line.split(" ", 2);
                out.append(parts[0]).append("|T|").append(parts[1]).append('\n');
            }
            return out.toString();
        }
    };
    private final StatementAnalysis analysis = new StatementAnalysis(
            snapshot -> Optional.of(StatementAnalysisTest.text(snapshot)), store,
            new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), () -> parser,
            StatementAnalysisTest.clock(), new Simplifications(model, new SimplificationStore(
                    new InMemoryEnrichmentStore(), StatementAnalysisTest.clock()), page -> released.get()));
    private final StatementAnalysis.SimplificationRequest request = new StatementAnalysis.SimplificationRequest(
            SimplificationSettings.Mode.WHERE_NEEDED, 1);

    @Test
    void withoutStageDropsStageStatementsAndOnlyTheirMentions() {
        ParsedStatements original = parser.analyze("wiki/1@abc", TEXT, () -> false);
        ParsedStatements mixed = withStageSecond(original);

        ParsedStatements stripped = StatementAnalysis.withoutStage(mixed);

        assertThat(stripped.statements()).containsExactly(original.statements().get(0));
        assertThat(stripped.mentions()).extracting(ParsedStatements.Mention::text)
                .contains("Sachbearbeiter", "Beleg").doesNotContain("Stelle", "Antrag");
        assertThat(StatementAnalysis.withoutStage(original)).isSameAs(original);
    }

    @Test
    void withdrawingTheReleaseRemovesStageStatementsFromTheStoredAnalysisWithoutReparsing() {
        PageSnapshot page = EnrichmentTestPages.text("wiki", "1", "1", TEXT);
        analysis.analyze(CALLER, page, () -> false);
        PageStatementAnalysis stored = store.find(page.key()).orElseThrow();
        store.replace(stored.withResult(withStageSecond(stored.result())));
        int calls = parser.calls.get();

        assertThat(analysis.withdrawStage(List.of(page.key()), key -> true)).as("Freigabe besteht").isZero();
        released.set(false);
        assertThat(analysis.withdrawStage(List.of(page.key(), new PageKey("wiki", "fehlt")), key -> false))
                .isEqualTo(1);

        ParsedStatements after = store.find(page.key()).orElseThrow().result();
        assertThat(after.statements()).hasSize(1).allSatisfy(statement -> assertThat(statement.derivation())
                .isEqualTo(ParsedStatements.Derivation.ORIGINAL));
        assertThat(after.mentions()).extracting(ParsedStatements.Mention::text).doesNotContain("Stelle");
        assertThat(parser.calls).hasValue(calls);
        assertThat(analysis.withdrawStage(List.of(page.key()), key -> false)).as("nichts mehr zu entfernen").isZero();
    }

    @Test
    void aReleaseWithdrawnDuringTheAnalysisKeepsStageStatementsOutOfTheGraph() {
        PageSnapshot page = EnrichmentTestPages.text("wiki", "1", "1", TEXT);
        parser.derivation = ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.FACT,
                "Der Sachbearbeiter übermittelt den Beleg.");
        parser.afterStage = () -> released.set(false);

        analysis.analyze(CALLER, page, () -> false, request, costs -> {
        });

        assertThat(parser.stages).hasSize(1);
        assertThat(store.find(page.key()).orElseThrow().result().statements()).isEmpty();
    }

    @Test
    void withReleaseStageStatementsAreStored() {
        PageSnapshot page = EnrichmentTestPages.text("wiki", "1", "1", TEXT);
        parser.derivation = ParsedStatements.Derivation.vorstufe(ParsedStatements.StageFlag.FACT,
                "Der Sachbearbeiter übermittelt den Beleg.");

        analysis.analyze(CALLER, page, () -> false, request, costs -> {
        });

        assertThat(store.find(page.key()).orElseThrow().result().statements()).hasSize(2)
                .allSatisfy(statement -> assertThat(statement.derivation().origin())
                        .isEqualTo(ParsedStatements.Origin.VORSTUFE));
    }

    /** Die zweite Aussage als Aussage der Vorstufe. */
    private static ParsedStatements withStageSecond(ParsedStatements result) {
        List<ParsedStatements.Statement> statements = new ArrayList<>(result.statements());
        ParsedStatements.Statement s = statements.get(1);
        statements.set(1, new ParsedStatements.Statement(s.id(), s.sentence(), s.start(), s.end(), s.predicateText(),
                s.predicateLemma(), s.voice(), s.clause(), s.core(), s.arguments(), s.findings(), s.paragraph(),
                s.quality(), s.qualityReasons(), s.review(), s.features(), ParsedStatements.Derivation.vorstufe(
                        ParsedStatements.StageFlag.FACT, "Die Stelle prüft den Antrag.")));
        return new ParsedStatements(result.sentences(), result.mentions(), statements, result.attachments(),
                result.components(), result.conllu());
    }
}
