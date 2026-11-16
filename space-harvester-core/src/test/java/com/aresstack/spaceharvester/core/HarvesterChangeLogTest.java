package com.aresstack.spaceharvester.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.changelog.ChangeEntry;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0030 über die ganze Bibliothek: Abgleich, Lesen, Nachladen und Export schreiben ihre Zeilen mit ihrem Auslöser,
 * nie {@link ChangeTrigger#UNKNOWN}; nur die manuelle Synchronisierung setzt eine Update-Marke.
 */
class HarvesterChangeLogTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<p>Start</p>");
        CONFLUENCE.page("102", "Prüfung", "DEV", "<p>Inhalt</p>").storage("<p>Inhalt</p>");
        CONFLUENCE.child("100", "102");
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

    private Harvester harvester() {
        return Harvester.builder()
                .source(new SourceSettingsConfig("wiki", "confluence", "Wiki", SERVER.baseUrl(),
                        SourceSettingsConfig.Auth.anonymous(), Map.of("connectTimeoutMillis", "2000")))
                .dataDirectory(data)
                .build();
    }

    private static void allBy(List<ChangeEntry> rows, ChangeTrigger trigger) {
        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(ChangeEntry::trigger).containsOnly(trigger);
    }

    /** Die Marke folgt den Auftragsmeldungen (ADR 0027) und kommt daher kurz nach dem Ende des Auftrags. */
    private static List<UpdateMark> awaitMarks(Harvester harvester, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<UpdateMark> marks = harvester.updateMarks("wiki");
        while (marks.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
            marks = harvester.updateMarks("wiki");
        }
        return marks;
    }

    @Test
    void aManualSyncWritesManualRowsAndThenTheInitialMark() throws Exception {
        try (Harvester harvester = harvester()) {
            JobStatus sync = harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30));
            assertThat(sync.state()).isEqualTo(JobState.COMPLETED);

            allBy(harvester.changeEntries("wiki"), ChangeTrigger.MANUAL);
            assertThat(harvester.changeEntries("wiki")).extracting(ChangeEntry::pageId)
                    .containsExactlyInAnyOrder("100", "102");
            assertThat(awaitMarks(harvester, 1)).singleElement().satisfies(mark -> {
                assertThat(mark.initial()).isTrue();
                assertThat(mark.at()).isEqualTo(sync.finishedAt());
                assertThat(mark.spaces()).containsExactly("DEV");
                assertThat(mark.failedSyncs()).isZero();
            });
            int rows = harvester.changeEntries("wiki").size();

            JobStatus again = harvester.await(harvester.sync("wiki", "DEV").id(), Duration.ofSeconds(30));
            assertThat(again.state()).isEqualTo(JobState.COMPLETED);

            assertThat(awaitMarks(harvester, 2)).extracting(UpdateMark::initial).containsExactly(true, false);
            // Unveränderte Seiten schreiben keine Zeile.
            assertThat(harvester.changeEntries("wiki")).hasSize(rows);
        }
    }

    @Test
    void readingAPageIsRead() throws Exception {
        try (Harvester harvester = harvester()) {
            harvester.pages().page(harvester.caller(), "wiki", "102", false);

            allBy(harvester.changeEntries("wiki"), ChangeTrigger.READ);
            assertThat(harvester.changeEntries("wiki")).extracting(ChangeEntry::kind).contains(ChangeKind.NEW);
            assertThat(harvester.updateMarks("wiki")).isEmpty();
        }
    }

    @Test
    void acquiringIsSearchAndBackgroundFillingIsBackground() throws Exception {
        try (Harvester harvester = harvester()) {
            harvester.pages().acquire(harvester.caller(), new PageKey("wiki", "100"), false);
            allBy(harvester.changeEntries("wiki"), ChangeTrigger.SEARCH);
            int before = harvester.changeEntries("wiki").size();

            try {
                harvester.pages().acquire(harvester.caller(), new PageKey("wiki", "102"), true);
            } catch (IllegalStateException e) {
                // Die Hintergrundfüllung meldet, wenn sie live statt aus dem Cache lieferte; abgelegt ist trotzdem.
            }

            List<ChangeEntry> entries = harvester.changeEntries("wiki");
            allBy(entries.subList(before, entries.size()), ChangeTrigger.BACKGROUND);
        }
    }

    @Test
    void anExportIsExportAndSetsNoMark() throws Exception {
        try (Harvester harvester = harvester()) {
            JobStatus export = harvester.await(harvester.export("wiki", "DEV", List.of("markdown")).id(),
                    Duration.ofSeconds(30));
            assertThat(export.state()).isEqualTo(JobState.COMPLETED);

            allBy(harvester.changeEntries("wiki"), ChangeTrigger.EXPORT);
            Thread.sleep(200);
            assertThat(harvester.updateMarks("wiki")).isEmpty();
        }
    }
}
