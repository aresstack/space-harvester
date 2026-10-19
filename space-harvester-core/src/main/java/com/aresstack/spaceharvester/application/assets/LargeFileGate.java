package com.aresstack.spaceharvester.application.assets;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Gemeinsame Schranke für große Dateien in {@link Assets}: Jede Datei liegt beim Laden, Ablegen, Lesen aus der Ablage,
 * bei Vorschau, Textextraktion und Bildbeschreibung vollständig als {@code byte[]} im Speicher (wie im Enterprise AI
 * Client, der Anhänge bis {@code READER_MAX_BYTES} = 100 MiB liest). Damit wenige große Dateien den Heap nicht
 * füllen, laufen Dateien über {@link #thresholdBytes()} (Standard 20 MiB) höchstens {@link #maxConcurrent()} (Standard
 * 2) gleichzeitig; kleinere laufen ohne Schranke. Ob eine Datei groß ist, entscheidet ihre angegebene Größe; eine
 * unbekannte zählt als groß.
 *
 * <p>Ein weiterer Aufrufer wartet höchstens {@link #maxWait()} (Standard 30 s) auf einen freien Platz und scheitert
 * dann mit {@link SourceAccessException.Reason#RATE_LIMITED}: ein vorübergehender Zustand, der nie als dauerhaft
 * defekter Anhang gilt. Ein Thread, der schon einen Platz hält, belegt bei verschachtelten Aufrufen (etwa Download und
 * anschließende Textextraktion derselben Datei) keinen zweiten, damit er nicht auf sich selbst wartet.
 */
public final class LargeFileGate {

    /** Ab dieser Größe (ausschließlich) gilt eine Datei als groß: 20 MiB. */
    public static final long DEFAULT_THRESHOLD_BYTES = 20L * 1024 * 1024;
    /** Gleichzeitige große Dateien. */
    public static final int DEFAULT_MAX_CONCURRENT = 2;
    /** Wie lange ein weiterer Aufrufer auf einen freien Platz wartet, wie bei den Downloads in {@link Assets}. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);

    /** Platzhalter für kleine Dateien: gibt nichts frei. */
    private static final Permit NONE = new Permit(null);

    private final long thresholdBytes;
    private final int maxConcurrent;
    private final Duration maxWait;
    private final Semaphore permits;
    /** Tiefe der verschachtelten Aufrufe des aktuellen Threads; nur die äußerste belegt und gibt frei. */
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * @param thresholdBytes Dateien über dieser Größe gelten als groß
     * @param maxConcurrent  höchstens so viele große Dateien gleichzeitig
     * @param maxWait        Wartezeit auf einen freien Platz
     */
    public LargeFileGate(long thresholdBytes, int maxConcurrent, Duration maxWait) {
        if (thresholdBytes < 0) {
            throw new IllegalArgumentException("thresholdBytes darf nicht negativ sein");
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent muss positiv sein");
        }
        Objects.requireNonNull(maxWait, "maxWait");
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait darf nicht negativ sein");
        }
        this.thresholdBytes = thresholdBytes;
        this.maxConcurrent = maxConcurrent;
        this.maxWait = maxWait;
        this.permits = new Semaphore(maxConcurrent, true);
    }

    /** Schranke mit 20 MiB, 2 gleichzeitigen großen Dateien und 30 s Wartezeit. */
    public static LargeFileGate defaults() {
        return new LargeFileGate(DEFAULT_THRESHOLD_BYTES, DEFAULT_MAX_CONCURRENT, DEFAULT_WAIT);
    }

    public long thresholdBytes() {
        return thresholdBytes;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public Duration maxWait() {
        return maxWait;
    }

    /** Ob eine Datei dieser angegebenen Größe groß ist; {@code null} oder negativ heißt unbekannt und zählt als groß. */
    public boolean large(Long size) {
        return size == null || size < 0 || size > thresholdBytes;
    }

    /**
     * Belegt für eine große Datei einen Platz, für eine kleine nichts. Immer mit try-with-resources im selben Thread
     * schließen.
     *
     * @param sourceId Quelle für die Fehlermeldung
     * @param size     angegebene Größe in Bytes oder {@code null}, wenn unbekannt
     * @throws SourceAccessException {@code RATE_LIMITED}, wenn nach {@link #maxWait()} kein Platz frei ist,
     *                               {@code UNAVAILABLE}, wenn das Warten unterbrochen wird
     */
    public Permit enter(String sourceId, Long size) {
        if (!large(size)) {
            return NONE;
        }
        int[] held = depth.get();
        if (held[0] == 0) {
            try {
                if (!permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                    depth.remove();
                    throw new SourceAccessException(sourceId, SourceAccessException.Reason.RATE_LIMITED,
                            "Zu viele gleichzeitige große Dateien", null);
                }
            } catch (InterruptedException e) {
                depth.remove();
                Thread.currentThread().interrupt();
                throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE,
                        "Warten auf große Datei abgebrochen", e);
            }
        }
        held[0]++;
        return new Permit(this);
    }

    private void exit() {
        int[] held = depth.get();
        held[0]--;
        if (held[0] <= 0) {
            depth.remove();
            permits.release();
        }
    }

    /** Ein belegter Platz; {@link #close()} gibt ihn frei, mehrfaches Schließen ist harmlos. */
    public static final class Permit implements AutoCloseable {

        private final LargeFileGate gate;
        private boolean closed;

        private Permit(LargeFileGate gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            if (gate != null && !closed) {
                closed = true;
                gate.exit();
            }
        }
    }
}
