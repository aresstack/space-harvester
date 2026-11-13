package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * <p>Von selbst ({@link #afterChange}, AP5c): Beim Start vergleicht der Dienst je Quelle den eingestellten Stand der
 * Extraktion ({@link ReadState}) mit dem zuletzt vollständig gelesenen ({@link ReadStates}). Nach einem Wechsel von
 * Extraktion, Version oder Strukturmodell liest er alle veralteten Stände neu, nach geänderten Grenzen nur Dokumente,
 * die an einer Grenze enden konnten ({@link #LIMITED}, auch in enthaltenen Dateien). Den neuen Stand merkt er sich
 * erst, wenn der Auftrag durchgelaufen ist; ein unterbrochener oder abgebrochener Lauf setzt beim nächsten Start im
 * selben Eintrag fort, mit dem dann eingestellten Stand.
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
    /**
     * Angabe eines Auftrags, der von selbst startete ({@link #afterChange}): der Stand, den er herstellt; nach seinem
     * Durchlauf gemerkt.
     */
    public static final String AUTO = "auto";
    /**
     * Status, auf die „Neu lesen“ nach geänderten Grenzen beschränkt ist: was an einer Grenze teilweise gelesen,
     * gescheitert oder ausgelassen ist.
     */
    public static final Set<ExtractionStatus> LIMITED = Set.copyOf(EnumSet.of(ExtractionStatus.PARTIAL,
            ExtractionStatus.FAILED, ExtractionStatus.EXCLUDED));
    /** So viele neu gelesene Dokumente, dann wird der Volltextindex gesichert. */
    static final int FLUSH_EVERY = 50;
    private static final System.Logger LOG = System.getLogger(DocumentRereads.class.getName());

    private final Jobs jobs;
    private final Assets assets;
    private final AssetStore store;
    private final Predicate<String> configured;
    private final Function<PageKey, Optional<String>> spaceKeys;
    private final ReadStates states;
    private final Clock clock;

    /** Ohne gemerkten Stand: startet nie von selbst. */
    public DocumentRereads(Jobs jobs, Assets assets, AssetStore store, Predicate<String> configured,
                           Function<PageKey, Optional<String>> spaceKeys, Clock clock) {
        this(jobs, assets, store, configured, spaceKeys, ReadStates.NONE, clock);
    }

    /**
     * @param configured ob eine Quelle eingerichtet ist
     * @param spaceKeys  Space einer Seite aus dem Cache, für die Einträge im Volltextindex; leer für eine unbekannte
     *                   Seite (dann bleibt ihr Indexeintrag bis zur nächsten Ernte)
     * @param states     je Quelle der zuletzt vollständig gelesene Stand, für {@link #afterChange}
     */
    public DocumentRereads(Jobs jobs, Assets assets, AssetStore store, Predicate<String> configured,
                           Function<PageKey, Optional<String>> spaceKeys, ReadStates states, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.store = Objects.requireNonNull(store, "store");
        this.configured = Objects.requireNonNull(configured, "configured");
        this.spaceKeys = Objects.requireNonNull(spaceKeys, "spaceKeys");
        this.states = Objects.requireNonNull(states, "states");
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
        return start(sourceId, owner, statuses, null);
    }

    private JobStatus start(String sourceId, String owner, Set<ExtractionStatus> statuses, String auto) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(statuses, "statuses");
        if (!configured.test(sourceId)) {
            throw new UnknownSourceException(sourceId);
        }
        Instant since = clock.instant();
        return jobs.submit(JobType.REREAD, sourceId, null, null, owner, parameters(statuses, since, auto),
                task(sourceId, copy(statuses), since, auto));
    }

    private static Map<String, String> parameters(Set<ExtractionStatus> statuses, Instant since, String auto) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (!statuses.isEmpty()) {
            parameters.put(STATUSES, statuses.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        }
        parameters.put(SINCE, since.toString());
        if (auto != null) {
            parameters.put(AUTO, auto);
        }
        return parameters;
    }

    private static Set<ExtractionStatus> copy(Set<ExtractionStatus> statuses) {
        return statuses.isEmpty() ? Set.of() : EnumSet.copyOf(statuses);
    }

    /**
     * Startet „Neu lesen“ für jede dieser Quellen, deren abgelegte Dokumente nicht mit dem eingestellten Stand der
     * Extraktion gelesen sind (AP5c, beim Start des Dienstes): nach einem Wechsel von Extraktion, Version oder
     * Strukturmodell ohne Beschränkung, nach geänderten Grenzen beschränkt auf {@link #LIMITED}. Ohne gemerkten Stand
     * gilt der eingestellte, wenn die Quelle noch keine Dokumente abgelegt hat; sonst wird neu gelesen. Läuft für eine
     * Quelle schon ein solcher Auftrag oder hat der Benutzer ihn angehalten, startet kein zweiter. Einen abgebrochenen
     * (auch beim Beenden des Dienstes) oder gescheiterten (auch durch einen Neustart unterbrochenen) setzt er im selben
     * Eintrag fort; galt er einem anderen Stand, mit dem Umfang und Ziel des eingestellten. Wirft nie: Eine Quelle, für
     * die das nicht gelingt, versucht der nächste Start erneut.
     *
     * @return die gestarteten oder fortgesetzten Aufträge
     */
    public List<JobStatus> afterChange(Collection<String> sourceIds, String owner) {
        Optional<ReadState> wanted = ReadState.of(assets);
        if (wanted.isEmpty() || !states.remembers()) {
            return List.of();
        }
        String current = wanted.get().encoded();
        List<JobStatus> started = new ArrayList<>();
        for (String sourceId : sourceIds) {
            try {
                afterChange(sourceId, owner, wanted.get(), current).ifPresent(started::add);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "„Neu lesen“ für {0} nicht gestartet ({1})", sourceId,
                        e.getClass().getName());
            }
        }
        return List.copyOf(started);
    }

    private Optional<JobStatus> afterChange(String sourceId, String owner, ReadState wanted, String current) {
        Optional<ReadState> known = states.get(sourceId).flatMap(ReadState::decode);
        if (known.filter(wanted::equals).isPresent() || !configured.test(sourceId)) {
            return Optional.empty();
        }
        Set<ExtractionStatus> scope = known.isEmpty() || !known.get().extraction().equals(wanted.extraction())
                ? Set.of() : LIMITED;
        // Der jüngste offene Auftrag dieser Quelle, der von selbst startete (list liefert den jüngsten zuerst).
        Optional<JobStatus> open = jobs.list(owner, JobType.REREAD).stream()
                .filter(job -> sourceId.equals(job.sourceId()) && job.parameter(AUTO) != null
                        && job.state() != JobState.COMPLETED)
                .findFirst();
        try {
            if (open.isPresent()) {
                return continued(open.get(), owner, scope, current);
            }
            if (known.isEmpty() && !hasDocuments(sourceId)) {
                states.put(sourceId, current);
                return Optional.empty();
            }
            return Optional.of(start(sourceId, owner, scope, current));
        } catch (JobCapacityExceededException e) {
            LOG.log(System.Logger.Level.INFO, "„Neu lesen“ für {0} wartet auf den nächsten Start (kein Platz)",
                    sourceId);
            return Optional.empty();
        }
    }

    /**
     * Ein offener Auftrag von selbst: Angehalten hält ihn der Benutzer an, wartend oder laufend ist er unterwegs; sonst
     * (abgebrochen, auch beim Beenden des Dienstes, oder gescheitert, auch durch einen Neustart unterbrochen) läuft er
     * im selben Eintrag weiter, nach einem inzwischen geänderten Stand mit dessen Umfang und Ziel.
     */
    private Optional<JobStatus> continued(JobStatus job, String owner, Set<ExtractionStatus> scope, String current) {
        if (job.state() == JobState.PAUSED || !job.state().terminal()) {
            return Optional.empty();
        }
        if (current.equals(job.parameter(AUTO))) {
            return Optional.of(resume(job.id(), owner));
        }
        Instant since = clock.instant();
        return Optional.of(jobs.rerun(job.id(), owner, parameters(scope, since, current),
                task(job.sourceId(), copy(scope), since, current)));
    }

    private boolean hasDocuments(String sourceId) {
        try (Stream<Asset> entries = store.entries(sourceId)) {
            return entries.anyMatch(asset -> asset.kind() == AssetKind.OTHER);
        }
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
        return jobs.rerun(jobId, owner, task(current.sourceId(), statuses(current), since(current),
                current.parameter(AUTO)));
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

    private JobTask task(String sourceId, Set<ExtractionStatus> statuses, Instant since, String auto) {
        return JobTask.blocking(context -> {
            run(sourceId, statuses, since, context);
            if (auto != null) {
                // Durchgelaufen: Dieser Stand gilt jetzt als gelesen, auch wenn einzelne Dokumente scheiterten.
                states.put(sourceId, auto);
            }
        });
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

    /**
     * Der Stand der Extraktion, mit dem abgelegte Dokumente gelesen sind: Extraktion, Version und Strukturmodell sowie
     * die Grenzen, die ein abgelegtes Ergebnis bestimmen. Laufzeitgrenzen, Reserve und Plätze fehlen: Was an ihnen
     * abbricht, wird nie abgelegt und ohnehin neu gelesen.
     *
     * @param extraction {@code <Extraktion>/<Version>/<Strukturmodell>}
     * @param limits     die übrigen Grenzen in fester Reihenfolge
     */
    public record ReadState(String extraction, String limits) {

        private static final String SEPARATOR = "|";

        public ReadState {
            Objects.requireNonNull(extraction, "extraction");
            Objects.requireNonNull(limits, "limits");
        }

        /** Der eingestellte Stand; leer ohne strukturierte Extraktion. */
        static Optional<ReadState> of(Assets assets) {
            return assets.documentExtractions().map(found -> {
                ExtractionLimits l = found.limits();
                String memory = new java.util.TreeMap<>(l.memoryLimits()).entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(","));
                return new ReadState(found.id() + "/" + found.version() + "/" + DocumentStructure.MODEL_VERSION,
                        "file=" + l.maxFileBytes() + ";chars=" + l.maxChars() + ";unpack=" + l.unpackRatio() + "@"
                                + l.unpackRatioFromBytes() + ";text=" + l.textRatio() + "@" + l.textRatioFromChars()
                                + ";depth=" + l.maxDepth() + ";files=" + l.maxEmbeddedFiles() + ";memory=" + memory);
            });
        }

        /** Für {@link ReadStates}. */
        public String encoded() {
            return extraction + SEPARATOR + limits;
        }

        /** Aus {@link #encoded()}; leer, wenn der Wert nicht lesbar ist. */
        public static Optional<ReadState> decode(String value) {
            int at = value == null ? -1 : value.indexOf(SEPARATOR);
            return at < 1 ? Optional.empty() : Optional.of(new ReadState(value.substring(0, at),
                    value.substring(at + 1)));
        }
    }
}
