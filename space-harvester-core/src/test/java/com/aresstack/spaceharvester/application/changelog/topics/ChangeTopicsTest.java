package com.aresstack.spaceharvester.application.changelog.topics;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.changelog.ChangeKind;
import com.aresstack.spaceharvester.application.changelog.ChangeReport;
import com.aresstack.spaceharvester.application.changelog.ChangeUpdate;
import com.aresstack.spaceharvester.application.changelog.ContentChange;
import com.aresstack.spaceharvester.application.changelog.ContentDiff;
import com.aresstack.spaceharvester.application.changelog.ParagraphChange;
import com.aresstack.spaceharvester.application.changelog.ReportedPage;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Abfrage der Themen über dem Änderungsprotokoll: Ebene, Rückfall ohne Dienst, Ablage. Erfundene Beispieldaten. */
class ChangeTopicsTest {

    private static final Instant MONDAY = Instant.parse("2026-11-16T08:00:00Z");
    private static final SentenceSegmenter SENTENCES = new SentenceSegmenter() {
        @Override
        public List<String> sentences(String paragraph) {
            return Arrays.asList(paragraph.split("(?<=\\.)\\s+"));
        }

        @Override
        public String id() {
            return "test";
        }
    };

    private final List<Boolean> diffsRequested = new ArrayList<>();

    @Test
    void theSpaceLevelSeesOnlyItsPagesAndWithoutServiceStructureGroups() {
        ChangeTopics.Reports reports = (caller, sourceId, update, withDiffs) -> {
            diffsRequested.add(withDiffs);
            return report(page("1", "DEV", "Archivierung von Akten ist neu geregelt."),
                    page("2", "DEV", "Die Archivierung beginnt nach der Frist."),
                    page("3", "OPS", "Der Speiseplan der Kantine wechselt."));
        };
        ChangeTopics topics = ChangeTopics.structureOnly(reports, key -> true, SENTENCES);

        TopicSet dev = topics.topics(caller(), "wiki", "1", Optional.of("DEV"), job());
        TopicSet all = topics.topics(caller(), "wiki", "1", Optional.empty(), job());

        assertThat(diffsRequested).containsOnly(true);
        assertThat(dev.spaceKey()).contains("DEV");
        assertThat(dev.topics()).allSatisfy(topic -> assertThat(topic.spaces()).containsExactly("DEV"));
        assertThat(dev.units()).hasSize(2);
        assertThat(all.units()).hasSize(3);
        assertThat(all.missingSignals()).contains(Signal.VECTOR);
        assertThat(all.signals()).doesNotContain(Signal.VECTOR);
    }

    @Test
    void theSameFilteredReportIsServedFromTheCacheAnotherOneIsNot() {
        List<ReportedPage> pages = new ArrayList<>(List.of(page("1", "DEV", "Neue Regel zur Löschung.")));
        ChangeTopics topics = ChangeTopics.structureOnly((caller, sourceId, update, withDiffs) -> report(
                pages.toArray(ReportedPage[]::new)), key -> true, SENTENCES);

        TopicSet first = topics.topics(caller(), "wiki", "1", Optional.empty(), job());
        TopicSet again = topics.topics(caller(), "wiki", "1", Optional.empty(), job());
        pages.add(page("2", "DEV", "Weitere Regel zur Archivierung."));
        TopicSet changed = topics.topics(caller(), "wiki", "1", Optional.empty(), job());

        assertThat(again).isSameAs(first);
        assertThat(changed).isNotSameAs(first);
        assertThat(changed.units()).hasSize(2);
    }

    @Test
    void pagesWithoutAiReleaseGiveNoTopic() {
        ChangeTopics topics = ChangeTopics.structureOnly((caller, sourceId, update, withDiffs) -> report(
                page("1", "DEV", "Freigegebener Absatz."), page("2", "DEV", "Nicht freigegebener Absatz.")),
                key -> key.pageId().equals("1"), SENTENCES);

        TopicSet set = topics.topics(caller(), "wiki", "1", Optional.empty(), job());

        assertThat(set.notReleased()).isEqualTo(1);
        assertThat(set.topics()).flatExtracting(ChangeTopic::pages).extracting(page -> page.pageId())
                .containsOnly("1");
    }

    private static ReportedPage page(String pageId, String space, String added) {
        ContentChange step = new ContentChange("wiki", pageId, "1", "2", MONDAY,
                List.of(new ParagraphChange(ParagraphChange.Kind.ADDED, null, added)), false);
        return new ReportedPage("wiki", space, pageId, ChangeKind.CONTENT_CHANGED,
                Set.of(ChangeKind.CONTENT_CHANGED), "Seite " + pageId, List.of("Handbuch"), null, "1", "2",
                "Seite " + pageId, "Seite " + pageId, "0", "0", space, space, false, MONDAY,
                new ContentDiff(ContentDiff.Status.AVAILABLE, List.of(step)));
    }

    private static ChangeReport report(ReportedPage... pages) {
        ChangeUpdate update = new ChangeUpdate("1", "wiki", MONDAY, MONDAY, null, false, Map.of(), Map.of(),
                pages.length, 0, 0, false);
        return new ChangeReport(update, List.of(pages));
    }

    private static AccessContext caller() {
        return AccessContext.technicalClient("test");
    }

    private static JobContext job() {
        return new JobContext() {
            @Override
            public String jobId() {
                return "job";
            }

            @Override
            public void checkpoint() {
            }

            @Override
            public boolean cancelRequested() {
                return false;
            }

            @Override
            public void discovered(long pages) {
            }

            @Override
            public void processed(long pages) {
            }

            @Override
            public void written(long pages) {
            }

            @Override
            public void failed(long pages) {
            }
        };
    }
}
