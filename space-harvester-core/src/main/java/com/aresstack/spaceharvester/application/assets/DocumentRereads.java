package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * „Neu lesen“ (ADR 0026, Punkt 11, Abnahme 8): liest die abgelegten Dokumente einer Quelle ohne Download neu, als
 * eigener, anhaltbarer Auftrag {@link JobType#REREAD}. Ohne Beschränkung trifft er jedes Dokument ohne Extraktion der
 * eingestellten Version (nach einem Versionswechsel, nach einem Import, nach einer vorübergehenden Grenze); beschränkt
 * auf Status (wie ↻ „Nur Fehler“) nur Dokumente, deren aktuelle Extraktion einen dieser Status hat, etwa nach
 * geänderten Grenzen.
 *
 * <p>Je Anhang zählt die jüngste abgelegte Version, je Inhalt wird einmal gelesen ({@link Assets#reread}). Der
 * bisherige Stand gilt, bis das Lesen Text liefert; erst dann ersetzt der Auftrag die Einträge im Volltextindex und
 * entfernt Texte und Extraktionen anderer Versionen. Ein gescheitertes Lesen lässt den alten Stand stehen und zählt als
 * Fehler. Angehalten oder abgebrochen setzt er im selben Eintrag fort und überspringt, was schon neu gelesen ist, auch
 * nach einem Neustart (aus {@link JobStatus#parameters()}).
 *
 * <p>Rechte prüft der Aufrufer, wie bei {@code SourceDataPurge}. Meldungen nennen nie Dateinamen oder Inhalte.
 */
public final class DocumentRereads {

    /** Komponente in Phasen des Auftrags. */
    public static final String COMPONENT = "reread";
    /** Angabe des Auftrags: die Status, auf die er beschränkt ist, kommagetrennt; fehlt ohne Beschränkung. */
    public static final String STATUSES = "statuses";
    /** Angabe des Auftrags: Beginn des ersten Laufs; mit Status gilt eine Extraktion ab dann als neu gelesen. */
    public static final String SINCE = "since";
    /** So viele neu gelesene Dokumente, dann wird der Volltextindex gesichert. */
    static final int FLUSH_EVERY = 50;

    private final Jobs jobs;
    private final Assets assets;
    private final AssetStore store;
    private final Predicate<String> configured;
    private final Function<PageKey, Optional<String>> spaceKeys;
    private final Clock clock;

    /**
     * @param configured ob eine Quelle eingerichtet ist
     * @param spaceKeys  Space einer Seite aus dem Cache, für die Einträge im Volltextindex; leer für eine unbekannte
     *                   Seite (dann bleibt ihr Indexeintrag bis zur nächsten Ernte)
     */
    public DocumentRereads(Jobs jobs, Assets assets, AssetStore store, Predicate<String> configured,
                           Function<PageKey, Optional<String>> spaceKeys, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.store = Objects.requireNonNull(store, "store");
        this.configured = Objects.requireNonNull(configured, "configured");
        this.spaceKeys = Objects.requireNonNull(spaceKeys, "spaceKeys");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Startet „Neu lesen“ für eine Quelle.
     *
     * @param statuses leer für alle Dokumente ohne aktuelle Extraktion, sonst nur Dokumente mit diesem Status
     * @param owner    auslösender Client, siehe {@link JobStatus#owner()}
     * @throws UnknownSourceException ohne eingerichtete Quelle dieser ID
     */
    public JobStatus start(String sourceId, String owner, Set<ExtractionStatus> statuses) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(statuses, "statuses");
        if (!configured.test(sourceId)) {
            throw new UnknownSourceException(sourceId);
        }
        Instant since = clock.instant();
        Map<String, String> parameters = new LinkedHashMap<>();
        if (!statuses.isEmpty()) {
            parameters.put(STATUSES, statuses.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        }
        parameters.put(SINCE, since.toString());
        Set<ExtractionStatus> copy = statuses.isEmpty() ? Set.of() : EnumSet.copyOf(statuses);
        return jobs.submit(JobType.REREAD, sourceId, null, null, owner, parameters, task(sourceId, copy, since));
    }

    /**
     * Setzt einen eigenen angehaltenen, abgebrochenen oder fertigen Lauf im selben Eintrag fort, auch nach einem
     * Neustart; was schon neu gelesen ist, wird übersprungen.
     *
     * @throws JobNotResumableException wenn der Auftrag kein „Neu lesen“ ist oder noch läuft
     */
    public JobStatus resume(String jobId, String owner) {
        JobStatus current = jobs.status(jobId, owner);
        if (current.type() != JobType.REREAD) {
            throw new JobNotResumableException(jobId, "kein Auftrag „Neu lesen“");
        }
        if (jobs.resumable(jobId, owner)) {
            return jobs.resume(jobId, owner);
        }
        return jobs.rerun(jobId, owner, task(current.sourceId(), statuses(current), since(current)));
    }

    /** Die Status, auf die ein Auftrag beschränkt ist; leer ohne Beschränkung. */
    public static Set<ExtractionStatus> statuses(JobStatus job) {
        String value = job.parameter(STATUSES);
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<ExtractionStatus> statuses = EnumSet.noneOf(ExtractionStatus.class);
        for (String name : value.split(",")) {
            statuses.add(ExtractionStatus.valueOf(name.strip().toUpperCase(Locale.ROOT)));
        }
        return statuses;
    }

    private Instant since(JobStatus job) {
        String value = job.parameter(SINCE);
        return value == null ? job.createdAt() : Instant.parse(value);
    }

    private JobTask task(String sourceId, Set<ExtractionStatus> statuses, Instant since) {
        return JobTask.blocking(context -> run(sourceId, statuses, since, context));
    }

    private void run(String sourceId, Set<ExtractionStatus> statuses, Instant since, JobContext context) {
        context.phase(COMPONENT, "Abgelegte Dokumente zählen");
        Map<String, List<Asset>> byContent = documents(sourceId);
        context.discovered(byContent.size());
        context.phase(COMPONENT, "Dokumente neu lesen");
        int unflushed = 0;
        long failed = 0;
        try {
            for (List<Asset> attachments : byContent.values()) {
                // Anhalten und Abbrechen gelten zwischen zwei Dokumenten; das neu Gelesene bleibt.
                context.checkpoint();
                Assets.Reread result = assets.reread(attachments.getFirst(), statuses, since);
                if (result.text()) {
                    for (Asset attachment : attachments) {
                        Optional<String> space = spaceKeys.apply(new PageKey(sourceId, attachment.pageId()));
                        if (space.isPresent() && assets.reindexStored(attachment, space.get())) {
                            unflushed++;
                        }
                    }
                    context.written(1);
                } else if (result.code() != null
                        && !DocumentTextException.Reason.UNSUPPORTED.code().equals(result.code())) {
                    failed++;
                    context.failed(1);
                }
                context.processed(1);
                if (unflushed >= FLUSH_EVERY) {
                    assets.flushTextIndex();
                    unflushed = 0;
                }
            }
        } finally {
            if (unflushed > 0) {
                assets.flushTextIndex();
            }
        }
        if (failed > 0) {
            context.incomplete();
        }
    }

    /** Je Anhang die jüngste abgelegte Version eines Dokuments, gruppiert nach Inhalt in stabiler Reihenfolge. */
    private Map<String, List<Asset>> documents(String sourceId) {
        Map<String, Asset> latest = new LinkedHashMap<>();
        try (Stream<Asset> entries = store.entries(sourceId)) {
            entries.filter(asset -> asset.kind() == AssetKind.OTHER).sorted(java.util.Comparator
                    .comparing(Asset::pageId).thenComparing(Asset::attachmentId)).forEach(asset -> latest.merge(
                    asset.pageId() + '\u0000' + asset.attachmentId(), asset,
                    (known, other) -> other.retrievedAt().isAfter(known.retrievedAt()) ? other : known));
        }
        Map<String, List<Asset>> byContent = new LinkedHashMap<>();
        latest.values().forEach(asset -> byContent.computeIfAbsent(asset.sha256(), sha -> new ArrayList<>())
                .add(asset));
        return byContent;
    }
}
