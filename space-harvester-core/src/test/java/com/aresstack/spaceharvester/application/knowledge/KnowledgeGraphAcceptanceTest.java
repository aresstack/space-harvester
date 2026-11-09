package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Abnahme #499 über den ganzen Harvester: Abgleich und Lesen analysieren nie; erst der Auftrag füllt den globalen
 * Graphen; der Graph überlebt einen Neustart; gleiche Wörter auf zwei Seiten bleiben zwei Erwähnungen mit einer
 * Kandidatenverbindung; eine geänderte Seite wird veraltet und nicht von selbst neu berechnet.
 */
class KnowledgeGraphAcceptanceTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("200", "Start", "DEV", "<p>Betrieb</p>").storage("<p>Betrieb</p>");
        CONFLUENCE.homepage("DEV", "200");
        CONFLUENCE.page("201", "Nachtlauf", "DEV", "<p>Der Nachtlauf prüft die Datenbanken.</p>")
                .storage("<p>Der Nachtlauf prüft die Datenbanken.</p>");
        CONFLUENCE.page("202", "Revision", "DEV", "<p>Die Revision prüft die Datenbanken.</p>")
                .storage("<p>Die Revision prüft die Datenbanken.</p>");
        CONFLUENCE.child("200", "201");
        CONFLUENCE.child("200", "202");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TempDir
    Path data;

    private final FakeStatementAnalyzer parser = new FakeStatementAnalyzer();

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void onlyTheManualJobFillsAPersistentGraphThatNeverRecomputesItself() throws InterruptedException {
        try (Harvester harvester = harvester()) {
            awaitDone(harvester, harvester.sync("wiki", "DEV"));
            for (String id : new String[] {"200", "201", "202"}) {
                harvester.pages().page(harvester.caller(), "wiki", id, false);
            }
            assertThat(parser.calls).as("Abgleich und Lesen analysieren nie").hasValue(0);
            assertThat(harvester.knowledgeGraph().analysed(harvester.caller())).isEmpty();

            JobStatus job = awaitDone(harvester, harvester.statementAnalysisJobs().start(harvester.caller(),
                    new StatementAnalysisJobs.Scope.Space("wiki", "DEV"), StatementAnalysisJobs.Options.DEFAULT));

            assertThat(job.state()).isEqualTo(JobState.COMPLETED);
            assertThat(job.progress().written()).isEqualTo(3);
            KnowledgeGraph.Link link = harvester.knowledgeGraph().links(harvester.caller(), new PageKey("wiki", "201"))
                    .stream().filter(candidate -> candidate.target().key().equals(new PageKey("wiki", "202")))
                    .findFirst().orElseThrow();
            assertThat(link.match()).as("gleiche Grundform ist nur ein Kandidat").isEqualTo(KnowledgeGraph.Match.LEXICAL);
            assertThat(link.mentions()).doesNotContainAnyElementsOf(link.targetMentions());
        }
        int analysed = parser.calls.get();

        // Neustart: der Graph ist noch da, nichts wird neu berechnet.
        CONFLUENCE.page("202", "Revision", "DEV", "<p>Die Revision sendet die Berichte.</p>")
                .storage("<p>Die Revision sendet die Berichte.</p>").version(2, "2026-11-09T12:00:00.000Z");
        try (Harvester harvester = harvester()) {
            assertThat(harvester.knowledgeGraph().analysed(harvester.caller())).hasSize(3);
            awaitDone(harvester, harvester.sync("wiki", "DEV"));
            harvester.pages().page(harvester.caller(), "wiki", "202", false);

            PageStatements changed = harvester.statementAnalysis().read(harvester.caller(),
                    harvester.pages().page(harvester.caller(), "wiki", "202", false).snapshot());
            assertThat(changed.state()).isEqualTo(PageStatements.State.STALE);
            assertThat(changed.reason()).isEqualTo(PageStatements.TEXT_CHANGED);
            assertThat(parser.calls).as("veraltet heißt nicht neu berechnet").hasValue(analysed);
        }
    }

    private Harvester harvester() {
        return Harvester.builder().source(new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                        new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null, "token"),
                        Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true")))
                .dataDirectory(data).statementAnalyzer(parser).build();
    }

    /** Wartet auf das Ende des Auftrags, mit Frist, ohne feste Pause. */
    private static JobStatus awaitDone(Harvester harvester, JobStatus started) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        JobStatus status = harvester.status(started.id());
        while (!status.state().terminal()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Auftrag " + started.type() + " nach 60 s nicht beendet: " + status.state());
            }
            Thread.sleep(20);
            status = harvester.status(started.id());
        }
        return status;
    }
}
