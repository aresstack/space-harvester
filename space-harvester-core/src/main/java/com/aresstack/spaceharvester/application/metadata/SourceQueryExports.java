package com.aresstack.spaceharvester.application.metadata;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.holdings.Selection;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
 * ({@link HarvestSource#restQueries}) bekommt keinen Auftrag ({@code UNSUPPORTED}).
 *
 * <p>Fehler (Angelo 17.11.2026, ADR 0025): Ein Fehler je Seite oder je Abschnitt steht in der Datei und zählt als
 * fehlgeschlagen, der Auftrag läuft weiter. Antwortet die Quelle einer Seite nicht (HTTP 5xx, Drosselung), wird die
 * Seite zurückgestellt und nach allen anderen noch einmal versucht; erst dann steht sie als Fehler in der Datei und
 * der Auftrag endet „fertig, unvollständig“ statt „fehlgeschlagen“. Antworten {@value #CONNECTION_LOST_AFTER} Seiten
 * nacheinander nicht, ist die Verbindung weg: der Auftrag hält sich an ({@link PauseReason#CONNECTION_LOST}) und
 * läuft wieder an, sobald Fortsetzen (von Hand oder durch den Wiederanlauf) ihn weckt. Beide Arten sind fortsetzbar
 * ({@link JobType#resumable()}): Metadaten legen jede abgefragte Seite sofort in einer unsichtbaren Teildatei
 * ({@code .<Datei>.part}, eine Zeile je Seite) ab, ein neuer Lauf im selben Eintrag (Fortsetzen, „Nur Fehler neu
 * versuchen“, nach Abbruch, Absturz oder Neustart) fragt nur Seiten ohne Daten erneut und baut die Datei daraus;
 * Analytics braucht nur wenige Anfragen und fragt beim neuen Lauf von vorn. Die Datei entsteht unsichtbar und wird
 * erst fertig umbenannt; ein neuer Lauf desselben Auftrags ersetzt sie.
 */
public class SourceQueryExports {

    public static final String COMPONENT = "Quellen-Export";
    /** So viele Seiten nacheinander ohne Antwort der Quelle gelten als Verbindungsabbruch: Auftrag hält sich an. */
    static final int CONNECTION_LOST_AFTER = 3;
    /** Angaben am Auftrag, aus denen ein neuer Lauf nach einem Neustart die Arbeit wiederherstellt. */
    public static final String DIRECTORY = "directory";
    static final String ANALYTICS_DAYS = "analytics.days";
    static final String ANALYTICS_DETAILS = "analytics.details";
    static final String ANALYTICS_ACTIVITY_BY_DATE = "analytics.activityByDate";
    static final String ANALYTICS_CONTENT_VIEWS = "analytics.contentViews";
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

        /**
         * Titel und Adresse einer Seite aus dem Bestand, damit eine Meldung die Seite nennt statt nur ihre ID
         * (Angelo 17.11.2026); {@code null}, wenn der Bestand sie nicht kennt. Die Adresse steht nur in der Datei.
         */
        default PageName name(String sourceId, String pageId) {
            return null;
        }
    }

    /** Wie der Bestand eine Seite nennt: Titel und Adresse in der Quelle, beide auch leer. */
    public record PageName(String title, String url) {
    }

    private final Jobs jobs;
    private final SourceCatalog catalog;
    private final Scopes scopes;
    private final AccessGuard access;
    private final Clock clock;
    private final String owner;
    /** Wer den Auftrag gestartet hat, für einen neuen Lauf im selben Prozess; nach einem Neustart der Eigentümer. */
    private final Map<String, AccessContext> callers = new ConcurrentHashMap<>();

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
            JobStatus status = jobs.submit(type, root.sourceId(), root.spaceKey(), root.pageId(), owner,
                    parameters(directory, queries),
                    JobTask.blocking(context -> run(caller, root, directory, type, queries, context)));
            callers.put(status.id(), caller);
            started.add(status);
        }
        return started;
    }

    /** Die Angaben am Auftrag ({@link JobStatus#parameters()}), aus denen {@link #resume} die Arbeit wiederherstellt. */
    static Map<String, String> parameters(Path directory, AnalyticsQueries queries) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(DIRECTORY, directory.toString());
        if (queries != null) {
            parameters.put(ANALYTICS_DAYS, Integer.toString(queries.days()));
            for (AnalyticsQueries.Query query : queries.space()) {
                if (AnalyticsQueries.DETAILS.equals(query.name())) {
                    parameters.put(ANALYTICS_DETAILS, query.pathTemplate());
                } else if (AnalyticsQueries.ACTIVITY_BY_DATE.equals(query.name())) {
                    parameters.put(ANALYTICS_ACTIVITY_BY_DATE, query.pathTemplate());
                }
            }
            parameters.put(ANALYTICS_CONTENT_VIEWS, queries.contentViews().pathTemplate());
        }
        return Map.copyOf(parameters);
    }

    /**
     * Lässt einen eigenen angehaltenen, gescheiterten, abgebrochenen oder unvollständig fertigen Auftrag im selben
     * Eintrag wieder laufen (Fortsetzen, „Nur Fehler neu versuchen“; ADR 0025): dieselbe ID, dieselbe Datei. Metadaten
     * fragen nur Seiten ohne Daten erneut ({@code .part}-Datei), Analytics von vorn. Geht auch nach einem Neustart,
     * aus den abgelegten Angaben.
     *
     * @throws JobNotResumableException wenn die Art nicht hierher gehört oder der Auftrag noch wartet oder läuft
     */
    public JobStatus resume(String jobId, String owner) {
        JobStatus status = jobs.status(jobId, owner);
        JobType type = status.type();
        if (type != JobType.METADATA_EXPORT && type != JobType.ANALYTICS_EXPORT) {
            throw new JobNotResumableException(jobId, "kein Metadaten- oder Analytics-Export");
        }
        String directory = status.parameter(DIRECTORY);
        if (directory == null) {
            throw new JobNotResumableException(jobId, "der Auftrag kennt seinen Ausgabeordner nicht mehr");
        }
        Selection.Root root = status.rootPageId() == null ? Selection.Root.space(status.sourceId(),
                status.partitionKey()) : Selection.Root.subtree(status.sourceId(), status.partitionKey(),
                status.rootPageId());
        AnalyticsQueries queries = type == JobType.ANALYTICS_EXPORT ? AnalyticsQueries.of(
                status.parameter(ANALYTICS_DETAILS), status.parameter(ANALYTICS_ACTIVITY_BY_DATE),
                status.parameter(ANALYTICS_CONTENT_VIEWS), days(status.parameter(ANALYTICS_DAYS))) : null;
        AccessContext caller = callers.getOrDefault(jobId, AccessContext.technicalClient(owner));
        Path target = Path.of(directory);
        return jobs.rerun(jobId, owner, JobTask.blocking(context -> run(caller, root, target, type, queries,
                context)));
    }

    private static Integer days(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
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
        // #944: Die Abfragen bei der Quelle sind ein Download (JobType.download()); sie warten auf einen der
        // eingestellten Download-Plätze, statt einen der Plätze für Abgleiche und Exporte zu belegen.
        context.awaitDownloadSlot();
        String name = fileName(type, root.spaceKey(), root.pageId(), context.jobId());
        Path target = directory.resolve(name);
        Path part = directory.resolve("." + name + ".part");
        Path temp = directory.resolve("." + name + ".tmp");
        long written = 0;
        long failed = 0;
        ContentViews views = null;
        String failedPagesSummary = "";
        try {
            Files.createDirectories(directory);
            // Ein neuer Lauf im selben Eintrag übernimmt, was der vorige schon hat: aus der Teildatei, sonst aus der
            // fertigen Datei („Nur Fehler neu versuchen“ nach „fertig, unvollständig“).
            Map<String, Stored> stored = type == JobType.METADATA_EXPORT ? loadStored(part, target) : Map.of();
            ObjectNode space = null;
            if (type == JobType.ANALYTICS_EXPORT) {
                context.phase(COMPONENT, "Aufrufe abfragen");
                Instant now = clock.instant();
                space = spaceAnalytics(source, root.spaceKey(), queries, now, context);
                views = contentViews(source, root.spaceKey(), queries, now, context::checkpoint);
                if (!views.result().ok()) {
                    pauseIfConnectionLost(views.result(), context);
                    if (!space.has("ok")) {
                        throw unavailable(views.result());
                    }
                }
                space.remove("ok");
            } else {
                context.phase(COMPONENT, "Metadaten abfragen");
                long reused = stored.values().stream().filter(Stored::ok).count();
                if (reused > 0) {
                    context.record(JobEvent.Draft.info(COMPONENT, reused + " Seiten aus dem vorigen Lauf übernommen; "
                            + "nur Seiten ohne Daten werden erneut abgefragt"));
                }
            }
            Instant now = clock.instant();
            List<String> deferred = new ArrayList<>();
            List<String> failedPages = new ArrayList<>();
            try (BufferedWriter partOut = type == JobType.METADATA_EXPORT ? Files.newBufferedWriter(part,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND) : null) {
                int unanswered = 0;
                for (String pageId : pages) {
                    context.checkpoint();
                    if (type == JobType.ANALYTICS_EXPORT) {
                        // Aus der Seitenliste des Space; eine Seite ohne Eintrag hatte im Zeitraum keine Aufrufe oder
                        // ist der Liste unbekannt, was nicht zu unterscheiden ist: views bleibt null.
                        boolean any = views.result().ok();
                        stored = appendStored(stored, partOut, pageId, any, analyticsEntry(pageId,
                                views.byId().get(pageId)));
                        context.processed(1);
                        if (any) {
                            written++;
                            context.written(1);
                        } else {
                            failed++;
                            context.failed(1);
                        }
                        continue;
                    }
                    Stored before = stored.get(pageId);
                    if (before != null && before.ok()) {
                        written++;
                        context.processed(1);
                        context.written(1);
                        continue;
                    }
                    Map<String, RestJson.Result> sections = ConfluenceMetadataQueries.fetch(source, pageId);
                    if (unanswered(sections)) {
                        // Die Quelle antwortet dieser Seite nicht: zurückstellen und nach allen anderen noch einmal
                        // versuchen. Antwortet sie mehreren nacheinander nicht, ist die Verbindung weg.
                        deferred.add(pageId);
                        if (++unanswered >= CONNECTION_LOST_AFTER) {
                            context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT,
                                    "CONNECTION_LOST", unanswered + " Seiten nacheinander ohne Antwort der Quelle ("
                                            + firstMessage(sections) + "); der Auftrag hält sich an und setzt fort, "
                                            + "sobald die Quelle wieder antwortet"));
                            context.pause(PauseReason.CONNECTION_LOST);
                        }
                        continue;
                    }
                    unanswered = 0;
                    boolean any = sections.values().stream().anyMatch(RestJson.Result::ok);
                    stored = appendStored(stored, partOut, pageId, any,
                            ConfluenceMetadataQueries.entry(pageId, sections));
                    context.processed(1);
                    if (any) {
                        written++;
                        context.written(1);
                    } else {
                        failed++;
                        context.failed(1);
                        failedPages.add(reportFailedPage(context, root.sourceId(), pageId, sections));
                    }
                }
                if (!deferred.isEmpty()) {
                    context.phase(COMPONENT, "Zurückgestellte Seiten erneut abfragen");
                    context.record(JobEvent.Draft.info(COMPONENT, deferred.size() + " Seiten ohne Antwort der Quelle "
                            + "werden am Ende noch einmal versucht"));
                }
                for (String pageId : deferred) {
                    context.checkpoint();
                    Map<String, RestJson.Result> sections = ConfluenceMetadataQueries.fetch(source, pageId);
                    boolean any = sections.values().stream().anyMatch(RestJson.Result::ok);
                    stored = appendStored(stored, partOut, pageId, any,
                            ConfluenceMetadataQueries.entry(pageId, sections));
                    context.processed(1);
                    if (any) {
                        written++;
                        context.written(1);
                    } else {
                        failed++;
                        context.failed(1);
                        failedPages.add(reportFailedPage(context, root.sourceId(), pageId, sections));
                    }
                }
            }
            failedPagesSummary = summary(failedPages);
            context.checkpoint();
            try (OutputStream out = Files.newOutputStream(temp);
                 JsonGenerator json = RestJson.JSON.createGenerator(out, JsonEncoding.UTF8)) {
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
                    Stored entry = stored.get(pageId);
                    if (entry != null) {
                        json.writeRawValue(entry.entry());
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
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(part);
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
                    failed + " von " + pages.size() + " Seiten ohne Antwort"
                            + (failedPagesSummary.isEmpty() ? "" : ": " + failedPagesSummary)
                            + "; je Seite eine Meldung PAGE_FAILED, Gründe auch in der Datei"));
        }
        context.record(JobEvent.Draft.info(COMPONENT, name + ": " + written + " Seiten exportiert"));
    }

    /** So viele gescheiterte Seiten nennt die Sammelmeldung mit Namen; der Rest steht je Seite in PAGE_FAILED. */
    static final int NAMED_FAILURES = 10;

    /**
     * Meldet eine Seite, bei der keine Abfrage antwortete, mit Titel und dem Fehler je Abfrage, damit
     * niemand die Datei öffnen muss (Angelo 17.11.2026); liefert den Namen für die Sammelmeldung.
     */
    private String reportFailedPage(JobContext context, String sourceId, String pageId,
                                    Map<String, RestJson.Result> sections) {
        PageName name = scopes.name(sourceId, pageId);
        String title = name == null || name.title() == null || name.title().isBlank() ? null : name.title();
        String label = "Seite " + pageId + (title == null ? "" : " „" + title + "“");
        // Keine Adresse: Diagnosetexte maskieren URL-Pfade (Redaction); die Seiten-ID genügt zum Nachschlagen.
        StringBuilder message = new StringBuilder(label).append(" ohne Antwort:");
        Integer httpStatus = null;
        for (Map.Entry<String, RestJson.Result> section : sections.entrySet()) {
            RestJson.Result result = section.getValue();
            message.append("\n").append(section.getKey()).append(": ").append(result.reason())
                    .append(result.message() == null || result.message().isBlank() ? "" : " – " + result.message());
            if (httpStatus == null && result.message() != null) {
                httpStatus = httpStatus(result.message());
            }
        }
        context.record(new JobEvent.Draft(JobEvent.Severity.WARNING, JobEvent.Category.SOURCE, COMPONENT,
                "PAGE_FAILED", message.toString(), httpStatus, null, null));
        return label;
    }

    /** Der HTTP-Status aus einer Meldung wie „Confluence antwortet mit HTTP 500“, sonst {@code null}. */
    static Integer httpStatus(String message) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("HTTP (\\d{3})").matcher(message);
        return match.find() ? Integer.valueOf(match.group(1)) : null;
    }

    private static String summary(List<String> failedPages) {
        if (failedPages.isEmpty()) {
            return "";
        }
        List<String> named = failedPages.subList(0, Math.min(NAMED_FAILURES, failedPages.size()));
        return String.join(", ", named) + (failedPages.size() > named.size() ? " und "
                + (failedPages.size() - named.size()) + " weitere" : "");
    }

    /** Eine abgelegte Seite: ob sie Daten hat und ihr Eintrag als JSON-Text, wie er in die Datei kommt. */
    record Stored(boolean ok, String entry) {
    }

    /**
     * Die Seiten des vorigen Laufs: aus der Teildatei (eine Zeile je Seite, die letzte Zeile einer Seite gilt), sonst
     * aus der fertigen Datei; unlesbare Zeilen werden übersprungen und die Seite erneut abgefragt.
     */
    static Map<String, Stored> loadStored(Path part, Path target) throws IOException {
        Map<String, Stored> stored = new LinkedHashMap<>();
        if (Files.exists(part)) {
            try (BufferedReader in = Files.newBufferedReader(part, StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonNode node = RestJson.JSON.readTree(line);
                        JsonNode pageId = node.get("pageId");
                        JsonNode entry = node.get("entry");
                        if (pageId != null && pageId.isString() && entry != null && entry.isObject()) {
                            stored.put(pageId.asString(), new Stored(node.path("ok").asBoolean(false),
                                    RestJson.JSON.writeValueAsString(entry)));
                        }
                    } catch (RuntimeException e) {
                        // Eine abgebrochene Zeile am Ende (Absturz beim Schreiben): die Seite kommt noch einmal.
                    }
                }
            }
            return stored;
        }
        if (Files.exists(target)) {
            JsonNode file;
            try (var in = Files.newInputStream(target)) {
                file = RestJson.JSON.readTree(in);
            } catch (RuntimeException e) {
                return stored;
            }
            JsonNode pages = file.get("pages");
            if (pages != null && pages.isArray()) {
                for (JsonNode entry : pages) {
                    JsonNode pageId = entry.get("pageId");
                    if (pageId != null && pageId.isString()) {
                        stored.put(pageId.asString(), new Stored(hasData(entry),
                                RestJson.JSON.writeValueAsString(entry)));
                    }
                }
            }
        }
        return stored;
    }

    /** Ob ein Eintrag der fertigen Datei Daten hat: ein Abschnitt mit Status {@code ok} oder ein {@code content}. */
    private static boolean hasData(JsonNode entry) {
        if (entry.has("content")) {
            return !entry.get("content").isNull();
        }
        for (Map.Entry<String, JsonNode> section : entry.properties()) {
            JsonNode status = section.getValue().get("status");
            if (status != null && "ok".equals(status.asString())) {
                return true;
            }
        }
        return false;
    }

    /** Merkt sich den Eintrag einer Seite und schreibt ihn, wenn es eine Teildatei gibt, sofort als Zeile hinein. */
    private static Map<String, Stored> appendStored(Map<String, Stored> stored, BufferedWriter partOut,
                                                    String pageId, boolean ok, ObjectNode entry) throws IOException {
        Map<String, Stored> all = stored instanceof LinkedHashMap<String, Stored> ? stored
                : new LinkedHashMap<>(stored);
        String text = RestJson.JSON.writeValueAsString(entry);
        all.put(pageId, new Stored(ok, text));
        if (partOut != null) {
            ObjectNode line = RestJson.JSON.createObjectNode();
            line.put("pageId", pageId);
            line.put("ok", ok);
            line.set("entry", entry);
            partOut.write(RestJson.JSON.writeValueAsString(line));
            partOut.newLine();
            partOut.flush();
        }
        return all;
    }

    /** Ob die Quelle der Seite nicht antwortet (Netz, HTTP 5xx, Drosselung) statt Daten oder 404 zu liefern. */
    static boolean unanswered(Map<String, RestJson.Result> sections) {
        return sections.values().stream().anyMatch(result -> !result.ok()
                && (result.reason() == SourceAccessException.Reason.UNAVAILABLE
                || result.reason() == SourceAccessException.Reason.RATE_LIMITED));
    }

    private static String firstMessage(Map<String, RestJson.Result> sections) {
        return sections.values().stream().filter(result -> !result.ok()).map(RestJson.Result::message)
                .filter(Objects::nonNull).findFirst().orElse("keine Antwort");
    }

    /** Netz oder Drosselung bei einer Space-Abfrage: der Auftrag hält sich an, statt zu scheitern (ADR 0025). */
    private static void pauseIfConnectionLost(RestJson.Result result, JobContext context) {
        if (!result.ok() && (result.reason() == SourceAccessException.Reason.UNAVAILABLE
                || result.reason() == SourceAccessException.Reason.RATE_LIMITED)) {
            context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT, "CONNECTION_LOST",
                    "Quelle antwortet nicht (" + result.message() + "); der Auftrag hält sich an und setzt fort, "
                            + "sobald sie wieder antwortet"));
            context.pause(PauseReason.CONNECTION_LOST);
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
                                             Instant now, JobContext context) {
        ObjectNode space = RestJson.JSON.createObjectNode();
        for (AnalyticsQueries.Query query : queries.space()) {
            context.checkpoint();
            RestJson.Result result = RestJson.get(source, query.path(spaceKey, queries.from(now), now));
            pauseIfConnectionLost(result, context);
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
     * Blättert die Seitenliste des Space: {@code contentViews[]} je Antwort, weiter mit dem {@code nextPageToken} der Antwort als {@code pageToken} an der
     * Stelle von {@link AnalyticsQueries#TOKEN_PLACEHOLDER} der Vorlage, sonst als
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
