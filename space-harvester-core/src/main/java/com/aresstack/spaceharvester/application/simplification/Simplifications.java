package com.aresstack.spaceharvester.application.simplification;

import com.aresstack.spaceharvester.ai.ModelRetry;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Die Vorstufe „Sätze vereinfachen“ für eine Seite (ADR 0032, FL3): je Abschnitt ein Modellaufruf, sobald einer seiner
 * gewählten Sätze nicht in der Ablage steht, dann immer für den ganzen Abschnitt; Abschnitte laufen parallel bis zur
 * Obergrenze. Welche Sätze gewählt sind
 * und wie die Ergebnisse mit dem Original zusammengehen, entscheidet der Satzanalysator; hier liegen nur Freigabe,
 * Modellaufruf, strenges Lesen und Ablage.
 *
 * <ul>
 *     <li>Freigabe: Vor jedem Modellaufruf und vor dem Speichern seiner Antwort wird die KI-Freigabe der Seite gefragt
 *     ({@code AiRelease::mayProcess}); ist sie entzogen, geht nichts hinaus und nichts wird gespeichert.</li>
 *     <li>Fortsetzen: Jeder Abschnitt speichert seine Sätze sofort; nach Abbruch, Pause oder Fehler holt der nächste
 *     Lauf nur die fehlenden.</li>
 *     <li>Kosten: gewählte Sätze, Abschnitte mit Modellaufruf und Sätze aus der Ablage ({@link Costs}).</li>
 * </ul>
 */
public final class Simplifications {

    private final StepModel model;
    private final SimplificationStore store;
    private final Predicate<PageKey> released;

    /**
     * @param released ob die Seite jetzt an den KI-Dienst darf, etwa {@code AiRelease::mayProcess}
     */
    public Simplifications(StepModel model, SimplificationStore store, Predicate<PageKey> released) {
        this.model = Objects.requireNonNull(model, "model");
        this.store = Objects.requireNonNull(store, "store");
        this.released = Objects.requireNonNull(released, "released");
    }

    /** Ob ein Modell eingestellt ist. */
    public boolean available() {
        return model.available();
    }

    /** Ob die Seite jetzt an das Modell gehen darf. */
    public boolean released(PageKey page) {
        return released.test(page);
    }

    /** Version der Ablage: Modell und Anweisung. */
    public String version() {
        return model.version() + "|prompt-" + SentenceSimplifier.PROMPT_VERSION;
    }

    /** Nimmt die Vorstufe der Seiten zurück, die {@code released} jetzt nicht mehr zulässt (ADR 0028). */
    public int withdraw(java.util.Collection<PageKey> pages, Predicate<PageKey> released) {
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
     * Vereinfacht die gewählten Sätze der Abschnitte.
     *
     * @param inputSha256 Eingabe-Hash des Lesetextes der Seite
     * @param parallel    so viele Abschnitte höchstens zugleich, mindestens 1
     * @param cancelled   vor jedem Modellaufruf gefragt; {@code true} beendet den Lauf, Gespeichertes bleibt
     * @throws SimplificationUnavailableException ohne Modell oder ohne KI-Freigabe der Seite
     * @throws CancellationException              nach einem Abbruch
     * @throws RuntimeException                   wenn ein Modellaufruf scheitert; fertige Abschnitte bleiben gespeichert
     */
    public Result simplify(PageKey page, String inputSha256, List<SimplificationSection> sections, int parallel,
                           BooleanSupplier cancelled) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(inputSha256, "inputSha256");
        Objects.requireNonNull(cancelled, "cancelled");
        if (parallel < 1) {
            throw new IllegalArgumentException("parallel muss mindestens 1 sein");
        }
        if (!model.available()) {
            throw new SimplificationUnavailableException(SimplificationUnavailableException.Reason.NO_MODEL);
        }
        if (!released.test(page)) {
            throw new SimplificationUnavailableException(SimplificationUnavailableException.Reason.NOT_RELEASED);
        }
        String version = version();
        Map<String, List<SimplificationStore.Line>> stored = store.load(page, version);
        Map<Integer, List<SimplifiedSentence>> sentences = new java.util.concurrent.ConcurrentHashMap<>();
        Set<String> keys = new HashSet<>();
        List<Pending> pending = new ArrayList<>();
        int chosen = 0;
        int fromStore = 0;
        for (SimplificationSection section : sections) {
            // Das Ergebnis eines Satzes hängt an seinen Nachbarn (Pronomen, Stichpunkt aus dem Satz davor): Schlüssel
            // ist der Satz samt allen übrigen Zeilen des Aufrufs, und fehlt einer, geht der ganze Abschnitt neu hinaus.
            Map<Integer, String> sectionKeys = new LinkedHashMap<>();
            Map<Integer, List<SimplificationStore.Line>> hits = new LinkedHashMap<>();
            for (SimplificationSection.Sentence sentence : section.sentences()) {
                chosen++;
                List<String> others = new ArrayList<>();
                section.lines().forEach(line -> {
                    if (line.index() != sentence.index()) {
                        others.add(line.text());
                    }
                });
                String key = SimplificationStore.key(sentence.text(), others);
                keys.add(key);
                sectionKeys.put(sentence.index(), key);
                List<SimplificationStore.Line> found = stored.get(key);
                if (found != null) {
                    hits.put(sentence.index(), found);
                }
            }
            if (hits.size() == section.sentences().size()) {
                fromStore += hits.size();
                hits.forEach((index, found) -> sentences.put(index, found.stream()
                        .map(line -> new SimplifiedSentence(index, line.flag(), line.text())).toList()));
            } else {
                pending.add(new Pending(section, sectionKeys));
            }
        }
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger added = new AtomicInteger();
        AtomicInteger incomplete = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        Semaphore slots = new Semaphore(parallel);
        List<Future<?>> futures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
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
                            throw new CancellationException("Vorstufe abgebrochen");
                        }
                        if (!released.test(page)) {
                            throw new SimplificationUnavailableException(
                                    SimplificationUnavailableException.Reason.NOT_RELEASED);
                        }
                        String response = model.completeRetrying(SentenceSimplifier.instructions(),
                                SentenceSimplifier.input(work.section()), ModelRetry.logging(cancelled));
                        SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(work.section(), response);
                        if (!released.test(page)) {
                            throw new SimplificationUnavailableException(
                                    SimplificationUnavailableException.Reason.NOT_RELEASED);
                        }
                        Map<String, List<SimplificationStore.Line>> entries = new LinkedHashMap<>();
                        parsed.sentences().forEach((index, list) -> {
                            sentences.put(index, list);
                            entries.put(work.keys().get(index), list.stream()
                                    .map(s -> new SimplificationStore.Line(s.flag(), s.text())).toList());
                        });
                        store.put(page, version, inputSha256, entries);
                        rejected.addAndGet(parsed.rejected());
                        added.addAndGet(parsed.added());
                        incomplete.addAndGet(parsed.incomplete().size());
                        lost.addAndGet(parsed.lost().size());
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
            throw new CancellationException("Vorstufe abgebrochen");
        }
        store.retain(page, version, inputSha256, keys);
        return new Result(Map.copyOf(sentences), new Costs(chosen, pending.size(), fromStore, rejected.get(),
                added.get(), incomplete.get(), lost.get()));
    }

    private record Pending(SimplificationSection section, Map<Integer, String> keys) {
    }

    /**
     * Ergebnis eines vollständigen Laufs.
     *
     * @param sentences je gewähltem Satz (Satzindex) die vereinfachten Sätze; leer, wenn keiner galt
     */
    public record Result(Map<Integer, List<SimplifiedSentence>> sentences, Costs costs) {
    }

    /**
     * Zahlen eines Laufs, ohne Inhalte.
     *
     * @param chosen     gewählte Sätze
     * @param modelCalls Abschnitte mit Modellaufruf
     * @param fromStore  Sätze aus der Ablage
     * @param rejected   verworfene Antwortzeilen außerhalb des Formats
     * @param added      verworfene Sätze mit Wörtern außerhalb des Abschnitts
     * @param incomplete Sätze mit Weglassungen
     * @param lost       Sätze, deren Verneinung oder Modalverb verloren ging; ihre vereinfachten Sätze sind verworfen
     */
    public record Costs(int chosen, int modelCalls, int fromStore, int rejected, int added, int incomplete,
                        int lost) {
    }
}
