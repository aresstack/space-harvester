package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer;
import com.aresstack.spaceharvester.application.knowledge.PageStatementAnalysis;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.persistence.files.FileKnowledgeGraphStore;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D6 mit echtem Harvester-Aufbau: Die Meldung sitzt innen an jeder Ablage, die Ablagewahl bleibt erkennbar
 * ({@code StatementAnalysis.selected()}), und eine geänderte KI-Analyse meldet sich beim Graphen.
 */
class HarvesterAnalysesChangedTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void theStoreChoiceStaysVisibleAndAModelAnalysisReportsToTheGraph() throws InterruptedException {
        PageKey page = new PageKey("wiki", "100");
        new FileKnowledgeGraphStore(data.resolve("knowledge-graph-model")).replace(analysis(page,
                ModelStatementAnalyzer.ID, "Die Stelle prüft den Antrag."));
        try (Harvester harvester = Harvester.builder().source(wiki()).dataDirectory(data).build()) {
            Semaphore reported = new Semaphore(0);
            Subscription subscription = harvester.knowledgeGraph().subscribe(reported::release);
            try {
                assertThat(harvester.statementAnalysis().selected()).isEqualTo(AnalyzerChoice.PARSER);

                harvester.statementAnalyzerChoice(AnalyzerChoice.MODEL);
                assertThat(harvester.statementAnalysis().selected())
                        .as("die Ablagewahl bleibt hinter der Meldung erkennbar").isEqualTo(AnalyzerChoice.MODEL);
                assertThat(reported.tryAcquire(10, TimeUnit.SECONDS)).as("Umstellen meldet sich").isTrue();
                reported.drainPermits();

                assertThat(harvester.statementAnalysis().withdrawModel(List.of(page), released -> false))
                        .as("die KI-Analyse liegt in der KI-Ablage").isEqualTo(1);
                assertThat(reported.tryAcquire(10, TimeUnit.SECONDS)).as("geänderte KI-Analyse meldet sich")
                        .isTrue();
            } finally {
                subscription.close();
            }
        }
    }

    private static SourceSettingsConfig wiki() {
        return new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, "token"),
                Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true"));
    }

    private static PageStatementAnalysis analysis(PageKey page, String analyzerId, String text) {
        String doc = page.sourceId() + "/" + page.pageId() + "@0123456789ab";
        ParsedStatements.Mention subject = new ParsedStatements.Mention(doc + "#s0.k1", 0, 0, 9, "Die Stelle",
                "Stelle", "Stelle", ParsedStatements.MentionKind.NOUN, false, Set.of());
        ParsedStatements.Statement statement = new ParsedStatements.Statement(doc + "#s0.v0", 0, 10, 15, "prüft",
                "prüfen", ParsedStatements.Voice.ACTIVE, ParsedStatements.Clause.MAIN, true,
                List.of(new ParsedStatements.Argument(ParsedStatements.Role.ACTOR, subject.id(), "nsubj", null,
                        ParsedStatements.Priority.CORE, ParsedStatements.Basis.PARSER, true)), Set.of());
        ParsedStatements result = new ParsedStatements(List.of(new ParsedStatements.Sentence(0, 0, text.length())),
                List.of(subject), List.of(statement), List.of(), List.of("analyzer=" + analyzerId), null);
        return new PageStatementAnalysis(page, "DEV", "Start", "1", "sha256:0123456789abcdef", analyzerId,
                "1|test", Instant.parse("2026-11-21T18:00:00Z"), result);
    }
}
