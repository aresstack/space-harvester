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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Die beiden Exporte aus der Quelle (Angelo, 16.11.2026), getrennt vom Seitenexport und vom Analyse-Export:
 * <ul>
 *     <li>{@link JobType#METADATA_EXPORT} „Confluence-Metadaten“: je Seite der Auswahl die dokumentierten Abfragen
 *     aus {@link ConfluenceMetadataQueries}, jede Antwort unverändert, Datei
 *     {@code confluence-metadaten-<Space>[-<Seite>]-<Auftrag>.json}.</li>
 *     <li>{@link JobType#ANALYTICS_EXPORT} „Analytics“: Aktivität des Space und Aufrufe je Seite über die Abfragen
 *     aus {@link AnalyticsQueries} (Seitenliste des Space, nicht eine Abfrage je Seite), Datei
 *     {@code confluence-analytics-…json}. Antwortet die Quelle auf keine der Abfragen, scheitert der Auftrag mit
 *     {@code ANALYTICS_UNAVAILABLE}; der Rest der App ist nicht betroffen.</li>
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
    public static final String ANALYTICS_SCHEMA = "space-harvester/confluence-analytics-export/v2";
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
            // Wie jeder Export (Harvester.requireExport): EXPORT ist eigens vergeben; dazu METADATA, weil die Datei
            // Metadaten der Quelle und keine Seiteninhalte enthält.
            ResourceScope space = ResourceScope.space(root.sourceId(), root.spaceKey());
            access.require(caller, space, AccessKind.EXPORT);
            access.require(caller, space, AccessKind.METADATA);
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
        ContentViews views = null;
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
                    ObjectNode space = spaceAnalytics(source, root.spaceKey(), queries, now);
                    views = contentViews(source, root.spaceKey(), queries, now, context::checkpoint);
                    if (!views.result().ok() && !space.has("ok")) {
                        throw unavailable(views.result());
                    }
                    space.remove("ok");
                    RestJson.JSON.writeTree(json, space);
                    json.writeName(AnalyticsQueries.CONTENT_VIEWS);
                    RestJson.JSON.writeTree(json, views.section());
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
                for (String pageId : pages) {
                    context.checkpoint();
                    ObjectNode entry;
                    boolean any;
                    if (type == JobType.ANALYTICS_EXPORT) {
                        // Aus der Seitenliste des Space; eine Seite ohne Eintrag hatte im Zeitraum keine Aufrufe oder
                        // ist der Liste unbekannt, was nicht zu unterscheiden ist: views bleibt null.
                        any = views.result().ok();
                        entry = analyticsEntry(pageId, views.byId().get(pageId));
                    } else {
                        Map<String, RestJson.Result> sections = ConfluenceMetadataQueries.fetch(source, pageId);
                        failIfSourceGone(sections);
                        any = sections.values().stream().anyMatch(RestJson.Result::ok);
                        entry = ConfluenceMetadataQueries.entry(pageId, sections);
                    }
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
        if (views != null && views.truncated()) {
            context.incomplete();
            context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT, "CONTENT_VIEWS_INCOMPLETE",
                    "Die Seitenliste des Space wurde nicht zu Ende geblättert (" + views.requests() + " Anfragen); "
                            + "Seiten ohne Eintrag haben deshalb keine Aufrufe in der Datei"));
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

    private static JobFailedException unavailable(RestJson.Result sample) {
        return new JobFailedException("ANALYTICS_UNAVAILABLE", "Analytics antwortet nicht (" + sample.reason()
                + "): " + sample.message() + " – Analytics-Plugin fehlt, ist für dieses Konto gesperrt oder die "
                + "Pfade unter " + AnalyticsQueries.ROOT + " sind an dieser Instanz andere (Einstellungen › "
                + "Analytics-Export)");
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
        json.writeStringProperty(AnalyticsQueries.CONTENT_VIEWS, queries.contentViews().pathTemplate());
        json.writeEndObject();
        json.writeName("space");
    }

    /** Die Abfragen je Space; {@code ok} merkt sich, ob eine antwortete, und wird vor dem Schreiben entfernt. */
    private static ObjectNode spaceAnalytics(HarvestSource source, String spaceKey, AnalyticsQueries queries,
                                             Instant now) {
        ObjectNode space = RestJson.JSON.createObjectNode();
        for (AnalyticsQueries.Query query : queries.space()) {
            RestJson.Result result = RestJson.get(source, query.path(spaceKey, queries.from(now), now));
            failIfSourceGone(Map.of(query.name(), result));
            space.set(query.name(), RestJson.section(result));
            if (result.ok()) {
                space.put("ok", true);
            }
        }
        return space;
    }

    /** Die Seitenliste des Space mit ihren Einträgen je Seiten-ID. */
    record ContentViews(RestJson.Result result, Map<String, JsonNode> byId, boolean truncated, int requests) {

        /** Der Abschnitt in der Datei: Status, Anfragen, Einträge gesamt, ob die Liste zu Ende geblättert wurde. */
        ObjectNode section() {
            ObjectNode section = RestJson.section(result);
            section.put("requests", requests);
            if (result.ok()) {
                section.remove("data");
                section.put("entries", byId.size());
                section.put("complete", !truncated);
            }
            return section;
        }
    }

    /**
     * Blättert die Seitenliste des Space: {@code contentViews[]} je Antwort, weiter mit {@code nextPageToken} als
     * Parameter {@link AnalyticsQueries#PAGE_TOKEN}, bis kein Token mehr kommt. Wiederholt die Quelle ein Token oder
     * dieselbe erste Seite (der Parameter heißt dort anders), endet die Liste als unvollständig statt endlos.
     */
    static ContentViews contentViews(HarvestSource source, String spaceKey, AnalyticsQueries queries, Instant now,
                                     Runnable checkpoint) {
        Map<String, JsonNode> byId = new java.util.LinkedHashMap<>();
        AnalyticsQueries.Query query = queries.contentViews();
        Instant from = queries.from(now);
        String token = null;
        String previousFirst = null;
        int requests = 0;
        boolean truncated = false;
        RestJson.Result last = null;
        for (int page = 0; page < RestJson.MAX_LIST_PAGES; page++) {
            String path = token == null ? query.path(spaceKey, from, now) : query.path(spaceKey, from, now, token);
            // Vor jeder Listenseite: Abbruch und Frist des Auftrags gelten auch mitten in der Liste.
            checkpoint.run();
            RestJson.Result result = RestJson.get(source, path);
            requests++;
            failIfSourceGone(Map.of(query.name(), result));
            if (!result.ok()) {
                // Die erste Antwort entscheidet; ein Fehler mitten in der Liste macht sie unvollständig.
                if (last == null) {
                    return new ContentViews(result, Map.of(), false, requests);
                }
                truncated = true;
                break;
            }
            last = result;
            JsonNode entries = result.data().get(AnalyticsQueries.CONTENT_VIEWS);
            if (entries == null || !entries.isArray()) {
                return new ContentViews(new RestJson.Result(null, requests,
                        SourceAccessException.Reason.INVALID_RESPONSE, "Antwort ohne " + AnalyticsQueries.CONTENT_VIEWS
                        + "[]; die Pfade unter " + AnalyticsQueries.ROOT + " sind an dieser Instanz andere"),
                        Map.of(), false, requests);
            }
            String first = entries.isEmpty() ? null : id(entries.get(0));
            if (token != null && first != null && first.equals(previousFirst)) {
                truncated = true;
                break;
            }
            previousFirst = first;
            for (JsonNode entry : entries) {
                String id = id(entry);
                if (id != null) {
                    byId.putIfAbsent(id, entry);
                }
            }
            JsonNode next = result.data().get("nextPageToken");
            String nextToken = next == null || next.isNull() ? null : next.asString();
            if (nextToken == null || nextToken.isBlank() || entries.isEmpty()) {
                return new ContentViews(last, byId, false, requests);
            }
            if (nextToken.equals(token)) {
                // Dasselbe Token noch einmal: die Liste kommt nicht voran, also unvollständig statt endlos.
                truncated = true;
                break;
            }
            token = nextToken;
            if (page == RestJson.MAX_LIST_PAGES - 1) {
                truncated = true;
            }
        }
        return new ContentViews(last, byId, truncated, requests);
    }

    private static String id(JsonNode entry) {
        JsonNode id = entry == null ? null : entry.get("id");
        return id == null || id.isNull() ? null : id.asString();
    }

    /**
     * Der Eintrag einer Seite aus der Seitenliste: {@code content} ist der Listeneintrag unverändert (Titel, Anlage,
     * letzte Änderung, letzter Aufruf, Leser, Aufrufe, Kommentare) oder {@code null}, dazu {@code summary.views} und
     * {@code summary.lastViewedAt}; ohne Eintrag ist beides {@code null}, denn nicht erhoben ist nie 0.
     */
    static ObjectNode analyticsEntry(String pageId, JsonNode content) {
        ObjectNode entry = RestJson.JSON.createObjectNode();
        entry.put("pageId", pageId);
        if (content == null) {
            entry.putNull("content");
        } else {
            entry.set("content", content);
        }
        ObjectNode summary = entry.putObject("summary");
        JsonNode views = content == null ? null : content.get("views");
        if (views != null && views.isNumber()) {
            summary.put("views", views.asLong());
        } else {
            summary.putNull("views");
        }
        JsonNode lastViewed = content == null ? null : content.get("lastViewedAt");
        if (lastViewed != null && lastViewed.isString()) {
            summary.put("lastViewedAt", lastViewed.asString());
        } else {
            summary.putNull("lastViewedAt");
        }
        return entry;
    }
}
