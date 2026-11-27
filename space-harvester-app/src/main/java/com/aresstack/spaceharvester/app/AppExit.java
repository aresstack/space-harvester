package com.aresstack.spaceharvester.app;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Beendet die App sicher und mit Zeitlimit.
 *
 * <p>Das Aufräumen (Command Deck, Browser, Laufzeit mit Aufträgen und Indizes) läuft in einem eigenen Faden, nie
 * auf dem Swing-Faden. Endet es nicht innerhalb der Frist, beendet ein Wächter die JVM trotzdem: Ein hängender Abruf
 * oder ein Pool, der auf seine Aufgaben wartet, darf das Beenden nicht verhindern (sonst bleibt der Prozess ohne
 * Fenster im Speicher und hält das JAR fest). Vor dem Ende werden alle Kindprozesse der App beendet, etwa der
 * Browser der Websuche.</p>
 *
 * <p>Die Frist lässt sich mit {@code -Dspace-harvester.exit-timeout=<Sekunden>} einstellen.</p>
 */
public final class AppExit {

    private static final System.Logger LOG = System.getLogger(AppExit.class.getName());
    /** Systemeigenschaft für die Frist in Sekunden. */
    public static final String TIMEOUT_PROPERTY = "space-harvester.exit-timeout";
    /** Vorgabe der Frist: länger als die längste eigene Frist eines Bausteins (Aufträge 30 s). */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(40);
    /** Wie lange Kindprozesse nach der Bitte zu enden Zeit bekommen, bevor sie hart beendet werden. */
    static final Duration CHILD_GRACE = Duration.ofSeconds(3);

    /** Wie viele Rahmen eines hängenden Schritts ins Protokoll gehen. */
    private static final int STACK_FRAMES = 25;

    private static final AtomicBoolean CLEANED = new AtomicBoolean();
    private static final AtomicBoolean EXITING = new AtomicBoolean();

    private AppExit() {
    }

    /** Die eingestellte Frist; ungültige oder fehlende Angaben ergeben die Vorgabe. */
    public static Duration timeout() {
        String value = System.getProperty(TIMEOUT_PROPERTY);
        if (value != null) {
            try {
                long seconds = Long.parseLong(value.strip());
                if (seconds > 0) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (NumberFormatException ignored) {
                // Vorgabe gilt.
            }
            LOG.log(System.Logger.Level.WARNING, "{0} ungültig, es gilt die Vorgabe", TIMEOUT_PROPERTY);
        }
        return DEFAULT_TIMEOUT;
    }

    /**
     * Beendet die App: räumt in einem eigenen Faden auf, beendet die Kindprozesse und dann die JVM. Kehrt sofort
     * zurück; ein zweiter Aufruf tut nichts.
     */
    public static void exit(int status, List<Runnable> cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        if (!EXITING.compareAndSet(false, true)) {
            return;
        }
        Duration timeout = timeout();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeout.toMillis());
            } catch (InterruptedException e) {
                return;
            }
            LOG.log(System.Logger.Level.WARNING, "Beenden dauert länger als {0} s; die App wird hart beendet",
                    timeout.toSeconds());
            stopChildren(Duration.ZERO);
            Runtime.getRuntime().halt(status);
        }, "space-harvester-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread exit = new Thread(() -> {
            cleanUp(cleanup, timeout);
            stopChildren(CHILD_GRACE);
            System.exit(status);
        }, "space-harvester-exit");
        exit.setDaemon(true);
        exit.start();
    }

    /**
     * Räumt einmal auf und wartet höchstens {@code timeout}; weitere Aufrufe (etwa der Shutdown-Hook nach
     * {@link #exit}) kehren sofort zurück. Hängt ein Schritt, laufen die übrigen trotzdem.
     */
    public static void cleanUp(List<Runnable> cleanup, Duration timeout) {
        if (!CLEANED.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Runnable step : cleanup) {
            Thread worker = new Thread(() -> {
                try {
                    step.run();
                } catch (RuntimeException | Error e) {
                    LOG.log(System.Logger.Level.WARNING, "Aufräumen beim Beenden fehlgeschlagen ({0})",
                            e.getClass().getName());
                }
            }, "space-harvester-cleanup");
            worker.setDaemon(true);
            worker.start();
            try {
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (worker.isAlive()) {
                LOG.log(System.Logger.Level.WARNING, "Aufräumen beim Beenden nicht rechtzeitig fertig; hängt in {0}",
                        describe(worker));
                if (System.nanoTime() >= deadline) {
                    return;
                }
            }
        }
    }

    /** Beendet alle Kindprozesse dieser JVM (auch deren Kinder): erst bitten, nach {@code grace} hart. */
    static void stopChildren(Duration grace) {
        List<ProcessHandle> children = ProcessHandle.current().descendants().toList();
        if (children.isEmpty()) {
            return;
        }
        children.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + grace.toNanos();
        for (ProcessHandle child : children) {
            long left = deadline - System.nanoTime();
            if (left > 0) {
                try {
                    child.onExit().get(left, TimeUnit.NANOSECONDS);
                } catch (Exception stillRunning) {
                    // unten hart beendet
                }
            }
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    /** Die obersten Rahmen des hängenden Fadens, damit das Protokoll die Stelle nennt. */
    static String describe(Thread worker) {
        StackTraceElement[] stack = worker.getStackTrace();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(stack.length, STACK_FRAMES); i++) {
            text.append(i == 0 ? "" : " ← ").append(stack[i]);
        }
        return text.length() == 0 ? "?" : text.toString();
    }

    /** Nur für Tests: setzt den Zustand zurück. */
    static void resetForTest() {
        CLEANED.set(false);
        EXITING.set(false);
    }
}
