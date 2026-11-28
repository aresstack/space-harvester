package com.aresstack.spaceharvester.application.background;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionStatus;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.application.signin.SignInPendingException;
import com.aresstack.spaceharvester.application.sources.SignInRequiredException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * „Seiten nachladen“ als Hintergrundauftrag ({@value #KIND}): Seiten, die die App von sich aus braucht, etwa fehlende
 * Nachbarseiten eines Suchtreffers (R2), gehen je Space als Unterauftrag an den {@link AcquisitionScheduler}, auf dem
 * Weg des Hintergrunds ({@link AcquisitionScheduler.Priority#BACKGROUND}); der legt sie ab, Volltextindex und
 * Anreicherungen folgen von selbst. Was schon im Cache liegt, wird übersprungen, darum holt ein Fortsetzen nur, was noch
 * fehlt. Der Hintergrund lädt nie mit der Anmeldung eines Benutzers; nach einem Neustart lädt der technische Aufrufer des
 * Dienstes weiter.
 */
public final class PageLoads {

    /** Kennung der Art. */
    public static final String KIND = "pages";
    /** Angabe: die Seiten-IDs des Unterauftrags, durch {@value #SEPARATOR} getrennt. */
    public static final String PAGES = "pages";
    static final String SEPARATOR = "\n";
    /** So viele Abrufe hat ein Unterauftrag höchstens zugleich beim Planer. */
    static final int WINDOW = 32;
    /** Fehlercode: Seiten ließen sich nicht laden. */
    public static final String PAGES_MISSING = "PAGES_MISSING";
    /** Angabe: {@code true}, wenn der Teil auch Seiten im Cache lädt, etwa veraltete Treffer (Fundstelle 10). */
    public static final String REFRESH = "refresh";
    /**
     * Angabe eines Teils mit der Anmeldung eines Benutzers: Kennung seines Aufrufers in diesem Prozess (wie
     * {@code StatementAnalysisJobs#CALLER}). Den Aufrufer selbst hält nur der Speicher.
     */
    public static final String CALLER = "caller";
    /** Angabe dazu: Eigentümer ({@link AccessContext#jobOwner()}) des Aufrufers. */
    public static final String OWNER = "owner";
    /** Code des gelben Hinweises: Nach dem Neustart fehlt die Anmeldung des Benutzers, der Teil endet. */
    public static final String NOT_RESUMED = "NOT_RESUMED";
    /**
     * Angabe: der Anlass im Änderungsprotokoll ({@link ChangeTrigger}, ADR 0030), ausdrücklich durchgereicht (Abnahme
     * #1080), etwa {@code SEARCH} für veraltete Treffer, egal mit welchem Aufrufer oder auf welchem Platz sie laden.
     */
    public static final String CHANGE_TRIGGER = "change-trigger";
    private static final String COMPONENT = "background-pages";

    private final BackgroundJobs background;
    private final AcquisitionScheduler scheduler;
    private final AccessContext technical;
    private final Predicate<PageKey> cached;
    private final Function<PageKey, Optional<String>> space;
    /**
     * Aufrufer der Gruppen in diesem Prozess, je Gruppe und je Kennung ({@value #CALLER}); ein Eintrag fällt erst, wenn
     * kein Teil seiner Gruppe mehr lädt oder angehalten ist (Abnahme #1080), nie vorher. Danach und nach einem Neustart
     * gilt {@link #technical}, für einen Benutzer mit Anmeldung {@value #NOT_RESUMED}.
     */
    private final Map<String, AccessContext> callers = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, com.aresstack.spaceharvester.application.observe.Subscription> watched =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Sperre aller Überladungen von {@code join}: Zwei Aufrufe sehen nie denselben Stand der wartenden Teile und starten
     * dieselbe Seite doppelt. {@link java.util.concurrent.locks.ReentrantLock} statt {@code synchronized}, weil darin
     * der Auftragsspeicher gelesen und geschrieben wird; ein virtueller Thread gibt dabei seinen Träger frei. Der
     * {@link Wake} hat seine eigene Sperre.
     */
    private final java.util.concurrent.locks.ReentrantLock joining = new java.util.concurrent.locks.ReentrantLock();
    /**
     * Die offenen Teile dieser Art (wartend, laufend, angehalten) mit ihrem jüngsten Stand, je Teil-ID: ein Abbild aus
     * den Auftragsmeldungen (ADR 0027, Abnahme #1132), damit {@link #join} auf dem Weg der Suchtreffer nie den
     * Auftragsspeicher liest. Nur beim Start einmal aus dem Speicher gefüllt. Gestartete und neu laufende Teile kommen
     * sofort hinein, nicht erst mit der Meldung. Nur unter {@link #image}.
     */
    private final Map<String, com.aresstack.spaceharvester.application.jobs.JobStatus> open = new java.util.HashMap<>();
    /**
     * Sperre des Abbilds ({@link #open}, {@link #gone}, {@link #starting}, {@link #rerunning}, {@link #beforeRerun},
     * {@link #seeding}); nie über Ein- und Ausgabe gehalten, nur über Kartenzugriffe.
     */
    private final java.util.concurrent.locks.ReentrantLock image = new java.util.concurrent.locks.ReentrantLock();
    /** Gruppen, die {@link #join} gerade startet, bis ihre Teile im Abbild stehen. */
    private final Set<String> starting = new java.util.HashSet<>();
    /**
     * Teile, die gerade im selben Eintrag neu starten ({@link BackgroundJobs#onRerun}), bis ihr Stand im Abbild steht;
     * je Teil die Zahl laufender Neustarts, nicht nur ob einer läuft (Abnahme #1133): Zwei können überlappen, etwa ein
     * Doppelklick auf Fortsetzen; der gescheiterte darf {@link #gone} nicht räumen, solange der andere noch läuft.
     */
    private final Map<String, Integer> rerunning = new java.util.HashMap<>();
    /**
     * Je neu startendem Teil sein Eintrag in {@link #open} beim ersten {@code before()} (auch {@code null}): Steht nach
     * dem Neustart noch genau dieser Eintrag da, kam keine Meldung dazwischen, und der Stand des Neustarts ist der
     * jüngere. Sonst gilt die Meldung; las sie noch den alten Stand, folgt die des Neustarts.
     */
    private final Map<String, com.aresstack.spaceharvester.application.jobs.JobStatus> beforeRerun =
            new java.util.HashMap<>();
    /**
     * Teile, deren Ende gemeldet wurde, während sie starteten, neu starteten oder das Abbild gefüllt wurde, mit dem Anlass
     * (Gruppe oder Teil): Der ältere Stand aus {@code start()}, dem Neustart oder dem Speicher bringt sie nicht als offen
     * zurück. Der Anlass räumt seine Einträge am Ende selbst.
     */
    private final Map<String, String> gone = new java.util.HashMap<>();
    private boolean seeding = true;
    /** Die Auftragsmeldungen für {@link #open}; lebt so lange wie der Dienst. */
    private final com.aresstack.spaceharvester.application.observe.Subscription parts;
    /** Die Neustarts für {@link #open}; lebt so lange wie der Dienst. */
    private final com.aresstack.spaceharvester.application.observe.Subscription reruns;

    /**
     * @param technical der technische Aufrufer des Dienstes, für fortgesetzte Unteraufträge nach einem Neustart
     * @param cached    ob eine Seite schon vollständig im Cache liegt
     * @param space     der Space einer Seite, soweit bekannt; sonst zählt sie zum Unterauftrag ohne Space
     */
    public PageLoads(BackgroundJobs background, AcquisitionScheduler scheduler, AccessContext technical,
                     Predicate<PageKey> cached, Function<PageKey, Optional<String>> space) {
        this.background = Objects.requireNonNull(background, "background");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.technical = Objects.requireNonNull(technical, "technical");
        this.cached = Objects.requireNonNull(cached, "cached");
        this.space = Objects.requireNonNull(space, "space");
        background.register(new BackgroundKind(KIND, this::work));
        this.parts = background.subscribe(KIND, this::track, this::untrack);
        // Einmal beim Start: was vor dem Neustart offen war. Meldungen währenddessen gehen vor (gone).
        for (BackgroundGroup group : background.groups()) {
            if (KIND.equals(group.kind())) {
                group.parts().forEach(this::adopt);
            }
        }
        image.lock();
        try {
            seeding = false;
            gone.values().removeIf(SEEDING::equals);
        } finally {
            image.unlock();
        }
        this.reruns = background.onRerun(rerunWatch);
    }

    /** Hält das Abbild bei Neustarts im selben Eintrag aktuell; paketsichtbar für Tests überlappender Neustarts. */
    final BackgroundJobs.Reruns rerunWatch = new BackgroundJobs.Reruns() {
        @Override
        public void before(String partId) {
            guard(() -> {
                if (rerunning.merge(partId, 1, Integer::sum) == 1) {
                    beforeRerun.put(partId, open.get(partId));
                }
            });
        }

        @Override
        public void after(String partId, com.aresstack.spaceharvester.application.jobs.JobStatus rerun) {
            guard(() -> {
                // In einem Zug (Abnahme #1137): Ist das Ende schon gemeldet, bringt kein Neustart seinen älteren Stand
                // zurück, und die Marke bleibt für die übrigen überlappenden stehen; erst der letzte räumt sie.
                if (rerun != null && KIND.equals(rerun.parameter(BackgroundJobs.KIND))
                        && !gone.containsKey(partId) && !gone.containsKey(rerun.id())) {
                    com.aresstack.spaceharvester.application.jobs.JobStatus now = open.get(rerun.id());
                    if (now != null && now == beforeRerun.get(partId) && loading(rerun.state())) {
                        // Ein fortgesetzter angehaltener Teil stand noch als angehalten da (Codex-Befund nach #1140):
                        // Ohne Ersatz startete ein Refresh bis zur gebündelten Meldung eine doppelte Gruppe.
                        open.put(rerun.id(), rerun);
                    } else {
                        adopt(rerun);
                    }
                }
                if (rerunning.merge(partId, -1, Integer::sum) <= 0) {
                    rerunning.remove(partId);
                    beforeRerun.remove(partId);
                    gone.values().removeIf(partId::equals);
                }
            });
        }
    };

    /** Anlass in {@link #gone} für Enden, die beim Füllen gemeldet wurden. */
    private static final String SEEDING = "";

    private void guard(Runnable change) {
        image.lock();
        try {
            change.run();
        } finally {
            image.unlock();
        }
    }

    /** Auftragsmeldung: der jüngste Stand eines Teils dieser Art. */
    private void track(com.aresstack.spaceharvester.application.jobs.JobStatus status) {
        if (!loading(status.state())) {
            untrack(status);
            return;
        }
        guard(() -> open.put(status.id(), status));
    }

    /** Auftragsmeldung: Der Teil lädt nicht mehr oder ist gelöscht. */
    private void untrack(com.aresstack.spaceharvester.application.jobs.JobStatus last) {
        guard(() -> {
            open.remove(last.id());
            String group = groupOf(last);
            if (starting.contains(group)) {
                gone.put(last.id(), group);
            } else if (rerunning.containsKey(last.id())) {
                gone.put(last.id(), last.id());
            } else if (seeding) {
                gone.put(last.id(), SEEDING);
            }
        });
    }

    /**
     * Übernimmt einen Stand aus dem Speicher, aus {@code start()} oder aus einem Neustart, ohne einen jüngeren aus
     * Meldungen zu ersetzen und ohne einen schon beendet gemeldeten zurückzubringen.
     */
    private void adopt(com.aresstack.spaceharvester.application.jobs.JobStatus status) {
        guard(() -> {
            if (open.containsKey(status.id()) || gone.remove(status.id()) != null || !loading(status.state())) {
                return;
            }
            open.put(status.id(), status);
        });
    }

    private static String groupOf(com.aresstack.spaceharvester.application.jobs.JobStatus status) {
        String group = status.parameter(BackgroundJobs.GROUP);
        return group != null ? group : status.id();
    }

    /**
     * Lädt die Seiten im Hintergrund, je Quelle und Space ein Unterauftrag; schon gespeicherte fallen vorher heraus,
     * ebenso Seiten, die schon ein wartender oder laufender Unterauftrag dieser Art lädt (keine doppelten Gruppen, etwa
     * wenn dieselbe Suche zweimal läuft).
     *
     * @param caller technischer Aufrufer (ohne Benutzeranmeldung), dessen Rechte gelten
     * @param title  Titel der Gruppe in der Auftragsliste, etwa „Nachbarseiten laden“
     * @return die Gruppe, oder leer, wenn nichts fehlt
     * @throws IllegalArgumentException wenn der Aufrufer eine Benutzeranmeldung durchreicht
     */
    public Optional<BackgroundGroup> load(AccessContext caller, String title, Collection<PageKey> keys) {
        return join(caller, title, keys).started();
    }

    /**
     * Wie {@link #load}, nennt dazu die Gruppen, die übersprungene Seiten schon laden: Wer auf die Seiten wartet,
     * wartet auf alle beteiligten Gruppen.
     */
    public Joined join(AccessContext caller, String title, Collection<PageKey> keys) {
        Objects.requireNonNull(caller, "caller");
        if (caller.credential() != null) {
            throw new IllegalArgumentException("Der Hintergrund lädt nie mit der Anmeldung eines Benutzers");
        }
        return join(caller, title, keys, false);
    }

    /**
     * Wie {@link #join(AccessContext, String, Collection)}, auch für einen Benutzer mit Anmeldung und auf Wunsch für
     * Seiten im Cache (Fundstelle 10, Abnahme PR 4): Mit {@code refresh} lädt der Teil auch Seiten, die schon gespeichert
     * sind, etwa weil die Suche sie als veraltet meldet. Die Anmeldung eines Benutzers hält nur der Speicher
     * ({@value #CALLER}); seine Abrufe gehen über den Vordergrund des Planers, der den Hintergrund nie mit einer
     * Anmeldung lädt. Nach einem Neustart endet so ein Teil gelb ({@value #NOT_RESUMED}), statt mit fremden Rechten
     * weiterzuladen; die nächste Suche holt nach.
     */
    public Joined join(AccessContext caller, String title, Collection<PageKey> keys, boolean refresh) {
        return join(caller, title, keys, refresh, null);
    }

    /**
     * Wie {@link #join(AccessContext, String, Collection, boolean)}, mit dem Anlass im Änderungsprotokoll (ADR 0030):
     * ausdrücklich durchgereicht bis zum Abruf (Abnahme #1080), auch nach einem Neustart.
     *
     * @param trigger der Anlass, oder {@code null} für den des Wegs (Hintergrund bzw. Suche)
     */
    public Joined join(AccessContext caller, String title, Collection<PageKey> keys, boolean refresh,
                       ChangeTrigger trigger) {
        Objects.requireNonNull(caller, "caller");
        joining.lock();
        try {
            return joinLocked(caller, title, keys, refresh, trigger);
        } finally {
            joining.unlock();
        }
    }

    /** Unter {@link #joining}: das Abgleichen mit wartenden Teilen und der Start gehen nicht durcheinander. */
    private Joined joinLocked(AccessContext caller, String title, Collection<PageKey> keys, boolean refresh,
                              ChangeTrigger trigger) {
        // Ein Teil ohne refresh überspränge eine veraltete Seite im Cache: Nachladen schließt sich nur seinesgleichen an.
        Map<PageKey, String> queued = queued(refresh, trigger);
        Set<String> joined = new LinkedHashSet<>();
        Map<List<String>, Set<String>> bySpace = new LinkedHashMap<>();
        for (PageKey key : keys) {
            String loading = queued.get(key);
            if (loading != null) {
                joined.add(loading);
                continue;
            }
            if (!refresh && cached.test(key)) {
                continue;
            }
            String spaceKey = space.apply(key).orElse(null);
            bySpace.computeIfAbsent(Arrays.asList(key.sourceId(), spaceKey), ignored -> new LinkedHashSet<>())
                    .add(key.pageId());
        }
        if (bySpace.isEmpty()) {
            return new Joined(Optional.empty(), joined);
        }
        Map<String, String> common = new LinkedHashMap<>();
        if (refresh) {
            common.put(REFRESH, "true");
        }
        if (trigger != null) {
            common.put(CHANGE_TRIGGER, trigger.name());
        }
        // Den Aufrufer vor dem Start ablegen: ein Unterauftrag kann schon während start() laufen.
        String groupId = BackgroundJobs.newGroupId();
        List<String> held = new ArrayList<>();
        held.add(groupId);
        callers.put(groupId, caller);
        if (caller.credential() != null) {
            String token = java.util.UUID.randomUUID().toString();
            callers.put(token, caller);
            held.add(token);
            common.put(CALLER, token);
            common.put(OWNER, caller.jobOwner());
        }
        List<BackgroundPart.Draft> parts = new ArrayList<>();
        bySpace.forEach((where, pageIds) -> {
            Map<String, String> parameters = new LinkedHashMap<>(common);
            parameters.put(PAGES, String.join(SEPARATOR, pageIds));
            parts.add(new BackgroundPart.Draft(where.get(0), where.get(1), parameters));
        });
        // Vor dem Start angemeldet: Auch ein Ende während start() kommt an.
        watched.put(groupId, background.watch(groupId, group -> {
            if (ended(group)) {
                forget(groupId, held);
            }
        }));
        BackgroundGroup started;
        guard(() -> starting.add(groupId));
        try {
            try {
                started = background.start(groupId, KIND, title, parts);
            } catch (RuntimeException e) {
                forget(groupId, held);
                throw e;
            }
            // Sofort ins Abbild, nicht erst mit der Meldung: Der nächste join sieht die Seiten schon als geladen.
            started.parts().forEach(this::adopt);
        } finally {
            guard(() -> {
                starting.remove(groupId);
                gone.values().removeIf(groupId::equals);
            });
        }
        if (ended(started)) {
            forget(groupId, held);
        }
        return new Joined(Optional.of(started), joined);
    }

    /** Wie viele Aufrufer dieser Prozess gerade hält; für Tests. */
    int heldCallers() {
        return callers.size();
    }

    /** Kein Teil der Gruppe lädt mehr oder ist angehalten: Fortsetzen geht nicht mehr. */
    private static boolean ended(BackgroundGroup group) {
        return group.parts().stream().noneMatch(part -> loading(part.state()));
    }

    /** Lässt den Aufrufer einer beendeten Gruppe los (Abnahme #1080: erst jetzt, nie vorher). */
    private void forget(String groupId, List<String> held) {
        held.forEach(callers::remove);
        com.aresstack.spaceharvester.application.observe.Subscription subscription = watched.remove(groupId);
        if (subscription != null) {
            subscription.close();
        }
    }

    /**
     * Ausgang von {@link #join}.
     *
     * @param started die neue Gruppe für Seiten, die noch niemand lädt, oder leer
     * @param joined  Gruppen, die übersprungene Seiten schon laden (auch angehaltene)
     */
    public record Joined(Optional<BackgroundGroup> started, Set<String> joined) {

        public Joined {
            Objects.requireNonNull(started, "started");
            joined = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(joined));
        }

        /** Alle Gruppen, auf die zu warten ist, die neue zuerst. */
        public Set<String> groups() {
            Set<String> all = new LinkedHashSet<>();
            started.ifPresent(group -> all.add(group.groupId()));
            all.addAll(joined);
            return java.util.Collections.unmodifiableSet(all);
        }
    }

    /**
     * Seiten, die ein wartender, laufender oder angehaltener Unterauftrag dieser Art schon lädt, mit seiner Gruppe.
     * Angehalten zählt mit: Er lädt weiter, sobald er fortgesetzt wird.
     *
     * @param refreshing nur Teile, die auch Seiten im Cache laden ({@value #REFRESH}), und davon nur wartende oder
     *                   laufende (Abnahme #1080): Ein angehaltener, etwa nach abgelaufener Anmeldung, lüde die als
     *                   veraltet gemeldeten Seiten nie
     */
    private Map<PageKey, String> queued(boolean refreshing, ChangeTrigger trigger) {
        Map<PageKey, String> queued = new java.util.HashMap<>();
        List<com.aresstack.spaceharvester.application.jobs.JobStatus> parts;
        image.lock();
        try {
            parts = new ArrayList<>(open.values());
        } finally {
            image.unlock();
        }
        // Jüngste zuerst, wie zuvor die Gruppen (BackgroundJobs#groups): eine Seite zählt zur jüngsten Gruppe.
        parts.sort(java.util.Comparator.comparing(
                com.aresstack.spaceharvester.application.jobs.JobStatus::createdAt).reversed());
        for (com.aresstack.spaceharvester.application.jobs.JobStatus part : parts) {
            String pages = part.parameter(PAGES);
            if (!loading(part.state()) || pages == null || part.sourceId() == null
                    || trigger != ChangeTrigger.SEARCH && ChangeTrigger.SEARCH.name().equals(part.parameter(CHANGE_TRIGGER))
                    || refreshing && (!"true".equals(part.parameter(REFRESH)) || part.state().terminal())) {
                continue;
            }
            for (String pageId : pages.split(SEPARATOR)) {
                if (!pageId.isBlank()) {
                    queued.putIfAbsent(new PageKey(part.sourceId(), pageId), groupOf(part));
                }
            }
        }
        return queued;
    }

    /**
     * Ob die Aufträge auf dem Platz laufen können ({@link BackgroundJobs#canRun()}); sonst nehmen die Aufrufer ihren
     * bisherigen Weg (Abnahme #1080: bei nur einem Platz läuft kein Hintergrund).
     */
    public boolean canRun() {
        return background.canRun();
    }

    /** Ob ein Unterauftrag in diesem Zustand noch lädt oder weiterladen wird (angehalten). */
    public static boolean loading(com.aresstack.spaceharvester.application.jobs.JobState state) {
        return !state.terminal() || state == com.aresstack.spaceharvester.application.jobs.JobState.PAUSED;
    }

    private JobTask work(BackgroundPart part) {
        String token = part.parameter(CALLER);
        boolean refresh = "true".equals(part.parameter(REFRESH));
        AccessContext caller = token != null ? callers.get(token) : callers.getOrDefault(part.groupId(), technical);
        if (caller == null) {
            // Abnahme PR 4: nie mit technischen Rechten für einen Benutzer weiterladen.
            return JobTask.blocking(context -> {
                context.record(JobEvent.Draft.warning(JobEvent.Category.JOB, COMPONENT, NOT_RESUMED,
                        "Nach dem Neustart nicht fortgesetzt: Die Anmeldung des Benutzers lag nur im Speicher; die "
                                + "nächste Suche lädt nach"));
                context.incomplete();
            });
        }
        List<PageKey> keys = new ArrayList<>();
        String pages = part.parameter(PAGES);
        if (pages != null) {
            for (String pageId : pages.split(SEPARATOR)) {
                if (!pageId.isBlank()) {
                    keys.add(new PageKey(part.sourceId(), pageId));
                }
            }
        }
        String named = part.parameter(CHANGE_TRIGGER);
        ChangeTrigger trigger = named == null ? null : trigger(named);
        return JobTask.blocking(context -> run(context, caller, keys, refresh, trigger));
    }

    private static ChangeTrigger trigger(String named) {
        try {
            return ChangeTrigger.valueOf(named);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void run(JobContext context, AccessContext caller, List<PageKey> keys, boolean refresh,
                     ChangeTrigger trigger) throws InterruptedException {
        AcquisitionScheduler.Priority priority = priority(caller);
        // Im Vordergrund ist das Nachladen nachrangig (Abnahme #1080): Seiten einer Suche gehen vor, ein Platz.
        AcquisitionScheduler.Mode mode = new AcquisitionScheduler.Mode(refresh,
                priority == AcquisitionScheduler.Priority.FOREGROUND, trigger);
        List<PageKey> missing = refresh ? keys : keys.stream().filter(key -> !cached.test(key)).toList();
        context.discovered(keys.size());
        context.processed(keys.size() - missing.size());
        java.util.ArrayDeque<AcquisitionScheduler.Ticket> open = new java.util.ArrayDeque<>();
        long failed = 0;
        // Ohne Takt (ADR 0027): geweckt wird nur durch das Ende eines Abrufs, eine Anforderung zum Anhalten oder
        // Abbrechen und, solange der Planer voll ist, durch seine Standmeldung.
        Wake wake = new Wake();
        com.aresstack.spaceharvester.application.observe.Subscription full = null;
        AutoCloseable stop = context.whenStopRequested(wake::ring);
        try {
            int requested = 0;
            pages:
            for (PageKey key : missing) {
                // Höchstens WINDOW Abrufe zugleich, und ein voller Planer heißt warten statt scheitern.
                while (true) {
                    context.checkpoint();
                    if (open.size() >= WINDOW) {
                        failed += finish(context, open.pollFirst(), wake);
                        continue;
                    }
                    long seen = wake.mark();
                    try {
                        open.addLast(scheduler.request(caller, key, priority, mode));
                        requested++;
                        break;
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        if (scheduler.status().pause() == AcquisitionStatus.Pause.CLOSED) {
                            // Beendeter Planer meldet nichts mehr: Warten hinge für immer. Was er nicht mehr
                            // annimmt, fehlt wie eine von ihm abgeworfene Seite.
                            int dropped = missing.size() - requested;
                            context.processed(dropped);
                            context.failed(dropped);
                            failed += dropped;
                            break pages;
                        }
                        if (!open.isEmpty()) {
                            failed += finish(context, open.pollFirst(), wake);
                        } else {
                            if (full == null) {
                                // Erst anmelden, dann noch einmal versuchen: eine Meldung dazwischen weckt sofort.
                                full = scheduler.subscribe(status -> wake.ring());
                                continue;
                            }
                            wake.awaitAfter(seen);
                        }
                    }
                }
            }
            while (!open.isEmpty()) {
                failed += finish(context, open.pollFirst(), wake);
            }
        } finally {
            if (full != null) {
                full.close();
            }
            try {
                stop.close();
            } catch (Exception ignored) {
                // nur eine Abmeldung
            }
            // Abgebrochen oder angehalten: noch wartende Abrufe entfallen, ein laufender endet regulär.
            open.forEach(AcquisitionScheduler.Ticket::cancel);
        }
        if (failed > 0) {
            // Gescheitert statt fertig: „Fortsetzen“ lädt dann im selben Eintrag nur, was noch fehlt.
            throw new com.aresstack.spaceharvester.application.jobs.JobFailedException(PAGES_MISSING,
                    failed + (failed == 1 ? " Seite ließ" : " Seiten ließen") + " sich nicht laden; Fortsetzen lädt "
                            + "nur, was noch fehlt");
        }
    }

    /**
     * Wartet auf eine Seite und zählt sie; 1, wenn sie sich nicht laden ließ. Fehlt die Anmeldung (auch ein offener
     * Einmalcode) oder ist die Quelle nicht erreichbar, hält sich der Unterauftrag an (gelb), statt die übrigen Seiten
     * als Fehler zu zählen; Fortsetzen lädt dann nur, was noch fehlt.
     */
    private static long finish(JobContext context, AcquisitionScheduler.Ticket ticket, Wake wake)
            throws InterruptedException {
        boolean loaded = await(context, ticket.done(), wake);
        if (!loaded) {
            PauseReason reason = ticket.failure().map(PageLoads::pauseReason).orElse(null);
            if (reason != null) {
                context.record(JobEvent.Draft.warning(JobEvent.Category.SOURCE, COMPONENT, reason.name(),
                        reason == PauseReason.SIGN_IN_REQUIRED
                                ? "Die Quelle verlangt eine Anmeldung; der Unterauftrag hält sich an"
                                : "Die Quelle antwortet nicht; der Unterauftrag hält sich an"));
                context.pause(reason);
            }
        }
        context.processed(1);
        if (loaded) {
            context.written(1);
            return 0;
        }
        context.failed(1);
        return 1;
    }

    /** Anmeldung oder Verbindung als Grund zum Anhalten, sonst {@code null} (ein Fehler der Seite). */
    static PauseReason pauseReason(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SignInRequiredException || current instanceof SignInPendingException) {
                return PauseReason.SIGN_IN_REQUIRED;
            }
            if (current instanceof SourceAccessException access
                    && (access.reason() == SourceAccessException.Reason.UNAVAILABLE
                    || access.reason() == SourceAccessException.Reason.RATE_LIMITED)) {
                return PauseReason.CONNECTION_LOST;
            }
            if (current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.http.HttpConnectTimeoutException) {
                return PauseReason.CONNECTION_LOST;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * Der Weg im Planer: der Hintergrund, wenn er Plätze hat; sonst der Vordergrund, denn ohne Hintergrundfüllung ist
     * dessen Weg abgeschaltet. Nachrangig ist der Auftrag schon in der Auftragsliste.
     */
    private AcquisitionScheduler.Priority priority(AccessContext caller) {
        // Mit der Anmeldung eines Benutzers nie über den Hintergrund des Planers (der lehnt sie ab).
        return caller.credential() == null && scheduler.limits().background() > 0
                ? AcquisitionScheduler.Priority.BACKGROUND : AcquisitionScheduler.Priority.FOREGROUND;
    }

    /**
     * Wartet auf eine Seite ohne Takt: geweckt wird nur durch ihr Ende oder eine Anforderung zum Anhalten oder
     * Abbrechen ({@link JobContext#whenStopRequested}, in {@code wake}); dann prüft {@link JobContext#checkpoint()}.
     */
    static boolean await(JobContext context, java.util.concurrent.CompletableFuture<Boolean> done, Wake wake)
            throws InterruptedException {
        long seen = wake.mark();
        done.whenComplete((loaded, failure) -> wake.ring());
        while (true) {
            context.checkpoint();
            if (done.isDone()) {
                try {
                    return Boolean.TRUE.equals(done.getNow(false));
                } catch (java.util.concurrent.CompletionException | CancellationException e) {
                    return false;
                }
            }
            wake.awaitAfter(seen);
            seen = wake.mark();
        }
    }

    /**
     * Weckt den wartenden Unterauftrag; jede Meldung zählt eins hoch, damit keine verloren geht. Mit
     * {@link java.util.concurrent.locks.ReentrantLock} statt {@code synchronized}: Der Unterauftrag wartet auf einem
     * virtuellen Thread und gibt dabei seinen Träger frei (Architekt 22.11.2026).
     */
    static final class Wake {

        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private final java.util.concurrent.locks.Condition rung = lock.newCondition();
        private long generation;

        void ring() {
            lock.lock();
            try {
                generation++;
                rung.signalAll();
            } finally {
                lock.unlock();
            }
        }

        long mark() {
            lock.lock();
            try {
                return generation;
            } finally {
                lock.unlock();
            }
        }

        /** Wartet, bis nach {@code seen} geläutet wurde; ohne Zeitgrenze. */
        void awaitAfter(long seen) throws InterruptedException {
            lock.lock();
            try {
                while (generation == seen) {
                    rung.await();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
