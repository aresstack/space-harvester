package com.aresstack.spaceharvester.application.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
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

        @Override
        public SourceQueryExports.PageName name(String sourceId, String pageId) {
            return "2".equals(pageId) ? new SourceQueryExports.PageName("Kaputte Seite",
                    "https://wiki.example/pages/2") : null;
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

    /** Die Zeitangaben so, wie die Analytics-Oberfläche sie sendet: Millisekunden, UTC, URL-kodiert. */
    private static String period(Instant to, int days) {
        java.time.format.DateTimeFormatter stamp = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
        return "fromDate=" + enc(stamp.format(to.minus(Duration.ofDays(days)))) + "&toDate=" + enc(stamp.format(to))
                + "&type=total&period=week&timezone=UTC&content=page%2Cblog";
    }

    private static String contentViews(String period, String token) {
        return AnalyticsQueries.ROOT + "space/paginated/contentViews?spaceKey=DEV&" + period
                + "&sortField=VIEWED_COUNT&sortOrder=DESC&limit=100" + (token == null ? "" : "&pageToken=" + token);
    }

    @Test
    void analyticsComesFromThePagedSpaceListAndNullMeansNotCounted() throws Exception {
        pagesOfSpace.put("DEV", List.of("1", "2", "3"));
        Instant to = CLOCK.instant();
        String period = period(to, AnalyticsQueries.DEFAULT_DAYS);
        wiki.stub(AnalyticsQueries.ROOT + "space/details?spaceKey=DEV", "{\"space\":{\"key\":\"DEV\"}}");
        wiki.stub(AnalyticsQueries.ROOT + "space/activityByDate?spaceKey=DEV&" + period,
                "{\"activityByDate\":{\"views\":[{\"date\":\"2026-11-15\",\"total\":5}]}}");
        wiki.stub(contentViews(period, null), "{\"contentViews\":[{\"id\":1,\"type\":\"page\",\"title\":\"Eins\","
                + "\"lastViewedAt\":\"2026-11-16T16:11:09.090Z\",\"usersViewed\":67,\"views\":1325}],"
                + "\"nextPageToken\":\"1\"}");
        wiki.stub(contentViews(period, "1"), "{\"contentViews\":[{\"id\":3,\"type\":\"page\",\"title\":\"Drei\","
                + "\"usersViewed\":2,\"views\":7}]}");

        JobStatus done = run(exports.startAnalytics(CALLER, Selection.of(Selection.Root.subtree("wiki", "DEV", "1")),
                out, AnalyticsQueries.DEFAULT));

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.incomplete()).isFalse();
        assertThat(done.rootPageId()).isEqualTo("1");
        Path file = out.resolve(SourceQueryExports.fileName(JobType.ANALYTICS_EXPORT, "DEV", "1", done.id()));
        assertThat(file.getFileName().toString()).startsWith("confluence-analytics-dev-1-");
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(file));
        assertThat(doc.get("schema").asString()).isEqualTo(SourceQueryExports.ANALYTICS_SCHEMA);
        assertThat(doc.get("period").get("days").asInt()).isEqualTo(AnalyticsQueries.DEFAULT_DAYS);
        assertThat(doc.get("queries").get("contentViews").asString()).contains("{spaceKey}");
        assertThat(doc.get("space").get("details").get("status").asString()).isEqualTo("ok");
        assertThat(doc.get("space").get("activityByDate").get("data").get("activityByDate").get("views")).hasSize(1);
        assertThat(doc.get("space").has("ok")).isFalse();
        JsonNode list = doc.get("contentViews");
        assertThat(list.get("status").asString()).isEqualTo("ok");
        assertThat(list.get("requests").asInt()).isEqualTo(2);
        assertThat(list.get("entries").asInt()).isEqualTo(2);
        assertThat(list.get("complete").asBoolean()).isTrue();
        JsonNode first = doc.get("pages").get(0);
        assertThat(first.get("content").get("title").asString()).isEqualTo("Eins");
        assertThat(first.get("summary").get("views").asLong()).isEqualTo(1325);
        assertThat(first.get("summary").get("lastViewedAt").asString()).isEqualTo("2026-11-16T16:11:09.090Z");
        JsonNode second = doc.get("pages").get(1);
        assertThat(second.get("content").isNull()).isTrue();
        assertThat(second.get("summary").get("views").isNull()).isTrue();
        assertThat(doc.get("pages").get(2).get("summary").get("views").asLong()).isEqualTo(7);
        assertThat(doc.get("summary").get("failed").asInt()).isZero();
        // Je Space wenige Anfragen, keine je Seite.
        assertThat(wiki.requests).hasSize(4);
    }

    /** Angelo 17.11.2026: Analytics über eine Quelle mit zwei Spaces ergibt je Space einen Auftrag und eine Datei. */
    @Test
    void analyticsOverTwoSpacesGivesOneJobAndOneFilePerSpace() throws Exception {
        pagesOfSpace.put("DEV", List.of("1"));
        pagesOfSpace.put("OPS", List.of("7"));
        String period = period(CLOCK.instant(), AnalyticsQueries.DEFAULT_DAYS);
        for (String key : List.of("DEV", "OPS")) {
            wiki.stub(AnalyticsQueries.ROOT + "space/details?spaceKey=" + key, "{\"space\":{\"key\":\"" + key + "\"}}");
            wiki.stub(AnalyticsQueries.ROOT + "space/activityByDate?spaceKey=" + key + "&" + period,
                    "{\"activityByDate\":{\"views\":[]}}");
            wiki.stub(contentViews(period, null).replace("spaceKey=DEV", "spaceKey=" + key),
                    "{\"contentViews\":[]}");
        }

        List<JobStatus> started = exports.startAnalytics(CALLER, new Selection(List.of(
                Selection.Root.space("wiki", "DEV"), Selection.Root.space("wiki", "OPS"))), out,
                AnalyticsQueries.DEFAULT);

        assertThat(started).hasSize(2).extracting(JobStatus::partitionKey).containsExactly("DEV", "OPS");
        for (JobStatus job : started) {
            JobStatus done = jobs.status(job.id());
            assertThat(done.state()).isEqualTo(JobState.COMPLETED);
            assertThat(out.resolve(SourceQueryExports.fileName(JobType.ANALYTICS_EXPORT, done.partitionKey(), null,
                    done.id()))).exists();
        }
        assertThat(list(out)).as("je Space eine eigene Datei").hasSize(2);
    }

    @Test
    void aListWhoseTokenIsIgnoredEndsIncompleteInsteadOfEndless() throws Exception {
        pagesOfSpace.put("DEV", List.of("1"));
        String period = period(CLOCK.instant(), AnalyticsQueries.DEFAULT_DAYS);
        String page = "{\"contentViews\":[{\"id\":1,\"views\":3}],\"nextPageToken\":\"x\"}";
        wiki.stub(contentViews(period, null), page);
        wiki.stub(contentViews(period, "x"), page);

        JobStatus done = run(exports.startAnalytics(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out,
                AnalyticsQueries.DEFAULT));

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.incomplete()).as("unvollständige Liste macht den Auftrag unvollständig").isTrue();
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(list(out).get(0)));
        assertThat(doc.get("contentViews").get("complete").asBoolean()).isFalse();
        assertThat(doc.get("contentViews").get("requests").asInt()).isEqualTo(2);
        assertThat(doc.get("pages").get(0).get("summary").get("views").asLong()).isEqualTo(3);
        // Die Space-Abfragen fehlten (404), nur die Liste antwortete: der Auftrag läuft trotzdem.
        assertThat(doc.get("space").get("details").get("status").asString()).isEqualTo("error");
    }

    @Test
    void theTokenParameterComesFromTheTemplateAndIsDroppedOnTheFirstPage() {
        Instant now = CLOCK.instant();
        AnalyticsQueries.Query renamed = new AnalyticsQueries.Query("x",
                "/rest/confanalytics/1.0/space/paginated/contentViews?spaceKey={spaceKey}&pageToken={nextPageToken}"
                        + "&limit=50");
        assertThat(renamed.path("DEV", now, now)).isEqualTo(
                "/rest/confanalytics/1.0/space/paginated/contentViews?spaceKey=DEV&limit=50");
        assertThat(renamed.path("DEV", now, now, "a/b")).endsWith("?spaceKey=DEV&pageToken=a%2Fb&limit=50");
        AnalyticsQueries.Query first = new AnalyticsQueries.Query("x", "/rest/x?cursor={nextPageToken}&a=1");
        assertThat(first.path("DEV", now, now)).isEqualTo("/rest/x?a=1");
        assertThat(first.path("DEV", now, now, "t")).isEqualTo("/rest/x?cursor=t&a=1");
        AnalyticsQueries.Query only = new AnalyticsQueries.Query("x", "/rest/x?cursor={nextPageToken}");
        assertThat(only.path("DEV", now, now)).isEqualTo("/rest/x");
        AnalyticsQueries.Query none = new AnalyticsQueries.Query("x", "/rest/x?a=1");
        assertThat(none.path("DEV", now, now, "t")).isEqualTo("/rest/x?a=1&pageToken=t");
        assertThat(AnalyticsQueries.DEFAULT.contentViews().pathTemplate()).endsWith("&pageToken={nextPageToken}");
    }

    @Test
    void aRepeatedTokenWithNewEntriesIsIncompleteTooAndEveryListPageIsACheckpoint() {
        String period = period(CLOCK.instant(), AnalyticsQueries.DEFAULT_DAYS);
        wiki.stub(contentViews(period, null), "{\"contentViews\":[{\"id\":1,\"views\":3}],\"nextPageToken\":\"x\"}");
        wiki.stub(contentViews(period, "x"), "{\"contentViews\":[{\"id\":2,\"views\":4}],\"nextPageToken\":\"x\"}");
        int[] checkpoints = {0};

        SourceQueryExports.ContentViews views = SourceQueryExports.contentViews(wiki, "DEV", AnalyticsQueries.DEFAULT,
                CLOCK.instant(), () -> checkpoints[0]++);

        assertThat(views.result().ok()).isTrue();
        assertThat(views.byId()).containsKeys("1", "2");
        assertThat(views.truncated()).isTrue();
        assertThat(views.requests()).isEqualTo(2);
        assertThat(checkpoints[0]).isEqualTo(2);
    }

    @Test
    void analyticsFailsOnlyItselfWhenThePluginAnswersNothing() {
        pagesOfSpace.put("DEV", List.of("1", "2", "3"));

        JobStatus done = run(exports.startAnalytics(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out,
                AnalyticsQueries.of(null, null, null, 90)));

        assertThat(done.state()).isEqualTo(JobState.FAILED);
        assertThat(done.error().code()).isEqualTo("ANALYTICS_UNAVAILABLE");
        assertThat(done.error().message()).contains("NOT_FOUND").contains(AnalyticsQueries.ROOT);
        assertThat(wiki.requests).hasSize(3).allMatch(path -> path.startsWith(AnalyticsQueries.ROOT));
        assertThat(wiki.requests.get(2)).contains(period(CLOCK.instant(), 90));
        assertThat(list(out)).isEmpty();
    }

    private static final String HISTORY = "/rest/api/content/{id}/history?expand=lastUpdated,previousVersion,"
            + "contributors.publishers.users";

    private void stubHistory(String pageId, String author) {
        wiki.stub(HISTORY.replace("{id}", pageId), "{\"createdBy\":{\"username\":\"" + author + "\"}}");
    }

    @Test
    void aPageWhoseSourceDoesNotAnswerIsDeferredRetriedAtTheEndAndOnlyThenCountsAsFailed() throws Exception {
        pagesOfSpace.put("DEV", List.of("1", "2", "3"));
        stubHistory("1", "a");
        stubHistory("3", "c");
        // Seite 2 antwortet mit HTTP 500 (UNAVAILABLE), die Nachbarn antworten: kein Verbindungsabbruch.
        wiki.fail("/rest/api/content/2/history", SourceAccessException.Reason.UNAVAILABLE);

        JobStatus done = run(exports.startMetadata(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out));

        assertThat(done.state()).as("der Auftrag scheitert nicht an einer Seite").isEqualTo(JobState.COMPLETED);
        assertThat(done.incomplete()).isTrue();
        assertThat(done.progress().written()).isEqualTo(2);
        assertThat(done.progress().failed()).isEqualTo(1);
        // Seite 2 wurde nach den anderen noch einmal gefragt: zweimal history, dazwischen Seite 3.
        List<String> histories = wiki.requests.stream().filter(path -> path.contains("/history")).toList();
        assertThat(histories).containsExactly(HISTORY.replace("{id}", "1"), HISTORY.replace("{id}", "2"),
                HISTORY.replace("{id}", "3"), HISTORY.replace("{id}", "2"));
        assertThat(done.lifecycle().actions()).as("↻ Nur Fehler neu versuchen ist möglich")
                .contains(JobAction.RETRY_FAILED);
        Path file = out.resolve(SourceQueryExports.fileName(JobType.METADATA_EXPORT, "DEV", null, done.id()));
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(file));
        assertThat(doc.get("pages")).hasSize(3);
        assertThat(doc.get("pages").get(1).get("pageId").asString()).isEqualTo("2");
        assertThat(doc.get("pages").get(1).get("history").get("kind").asString()).isEqualTo("UNAVAILABLE");
        assertThat(doc.get("summary").get("failed").asInt()).isEqualTo(1);
        assertThat(list(out)).as("die Teildatei ist nach dem Ende weg").hasSize(1);
        // Die Meldungen nennen die Seite mit Titel, Adresse und Fehler je Abfrage; niemand muss die Datei öffnen.
        List<JobEvent> events = jobs.events(done.id(), "desktop");
        JobEvent page = events.stream().filter(event -> "PAGE_FAILED".equals(event.code())).findFirst().orElseThrow();
        assertThat(page.message()).startsWith("Seite 2 „Kaputte Seite“ ohne Antwort:")
                .contains("\nhistory: UNAVAILABLE").contains("\nversions: NOT_FOUND");
        JobEvent all = events.stream().filter(event -> "PAGES_FAILED".equals(event.code())).findFirst().orElseThrow();
        assertThat(all.message()).contains("1 von 3 Seiten ohne Antwort: Seite 2 „Kaputte Seite“");
        assertThat(SourceQueryExports.httpStatus("Confluence antwortet mit HTTP 500 (nach 2 Wiederholungen)"))
                .isEqualTo(500);
        assertThat(SourceQueryExports.httpStatus("keine Antwort")).isNull();
    }

    @Test
    void retryingTheFailuresOfAFinishedExportAsksOnlyThePagesWithoutDataAndReplacesTheFile() throws Exception {
        pagesOfSpace.put("DEV", List.of("1", "2"));
        stubHistory("1", "a");
        wiki.fail("/rest/api/content/2/history", SourceAccessException.Reason.UNAVAILABLE);
        JobStatus first = run(exports.startMetadata(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out));
        assertThat(first.incomplete()).isTrue();
        wiki.failures.clear();
        stubHistory("2", "b");
        wiki.requests.clear();

        JobStatus again = jobs.status(exports.resume(first.id(), "desktop").id());

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.state()).isEqualTo(JobState.COMPLETED);
        assertThat(again.incomplete()).isFalse();
        assertThat(again.progress().written()).isEqualTo(2);
        assertThat(wiki.requests).as("Seite 1 kommt aus der fertigen Datei").noneMatch(path -> path.contains("/1/"));
        Path file = out.resolve(SourceQueryExports.fileName(JobType.METADATA_EXPORT, "DEV", null, first.id()));
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(file));
        assertThat(doc.get("pages").get(0).get("history").get("data").get("createdBy").get("username").asString())
                .isEqualTo("a");
        assertThat(doc.get("pages").get(1).get("history").get("status").asString()).isEqualTo("ok");
        assertThat(doc.get("summary").get("failed").asInt()).isZero();
        assertThat(list(out)).hasSize(1);
    }

    @Test
    void severalUnansweredPagesInARowPauseTheJobAndResumingContinuesFromThePartFile() throws Exception {
        pagesOfSpace.put("DEV", List.of("1", "2", "3", "4", "5"));
        stubHistory("1", "a");
        wiki.fail("/rest/api/content/2/", SourceAccessException.Reason.UNAVAILABLE);
        wiki.fail("/rest/api/content/3/", SourceAccessException.Reason.UNAVAILABLE);
        wiki.fail("/rest/api/content/4/", SourceAccessException.Reason.UNAVAILABLE);

        JobStatus paused = run(exports.startMetadata(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out));

        assertThat(paused.state()).as("Verbindung weg: anhalten statt scheitern").isEqualTo(JobState.PAUSED);
        assertThat(paused.pauseReason()).isEqualTo(PauseReason.CONNECTION_LOST);
        assertThat(paused.lifecycle().actions()).contains(JobAction.RESUME);
        assertThat(wiki.requests).noneMatch(path -> path.contains("/5/"));
        assertThat(list(out)).as("nur die Teildatei").hasSize(1);
        assertThat(list(out).get(0).getFileName().toString()).startsWith(".").endsWith(".part");

        wiki.failures.clear();
        stubHistory("2", "b");
        stubHistory("3", "c");
        stubHistory("4", "d");
        stubHistory("5", "e");
        wiki.requests.clear();
        JobStatus done = jobs.status(exports.resume(paused.id(), "desktop").id());

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.progress().discovered()).isEqualTo(5);
        assertThat(done.progress().written()).isEqualTo(5);
        assertThat(wiki.requests).as("Seite 1 steht in der Teildatei").noneMatch(path -> path.contains("/1/"));
        assertThat(list(out)).hasSize(1);
        assertThat(list(out).get(0).getFileName().toString()).startsWith("confluence-metadaten-dev-");
        Path file = out.resolve(SourceQueryExports.fileName(JobType.METADATA_EXPORT, "DEV", null, done.id()));
        JsonNode doc = JsonMapper.builder().build().readTree(Files.readString(file));
        assertThat(doc.get("pages")).extracting(page -> page.get("pageId").asString())
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void anAnalyticsExportWhoseSourceIsGonePausesAndResumesFromScratchWithTheSameSettings() {
        pagesOfSpace.put("DEV", List.of("1"));
        wiki.fail(AnalyticsQueries.ROOT, SourceAccessException.Reason.UNAVAILABLE);
        AnalyticsQueries custom = AnalyticsQueries.of(null, null, null, 90);

        JobStatus paused = run(exports.startAnalytics(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out,
                custom));

        assertThat(paused.state()).isEqualTo(JobState.PAUSED);
        assertThat(paused.pauseReason()).isEqualTo(PauseReason.CONNECTION_LOST);
        assertThat(paused.parameter(SourceQueryExports.ANALYTICS_DAYS)).isEqualTo("90");
        wiki.failures.clear();
        String period = period(CLOCK.instant(), 90);
        wiki.stub(AnalyticsQueries.ROOT + "space/details?spaceKey=DEV", "{\"name\":\"Dev\"}");
        wiki.stub(contentViews(period, null), "{\"contentViews\":[{\"id\":1,\"views\":3}]}");

        JobStatus done = jobs.status(exports.resume(paused.id(), "desktop").id());

        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.incomplete()).isFalse();
        assertThat(wiki.requests).anyMatch(path -> path.contains(period));
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

    @Test
    void exportsNeedTheExportPermissionLikeEveryOtherExport() {
        pagesOfSpace.put("DEV", List.of("1"));
        SourceQueryExports guarded = new SourceQueryExports(jobs, catalog, new SourceQueryExports.Scopes() {
            @Override
            public List<Selection.Root> outputs(AccessContext caller, Selection selection) {
                return selection.roots();
            }

            @Override
            public List<String> pages(AccessContext caller, Selection.Root root) {
                return pagesOfSpace.getOrDefault(root.spaceKey(), List.of());
            }
        }, new AccessGuard((context, scope, kind) -> kind == AccessKind.EXPORT ? AccessDecision.DENY
                : AccessDecision.ALLOW), CLOCK, "desktop");
        assertThatThrownBy(() -> guarded.startMetadata(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guarded.startAnalytics(CALLER, Selection.of(Selection.Root.space("wiki", "DEV")), out,
                AnalyticsQueries.DEFAULT))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jobs.list("desktop", JobType.METADATA_EXPORT)).isEmpty();
        assertThat(jobs.list("desktop", JobType.ANALYTICS_EXPORT)).isEmpty();
        assertThat(list(out)).isEmpty();
    }

    @Test
    void aListThatNeverEndsIsAnErrorInsteadOfASilentlyShortenedResult() {
        for (int page = 0; page < RestJson.MAX_LIST_PAGES; page++) {
            StringBuilder results = new StringBuilder();
            for (int i = 0; i < RestJson.LIST_LIMIT; i++) {
                results.append(i == 0 ? "" : ",").append("{\"n\":").append(page * RestJson.LIST_LIMIT + i).append("}");
            }
            wiki.stub("/rest/experimental/content/1/version?start=" + page * RestJson.LIST_LIMIT + "&limit="
                    + RestJson.LIST_LIMIT, "{\"results\":[" + results + "],\"_links\":{\"next\":\"/weiter\"}}");
        }
        RestJson.Result result = RestJson.list(wiki, "/rest/experimental/content/1/version");
        assertThat(result.ok()).isFalse();
        assertThat(result.requests()).isEqualTo(RestJson.MAX_LIST_PAGES);
        assertThat(result.reason()).isEqualTo(SourceAccessException.Reason.INVALID_RESPONSE);
        assertThat(result.message()).contains("nicht zu Ende");
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
