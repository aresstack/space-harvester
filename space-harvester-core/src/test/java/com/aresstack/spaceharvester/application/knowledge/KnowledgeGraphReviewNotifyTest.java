package com.aresstack.spaceharvester.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.core.Harvester;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3 über den ganzen Harvester: Eine gesetzte oder zurückgenommene Prüfung durch Menschen meldet sich beim Knowledge
 * Graph, damit Graph, Fachsicht, Aussagen und Verben ohne Klick nachziehen; der Graph liest die wirksame Prüfung.
 */
class KnowledgeGraphReviewNotifyTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("300", "Ablauf", "DEV", "<p>Die Kasse prüft den Zuschuss.</p>")
                .storage("<p>Die Kasse prüft den Zuschuss.</p>");
        CONFLUENCE.homepage("DEV", "300");
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
    void aHumanReviewReportsToTheGraphWhichReadsTheEffectiveReview() throws InterruptedException {
        try (Harvester harvester = Harvester.builder().source(new SourceSettingsConfig("wiki", "confluence", "Wiki",
                        SERVER.baseUrl(), new SourceSettingsConfig.Auth(SourceSettingsConfig.AuthType.PAT, null, null,
                        "token"), Map.of("connectTimeoutMillis", "2000", "allowInsecureHttp", "true")))
                .dataDirectory(data).statementAnalyzer(parser).build()) {
            awaitDone(harvester, harvester.sync("wiki", "DEV"));
            harvester.pages().page(harvester.caller(), "wiki", "300", false);
            JobStatus job = awaitDone(harvester, harvester.statementAnalysisJobs().start(harvester.caller(),
                    new StatementAnalysisJobs.Scope.Space("wiki", "DEV"), StatementAnalysisJobs.Options.DEFAULT));
            assertThat(job.state()).isEqualTo(JobState.COMPLETED);
            PageKey page = new PageKey("wiki", "300");
            String id = statement(harvester, page).id();

            Semaphore reported = new Semaphore(0);
            try (Subscription ignored = harvester.knowledgeGraph().subscribe(reported::release)) {
                reported.drainPermits();
                harvester.statusQuo().review(harvester.caller(), page, id, ParsedStatements.Review.REJECTED, 0L);
                assertThat(reported.tryAcquire(10, TimeUnit.SECONDS)).as("Prüfen meldet sich beim Graphen").isTrue();
                assertThat(statement(harvester, page).review()).isEqualTo(ParsedStatements.Review.REJECTED);

                reported.drainPermits();
                harvester.statusQuo().withdrawReview(harvester.caller(), page, id, 1L);
                assertThat(reported.tryAcquire(10, TimeUnit.SECONDS)).as("Zurücknehmen meldet sich").isTrue();
                assertThat(statement(harvester, page).review()).isEqualTo(ParsedStatements.Review.UNREVIEWED);
            }
        }
    }

    private static ParsedStatements.Statement statement(Harvester harvester, PageKey page) {
        return harvester.knowledgeGraph().analysed(harvester.caller()).stream()
                .filter(found -> found.page().key().equals(page)).findFirst().orElseThrow()
                .analysis().result().statements().getFirst();
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
