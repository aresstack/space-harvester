package com.aresstack.spaceharvester.application.jobs;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Vorrang für die offene Ansicht (Angelo 24.11.2026: „das Fenster, das ich aufhabe aktuell, auf das ich warte, sollte
 * ja immer Priorität haben … der gegebenenfalls dann abgebrochen oder runter priorisiert wird, wenn ich von der Seite
 * weggehe“).
 *
 * <p>Eine Ansicht ist eine Kennung, die die App beim Öffnen meldet ({@link #open}) und beim Verlassen zurücknimmt
 * ({@link #close}). Arbeit für sie läuft im Kontext der Ansicht ({@link #run}); Threads, die sie startet, erben ihn.
 * Solange die Ansicht offen ist:
 * <ul>
 *   <li>startet ein Auftrag, der in ihrem Kontext angenommen wurde, vor allen anderen und ohne freien Platz
 *       ({@link Jobs#PRIORITY});</li>
 *   <li>wartet jede KI-Anfrage aus einem gewöhnlichen Auftrag ({@link #aiCall()}), solange die Ansicht arbeitet
 *       ({@link #busy}) oder eine KI-Anfrage für sie läuft. Anfragen der Ansicht selbst warten nie.</li>
 * </ul>
 * Beim Verlassen verliert ihre Arbeit den Vorrang: wartende Aufträge reihen sich normal ein, laufende belegen ab dann
 * einen Platz, und die zurückgehaltenen KI-Anfragen laufen weiter. Abgebrochen wird nichts.
 */
public final class Foreground {

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Condition QUIET = LOCK.newCondition();
    /** Die offenen Ansichten; unter {@link #LOCK}. */
    private static final Set<String> OPEN = new HashSet<>();
    /** Je offener Ansicht, wie viel gerade für sie arbeitet (Turns, KI-Anfragen); unter {@link #LOCK}. */
    private static final Map<String, Integer> BUSY = new HashMap<>();
    /** Wer vom Verlassen einer Ansicht erfährt, etwa {@link Jobs}; schwach gehalten. */
    private static final Set<Consumer<String>> LEAVING = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /** Die Ansicht, für die dieser Thread arbeitet, oder {@code null}. */
    private static final InheritableThreadLocal<String> VIEW = new InheritableThreadLocal<>();
    /** Ob dieser Thread zu einem gewöhnlichen Auftrag gehört: seine KI-Anfragen lassen der Ansicht den Vortritt. */
    private static final InheritableThreadLocal<Boolean> JOB = new InheritableThreadLocal<>();

    private Foreground() {
    }

    /** Die Ansicht ist offen: ihre Arbeit hat ab jetzt Vorrang. */
    public static void open(String view) {
        Objects.requireNonNull(view, "view");
        LOCK.lock();
        try {
            OPEN.add(view);
        } finally {
            LOCK.unlock();
        }
    }

    /** Die Ansicht ist verlassen: ihre Arbeit verliert den Vorrang; nichts wird abgebrochen. */
    public static void close(String view) {
        if (view == null) {
            return;
        }
        LOCK.lock();
        try {
            if (!OPEN.remove(view)) {
                return;
            }
            BUSY.remove(view);
            QUIET.signalAll();
        } finally {
            LOCK.unlock();
        }
        Consumer<String>[] listeners;
        synchronized (LEAVING) {
            @SuppressWarnings("unchecked")
            Consumer<String>[] copy = LEAVING.toArray(new Consumer[0]);
            listeners = copy;
        }
        for (Consumer<String> listener : listeners) {
            listener.accept(view);
        }
    }

    /** Ob die Ansicht offen ist. */
    public static boolean isOpen(String view) {
        if (view == null) {
            return false;
        }
        LOCK.lock();
        try {
            return OPEN.contains(view);
        } finally {
            LOCK.unlock();
        }
    }

    /** Führt {@code work} im Kontext der Ansicht aus; Threads, die es startet, erben ihn. */
    public static void run(String view, Runnable work) {
        String before = VIEW.get();
        VIEW.set(view);
        try {
            work.run();
        } finally {
            VIEW.set(before);
        }
    }

    /** Die offene Ansicht, für die dieser Thread arbeitet, oder {@code null}. */
    public static String view() {
        String view = VIEW.get();
        return isOpen(view) ? view : null;
    }

    /**
     * Die Ansicht arbeitet, bis der Griff geschlossen wird (etwa ein Chat-Turn vom Senden bis zur Antwort): So lange
     * warten die KI-Anfragen gewöhnlicher Aufträge. Ohne offene Ansicht ein leerer Griff.
     */
    public static Hold busy(String view) {
        LOCK.lock();
        try {
            if (view == null || !OPEN.contains(view)) {
                return () -> { };
            }
            BUSY.merge(view, 1, Integer::sum);
        } finally {
            LOCK.unlock();
        }
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) {
                idle(view);
            }
        };
    }

    private static void idle(String view) {
        LOCK.lock();
        try {
            BUSY.computeIfPresent(view, (key, count) -> count <= 1 ? null : count - 1);
            QUIET.signalAll();
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Vor jeder KI-Anfrage über das Netz: Für die offene Ansicht zählt sie als Arbeit der Ansicht, bis der Griff
     * geschlossen wird. Aus einem gewöhnlichen Auftrag wartet sie, bis keine offene Ansicht mehr arbeitet. Alles andere
     * geht sofort.
     *
     * @throws InterruptedRuntime wenn der Thread beim Warten unterbrochen wird; der Unterbrechungsstatus bleibt
     */
    public static Hold aiCall() {
        String view = view();
        if (view != null) {
            return busy(view);
        }
        if (Boolean.TRUE.equals(JOB.get())) {
            awaitQuiet();
        }
        return () -> { };
    }

    /** Ob gerade eine offene Ansicht arbeitet. */
    public static boolean working() {
        LOCK.lock();
        try {
            return !BUSY.isEmpty();
        } finally {
            LOCK.unlock();
        }
    }

    private static void awaitQuiet() {
        LOCK.lock();
        try {
            while (!BUSY.isEmpty()) {
                QUIET.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedRuntime();
        } finally {
            LOCK.unlock();
        }
    }

    /** Ein Griff, der beim Schließen nichts wirft; mehrfaches Schließen zählt einmal. */
    @FunctionalInterface
    public interface Hold extends AutoCloseable {
        @Override
        void close();
    }

    /** Unterbrochen, während eine KI-Anfrage der offenen Ansicht den Vortritt ließ. */
    public static final class InterruptedRuntime extends RuntimeException {
        InterruptedRuntime() {
            super("Beim Warten auf die offene Ansicht unterbrochen");
        }
    }

    /**
     * {@link Jobs}: führt einen Auftrag aus; mit Vorrang arbeitet er für {@code view}, sonst und nach dem Verlassen der
     * Ansicht als gewöhnlicher. Danach gilt im Thread wieder, was vorher galt.
     */
    static void inJob(String view, Runnable work) {
        String viewBefore = VIEW.get();
        Boolean jobBefore = JOB.get();
        VIEW.set(view);
        JOB.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            VIEW.set(viewBefore);
            JOB.set(jobBefore);
        }
    }

    /** {@link Jobs}: erfährt vom Verlassen einer Ansicht. */
    static void onLeave(Consumer<String> listener) {
        LEAVING.add(listener);
    }

    /** Für Tests: alles vergessen. */
    static void reset() {
        LOCK.lock();
        try {
            OPEN.clear();
            BUSY.clear();
            QUIET.signalAll();
        } finally {
            LOCK.unlock();
        }
    }
}
