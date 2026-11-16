package com.aresstack.spaceharvester.application.metadata;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.node.ObjectNode;

/**
 * Die beiden Exporte aus der Quelle (Angelo, 16.11.2026), getrennt vom Seitenexport und vom Analyse-Export:
 * <ul>
 *     <li>{@link JobType#METADATA_EXPORT} „Confluence-Metadaten“: je Seite der Auswahl die dokumentierten Abfragen
 *     aus {@link ConfluenceMetadataQueries}, jede Antwort unverändert, Datei
 *     {@code confluence-metadaten-<Space>[-<Seite>]-<Auftrag>.json}.</li>
 *     <li>{@link JobType#ANALYTICS_EXPORT} „Analytics“: Seitenaufrufe über die Abfragen aus
 *     {@link AnalyticsQueries}, Datei {@code confluence-analytics-…json}. Antwortet die Quelle schon bei der ersten
 *     Seite auf keine der Abfragen, scheitert der Auftrag mit {@code ANALYTICS_UNAVAILABLE}; der Rest der App ist
 *     nicht betroffen.</li>
 * </ul>
 * Je gewähltem Space bzw. Teilbaum ein Auftrag ({@link Scopes#outputs}); die Seiten kommen aus dem Bestand
 * ({@link Scopes#pages}: alle dort bekannten Seiten, auch nicht lokal gespeicherte), die Daten aus der Quelle mit
 * deren Anmeldung. Der Seitenabruf, der Snapshot und seine Hashes bleiben unberührt. Eine Quelle ohne REST-Abfragen
 * ({@link HarvestSource#restQueries}) bekommt keinen Auftrag ({@code UNSUPPORTED}). Eine unerreichbare oder
 * drosselnde Quelle beendet den Auftrag; ein Fehler je Seite oder je Abschnitt steht in der Datei und zählt als
 * fehlgeschlagen, der Auftrag läuft weiter. Die Datei entsteht unsichtbar und wird erst fertig umbenannt; nie
 * überschrieben.
 */
public class SourceQueryExports {

    public static final String COMPONENT = "Quellen-Export";
    public static final String METADATA_SCHEMA = "space-harvester/confluence-metadata-export/v1";
    public static final String ANALYTICS_SCHEMA = "space-harvester/confluence-analytics-export/v1";
    static final String METADATA_PREFIX = "confluence-metadaten";
    static final String ANALYTICS_PREFIX = "confluence-analytics";

    /** Die Auswahl im Bestand, ohne Quellenzugriff ({@code Holdings}). */
    public interface Scopes {

        /** Je gewähltem Space bzw. Teilbaum eine Wurzel mit Space (ADR 0029, {@code Holdings.outputs}). */
        List<Selection.Root> outputs(AccessContext caller, Selection selection);

        /** Alle im Bestand bekannten Seiten einer Wurzel, die der Aufrufer sehen darf, in stabiler Reihenfolge. */
        List<String> pages(AccessContext caller, Selection.Root root);
    }

    private final Jobs jobs;
    private final SourceCatalog catalog;
    private final Scopes scopes;
    private final AccessGuard access;
    private final Clock clock;
    private final String owner;

    public SourceQueryExports(Jobs jobs, SourceCatalog catalog, Scopes scopes, AccessGuard access, Clock clock,
                              String owner) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.access = Objects.requireNonNull(access, "access");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner;
    }

    /** Ob die Quelle REST-Abfragen beantwortet, also beide Exporte anbietet; {@code false} für eine unbekannte. */
    public boolean supports(String sourceId) {
        return catalog.find(sourceId).map(HarvestSource::restQueries).orElse(false);
    }

    /** Startet je Wurzel der Auswahl einen Auftrag „Confluence-Metadaten exportieren“; der Aufrufer wartet nicht. */
    public List<JobStatus> startMetadata(AccessContext caller, Selection selection, Path directory) {
        return start(caller, selection, directory, JobType.METADATA_EXPORT, null);
    }

    /** Startet je Wurzel der Auswahl einen Auftrag „Analytics exportieren“ mit diesen Abfragen. */
    public List<JobStatus> startAnalytics(AccessContext caller, Selection selection, Path directory,
                                          AnalyticsQueries queries) {
        return start(caller, selection, directory, JobType.ANALYTICS_EXPORT,
                Objects.requireNonNull(queries, "queries"));
    }

    private List<JobStatus> start(AccessContext caller, Selection selection, Path directory, JobType type,
                                  AnalyticsQueries queries) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(directory, "directory");
        List<Selection.Root> outputs = scopes.outputs(caller, selection);
        for (Selection.Root root : outputs) {
            access.require(caller, ResourceScope.space(root.sourceId(), root.spaceKey()), AccessKind.METADATA);
            if (!source(root.sourceId()).restQueries()) {
                throw new SourceAccessException(root.sourceId(), SourceAccessException.Reason.UNSUPPORTED,
                        "Quelle beantwortet keine REST-Abfragen; der Export gibt es nur für Confluence", null);
            }
        }
        List<JobStatus> started = new ArrayList<>(outputs.size());
        for (Selection.Root root : outputs) {
            started.add(jobs.submit(type, root.sourceId(), root.spaceKey(), root.pageId(), owner,
                    Map.of("directory", directory.toString()),
                    JobTask.blocking(context -> run(caller, root, directory, type, queries, context))));
        }
        return started;
    }

    private HarvestSource source(String sourceId) {
        return catalog.find(sourceId).orElseThrow(() -> new UnknownSourceException(sourceId));
    }

    /** Name der Datei im Ausgabeordner, wie der Auftrag sie anlegt; die App zeigt ihn nach dem Ende. */
    public static String fileName(JobType type, String spaceKey, String rootPageId, String jobId) {
        String prefix = type == JobType.ANALYTICS_EXPORT ? ANALYTICS_PREFIX : METADATA_PREFIX;
        return prefix + "-" + slug(spaceKey) + (rootPageId == null ? "" : "-" + slug(rootPageId)) + "-"
                + jobId.substring(0, Math.min(8, jobId.length())) + ".json";
    }

    private static String slug(String value) {
        String slug = value.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT);
        return slug.isEmpty() ? "x" : slug;
    }

    private void run(AccessContext caller, Selection.Root root, Path directory, JobType type,
                     AnalyticsQueries queries, JobContext context) {
        context.phase(COMPONENT, "Seiten bestimmen");
        HarvestSource source = source(root.sourceId());
        List<String> pages = scopes.pages(caller, root);
        context.discovered(pages.size());
        context.checkpoint();
        String name = fileName(type, root.spaceKey(), root.pageId(), context.jobId());
        Path target = directory.resolve(name);
        Path temp = directory.resolve("." + name + ".tmp");
        context.phase(COMPONENT, type == JobType.ANALYTICS_EXPORT ? "Aufrufe abfragen" : "Metadaten abfragen");
        long written = 0;
        long failed = 0;
        try {
            Files.createDirectories(directory);
            if (Files.exists(target)) {
                throw new JobFailedException("TARGET_EXISTS", "Die Zieldatei gibt es schon; sie wird nicht "
                        + "überschrieben.");
            }
            try (OutputStream out = Files.newOutputStream(temp);
                 JsonGenerator json = RestJson.JSON.createGenerator(out, JsonEncoding.UTF8)) {
                Instant now = clock.instant();
                json.writeStartObject();
                json.writeStringProperty("schema", type == JobType.ANALYTICS_EXPORT ? ANALYTICS_SCHEMA
                        : METADATA_SCHEMA);
                json.writeStringProperty("jobId", context.jobId());
                json.writeStringProperty("exportedAt", now.toString());
                json.writeStringProperty("sourceId", root.sourceId());
                json.writeStringProperty("spaceKey", root.spaceKey());
                if (root.pageId() != null) {
                    json.writeStringProperty("rootPageId", root.pageId());
                }
                if (type == JobType.ANALYTICS_EXPORT) {
                    writeAnalyticsHead(json, queries, now);
                    RestJson.JSON.writeTree(json, spaceAnalytics(source, root.spaceKey(), queries, now));
                } else {
                    json.writeName("endpoints");
                    json.writeStartObject();
                    for (Map.Entry<String, String> endpoint : ConfluenceMetadataQueries.ENDPOINTS.entrySet()) {
                        json.writeStringProperty(endpoint.getKey(), endpoint.getValue());
                    }
                    json.writeEndObject();
                }
                json.writeName("pages");
                json.writeStartArray();
                boolean first = true;
                for (String pageId : pages) {
                    context.checkpoint();
                    ObjectNode entry;
                    boolean any;
                    if (type == JobType.ANALYTICS_EXPORT) {
                        Map<String, RestJson.Result> results = pageAnalytics(source, root.spaceKey(), pageId,
                                queries, now);
                        any = results.values().stream().anyMatch(RestJson.Result::ok);
                        if (first && !any && !results.isEmpty()) {
                            throw unavailable(results);
                        }
                        entry = analyticsEntry(pageId, results);
                    } else {
                        Map<String, RestJson.Result> sections = ConfluenceMetadataQueries.fetch(source, pageId);
                        failIfSourceGone(sections);
                        any = sections.values().stream().anyMatch(RestJson.Result::ok);
                        entry = ConfluenceMetadataQueries.entry(pageId, sections);
                    }
                    first = false;
                    RestJson.JSON.writeTree(json, entry);
                    context.processed(1);
                    if (any) {
                        written++;
                        context.written(1);
                    } else {
                        failed++;
                        context.failed(1);
                    }
                }
                json.writeEndArray();
                json.writeName("summary");
                json.writeStartObject();
                json.writeNumberProperty("pages", pages.size());
                json.writeNumberProperty("withData", written);
                json.writeNumberProperty("failed", failed);
                json.writeEndObject();
                json.writeEndObject();
            }
            context.checkpoint();
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Aufräumen ist best effort; die unsichtbare Datei beginnt mit einem Punkt.
            }
        }
        if (failed > 0) {
            context.incomplete();
            context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT, "PAGES_FAILED",
                    failed + " von " + pages.size() + " Seiten ohne Antwort; Gründe stehen in der Datei"));
        }
        context.record(JobEvent.Draft.info(COMPONENT, name + ": " + written + " Seiten exportiert"));
    }

    /** Netz, Drosselung oder Anmeldung: der Auftrag scheitert, statt jede Seite als Fehler zu verbuchen. */
    private static void failIfSourceGone(Map<String, RestJson.Result> results) {
        for (RestJson.Result result : results.values()) {
            if (!result.ok() && (result.reason() == SourceAccessException.Reason.UNAVAILABLE
                    || result.reason() == SourceAccessException.Reason.RATE_LIMITED)) {
                throw new JobFailedException("SOURCE_" + result.reason().name(),
                        "Quelle antwortet nicht: " + result.message());
            }
        }
    }

    private static JobFailedException unavailable(Map<String, RestJson.Result> results) {
        RestJson.Result sample = results.values().iterator().next();
        return new JobFailedException("ANALYTICS_UNAVAILABLE", "Analytics antwortet nicht (" + sample.reason()
                + "): " + sample.message() + " – Analytics-Plugin fehlt, ist für dieses Konto gesperrt oder die "
                + "Pfade unter " + AnalyticsQueries.ROOT + " sind an dieser Instanz andere");
    }

    private static void writeAnalyticsHead(JsonGenerator json, AnalyticsQueries queries, Instant now) {
        json.writeName("period");
        json.writeStartObject();
        json.writeStringProperty("from", queries.from(now).toString());
        json.writeStringProperty("to", now.toString());
        json.writeNumberProperty("days", queries.days());
        json.writeEndObject();
        json.writeName("queries");
        json.writeStartObject();
        json.writeName("space");
        json.writeStartObject();
        for (AnalyticsQueries.Query query : queries.space()) {
            json.writeStringProperty(query.name(), query.pathTemplate());
        }
        json.writeEndObject();
        json.writeName("page");
        json.writeStartObject();
        for (AnalyticsQueries.Query query : queries.page()) {
            json.writeStringProperty(query.name(), query.pathTemplate());
        }
        json.writeEndObject();
        json.writeEndObject();
        json.writeName("space");
    }

    private static ObjectNode spaceAnalytics(HarvestSource source, String spaceKey, AnalyticsQueries queries,
                                             Instant now) {
        ObjectNode space = RestJson.JSON.createObjectNode();
        for (AnalyticsQueries.Query query : queries.space()) {
            RestJson.Result result = RestJson.get(source, query.path(spaceKey, null, queries.from(now), now));
            failIfSourceGone(Map.of(query.name(), result));
            space.set(query.name(), RestJson.section(result));
        }
        return space;
    }

    private static Map<String, RestJson.Result> pageAnalytics(HarvestSource source, String spaceKey, String pageId,
                                                              AnalyticsQueries queries, Instant now) {
        Map<String, RestJson.Result> results = new java.util.LinkedHashMap<>();
        for (AnalyticsQueries.Query query : queries.page()) {
            RestJson.Result result = RestJson.get(source, query.path(spaceKey, pageId, queries.from(now), now));
            failIfSourceGone(Map.of(query.name(), result));
            results.put(query.name(), result);
        }
        return results;
    }

    /**
     * Der Eintrag einer Seite: je Abfrage die Antwort unverändert, dazu {@code summary.views} als Summe aller Felder
     * {@code views} der Antworten, oder {@code null}, wenn keine Antwort ein solches Feld hat (nicht erhoben ist
     * nie 0).
     */
    static ObjectNode analyticsEntry(String pageId, Map<String, RestJson.Result> results) {
        ObjectNode entry = RestJson.JSON.createObjectNode();
        entry.put("pageId", pageId);
        ObjectNode queries = entry.putObject("queries");
        long views = 0;
        boolean counted = false;
        for (Map.Entry<String, RestJson.Result> result : results.entrySet()) {
            queries.set(result.getKey(), RestJson.section(result.getValue()));
            if (result.getValue().ok()) {
                for (Long value : RestJson.numbers(result.getValue().data(), "views")) {
                    views += value;
                    counted = true;
                }
            }
        }
        ObjectNode summary = entry.putObject("summary");
        if (counted) {
            summary.put("views", views);
        } else {
            summary.putNull("views");
        }
        return entry;
    }
}
