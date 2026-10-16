package com.aresstack.spaceharvester.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Einzelprüfung für {@code ?refresh=true}: Inhalt nur bei neuer Revision, nie stiller Rückfall auf den Cache. */
class PageFreshnessTest {

    private final FakeSource source = new FakeSource().put("1", "DOC", null, "3", "api");
    private final List<String> fetched = new ArrayList<>();
    /** Was der Inhaltsabruf liefert, wenn es nicht die Seite der Quelle sein soll. */
    private PageMetadata fetchAnswer;
    private final PageFreshness freshness = new PageFreshness(source.queries(), (sourceId, pageId) -> {
        fetched.add(pageId);
        return snapshot(fetchAnswer != null ? fetchAnswer : source.pageMetadata(pageId));
    }, FakeSource.CLOCK);
    private final PageSnapshot cached = snapshot(source.pageMetadata("1"));

    private static PageSnapshot snapshot(PageMetadata page) {
        return PageSnapshot.of("wiki", page, new NativePayload(("{\"v\":" + page.version() + "}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-content/v1"),
                FakeSource.CLOCK.instant());
    }

    private PageFreshness.Refreshed check(Optional<PageHeader> header, Optional<PageSnapshot> snapshot) {
        return freshness.check("wiki", "1", header, snapshot);
    }

    @Test
    void anUnchangedPageIsCurrentWithoutFetchingContent() {
        PageFreshness.Refreshed refreshed = check(Optional.of(cached.header()), Optional.of(cached));

        assertThat(refreshed.outcome()).isEqualTo(PageFreshness.Outcome.CURRENT);
        assertThat(refreshed.cacheStale()).isFalse();
        assertThat(refreshed.snapshot()).isSameAs(cached);
        assertThat(refreshed.checkedAt()).isEqualTo(FakeSource.CLOCK.instant());
        assertThat(fetched).isEmpty();
    }

    @Test
    void newLabelsAtTheSameRevisionGiveLiveHeadersAndTheCachedPayload() {
        source.put("1", "DOC", null, "3", "api,veraltet");

        PageFreshness.Refreshed refreshed = check(Optional.of(cached.header()), Optional.of(cached));

        assertThat(refreshed.outcome()).isEqualTo(PageFreshness.Outcome.HEADER_CHANGED);
        assertThat(refreshed.cacheStale()).isTrue();
        assertThat(refreshed.snapshot()).isSameAs(cached);
        assertThat(refreshed.header().metadata().attributes()).containsEntry("confluence.labels", "api,veraltet");
        assertThat(fetched).isEmpty();
    }

    @Test
    void aNewRevisionOrAMissingCacheEntryFetchesTheContentLive() {
        source.put("1", "DOC", null, "4", "api");

        PageFreshness.Refreshed newer = check(Optional.of(cached.header()), Optional.of(cached));
        PageFreshness.Refreshed uncached = check(Optional.empty(), Optional.empty());

        assertThat(newer.outcome()).isEqualTo(PageFreshness.Outcome.CONTENT_CHANGED);
        assertThat(newer.snapshot().revision()).isEqualTo("4");
        assertThat(uncached.outcome()).isEqualTo(PageFreshness.Outcome.CONTENT_CHANGED);
        assertThat(fetched).containsExactly("1", "1");
    }

    @Test
    void aPageTheSourceNoLongerKnowsIsGone() {
        source.remove("1");

        PageFreshness.Refreshed refreshed = check(Optional.of(cached.header()), Optional.of(cached));

        assertThat(refreshed.outcome()).isEqualTo(PageFreshness.Outcome.GONE);
        assertThat(refreshed.snapshot()).isNull();
        assertThat(refreshed.header()).isNull();
    }

    @Test
    void sourceErrorsAreNeverReplacedByTheCache() {
        source.failSinglePage("1", SourceAccessException.Reason.UNAVAILABLE);

        assertThatThrownBy(() -> check(Optional.of(cached.header()), Optional.of(cached)))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.UNAVAILABLE));
    }

    @Test
    void aFetchForAnotherPageIsRejected() {
        source.put("1", "DOC", null, "4", "api");
        fetchAnswer = FakeSource.page("2", "DOC", null, "4", "api");

        assertThatThrownBy(() -> check(Optional.empty(), Optional.empty()))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.INVALID_RESPONSE));
    }

    @Test
    void withoutAFetcherNoCheckIsPossible() {
        PageFreshness without = new PageFreshness(source.queries(), null, FakeSource.CLOCK);

        assertThat(without.available()).isFalse();
        assertThatThrownBy(() -> without.check("wiki", "1", Optional.empty(), Optional.empty()))
                .isInstanceOf(SyncUnavailableException.class);
    }
}
