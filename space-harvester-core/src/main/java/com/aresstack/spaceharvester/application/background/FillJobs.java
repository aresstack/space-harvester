package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Die Hintergrundfüllung als Hintergrundauftrag „{@value #TITLE}“ (Fundstelle 12, Angelo 21.11.2026: nichts läuft
 * unsichtbar): je Quelle und Space ein Teil, solange dort Lücken warten oder laden. Die Füllung selbst bleibt, wie sie
 * ist ({@link BackgroundFill}, über den Planer); der Teil rechnet nichts, er zählt ihre Abrufe (geladen, gescheitert,
 * wartend) und wartet über ihre Rückrufe, ohne Thread (ADR 0027). Darum läuft er auf eigener Spur
 * ({@link BackgroundKind#ownLane()}) und belegt keinen Platz unter „Vorbereitung“.
 *
 * <ul>
 *   <li>Anhalten: Der Space ruht, bis der Teil fortgesetzt wird.</li>
 *   <li>Abbrechen: Der Space ruht bis zum nächsten abgeschlossenen Abgleich. Abgeleitet aus den Aufträgen, nicht im
 *       Speicher gehalten, übersteht es den Neustart: Ein abgebrochener Teil, jünger als der letzte abgeschlossene
 *       Abgleich des Space oder der ganzen Quelle, hält ihn ruhig.</li>
 *   <li>Gescheiterte Abrufe stehen rot mit Grund im Teil; endet er mit Fehlern, scheitert er (die Füllung versucht die
 *       Seiten nach ihrer Wartezeit erneut).</li>
 * </ul>
 *
 * Bei nur einem Platz ({@link BackgroundJobs#canRun()}) bleibt die Füllung unsichtbar wie bisher.
 */
public final class FillJobs implements BackgroundFill.Observer, AutoCloseable {

    /** Kennung der Art. */
    public static final String KIND = "fill";
    /** Titel der Gruppe. */
    public static final String TITLE = "Hintergrundfüllung";
    /** Fehlercode: Seiten ließen sich nicht laden. */
    public static final String PAGES_MISSING = PageLoads.PAGES_MISSING;
    /**
     * Angabe: der wievielte Versuch eines Teils (Angelo 22.11.2026). Holt die Füllung gescheiterte Seiten nach ihrer
     * Wartezeit erneut, läuft der gescheiterte Teil des Space im selben Eintrag weiter
     * ({@link BackgroundJobs#startOrRetryUnlessRunning}), statt je Versuch einen neuen Auftrag anzulegen.
     */
    public static final String ATTEMPTS = "fill.attempts";
    /** Code der Meldung, mit der ein weiterer Versuch beginnt. */
    public static final String RETRY = "RETRY";
    private static final String COMPONENT = "background-fill";

    private final BackgroundJobs background;
    private final Object monitor = new Object();
    /** Laufende Teile je Space, mit ihrem Abschluss; unter {@link #monitor}. */
    private final Map<BackgroundFill.Where, Live> live = new HashMap<>();
    /** Spaces mit wartendem oder laufendem Teil; unter {@link #monitor}. */
    private final Set<BackgroundFill.Where> open = new HashSet<>();
    /** Spaces, deren Teil Anhalten oder Abbrechen angefordert hat; unter {@link #monitor}. */
    private final Set<BackgroundFill.Where> stopping = new HashSet<>();
    /** Zählungen von Abrufen, die endeten, bevor ihr Teil lief; unter {@link #monitor}. */
    private final Map<BackgroundFill.Where, Early> early = new HashMap<>();
    /** Spaces mit angehaltenem Teil; unter {@link #monitor}. */
    private final Set<BackgroundFill.Where> paused = new HashSet<>();
    /** Ende des jüngsten abgebrochenen Teils je Space; unter {@link #monitor}. */
    private final Map<BackgroundFill.Where, Instant> cancelled = new HashMap<>();
    /** Der zuletzt gemeldete Zustand jedes Teils, aus den Auftragsmeldungen geführt; unter {@link #monitor}. */
    private final Map<String, JobStatus> known = new HashMap<>();
    /** Ende des jüngsten abgeschlossenen Abgleichs je Quelle und Space ({@code null}: ganze Quelle). */
    private final Map<BackgroundFill.Where, Instant> synced = new HashMap<>();
    private final Subscription parts;
    private final Subscription syncs;
    private volatile Fill fill;
    private volatile com.aresstack.spaceharvester.application.network.NetworkState network;
    private volatile Subscription networkWatch = Subscription.NONE;

    /**
     * Was die Teile von der Füllung brauchen ({@link BackgroundFill}); eigene Schnittstelle, damit Tests einen Ablauf
     * gezielt herbeiführen können, ohne Haken im Code.
     */
    interface Fill {

        boolean enabled();

        int waiting(BackgroundFill.Where where);

        void scanFor(BackgroundFill.Where where);

        void wake();

        void rest(BackgroundFill.Where where);
    }

    /** Was vor dem Lauf des Teils endete: wird beim Start nachgemeldet, nichts verschwindet still. */
    private static final class Early {

        long written;
        long failed;
        final List<Throwable> failures = new java.util.ArrayList<>();
    }

    /** Ein laufender Teil. */
    private static final class Live {

        final JobContext context;
        /** Der wievielte Versuch ({@link #ATTEMPTS}). */
        final int attempt;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        long failed;
        long processed;
        long discovered;
        /**
         * Unter {@link #monitor}: Der Teil bestellt gleich eine Suche ({@link BackgroundFill#scanFor}); bis dahin
         * beendet ihn kein „leer“, das noch aus der Zeit vor seinem Start stammt (etwa die letzte Seite, die nach dem
         * Abbrechen endete). Die Suche meldet danach selbst „leer“ oder lädt in ihn.
         */
        boolean scanAhead;
        /**
         * Unter {@link #monitor}: In der Zeit von {@link #scanAhead} kam ein „leer“. Es kann auch schon das der eben
         * bestellten Suche gewesen sein; dann sucht der Teil einmal neu, und deren „leer“ beendet ihn.
         */
        boolean idleWhileScanAhead;
        /** Unter {@link #monitor}: steht als „Wartet auf Netzverbindung“ ({@link #offline}). */
        boolean offline;

        Live(JobContext context, int attempt) {
            this.context = context;
            this.attempt = attempt;
        }
    }

    /**
     * Meldet die Art an und liest einmal, welche Spaces ruhen (angehaltene Teile, abgebrochene jünger als der letzte
     * Abgleich); danach folgt sie den Auftragsmeldungen, ohne Abfragen.
     */
    public FillJobs(BackgroundJobs background, Jobs jobs) {
        this.background = Objects.requireNonNull(background, "background");
        Objects.requireNonNull(jobs, "jobs");
        // Ausschließlich je Quelle und Space: Ein fortgesetzter alter Teil verdrängt nie den laufenden (Codex #1087).
        background.register(new BackgroundKind(KIND, this::work, () -> true, true, true));
        synchronized (monitor) {
            for (JobStatus status : jobs.listAll(JobType.SYNC)) {
                if (status.state() == JobState.COMPLETED) {
                    noteSync(status);
                }
            }
            for (JobStatus part : background.parts(KIND)) {
                track(part);
            }
        }
        this.parts = background.subscribe(KIND, status -> {
            boolean woke;
            boolean rests;
            synchronized (monitor) {
                woke = track(status);
                rests = status.state() == JobState.PAUSED || status.state() == JobState.CANCELLED;
            }
            if (rests) {
                // Auch ein noch wartender Teil, angehalten oder abgebrochen, lässt seinen Space ruhen (Abnahme #1087).
                restFill(where(status));
            }
            if (woke) {
                wakeFill();
            }
        }, this::removed);
        this.syncs = jobs.subscribeAll(new JobListener() {
            @Override
            public void changed(JobStatus status) {
                if (status.type() == JobType.SYNC && status.state() == JobState.COMPLETED) {
                    boolean woke;
                    synchronized (monitor) {
                        woke = noteSync(status);
                    }
                    if (woke) {
                        // Abbrechen gilt bis zum nächsten Abgleich: jetzt wieder suchen.
                        wakeFill();
                    }
                }
            }
        });
    }

    /**
     * Hängt die Füllung an: Sie meldet ihre Abrufe hierher und lässt ruhende Spaces aus; was schon vor dem Neustart
     * ruhte, räumt sie aus ihrer Warteschlange.
     */
    public void attach(BackgroundFill fill, Function<PageKey, Optional<String>> spaceOf) {
        Objects.requireNonNull(fill, "fill");
        this.fill = new Fill() {
            @Override
            public boolean enabled() {
                return fill.enabled();
            }

            @Override
            public int waiting(BackgroundFill.Where where) {
                return fill.waiting(where);
            }

            @Override
            public void scanFor(BackgroundFill.Where where) {
                fill.scanFor(where);
            }

            @Override
            public void wake() {
                fill.wake();
            }

            @Override
            public void rest(BackgroundFill.Where where) {
                fill.rest(where);
            }
        };
        fill.observe(spaceOf, this, this::resting);
        restResting();
    }

    /**
     * Hängt den Netzzustand an (Angelo 22.11.2026): Scheitert eine Seite am Netz, steht der Teil ihres Space als
     * „Wartet auf Netzverbindung“ mit dem Grund in der Diagnose, nichts zählt als gescheitert; ist das Netz zurück,
     * lädt er im selben Auftrag weiter.
     */
    public void network(com.aresstack.spaceharvester.application.network.NetworkState network) {
        this.network = network;
        Subscription previous = networkWatch;
        networkWatch = network == null ? Subscription.NONE : network.subscribe(status -> {
            if (status.online()) {
                back();
            }
        });
        previous.close();
    }

    /** Das Netz ist zurück: Jeder wartende Teil läuft wieder sichtbar. */
    private void back() {
        List<Live> waiting = new java.util.ArrayList<>();
        synchronized (monitor) {
            for (Live part : live.values()) {
                if (part.offline) {
                    part.offline = false;
                    waiting.add(part);
                }
            }
        }
        for (Live part : waiting) {
            part.context.awaitingNetwork(false);
            part.context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.NETWORK, COMPONENT,
                    com.aresstack.spaceharvester.application.network.NetworkWait.ONLINE,
                    "Netzverbindung zurück; der Auftrag lädt hier weiter", null, null, null));
        }
    }

    @Override
    public void offline(BackgroundFill.Where where, Throwable cause) {
        Live part;
        synchronized (monitor) {
            part = live.get(where);
        }
        if (part != null) {
            markOffline(part, cause);
        }
    }

    /** Einmal je Netzausfall: „Wartet auf Netzverbindung“ und der Grund in der Diagnose. */
    private void markOffline(Live part, Throwable cause) {
        synchronized (monitor) {
            if (part.offline) {
                return;
            }
            part.offline = true;
        }
        com.aresstack.spaceharvester.application.network.NetworkState net = network;
        String why = net != null && net.status().reason() != null ? net.status().reason()
                : cause == null ? "Netz nicht erreichbar" : cause.getClass().getSimpleName();
        part.context.awaitingNetwork(true);
        part.context.record(new JobEvent.Draft(JobEvent.Severity.WARNING, JobEvent.Category.NETWORK, COMPONENT,
                com.aresstack.spaceharvester.application.network.NetworkWait.OFFLINE, "Keine Netzverbindung (" + why
                + "): Der Auftrag wartet, bis sie zurück ist, und lädt dann von selbst hier weiter; nichts zählt als "
                + "gescheitert", null, null, cause));
        if (net != null && net.online()) {
            // Inzwischen zurück: Die Meldung darüber kam vielleicht schon vor dem Vermerk.
            back();
        }
    }

    /** Hängt eine Füllung an, ohne dass sie hierher meldet; für Tests, die Meldungen selbst geben. */
    void attach(Fill fill) {
        this.fill = Objects.requireNonNull(fill, "fill");
        restResting();
    }

    /** Was schon vor dem Neustart ruhte, räumt die Füllung aus ihrer Warteschlange. */
    private void restResting() {
        Fill current = fill;
        Set<BackgroundFill.Where> resting = new HashSet<>();
        synchronized (monitor) {
            resting.addAll(paused);
            cancelled.keySet().stream().filter(this::restingLocked).forEach(resting::add);
        }
        resting.forEach(current::rest);
    }

    private void wakeFill() {
        Fill current = fill;
        if (current != null) {
            current.wake();
        }
    }

    /** Lässt den Space in der Füllung ruhen; kehrt sofort zurück, die Füllung räumt auf ihrem Arbeiter ab. */
    private void restFill(BackgroundFill.Where where) {
        Fill current = fill;
        if (current != null) {
            current.rest(where);
        }
    }

    /**
     * Ein Teil wurde gelöscht (von Hand oder nach der Aufbewahrungsfrist): Der Zustand seines Space folgt den übrigen
     * Teilen, sonst ruhte er bis zum Neustart (Abnahme #1087).
     */
    private void removed(JobStatus last) {
        BackgroundFill.Where where = where(last);
        boolean woke;
        synchronized (monitor) {
            boolean wasResting = restingLocked(where);
            known.remove(last.id());
            // Aus den Meldungen geführt, ohne die Aufträge zu lesen (Abnahme #1087).
            List<JobStatus> others = known.values().stream().filter(part -> where.equals(where(part))).toList();
            boolean otherOpen = others.stream().anyMatch(part -> !part.state().terminal()
                    && part.state() != JobState.PAUSED);
            boolean otherPaused = others.stream().anyMatch(part -> part.state() == JobState.PAUSED);
            if (!otherOpen && !live.containsKey(where)) {
                open.remove(where);
            }
            if (!otherPaused) {
                paused.remove(where);
            }
            if (!otherOpen && !otherPaused && !live.containsKey(where)) {
                // Nur, wenn kein anderer Teil des Space offen, angehalten oder im Stoppen ist.
                stopping.remove(where);
                early.remove(where);
            }
            cancelled.remove(where);
            others.stream().filter(part -> part.state() == JobState.CANCELLED && part.finishedAt() != null)
                    .forEach(part -> cancelled.merge(where, part.finishedAt(), (a, b) -> a.isAfter(b) ? a : b));
            woke = wasResting && !restingLocked(where);
        }
        if (woke) {
            wakeFill();
        }
    }

    private static BackgroundFill.Where where(JobStatus status) {
        return new BackgroundFill.Where(status.sourceId() == null ? "" : status.sourceId(), status.partitionKey());
    }

    /** Unter {@link #monitor}: der Zustand eines Teils; {@code true}, wenn sein Space nicht mehr ruht. */
    private boolean track(JobStatus part) {
        BackgroundFill.Where where = where(part);
        boolean wasResting = restingLocked(where);
        known.put(part.id(), part);
        switch (part.state()) {
            case QUEUED, RUNNING -> {
                open.add(where);
                paused.remove(where);
                // Ein fortgesetzter abgebrochener Teil lässt den Space nicht mehr ruhen (Abnahme #1087).
                cancelled.remove(where);
            }
            case PAUSED -> {
                open.remove(where);
                stopping.remove(where);
                paused.add(where);
            }
            case CANCELLED -> {
                open.remove(where);
                stopping.remove(where);
                paused.remove(where);
                Instant at = part.finishedAt();
                if (at != null) {
                    cancelled.merge(where, at, (a, b) -> a.isAfter(b) ? a : b);
                }
                // Was beim Abbrechen noch endete, gehört keinem späteren Teil (Abnahme #1087).
                if (!live.containsKey(where)) {
                    early.remove(where);
                }
            }
            default -> {
                open.remove(where);
                stopping.remove(where);
                paused.remove(where);
            }
        }
        return wasResting && !restingLocked(where);
    }

    /** Unter {@link #monitor}: merkt einen abgeschlossenen Abgleich; {@code true}, wenn dadurch ein Space aufwacht. */
    private boolean noteSync(JobStatus sync) {
        if (sync.sourceId() == null || sync.finishedAt() == null) {
            return false;
        }
        Set<BackgroundFill.Where> before = new HashSet<>();
        cancelled.keySet().stream().filter(this::restingLocked).forEach(before::add);
        synced.merge(new BackgroundFill.Where(sync.sourceId(), sync.partitionKey()), sync.finishedAt(),
                (a, b) -> a.isAfter(b) ? a : b);
        return before.stream().anyMatch(where -> !restingLocked(where));
    }

    /** Ob ein Space ruht (angehalten, im Anhalten oder abgebrochen seit dem letzten Abgleich). */
    public boolean resting(BackgroundFill.Where where) {
        synchronized (monitor) {
            return restingLocked(where);
        }
    }

    private boolean restingLocked(BackgroundFill.Where where) {
        if (stopping.contains(where) || paused.contains(where)) {
            return true;
        }
        Instant stopped = cancelled.get(where);
        if (stopped == null) {
            return false;
        }
        Instant space = synced.get(where);
        Instant source = synced.get(new BackgroundFill.Where(where.sourceId(), null));
        Instant last = space == null ? source : source == null ? space : space.isAfter(source) ? space : source;
        return last == null || stopped.isAfter(last);
    }

    @Override
    public void handed(BackgroundFill.Where where) {
        Live part;
        boolean start;
        // Außerhalb der eigenen Sperre: canRun nimmt die Sperre der Aufträge, deren Stopp-Rückruf diese hier nimmt.
        boolean canRun = background.canRun();
        synchronized (monitor) {
            part = live.get(where);
            start = part == null && !open.contains(where) && canRun && !restingLocked(where);
            if (start) {
                // Vor dem Start vermerkt, damit die nächste Seite keinen zweiten Teil anlegt.
                open.add(where);
            }
        }
        if (part != null) {
            count(part, where, 0);
            return;
        }
        if (start) {
            try {
                // Liegt schon ein offener Teil (etwa nach dem Neustart fortgesetzt), entsteht kein zweiter; ist der
                // jüngste gescheitert, ist dies seine Wiederholung und er läuft im selben Eintrag weiter.
                background.startOrRetryUnlessRunning(KIND, TITLE, new BackgroundPart.Draft(
                        where.sourceId(), where.spaceKey(), Map.of()), ATTEMPTS);
            } catch (RuntimeException e) {
                synchronized (monitor) {
                    open.remove(where);
                }
            }
        }
    }

    @Override
    public void completed(BackgroundFill.Where where, boolean stored, Throwable failure) {
        Live part;
        synchronized (monitor) {
            // In einem Sperrblock mit dem Nachmelden: Sonst könnte der Teil dazwischen starten und die Zählung verpassen.
            part = live.get(where);
            if (part != null) {
                part.processed++;
                if (!stored) {
                    part.failed++;
                }
            } else if (open.contains(where) || paused.contains(where) || stopping.contains(where)) {
                // Der Teil ist angelegt oder angehalten, läuft aber gerade nicht: beim (Wieder-)Start nachmelden.
                Early tally = early.computeIfAbsent(where, ignored -> new Early());
                if (stored) {
                    tally.written++;
                } else {
                    tally.failed++;
                    if (tally.failures.size() < 20) {
                        tally.failures.add(failure);
                    }
                }
            }
        }
        if (part == null) {
            return;
        }
        count(part, where, 0);
        part.context.processed(1);
        if (stored) {
            part.context.written(1);
            return;
        }
        part.context.failed(1);
        // Rot mit Grund: nichts verschwindet still.
        part.context.record(new JobEvent.Draft(JobEvent.Severity.ERROR, JobEvent.Category.SOURCE, COMPONENT,
                PAGES_MISSING, "Eine Seite ließ sich nicht laden: " + reason(failure), null, null, failure));
    }

    /**
     * Hält „gefunden“ bei erledigt plus wartend, damit der Teil zeigt, wie viele Seiten noch warten; nie unter
     * {@link #monitor} gerufen, weil die Füllung ihre eigene Sperre nimmt.
     */
    private void count(Live part, BackgroundFill.Where where, int adjust) {
        Fill current = fill;
        // Erst die erledigten, dann die wartenden: Eine Seite verlässt die Schlange, bevor sie als erledigt zählt. So
        // zählt sie höchstens kurz gar nicht, nie doppelt; „gefunden“ sinkt nie und bliebe sonst zu hoch (CI #1101).
        long processed;
        synchronized (monitor) {
            processed = part.processed;
        }
        int waiting = current == null ? 0 : Math.max(0, current.waiting(where) + adjust);
        long more;
        synchronized (monitor) {
            long target = processed + waiting;
            more = target - part.discovered;
            if (more > 0) {
                part.discovered = target;
            }
        }
        if (more > 0) {
            part.context.discovered(more);
        }
    }

    private static String reason(Throwable failure) {
        return PageFailures.reason(failure);
    }

    @Override
    public void idle(BackgroundFill.Where where) {
        Live part;
        synchronized (monitor) {
            Live current = live.get(where);
            if (current != null && current.scanAhead) {
                // Vielleicht ein „leer“ von vor dem Start des Teils: Seine Suche ist bestellt und meldet danach selbst;
                // war es schon ihres, sucht er einmal neu (CI #1092).
                current.idleWhileScanAhead = true;
                return;
            }
            part = live.remove(where);
            if (part != null) {
                // Gleich frei für die nächste Welle: Nicht erst die verzögerte Auftragsmeldung abwarten, sonst legte
                // eine jetzt übergebene Seite keinen Teil an (Codex #1087).
                open.remove(where);
            }
        }
        if (part != null) {
            finish(part);
        }
    }

    private static void finish(Live part) {
        if (part.failed > 0) {
            part.done.completeExceptionally(new JobFailedException(PAGES_MISSING, part.failed
                    + (part.failed == 1 ? " Seite ließ" : " Seiten ließen") + " sich nicht laden"
                    + (part.attempt > 1 ? " (Versuch " + part.attempt + ")" : "")
                    + "; die Füllung versucht sie nach ihrer Wartezeit erneut, in diesem Auftrag"));
        } else {
            part.done.complete(null);
        }
    }

    private JobTask work(BackgroundPart part) {
        BackgroundFill.Where where = new BackgroundFill.Where(part.sourceId() == null ? "" : part.sourceId(),
                part.partitionKey());
        int attempt = BackgroundJobs.attempt(part.parameters().get(ATTEMPTS));
        return context -> {
            Live mine = new Live(context, attempt);
            if (attempt > 1) {
                // Jeder Versuch steht in der Diagnose, die Gründe der früheren bleiben darüber stehen.
                context.record(new JobEvent.Draft(JobEvent.Severity.INFO, JobEvent.Category.JOB, COMPONENT, RETRY,
                        "Versuch " + attempt + ": Die Füllung lädt die gescheiterten Seiten erneut", null, null, null));
            }
            boolean woke;
            Early tally;
            Fill current = fill;
            boolean active = current != null && current.enabled();
            // Fortgesetzt (die Schlange des Space wurde beim Anhalten geräumt) oder dort wartet nichts: Der Teil setzt
            // seine Arbeit fort, die Füllung sucht für ihn sofort im Katalog und meldet „leer“, wenn sie nichts (mehr)
            // findet; keine neue Gruppe je Pause (Abnahme #1087).
            boolean rescan = active && (part.resumed() || current.waiting(where) == 0);
            mine.scanAhead = rescan;
            synchronized (monitor) {
                Live before = live.put(where, mine);
                if (before != null) {
                    before.done.complete(null);
                }
                open.add(where);
                stopping.remove(where);
                // Selbst geräumt, nicht erst über die gebündelten Meldungen (Abnahme #1087): Der Space ruht nicht mehr.
                woke = paused.remove(where) | cancelled.remove(where) != null;
                tally = early.remove(where);
                if (tally != null) {
                    mine.processed = tally.written + tally.failed;
                    mine.failed = tally.failed;
                }
            }
            com.aresstack.spaceharvester.application.network.NetworkState net = network;
            if (net != null && !net.online()) {
                // Schon offline beim Start: Die Füllung übergibt nichts, bis das Netz zurück ist.
                markOffline(mine, null);
            }
            if (tally != null) {
                context.processed(tally.written + tally.failed);
                context.written(tally.written);
                context.failed(tally.failed);
                tally.failures.forEach(failure -> context.record(new JobEvent.Draft(JobEvent.Severity.ERROR,
                        JobEvent.Category.SOURCE, COMPONENT, PAGES_MISSING, "Eine Seite ließ sich nicht laden: "
                        + reason(failure), null, null, failure)));
            }
            context.whenStopRequested(() -> {
                synchronized (monitor) {
                    stopping.add(where);
                    live.remove(where, mine);
                }
                // Nur vormerken: Der Rückruf läuft unter der Sperre der Aufträge, die Füllung räumt auf ihrem Arbeiter
                // ab, in einem Commit (Abnahme #1087).
                restFill(where);
                try {
                    context.checkpoint();
                    mine.done.complete(null);
                } catch (RuntimeException e) {
                    mine.done.completeExceptionally(e);
                }
            });
            if (active && !rescan && current.waiting(where) == 0) {
                // Endete die letzte wartende Seite, nachdem „wartend“ oben gelesen war, aber bevor der Teil in live
                // stand, zählte sie nur nachgemeldet und ihr „leer“ fand keinen Teil; ohne neue Suche liefe er ewig
                // (CI #1121). Jetzt steht er in live: Jede spätere Seite meldet sich selbst, für die früheren sucht er,
                // nach derselben Regel wie ein fortgesetzter Teil („leer“ zählt erst nach der eigenen Suche).
                rescan = true;
                synchronized (monitor) {
                    mine.scanAhead = true;
                }
            }
            if (!active) {
                // Ohne laufende Füllung gibt es nichts zu zeigen.
                synchronized (monitor) {
                    live.remove(where, mine);
                }
                count(mine, where, 0);
                finish(mine);
            } else {
                count(mine, where, 0);
                if (rescan) {
                    current.scanFor(where);
                    boolean again;
                    synchronized (monitor) {
                        mine.scanAhead = false;
                        again = mine.idleWhileScanAhead;
                        mine.idleWhileScanAhead = false;
                    }
                    if (again) {
                        current.scanFor(where);
                    }
                }
            }
            if (woke) {
                wakeFill();
            }
            return mine.done;
        };
    }

    @Override
    public void close() {
        parts.close();
        syncs.close();
        networkWatch.close();
    }
}
