package com.aresstack.spaceharvester.application.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceRestResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Confluence-Metadaten und Analytics als eigene Exporte aus der Quelle (Angelo 16.11.2026). */
class SourceQueryExportsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("desktop");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path out;

    private final FakeSource wiki = new FakeSource("wiki", true);
    private final FakeSource files = new FakeSource("ordner", false);
    private final Map<String, List<String>> pagesOfSpace = new LinkedHashMap<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(2, 10,
            Duration.ofHours(1)), CLOCK);
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(wiki, files);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return all().stream().filter(source -> source.id().equals(sourceId)).findFirst();
        }
    };
    private final SourceQueryExports exports = new SourceQueryExports(jobs, catalog, new SourceQueryExports.Scopes() {
        @Override
        public List<Selection.Root> outputs(AccessContext caller, Selection selection) {
            return selection.roots();
        }

        @Override
        public List<String> pages(AccessContext caller, Selection.Root root) {
            return pagesOfSpace.getOrDefault(root.spaceKey(), List.of());
        }
    }, new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW), CLOCK, "desktop");

    @Test
    void metadataOfEveryPageComesFromTheDocumentedEndpointsUnchangedWithPagedLists() throws Exception {
        pagesOfSpace.put("DEV", List.of("1", "2"));
        wiki.stub("/rest/api/content/1/history?expand=lastUpdated,previousVersion,contributors.publishers.users",
                "{\"createdBy\":{\"username\":\"autorin\"},\"contributors\":{\"publishers\":{\"users\":[]}}}");
        wiki.stub("/rest/experimental/content/1/version?start=0&limit=100",
                "{\"results\":[{\"number\":2},{\"number\":1}],\"size\":2,\"_links\":{}}");
        wiki.stub("/rest/api/content/1/child/comment?depth=all&expand=version,history.lastUpdated,"
                + "extensions.resolution,extensions.inlineProperties&start=0&limit=100",
                "{\"results\":[{\"id\":\"c1\"}],\"size\":1,\"_links\":{\"next\":\"/x?start=1\"}}");
        wiki.stub("/rest/api/content/1/child/comment?depth=all&expand=version,history.lastUpdated,"
                + "extensions.resolution,extensions.inlineProperties&start=1&limit=100",
                "{\"results\":[{\"id\":\"c2\"}],\"size\":1,\"_links\":{}}");
        wiki.stub("/rest/api/content/1/property?start=0&limit=100", "{\"results\":[],\"size\":0}");
        wiki.stub("/rest/api/content/1/restriction/byOperation?expand=read.restrictions.user,"
                + "read.restrictions.group,update.restrictions.user,update.restrictions.group",
                "{\"read\":{\"operation\":\"read\"}}");
        // Seite 2: nur die Historie antwortet, der Rest ist 404 bzw. gesperrt; die Seite zählt trotzdem als mit Daten.
        wiki.stub("/rest/api/content/2/history?expand=lastUpdated,previousVersion,contributors.publishers.users",
                "{\"createdBy\":{\"username\":\"b\"}}");
        wiki.fail("/rest/api/content/2/restriction", SourceAccessException.Reason.ACCESS_DENIED);

        JobStatus done = run(exports.startMetadata(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out));

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.type()).isEqualTo(JobType.METADATA_EXPORT);
        assertThat(done.progress().discovered()).isEqualTo(2);
        assertThat(done.progress().written()).isEqualTo(2);
        assertThat(done.incomplete()).isFalse();
        Path file = out.resolve(SourceQueryExports.fileName(JobType.METADATA_EXPORT, "DEV", null, done.id()));
        assertThat(file).exists();
        assertThat(file.getFileName().toString()).startsWith("confluence-metadaten-dev-").endsWith(".json");
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(file));
        assertThat(doc.get("schema").asString()).isEqualTo(SourceQueryExports.METADATA_SCHEMA);
        assertThat(doc.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(doc.get("endpoints").get("versions").asString()).contains("/rest/experimental/");
        JsonNode first = doc.get("pages").get(0);
        assertThat(first.get("pageId").asString()).isEqualTo("1");
        assertThat(first.get("history").get("status").asString()).isEqualTo("ok");
        assertThat(first.get("history").get("data").get("createdBy").get("username").asString()).isEqualTo("autorin");
        // Zwei Listenseiten der Kommentare ergeben ein Array, mit der Zahl der Anfragen.
        assertThat(first.get("comments").get("requests").asInt()).isEqualTo(2);
        assertThat(first.get("comments").get("data")).hasSize(2);
        assertThat(first.get("versions").get("data")).hasSize(2);
        assertThat(first.get("properties").get("data")).isEmpty();
        assertThat(first.get("restrictions").get("data").get("read").get("operation").asString()).isEqualTo("read");
        JsonNode second = doc.get("pages").get(1);
        assertThat(second.get("versions").get("status").asString()).isEqualTo("error");
        assertThat(second.get("versions").get("kind").asString()).isEqualTo("NOT_FOUND");
        assertThat(second.get("restrictions").get("kind").asString()).isEqualTo("ACCESS_DENIED");
        assertThat(doc.get("summary").get("withData").asInt()).isEqualTo(2);
        // Kein zusätzliches expand im Seitenabruf: der Snapshot wird nie geholt.
        assertThat(wiki.requests).noneMatch(path -> path.contains("body.storage"));
    }

    @Test
    void analyticsSumsViewsPerPageAndNullMeansNotCounted() throws Exception {
        pagesOfSpace.put("DEV", List.of("1", "2"));
        Instant to = CLOCK.instant();
        String from = to.minus(Duration.ofDays(AnalyticsQueries.DEFAULT_DAYS)).toString();
        String period = "fromDate=" + enc(from) + "&toDate=" + enc(to.toString()) + "&period=day&type=total&timezone=UTC";
        wiki.stub(AnalyticsQueries.ROOT + "space/activityByDate?spaceKey=DEV&" + period,
                "{\"activityByDate\":[{\"date\":\"2026-11-15\",\"views\":5}]}");
        wiki.stub(AnalyticsQueries.ROOT + "content/viewsByDate?contentId=1&contentType=page&" + period,
                "{\"viewsByDate\":[{\"date\":\"2026-11-14\",\"views\":3},{\"date\":\"2026-11-15\",\"views\":4}]}");
        wiki.stub(AnalyticsQueries.ROOT + "content/viewsByDate?contentId=2&contentType=page&" + period,
                "{\"message\":\"no data\"}");

        JobStatus done = run(exports.startAnalytics(CALLER, Selection.of(Selection.Root.subtree("wiki", "DEV", "1")),
                out, AnalyticsQueries.DEFAULT));

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.rootPageId()).isEqualTo("1");
        Path file = out.resolve(SourceQueryExports.fileName(JobType.ANALYTICS_EXPORT, "DEV", "1", done.id()));
        assertThat(file.getFileName().toString()).startsWith("confluence-analytics-dev-1-");
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(file));
        assertThat(doc.get("schema").asString()).isEqualTo(SourceQueryExports.ANALYTICS_SCHEMA);
        assertThat(doc.get("period").get("days").asInt()).isEqualTo(AnalyticsQueries.DEFAULT_DAYS);
        assertThat(doc.get("queries").get("page").get("viewsByDate").asString()).contains("{pageId}");
        assertThat(doc.get("space").get("spaceViewsByDate").get("status").asString()).isEqualTo("ok");
        JsonNode first = doc.get("pages").get(0);
        assertThat(first.get("queries").get("viewsByDate").get("data").get("viewsByDate")).hasSize(2);
        assertThat(first.get("summary").get("views").asLong()).isEqualTo(7);
        assertThat(doc.get("pages").get(1).get("summary").get("views").isNull()).isTrue();
    }

    @Test
    void analyticsFailsOnlyItselfWhenThePluginAnswersNotFoundOnTheFirstPage() {
        pagesOfSpace.put("DEV", List.of("1", "2", "3"));

        JobStatus done = run(exports.startAnalytics(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out,
                AnalyticsQueries.DEFAULT));

        assertThat(done.state()).isEqualTo(JobState.FAILED);
        assertThat(done.error().code()).isEqualTo("ANALYTICS_UNAVAILABLE");
        assertThat(done.error().message()).contains("NOT_FOUND").contains(AnalyticsQueries.ROOT);
        // Nach der ersten Seite ist Schluss: keine Abfrage für die Seiten 2 und 3.
        assertThat(wiki.requests).noneMatch(path -> path.contains("contentId=2"));
        assertThat(list(out)).isEmpty();
    }

    @Test
    void anUnreachableSourceFailsTheJobInsteadOfRecordingEveryPage() {
        pagesOfSpace.put("DEV", List.of("1", "2"));
        wiki.fail("/rest/api/content/1/history", SourceAccessException.Reason.UNAVAILABLE);

        JobStatus done = run(exports.startMetadata(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out));

        assertThat(done.state()).isEqualTo(JobState.FAILED);
        assertThat(done.error().code()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(list(out)).isEmpty();
    }

    @Test
    void sourcesWithoutRestQueriesGetNoJobAndAreNotSupported() {
        assertThat(exports.supports("wiki")).isTrue();
        assertThat(exports.supports("ordner")).isFalse();
        assertThat(exports.supports("fremd")).isFalse();
        assertThatThrownBy(() -> exports.startMetadata(CALLER, Selection.of(Selection.Root.space("ordner", "A")), out))
                .isInstanceOf(SourceAccessException.class)
                .hasMessageContaining("REST-Abfragen");
        assertThat(jobs.list("desktop", JobType.METADATA_EXPORT)).isEmpty();
    }

    @Test
    void pagesWithoutAnyAnswerMakeTheJobIncompleteAndFileNamesAreSlugs() throws Exception {
        pagesOfSpace.put("DEV", List.of("1"));
        JobStatus done = run(exports.startMetadata(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out));
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.incomplete()).as("alle Abschnitte 404: Seite ohne Antwort").isTrue();
        assertThat(done.progress().failed()).isEqualTo(1);
        assertThat(list(out)).hasSize(1);
        assertThat(SourceQueryExports.fileName(JobType.METADATA_EXPORT, "Mein Space!", "12", "abcdefghijk"))
                .isEqualTo("confluence-metadaten-mein-space-12-abcdefgh.json");
    }

    private JobStatus run(List<JobStatus> started) {
        assertThat(started).hasSize(1);
        return jobs.status(started.get(0).id());
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<Path> list(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Eine Quelle, die REST-Abfragen aus einer Tabelle beantwortet; alles andere ist {@code NOT_FOUND}. */
    private static final class FakeSource implements HarvestSource {

        private final String id;
        private final boolean rest;
        private final Map<String, String> stubs = new LinkedHashMap<>();
        private final Map<String, SourceAccessException.Reason> failures = new LinkedHashMap<>();
        final List<String> requests = new ArrayList<>();

        FakeSource(String id, boolean rest) {
            this.id = id;
            this.rest = rest;
        }

        void stub(String pathAndQuery, String json) {
            stubs.put(pathAndQuery, json);
        }

        void fail(String pathPrefix, SourceAccessException.Reason reason) {
            failures.put(pathPrefix, reason);
        }

        @Override
        public boolean restQueries() {
            return rest;
        }

        @Override
        public SourceRestResponse restGet(String pathAndQuery) {
            if (!rest) {
                return HarvestSource.super.restGet(pathAndQuery);
            }
            requests.add(pathAndQuery);
            for (Map.Entry<String, SourceAccessException.Reason> failure : failures.entrySet()) {
                if (pathAndQuery.startsWith(failure.getKey())) {
                    throw new SourceAccessException(id, failure.getValue(), "Quelle: " + failure.getValue(), null);
                }
            }
            String json = stubs.get(pathAndQuery);
            if (json == null) {
                throw new SourceAccessException(id, SourceAccessException.Reason.NOT_FOUND, "404 " + pathAndQuery,
                        null);
            }
            return new SourceRestResponse("application/json;charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String type() {
            return rest ? "confluence" : "files";
        }

        @Override
        public String title() {
            return id;
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            throw new SourceAccessException(id, SourceAccessException.Reason.NOT_FOUND, pageId, null);
        }
    }
}
