package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.ChangeEvent;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.sources.UnreadableChangeEventException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Änderungsquelle Webhooks (ADR 0014, optional, Standard aus): nimmt Aufrufe der Quelle an, prüft ihre Signatur und
 * gibt die Ereignisse im Hintergrund an {@link SpaceSyncJobs#applyEvents}, also nur als Katalog-Markierung ohne
 * Inhaltsabruf. Der Aufruf kehrt sofort zurück; dieselbe Seite mehrfach gemeldet wird einmal nachgefragt.
 *
 * <p>Angenommen wird nur, wenn für die Quelle Ereignisse eingeschaltet sind ({@code sync.events}) und ein Geheimnis
 * vorliegt; sonst wird der Aufruf ohne jede Wirkung verworfen. Die Signatur ist ein HMAC-SHA256 über den Rumpf mit dem
 * Geheimnis der Quelle, wie Confluence sie im Kopf {@code X-Hub-Signature} als {@code sha256=<hex>} schickt. Das
 * Geheimnis kommt vom einbettenden Programm, wird nach jeder Prüfung aus dem Speicher gelöscht und erscheint in keiner
 * Meldung.
 */
public class ChangeEvents implements AutoCloseable {

    /** Größter angenommener Rumpf; Confluence-Meldungen sind wenige Kilobyte groß. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Wartezeit, bevor Ereignisse eines gerade abgeglichenen Space erneut versucht werden. */
    static final Duration RETRY = Duration.ofSeconds(5);
    /** Seiten in der Warteschlange je Quelle, darüber fallen weitere Meldungen weg (Inventur als Sicherheitsnetz). */
    static final int MAX_QUEUED = 10_000;

    private static final System.Logger LOG = System.getLogger(ChangeEvents.class.getName());
    private static final String PREFIX = "sha256=";

    /** Ergebnis eines Aufrufs. */
    public enum Receipt {
        /** Signatur gültig, Ereignisse zur Verarbeitung angenommen (auch null Ereignisse). */
        ACCEPTED,
        /** Für diese Quelle sind Ereignisse aus, ohne Geheimnis oder die Quelle liest keine; nichts geschieht. */
        DISABLED,
        /** Keine Quelle mit dieser ID. */
        UNKNOWN_SOURCE,
        /** Signatur fehlt oder passt nicht; nichts geschieht. */
        BAD_SIGNATURE,
        /** Rumpf zu groß ({@link #MAX_BODY_BYTES}, vor der Signatur geprüft) oder keine Meldung der Quelle. */
        UNREADABLE
    }

    private final SpaceSyncJobs syncs;
    private final SourceQueries queries;
    private final Predicate<String> enabled;
    private final Function<String, char[]> secrets;
    private final ScheduledExecutorService worker;
    private final Map<String, Map<String, ChangeEvent>> queued = new LinkedHashMap<>();
    private int busy;
    private boolean scheduled;

    /**
     * @param enabled ob die Quelle Ereignisse annimmt, bei jedem Aufruf neu gelesen (Konfiguration zur Laufzeit)
     * @param secrets Geheimnis je Quellen-ID als frische Kopie oder {@code null} ohne Geheimnis; die Kopie wird nach
     *                Gebrauch überschrieben
     */
    public ChangeEvents(SpaceSyncJobs syncs, SourceQueries queries, Predicate<String> enabled,
                        Function<String, char[]> secrets) {
        this.syncs = Objects.requireNonNull(syncs, "syncs");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "change-events");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Nimmt einen Webhook-Aufruf an.
     *
     * @param signature Wert von {@code X-Hub-Signature} oder {@code null}
     * @param eventKey  Wert von {@code X-Event-Key} oder {@code null}
     */
    public Receipt receive(String sourceId, byte[] body, String signature, String eventKey) {
        Objects.requireNonNull(body, "body");
        Receipt open = receiving(sourceId);
        if (open != Receipt.ACCEPTED) {
            return open;
        }
        if (body.length > MAX_BODY_BYTES) {
            return Receipt.UNREADABLE;
        }
        char[] secret = secrets.apply(sourceId);
        if (secret == null) {
            return Receipt.DISABLED;
        }
        try {
            if (secret.length == 0) {
                return Receipt.DISABLED;
            }
            if (!signed(secret, body, signature)) {
                return Receipt.BAD_SIGNATURE;
            }
        } finally {
            Arrays.fill(secret, '\0');
        }
        List<ChangeEvent> events;
        try {
            events = queries.changeEvents(sourceId, body, eventKey);
        } catch (UnreadableChangeEventException e) {
            return Receipt.UNREADABLE;
        }
        enqueue(sourceId, events);
        return Receipt.ACCEPTED;
    }

    /**
     * Ob die Quelle gerade Aufrufe annähme, ohne Rumpf und Signatur: {@link Receipt#ACCEPTED},
     * {@link Receipt#UNKNOWN_SOURCE} oder {@link Receipt#DISABLED}. Eine Empfangsstelle fragt das, bevor sie den Rumpf
     * liest, damit abgelehnte Aufrufe nichts kosten.
     */
    public Receipt receiving(String sourceId) {
        boolean reads;
        try {
            reads = queries.readsChangeEvents(sourceId);
        } catch (UnknownSourceException e) {
            return Receipt.UNKNOWN_SOURCE;
        }
        if (!reads || !enabled.test(sourceId) || !syncs.available()) {
            return Receipt.DISABLED;
        }
        char[] secret = secrets.apply(sourceId);
        if (secret == null) {
            return Receipt.DISABLED;
        }
        boolean empty = secret.length == 0;
        Arrays.fill(secret, '\0');
        return empty ? Receipt.DISABLED : Receipt.ACCEPTED;
    }

    /**
     * Gibt schon gelesene und geprüfte Ereignisse zur Verarbeitung, etwa aus einer eigenen Empfangsstelle.
     *
     * @return {@code false} ohne Wirkung, wenn für die Quelle Ereignisse aus sind
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public boolean accept(String sourceId, List<ChangeEvent> events) {
        Objects.requireNonNull(events, "events");
        if (queries.sources().stream().noneMatch(source -> source.id().equals(sourceId))) {
            throw new UnknownSourceException(sourceId);
        }
        if (!enabled.test(sourceId) || !syncs.available()) {
            return false;
        }
        enqueue(sourceId, events);
        return true;
    }

    /** Wartet, bis keine Ereignisse mehr anstehen; {@code false} nach Ablauf von {@code timeout}. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (busy > 0 || !queued.isEmpty()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    /** Ob die Signatur ein HMAC-SHA256 des Rumpfs mit dem Geheimnis ist; Vergleich in konstanter Zeit. */
    static boolean signed(char[] secret, byte[] body, String signature) {
        if (signature == null || !signature.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(signature.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] key = new byte[encoded.remaining()];
        encoded.get(key);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), expected);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 nicht verfügbar", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private synchronized void enqueue(String sourceId, List<ChangeEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        Map<String, ChangeEvent> pages = queued.computeIfAbsent(sourceId, id -> new LinkedHashMap<>());
        int dropped = 0;
        for (ChangeEvent event : events) {
            ChangeEvent earlier = pages.get(event.pageId());
            if (earlier == null && pages.size() >= MAX_QUEUED) {
                dropped++;
                continue;
            }
            pages.put(event.pageId(), merge(earlier, event));
        }
        if (dropped > 0) {
            LOG.log(System.Logger.Level.WARNING, "Änderungsereignisse von {0}: Warteschlange voll, {1} verworfen",
                    sourceId, dropped);
        }
        schedule(Duration.ZERO);
    }

    /** Die spätere Meldung gilt; eine Rechteänderung bleibt aber stehen, damit die Seite neu geprüft wird. */
    private static ChangeEvent merge(ChangeEvent earlier, ChangeEvent later) {
        if (earlier != null && earlier.kind() == ChangeEvent.Kind.PERMISSIONS
                && later.kind() == ChangeEvent.Kind.CHANGED) {
            return new ChangeEvent(ChangeEvent.Kind.PERMISSIONS, later.pageId(),
                    later.spaceKey() != null ? later.spaceKey() : earlier.spaceKey(), later.version());
        }
        return later;
    }

    private void schedule(Duration delay) {
        if (scheduled) {
            return;
        }
        scheduled = true;
        try {
            worker.schedule(this::drain, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // geschlossen: anstehende Ereignisse verfallen, das Nachholen holt sie
            scheduled = false;
            queued.clear();
            notifyAll();
        }
    }

    private void drain() {
        Map<String, List<ChangeEvent>> batch;
        synchronized (this) {
            scheduled = false;
            batch = new LinkedHashMap<>();
            queued.forEach((sourceId, pages) -> batch.put(sourceId, List.copyOf(pages.values())));
            queued.clear();
            busy++;
        }
        Map<String, List<ChangeEvent>> deferred = new LinkedHashMap<>();
        try {
            batch.forEach((sourceId, events) -> {
                if (!enabled.test(sourceId)) {
                    return;
                }
                try {
                    List<ChangeEvent> again = syncs.applyEvents(sourceId, events);
                    if (!again.isEmpty()) {
                        deferred.put(sourceId, again);
                    }
                } catch (RuntimeException e) {
                    // Webhook-Aufrufe dürfen ausfallen; Nachholen und Inventur bleiben das Sicherheitsnetz.
                    LOG.log(System.Logger.Level.WARNING, "Änderungsereignisse von {0} nicht angewendet ({1})",
                            sourceId, e.getClass().getName());
                }
            });
        } finally {
            synchronized (this) {
                busy--;
                deferred.forEach((sourceId, events) -> {
                    Map<String, ChangeEvent> pages = queued.computeIfAbsent(sourceId, id -> new LinkedHashMap<>());
                    for (ChangeEvent event : events) {
                        pages.putIfAbsent(event.pageId(), event);
                    }
                });
                if (!queued.isEmpty()) {
                    schedule(deferred.isEmpty() ? Duration.ZERO : RETRY);
                }
                notifyAll();
            }
        }
    }
}
