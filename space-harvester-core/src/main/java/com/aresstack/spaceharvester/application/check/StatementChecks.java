package com.aresstack.spaceharvester.application.check;

import com.aresstack.spaceharvester.ai.ModelRetry;
import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Der Prüfschritt für eine Seite (ADR 0032, FL4): je Originalsatz ein Modellaufruf mit seinen gewählten Aussagen,
 * sobald eine davon nicht in der Ablage steht, dann für alle Aussagen des Satzes; Sätze laufen parallel bis zur
 * Obergrenze. Welche Aussagen gewählt sind und was die Urteile an der Analyse ändern, entscheidet
 * {@code StatementAnalysis}; hier liegen nur Freigabe, Modellaufruf, strenges Lesen und Ablage, wie bei der Vorstufe
 * ({@code Simplifications}).
 *
 * <ul>
 *     <li>Freigabe: Vor jedem Modellaufruf und vor dem Speichern seiner Antwort wird die KI-Freigabe der Seite gefragt
 *     ({@code AiRelease::mayProcess}); ist sie entzogen, geht nichts hinaus und nichts wird gespeichert.</li>
 *     <li>Fortsetzen: Jeder Satz speichert seine Urteile sofort; nach Abbruch, Pause oder Fehler holt der nächste Lauf
 *     nur die fehlenden.</li>
 *     <li>Kosten: geprüfte Aussagen, Modellaufrufe, Urteile aus der Ablage, verworfene Zeilen ({@link Costs}).</li>
 * </ul>
 */
public final class StatementChecks {

    private final StepModel model;
    private final StatementCheckStore store;
    private final Predicate<PageKey> released;
    private final BiPredicate<AccessContext, PageKey> readable;
    private final Listeners<Runnable> listeners = new Listeners<>("Prüfurteile");

    /**
     * Ohne Leserecht für {@link #delete}: dort wird nichts gelöscht.
     *
     * @param released ob die Seite jetzt an den KI-Dienst darf, etwa {@code AiRelease::mayProcess}
     */
    public StatementChecks(StepModel model, StatementCheckStore store, Predicate<PageKey> released) {
        this(model, store, released, (caller, page) -> false);
    }

    /**
     * @param released ob die Seite jetzt an den KI-Dienst darf, etwa {@code AiRelease::mayProcess}
     * @param readable ob der Aufrufer die Seite lesen darf, wie in {@code KnowledgeGraph#delete}
     */
    public StatementChecks(StepModel model, StatementCheckStore store, Predicate<PageKey> released,
                           BiPredicate<AccessContext, PageKey> readable) {
        this.model = Objects.requireNonNull(model, "model");
        this.store = Objects.requireNonNull(store, "store");
        this.released = Objects.requireNonNull(released, "released");
        this.readable = Objects.requireNonNull(readable, "readable");
    }

    /**
     * „Alles löschen“ (Angelo 17.11.2026): entfernt die Urteile des Prüfschritts der Seiten, die {@code pages} trifft
     * und die der Aufrufer lesen darf, auch wenn ihre Analyse schon gelöscht ist. Die Auswahl kommt vom Aufrufer, der
     * Core rechnet keine eigene. Urteile liegen nur je Seite (Anhänge haben keine eigenen), daher keine Dokumente.
     * Meldet sich einmal je Aufruf bei {@link #subscribe}, nur wenn etwas entfernt wurde.
     *
     * @return Zahl der Seiten, deren Urteile entfernt wurden
     */
    public int delete(AccessContext caller, Predicate<PageKey> pages) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(pages, "pages");
        List<PageKey> doomed = new ArrayList<>();
        for (PageKey page : store.pages()) {
            if (pages.test(page) && readable.test(caller, page)) {
                doomed.add(page);
            }
        }
        if (doomed.isEmpty()) {
            return 0;
        }
        store.removeAll(doomed);
        listeners.deliver(null, Runnable::run);
        return doomed.size();
    }

    /**
     * Leseprüfung für {@link #delete} wie in {@code KnowledgeGraph#delete}: mit dem Space der Seite aus dem Cache. Liegt
     * die Seite nicht mehr im Cache (verwaiste Urteile), ist ihr Space unbekannt; {@link AccessGuard#canRead} gibt dann
     * nur frei, wer alle Spaces der Quelle lesen darf. Zusätzlich gibt hier frei, wer die ganze Quelle lesen darf
     * ({@link AccessGuard#permitsSourceWide}), damit verwaiste Urteile bei „Alles löschen“ nicht liegen bleiben.
     */
    public static BiPredicate<AccessContext, PageKey> pageAccess(AccessGuard access,
                                                                 Function<PageKey, Optional<String>> spaceKey) {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(spaceKey, "spaceKey");
        return (caller, page) -> {
            Optional<String> space = spaceKey.apply(page);
            if (space.isPresent()) {
                return access.canRead(caller, page.sourceId(), space.get(), page.pageId());
            }
            return access.canRead(caller, page.sourceId(), null, page.pageId())
                    || access.permitsSourceWide(caller, page.sourceId(), AccessKind.CONTENT);
        };
    }

    /** Meldet sich, nachdem Urteile gelöscht wurden ({@link #delete}). Kein Polling (ADR 0027). */
    public Subscription subscribe(Runnable listener) {
        return listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Ob ein Modell eingestellt ist. */
    public boolean available() {
        return model.available();
    }

    /** Ob die Seite jetzt an das Modell gehen darf. */
    public boolean released(PageKey page) {
        return released.test(page);
    }

    /** Version der Ablage und der Urteile: Modell und Anweisung. */
    public String version() {
        return model.version() + "|prompt-" + StatementCheck.PROMPT_VERSION;
    }

    /** Nimmt die Urteile der Seiten zurück, die {@code released} jetzt nicht mehr zulässt (ADR 0028). */
    public int withdraw(Collection<PageKey> pages, Predicate<PageKey> released) {
        int removed = 0;
        for (PageKey page : pages) {
            if (!released.test(page) && store.contains(page)) {
                store.remove(page);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Ein Originalsatz mit den gewählten Aussagen.
     *
     * @param context die Zeilen seines Abschnitts als Zusammenhang, wenn eine Aussage aus der Vorstufe stammt; sonst
     *                leer
     */
    public record Group(String sentence, List<String> context, List<StatementCheck.Item> items) {

        /** Ohne Zusammenhang. */
        public Group(String sentence, List<StatementCheck.Item> items) {
            this(sentence, List.of(), items);
        }

        public Group {
            Objects.requireNonNull(sentence, "sentence");
            context = List.copyOf(context);
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("Ein Satz ohne Aussage wird nicht geprüft");
            }
        }
    }

    /**
     * Prüft die Aussagen der Sätze.
     *
     * @param inputSha256 Eingabe-Hash des Lesetextes der Seite
     * @param parallel    so viele Sätze höchstens zugleich, mindestens 1
     * @param cancelled   vor jedem Modellaufruf gefragt; {@code true} beendet den Lauf, Gespeichertes bleibt
     * @throws CheckUnavailableException ohne Modell oder ohne KI-Freigabe der Seite
     * @throws CancellationException     nach einem Abbruch
     * @throws RuntimeException          wenn ein Modellaufruf scheitert; fertige Sätze bleiben gespeichert
     */
    public Result check(PageKey page, String inputSha256, List<Group> groups, int parallel, BooleanSupplier cancelled) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(inputSha256, "inputSha256");
        Objects.requireNonNull(cancelled, "cancelled");
        if (parallel < 1) {
            throw new IllegalArgumentException("parallel muss mindestens 1 sein");
        }
        if (!model.available()) {
            throw new CheckUnavailableException(CheckUnavailableException.Reason.NO_MODEL);
        }
        if (!released.test(page)) {
            throw new CheckUnavailableException(CheckUnavailableException.Reason.NOT_RELEASED);
        }
        String version = version();
        Map<String, StatementCheckStore.Line> stored = store.load(page, version);
        Map<Integer, Map<Integer, StatementCheck.Answer>> answers = new ConcurrentHashMap<>();
        Set<String> keys = new HashSet<>();
        List<Pending> pending = new ArrayList<>();
        int statements = 0;
        int fromStore = 0;
        for (int g = 0; g < groups.size(); g++) {
            Group group = groups.get(g);
            List<String> groupKeys = new ArrayList<>();
            Map<Integer, StatementCheck.Answer> hits = new LinkedHashMap<>();
            for (int i = 0; i < group.items().size(); i++) {
                statements++;
                String key = StatementCheckStore.key(group.sentence(), group.context(),
                        StatementCheck.render(group.items().get(i)));
                keys.add(key);
                groupKeys.add(key);
                StatementCheckStore.Line found = stored.get(key);
                if (found != null) {
                    hits.put(i + 1, new StatementCheck.Answer(found.verdict(), found.correction()));
                }
            }
            if (hits.size() == group.items().size()) {
                fromStore += hits.size();
                answers.put(g, hits);
            } else {
                pending.add(new Pending(g, group, groupKeys));
            }
        }
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger conflicting = new AtomicInteger();
        AtomicInteger correctionsRejected = new AtomicInteger();
        Semaphore slots = new Semaphore(parallel);
        List<Future<?>> futures = new ArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        RuntimeException failure = null;
        boolean stopped = false;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Pending work : pending) {
                try {
                    slots.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopped = true;
                    break;
                }
                if (failed.get()) {
                    slots.release();
                    break;
                }
                if (cancelled.getAsBoolean()) {
                    slots.release();
                    stopped = true;
                    break;
                }
                futures.add(executor.submit(() -> {
                    try {
                        if (cancelled.getAsBoolean()) {
                            throw new CancellationException("Prüfschritt abgebrochen");
                        }
                        if (!released.test(page)) {
                            throw new CheckUnavailableException(CheckUnavailableException.Reason.NOT_RELEASED);
                        }
                        Group group = work.group();
                        String response = model.completeRetrying(StatementCheck.instructions(),
                                StatementCheck.input(group.sentence(), group.context(), group.items()),
                                ModelRetry.logging(cancelled));
                        StatementCheck.Parsed parsed = StatementCheck.parse(group.sentence(), group.context(),
                                group.items().size(), response);
                        if (!released.test(page)) {
                            throw new CheckUnavailableException(CheckUnavailableException.Reason.NOT_RELEASED);
                        }
                        Map<String, StatementCheckStore.Line> entries = new LinkedHashMap<>();
                        parsed.answers().forEach((number, answer) -> entries.put(work.keys().get(number - 1),
                                new StatementCheckStore.Line(answer.verdict(), answer.correction())));
                        store.put(page, version, inputSha256, entries);
                        answers.put(work.index(), parsed.answers());
                        rejected.addAndGet(parsed.rejected());
                        conflicting.addAndGet(parsed.conflicting());
                        correctionsRejected.addAndGet(parsed.correctionsRejected());
                    } catch (RuntimeException e) {
                        failed.set(true);
                        throw e;
                    } finally {
                        slots.release();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    RuntimeException cause = e.getCause() instanceof RuntimeException runtime ? runtime
                            : new IllegalStateException(e.getCause().getClass().getSimpleName());
                    if (failure == null || failure instanceof CancellationException) {
                        failure = cause;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopped = true;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        if (stopped) {
            throw new CancellationException("Prüfschritt abgebrochen");
        }
        store.retain(page, version, inputSha256, keys);
        int[] verdicts = new int[ParsedStatements.Verdict.values().length];
        answers.values().forEach(map -> map.values().forEach(answer -> verdicts[answer.verdict().ordinal()]++));
        List<Map<Integer, StatementCheck.Answer>> ordered = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            ordered.add(answers.getOrDefault(g, Map.of()));
        }
        return new Result(List.copyOf(ordered), new Costs(statements, pending.size(), fromStore, rejected.get(),
                conflicting.get(), correctionsRejected.get(), verdicts[ParsedStatements.Verdict.STIMMT.ordinal()],
                verdicts[ParsedStatements.Verdict.UNVOLLSTAENDIG.ordinal()],
                verdicts[ParsedStatements.Verdict.FALSCH.ordinal()],
                statements - verdicts[0] - verdicts[1] - verdicts[2]));
    }

    private record Pending(int index, Group group, List<String> keys) {
    }

    /**
     * Ergebnis eines vollständigen Laufs.
     *
     * @param answers je Satz (Reihenfolge wie die Eingabe) die Urteile je Nummer ab 1; fehlende Nummern ohne Urteil
     */
    public record Result(List<Map<Integer, StatementCheck.Answer>> answers, Costs costs) {
    }

    /**
     * Zahlen eines Laufs, ohne Inhalte.
     *
     * @param statements          geprüfte Aussagen
     * @param modelCalls          Sätze mit Modellaufruf
     * @param fromStore           Urteile aus der Ablage
     * @param rejected            verworfene Antwortzeilen außerhalb des Formats
     * @param conflicting         Aussagen mit zwei verschiedenen Urteilen, also ohne
     * @param correctionsRejected Korrekturen, die den Wortabgleich nicht bestanden
     * @param stimmt              Urteile STIMMT
     * @param unvollstaendig      Urteile UNVOLLSTAENDIG
     * @param falsch              Urteile FALSCH
     * @param unanswered          Aussagen ohne gültiges Urteil; ihr Satz wird im nächsten Lauf erneut gefragt
     */
    public record Costs(int statements, int modelCalls, int fromStore, int rejected, int conflicting,
                        int correctionsRejected, int stimmt, int unvollstaendig, int falsch, int unanswered) {
    }
}
