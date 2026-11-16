package com.aresstack.spaceharvester.application.changelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ADR 0030, Abschnitt 3 und 4 (CL2): Updates, Bericht und rohe Zeilen, mit den Leserechten je Seite. */
class ChangeLogTest {

    private static final Instant T0 = Instant.parse("2026-11-01T08:00:00Z");
    private static final AccessContext CALLER = AccessContext.technicalClient("client:a");

    private final List<ChangeEntry> entries = new ArrayList<>();
    private final List<UpdateMark> marks = new ArrayList<>();
    private final Map<PageKey, PageHeader> headers = new HashMap<>();
    private final Set<String> denied = new HashSet<>();
    private final Set<String> deniedSpaces = new HashSet<>();
    /** Seiten, deren Inhalt der Aufrufer nicht lesen darf (Kopfdaten schon). */
    private final Set<String> deniedContent = new HashSet<>();
    private final List<ContentChange> diffs = new ArrayList<>();
    private Consumer<String> recorded = ignored -> {
    };
    private final ChangeLogStore store = new ChangeLogStore() {
        @Override
        public List<ChangeEntry> changeEntries(String sourceId) {
            return entries.stream().filter(entry -> entry.sourceId().equals(sourceId)).toList();
        }

        @Override
        public void changeLogRecorded(Consumer<String> listener) {
            recorded = listener;
        }

        @Override
        public List<UpdateMark> marks(String sourceId) {
            return marks.stream().filter(mark -> mark.sourceId().equals(sourceId)).toList();
        }

        @Override
        public void mark(UpdateMark mark) {
            marks.add(mark);
        }

        @Override
        public List<ContentChange> contentChanges(PageKey key, Instant after, int limit) {
            return diffs.stream().filter(diff -> diff.sourceId().equals(key.sourceId())
                            && diff.pageId().equals(key.pageId()) && (after == null || diff.at().isAfter(after)))
                    .sorted(java.util.Comparator.comparing(ContentChange::at)).limit(limit).toList();
        }
    };
    /** Die Richtlinie hält einzelne Seiten zurück; alles andere ist frei. */
    private final AccessGuard access = new AccessGuard((context, scope, kind) ->
            scope.pageId() != null && (denied.contains(scope.pageId()) || deniedSpaces.contains(scope.spaceKey())
                    || kind == com.aresstack.spaceharvester.application.access.AccessKind.CONTENT
                    && deniedContent.contains(scope.pageId()))
                    ? AccessDecision.DENY : AccessDecision.ALLOW);
    private final ChangeLog log = new ChangeLog(store, access, key -> Optional.ofNullable(headers.get(key)),
            () -> List.of("wiki", "other"));

    @AfterEach
    void close() {
        log.close();
    }

    private static Instant at(long minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    private void row(long minutes, String pageId, ChangeKind kind, ChangeTrigger trigger, String oldTitle,
                     String newTitle, String oldVersion, String newVersion, boolean pending) {
        entries.add(new ChangeEntry("wiki", "DEV", pageId, at(minutes), kind, trigger, oldVersion, newVersion,
                oldTitle, newTitle, null, null, oldTitle == null ? null : "DEV", newTitle == null ? null : "DEV",
                null, pending));
    }

    private void mark(long minutes, boolean initial) {
        marks.add(new UpdateMark("wiki", at(minutes), "client:a", List.of("DEV"), 0, initial));
    }

    private void header(String pageId, String parentId, String title) {
        headers.put(new PageKey("wiki", pageId), PageHeader.of("wiki", new PageMetadata(pageId, "res:" + pageId, title,
                "DEV", parentId, "1", T0, URI.create("https://wiki.example.org/" + pageId), PageMetadata.sorted(Map.of())),
                T0));
    }

    /** Erstbefüllung bis Minute 10, erstes Update bis Minute 30, danach offen. */
    private void twoUpdatesAndAnOpenOne() {
        row(1, "100", ChangeKind.NEW, ChangeTrigger.MANUAL, null, "Start", null, "1", false);
        row(1, "101", ChangeKind.NEW, ChangeTrigger.MANUAL, null, "Betrieb", null, "1", false);
        mark(10, true);
        row(12, "100", ChangeKind.CONTENT_CHANGED, ChangeTrigger.BACKGROUND, "Start", "Start", "1", "2", true);
        row(14, "100", ChangeKind.RELOADED, ChangeTrigger.SEARCH, "Start", "Start", "2", "2", false);
        row(20, "100", ChangeKind.CONTENT_CHANGED, ChangeTrigger.MANUAL, "Start", "Start", "2", "3", false);
        row(20, "100", ChangeKind.RENAMED, ChangeTrigger.MANUAL, "Start", "Startseite", "2", "3", false);
        row(21, "102", ChangeKind.NEW, ChangeTrigger.MANUAL, null, "Neu", null, "1", true);
        row(22, "103", ChangeKind.NEW, ChangeTrigger.MANUAL, null, "Kurzlebig", null, "1", false);
        row(23, "103", ChangeKind.REMOVED, ChangeTrigger.MANUAL, "Kurzlebig", null, "1", null, false);
        row(24, "101", ChangeKind.WITHHELD, ChangeTrigger.MANUAL, "Betrieb", null, "1", null, false);
        mark(30, false);
        row(40, "102", ChangeKind.LOADED, ChangeTrigger.READ, "Neu", "Neu", "1", "1", false);
        row(41, "102", ChangeKind.LABELS_CHANGED, ChangeTrigger.EVENT, "Neu", "Neu", "1", "1", false);
    }

    @Test
    void updatesRunFromMarkToMarkNewestFirstWithTheOpenOneInFront() {
        twoUpdatesAndAnOpenOne();

        List<ChangeUpdate> updates = log.updates(CALLER, "wiki");

        assertThat(updates).extracting(ChangeUpdate::open).containsExactly(true, false, false);
        assertThat(updates).extracting(ChangeUpdate::initial).containsExactly(false, false, true);
        ChangeUpdate first = updates.get(1);
        assertThat(first.from()).isEqualTo(at(10));
        assertThat(first.to()).isEqualTo(at(30));
        assertThat(first.mark()).isEqualTo(marks.get(1));
        assertThat(first.kinds()).containsExactlyInAnyOrderEntriesOf(Map.of(ChangeKind.CONTENT_CHANGED, 1,
                ChangeKind.NEW, 1, ChangeKind.WITHHELD, 1));
        assertThat(first.spaces()).containsExactly(Map.entry("DEV", 3));
        assertThat(first.pages()).isEqualTo(3);
        assertThat(first.reloaded()).isEqualTo(1);
        assertThat(updates.get(0).id()).isEqualTo(ChangeUpdate.OPEN);
        assertThat(updates.get(0).loaded()).isEqualTo(1);
        assertThat(updates.get(2).pages()).isEqualTo(2);
    }

    @Test
    void theReportMergesChangesOfAPageAndLeavesOutPagesNewAndGoneInTheSameUpdate() {
        twoUpdatesAndAnOpenOne();
        header("100", null, "Startseite");
        header("102", "100", "Neu");
        String id = log.updates(CALLER, "wiki").get(1).id();

        ChangeReport report = log.report(CALLER, "wiki", id);

        assertThat(report.pages()).extracting(ReportedPage::pageId).containsExactly("102", "100", "101");
        ReportedPage start = report.pages().get(1);
        assertThat(start.kind()).isEqualTo(ChangeKind.CONTENT_CHANGED);
        assertThat(start.kinds()).containsExactly(ChangeKind.CONTENT_CHANGED, ChangeKind.RENAMED);
        assertThat(start.oldVersion()).isEqualTo("1");
        assertThat(start.newVersion()).isEqualTo("3");
        assertThat(start.oldTitle()).isEqualTo("Start");
        assertThat(start.title()).isEqualTo("Startseite");
        assertThat(start.contentPending()).isFalse();
        assertThat(start.url()).isEqualTo(URI.create("https://wiki.example.org/100"));
        ReportedPage created = report.pages().getFirst();
        assertThat(created.kind()).isEqualTo(ChangeKind.NEW);
        assertThat(created.contentPending()).isTrue();
        assertThat(created.path()).containsExactly("Startseite");
        assertThat(report.pages().get(2).kind()).isEqualTo(ChangeKind.WITHHELD);
        assertThat(report.byKind()).containsOnlyKeys(ChangeKind.NEW, ChangeKind.CONTENT_CHANGED,
                ChangeKind.WITHHELD);
    }

    @Test
    void theInitialLoadHasCountersButNoPageList() {
        twoUpdatesAndAnOpenOne();
        String id = log.updates(CALLER, "wiki").get(2).id();

        ChangeReport report = log.report(CALLER, "wiki", id);

        assertThat(report.update().initial()).isTrue();
        assertThat(report.update().kinds()).containsExactly(Map.entry(ChangeKind.NEW, 2));
        assertThat(report.pages()).isEmpty();
    }

    @Test
    void theOpenUpdateIsReportedButAnUnknownIdIsNot() {
        twoUpdatesAndAnOpenOne();

        assertThat(log.report(CALLER, "wiki", ChangeUpdate.OPEN).pages()).extracting(ReportedPage::kind)
                .containsExactly(ChangeKind.LABELS_CHANGED);
        assertThatThrownBy(() -> log.report(CALLER, "wiki", "123")).isInstanceOf(UnknownUpdateException.class);
        assertThatThrownBy(() -> log.updates(CALLER, "nirgends")).isInstanceOf(UnknownSourceException.class);
    }

    @Test
    void aWithheldPageIsMissingFromRowsReportPathAndCounters() {
        twoUpdatesAndAnOpenOne();
        header("100", null, "Startseite");
        header("102", "100", "Neu");
        denied.add("100");

        List<ChangeUpdate> updates = log.updates(CALLER, "wiki");
        ChangeReport report = log.report(CALLER, "wiki", updates.get(1).id());
        ChangeEntries rows = log.entries(CALLER, ChangeFilter.all(null, 100));

        assertThat(updates.get(1).kinds()).doesNotContainKey(ChangeKind.CONTENT_CHANGED);
        assertThat(updates.get(1).pages()).isEqualTo(2);
        assertThat(updates.get(1).reloaded()).isZero();
        assertThat(report.pages()).extracting(ReportedPage::pageId).containsExactly("102", "101");
        // Auch als Vorfahre bleibt die zurückgehaltene Seite unsichtbar.
        assertThat(report.pages().getFirst().path()).isEmpty();
        assertThat(rows.entries()).extracting(ChangeEntry::pageId).doesNotContain("100");
        assertThat(rows.total()).isEqualTo(entries.size() - 5);
        assertThat(log.entries(CALLER, new ChangeFilter("wiki", "100", Set.of(), Set.of(), null, null, null, 0, 10))
                .entries()).isEmpty();
    }

    @Test
    void rawRowsAreSearchedByTitlePageKindTriggerAndPeriodNewestFirstPageByPage() {
        twoUpdatesAndAnOpenOne();

        assertThat(log.entries(CALLER, new ChangeFilter("wiki", "start", Set.of(), Set.of(), null, null, null, 0,
                10)).entries()).extracting(ChangeEntry::pageId).containsOnly("100");
        assertThat(log.entries(CALLER, new ChangeFilter(null, "102", Set.of(), Set.of(), null, null, null, 0, 10))
                .total()).isEqualTo(3);
        assertThat(log.entries(CALLER, new ChangeFilter(null, null, Set.of(ChangeKind.NEW),
                Set.of(ChangeTrigger.MANUAL), "DEV", at(20), at(30), 0, 10)).entries())
                .extracting(ChangeEntry::pageId).containsExactly("103", "102");

        ChangeEntries first = log.entries(CALLER, new ChangeFilter("wiki", null, Set.of(), Set.of(), null, null,
                null, 0, 2));
        ChangeEntries second = log.entries(CALLER, new ChangeFilter("wiki", null, Set.of(), Set.of(), null, null,
                null, 2, 2));
        assertThat(first.total()).isEqualTo(entries.size());
        assertThat(first.entries()).extracting(ChangeEntry::at).containsExactly(at(41), at(40));
        assertThat(second.entries()).extracting(ChangeEntry::at).containsExactly(at(24), at(23));
    }

    @Test
    void newRowsAndMarksAreReportedToSubscribersOfTheSource() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        List<String> all = new CopyOnWriteArrayList<>();
        log.subscribe(CALLER, "wiki", seen::add);
        log.subscribe(CALLER, null, all::add);

        recorded.accept("wiki");
        recorded.accept("other");

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (all.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(seen).containsExactly("wiki");
        assertThat(all).containsExactlyInAnyOrder("wiki", "other");
    }

    /** Seite 300 wandert aus DEV nach OPS, Seite 301 aus OPS nach DEV; der Aufrufer darf OPS nicht sehen. */
    private void movesBetweenSpaces() {
        mark(10, true);
        for (ChangeKind kind : List.of(ChangeKind.RENAMED, ChangeKind.MOVED_OUT)) {
            entries.add(new ChangeEntry("wiki", "OPS", "300", at(50), kind, ChangeTrigger.MANUAL, "4", "4", "Alt",
                    "Geheim neu", "100", "900", "DEV", "OPS", null, false));
            entries.add(new ChangeEntry("wiki", "DEV", "301", at(51), kind, ChangeTrigger.MANUAL, "7", "7",
                    "Geheim alt", "Offen", "901", "100", "OPS", "DEV", null, false));
        }
        deniedSpaces.add("OPS");
    }

    @Test
    void aPageMovedIntoAHiddenSpaceShowsOnlyThatItLeftWithoutNewTitleOrSpace() {
        movesBetweenSpaces();

        ChangeEntries rows = log.entries(CALLER, new ChangeFilter("wiki", "300", Set.of(), Set.of(), null, null,
                null, 0, 10));
        ReportedPage page = log.report(CALLER, "wiki", ChangeUpdate.OPEN).pages().stream()
                .filter(candidate -> candidate.pageId().equals("300")).findFirst().orElseThrow();

        assertThat(rows.entries()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(ChangeKind.MOVED_OUT);
            assertThat(row.spaceKey()).isEqualTo("DEV");
            assertThat(row.oldTitle()).isEqualTo("Alt");
            assertThat(row.newTitle()).isNull();
            assertThat(row.newSpaceKey()).isNull();
            assertThat(row.newParentId()).isNull();
        });
        assertThat(page.kind()).isEqualTo(ChangeKind.MOVED_OUT);
        assertThat(page.kinds()).containsExactly(ChangeKind.MOVED_OUT);
        assertThat(page.title()).isEqualTo("Alt");
        assertThat(page.spaceKey()).isEqualTo("DEV");
        assertThat(page.newTitle()).isNull();
        assertThat(page.newSpaceKey()).isNull();
        assertThat(log.entries(CALLER, new ChangeFilter("wiki", "neu", Set.of(), Set.of(), null, null, null, 0, 10))
                .entries()).isEmpty();
        assertThat(log.entries(CALLER, new ChangeFilter("wiki", null, Set.of(), Set.of(), "OPS", null, null, 0, 10))
                .entries()).isEmpty();
    }

    @Test
    void aPageMovedOutOfAHiddenSpaceHidesItsOldTitleAndSpace() {
        movesBetweenSpaces();

        ChangeEntries rows = log.entries(CALLER, new ChangeFilter("wiki", "301", Set.of(), Set.of(), null, null,
                null, 0, 10));
        ReportedPage page = log.report(CALLER, "wiki", ChangeUpdate.OPEN).pages().stream()
                .filter(candidate -> candidate.pageId().equals("301")).findFirst().orElseThrow();

        assertThat(rows.entries()).hasSize(2).allSatisfy(row -> {
            assertThat(row.newTitle()).isEqualTo("Offen");
            assertThat(row.oldTitle()).isNull();
            assertThat(row.oldSpaceKey()).isNull();
            assertThat(row.oldParentId()).isNull();
        });
        assertThat(page.title()).isEqualTo("Offen");
        assertThat(page.oldTitle()).isNull();
        assertThat(page.oldSpaceKey()).isNull();
        assertThat(page.oldParentId()).isNull();
        // Weder der alte Titel noch der alte Space führen zur Zeile.
        assertThat(log.entries(CALLER, new ChangeFilter("wiki", "geheim", Set.of(), Set.of(), null, null, null, 0,
                10)).entries()).isEmpty();
        assertThat(log.entries(CALLER, new ChangeFilter("wiki", null, Set.of(), Set.of(), "OPS", null, null, 0, 10))
                .entries()).isEmpty();
        assertThat(log.updates(CALLER, "wiki").getFirst().spaces()).containsExactly(Map.entry("DEV", 2));
    }

    // --- Unterschied je Seite (CL5) --------------------------------------------------------------------------------

    private void diff(long minutes, String pageId, String from, String to, ParagraphChange... paragraphs) {
        diffs.add(new ContentChange("wiki", pageId, from, to, at(minutes), List.of(paragraphs), false));
    }

    private ReportedPage reported(String update, String pageId) {
        return log.report(CALLER, "wiki", update).pages().stream().filter(page -> page.pageId().equals(pageId))
                .findFirst().orElseThrow();
    }

    @Test
    void aChangedPageCarriesEveryStepFromItsOldToItsNewVersion() {
        twoUpdatesAndAnOpenOne();
        diff(5, "100", "0", "1", new ParagraphChange(ParagraphChange.Kind.ADDED, null, "vorher"));
        diff(14, "100", "1", "2", new ParagraphChange(ParagraphChange.Kind.CHANGED, "Alt", "Mittel"));
        diff(20, "100", "2", "3", new ParagraphChange(ParagraphChange.Kind.CHANGED, "Mittel", "Neu"));
        String id = log.updates(CALLER, "wiki").get(1).id();

        ContentDiff diff = reported(id, "100").diff();

        assertThat(diff.status()).isEqualTo(ContentDiff.Status.AVAILABLE);
        assertThat(diff.steps()).extracting(ContentChange::fromVersion, ContentChange::toVersion)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "2"),
                        org.assertj.core.groups.Tuple.tuple("2", "3"));
        assertThat(diff.steps().getLast().paragraphs())
                .containsExactly(new ParagraphChange(ParagraphChange.Kind.CHANGED, "Mittel", "Neu"));
    }

    @Test
    void pagesWithoutAContentChangeHaveNoDiff() {
        twoUpdatesAndAnOpenOne();
        String id = log.updates(CALLER, "wiki").get(1).id();

        assertThat(reported(id, "102").diff()).isNull();
        assertThat(reported(id, "101").diff()).isNull();
    }

    @Test
    void withoutTheStepToTheNewVersionTheOldContentIsMissingAndNothingIsGuessed() {
        twoUpdatesAndAnOpenOne();
        diff(14, "100", "1", "2", new ParagraphChange(ParagraphChange.Kind.CHANGED, "Alt", "Mittel"));
        String id = log.updates(CALLER, "wiki").get(1).id();

        ContentDiff diff = reported(id, "100").diff();

        assertThat(diff.status()).isEqualTo(ContentDiff.Status.PREVIOUS_MISSING);
        assertThat(diff.steps()).isEmpty();
    }

    @Test
    void aNewVersionNotYetLoadedIsPendingWithTheStepsSoFar() {
        twoUpdatesAndAnOpenOne();
        row(35, "100", ChangeKind.CONTENT_CHANGED, ChangeTrigger.SCHEDULE, "Startseite", "Startseite", "3", "4",
                true);

        ContentDiff diff = reported(ChangeUpdate.OPEN, "100").diff();

        assertThat(diff.status()).isEqualTo(ContentDiff.Status.CONTENT_PENDING);
        assertThat(diff.steps()).isEmpty();
    }

    @Test
    void aStepLoadedAfterTheUpdateEndedStillCompletesIt() {
        twoUpdatesAndAnOpenOne();
        diff(14, "100", "1", "2");
        diff(20, "100", "2", "3");
        String id = log.updates(CALLER, "wiki").get(1).id();
        diffs.removeLast();
        diff(32, "100", "2", "3", new ParagraphChange(ParagraphChange.Kind.REMOVED, "Weg", null));

        ContentDiff diff = reported(id, "100").diff();

        assertThat(diff.status()).isEqualTo(ContentDiff.Status.AVAILABLE);
        assertThat(diff.steps()).extracting(ContentChange::toVersion).containsExactly("2", "3");
    }

    @Test
    void withoutContentRightsTheDiffIsNotPermittedButThePageStays() {
        twoUpdatesAndAnOpenOne();
        diff(20, "100", "1", "3", new ParagraphChange(ParagraphChange.Kind.CHANGED, "geheim", "geheimer"));
        deniedContent.add("100");
        String id = log.updates(CALLER, "wiki").get(1).id();

        ReportedPage page = reported(id, "100");

        assertThat(page.kind()).isEqualTo(ChangeKind.CONTENT_CHANGED);
        assertThat(page.diff().status()).isEqualTo(ContentDiff.Status.NOT_PERMITTED);
        assertThat(page.diff().steps()).isEmpty();
    }
}
