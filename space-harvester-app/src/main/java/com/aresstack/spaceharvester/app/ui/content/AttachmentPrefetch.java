package com.aresstack.spaceharvester.app.ui.content;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lädt die Anhänge einer Art im Hintergrund vor, damit der Benutzer beim Blättern möglichst nie wartet: zuerst den
 * gezeigten, dann der Reihe nach Abstand zu ihm (+1, −1, +2, −2 …), schließlich alle übrigen. Wechselt der Benutzer den
 * Anhang, richtet sich die Reihenfolge sofort nach dem neuen. Geladen wird nacheinander über {@link Source}, die das
 * Ergebnis auch ablegt; die zuletzt geladenen Bytes bleiben im Speicher, damit Zurückblättern sofort zeigt.
 *
 * <p>Mit {@code handOff} (Fundstelle 18) lädt er selbst nur den gezeigten, seine {@value #NEAR} Nachbarn je Seite und
 * was {@code uiOnly} nennt (Standbilder der Videomarken brauchen den offenen Player); die übrigen gibt er einmal ab, an
 * den Hintergrundteil „Anhänge vorladen“. Lehnt der ab (nur ein Platz), bleibt es beim bisherigen Weg.
 */
final class AttachmentPrefetch implements AutoCloseable {

    /** Lädt einen Anhang und legt ihn ab; läuft im Hintergrund. */
    interface Source {

        byte[] load(String key) throws Exception;

        /** Nichts mehr zu tun: etwa einen offenen Player schließen. */
        default void idle() {
        }
    }

    /** So viele Nachbarn je Seite lädt der Vorlader mit {@code handOff} selbst. */
    static final int NEAR = 2;

    /** So viele zuletzt geladene Anhänge bleiben im Speicher. */
    private final int cached;
    /** Nach so vielen geladenen Anhängen zeigt {@code batch} den Stand, und am Ende. */
    private final int batchSize;

    private final Source source;
    private final Function<Throwable, String> reason;
    private final Runnable batch;
    /** Gibt die übrigen Anhänge ab; {@code true}, wenn sie angenommen wurden. {@code null}: alles selbst laden. */
    private final Function<List<String>, Boolean> handOff;
    private final java.util.function.Predicate<String> uiOnly;
    /** Ob die übrigen schon abgegeben oder abgelehnt sind. */
    private boolean handedOff;
    private boolean refused;
    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > cached;
        }
    };
    /** Schon geladen und abgelegt: im Hintergrund nicht noch einmal. */
    private final Set<String> loaded = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final Map<String, List<Waiter>> waiting = new HashMap<>();
    private List<String> order = List.of();
    private String focus;
    private Thread worker;
    private boolean closed;
    private int sinceBatch;

    private record Waiter(Consumer<byte[]> done, Consumer<String> failed) {
    }

    /**
     * @param reason lesbarer Grund eines Fehlers
     * @param batch     läuft auf dem EDT nach {@code batchSize} geladenen Anhängen und am Ende, etwa um die Liste neu
     *                  zu zeigen
     * @param cached    so viele zuletzt geladene Anhänge bleiben im Speicher
     * @param batchSize nach so vielen geladenen Anhängen läuft {@code batch}
     */
    AttachmentPrefetch(Source source, Function<Throwable, String> reason, Runnable batch, int cached,
                       int batchSize) {
        this(source, reason, batch, cached, batchSize, null, key -> false);
    }

    /**
     * @param handOff gibt die übrigen Anhänge ab (nicht auf dem EDT gerufen); {@code true}, wenn sie angenommen wurden
     * @param uiOnly  Anhänge, die nur hier geladen werden können, etwa Standbilder von Videomarken
     */
    AttachmentPrefetch(Source source, Function<Throwable, String> reason, Runnable batch, int cached,
                       int batchSize, Function<List<String>, Boolean> handOff,
                       java.util.function.Predicate<String> uiOnly) {
        this.handOff = handOff;
        this.uiOnly = Objects.requireNonNull(uiOnly, "uiOnly");
        this.cached = cached;
        this.batchSize = batchSize;
        this.source = Objects.requireNonNull(source, "source");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.batch = Objects.requireNonNull(batch, "batch");
    }

    /**
     * Die Anhänge einer Art in ihrer Reihenfolge und der gezeigte; {@code current == null} behält den bisherigen, wenn
     * er dazugehört, sonst den ersten. Startet das Vorladen.
     */
    synchronized void focus(List<String> keys, String current) {
        if (closed) {
            return;
        }
        order = List.copyOf(keys);
        if (current != null) {
            focus = current;
        } else if (focus == null || !order.contains(focus)) {
            focus = order.isEmpty() ? null : order.get(0);
        }
        start();
    }

    /** Die Bytes eines Anhangs: sofort aus dem Speicher, sonst mit Vorrang geladen. Antwortet auf dem EDT. */
    void load(String key, Consumer<byte[]> done, Consumer<String> failure) {
        synchronized (this) {
            byte[] bytes = cache.get(key);
            if (bytes != null) {
                SwingUtilities.invokeLater(() -> done.accept(bytes));
                return;
            }
            failed.remove(key);
            loaded.remove(key);
            waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(new Waiter(done, failure));
            if (!order.contains(key)) {
                List<String> extended = new ArrayList<>(order);
                extended.add(key);
                order = List.copyOf(extended);
            }
            focus = key;
            start();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        waiting.clear();
        cache.clear();
        notifyAll();
    }

    private void start() {
        if (worker == null && !closed) {
            worker = new Thread(this::run, "space-harvester-attachment-prefetch");
            worker.setDaemon(true);
            worker.start();
        }
    }

    /** Der nächste Anhang: wer wartet zuerst, dann nach Abstand zum gezeigten; {@code null}, wenn alles da ist. */
    private synchronized String next() {
        if (closed) {
            return null;
        }
        if (focus != null && waiting.containsKey(focus)) {
            return focus;
        }
        if (!waiting.isEmpty()) {
            return waiting.keySet().iterator().next();
        }
        int at = Math.max(0, order.indexOf(focus));
        boolean nearOnly = handOff != null && !refused;
        int reach = nearOnly ? Math.min(order.size(), NEAR + 1) : order.size();
        for (int distance = 0; distance < reach; distance++) {
            for (int index : new int[] {at + distance, at - distance}) {
                if (index >= 0 && index < order.size()) {
                    String key = order.get(index);
                    if (!loaded.contains(key) && !failed.contains(key)) {
                        return key;
                    }
                }
            }
        }
        if (nearOnly) {
            for (String key : order) {
                if (uiOnly.test(key) && !loaded.contains(key) && !failed.contains(key)) {
                    return key;
                }
            }
        }
        return null;
    }

    /** Die übrigen Anhänge zum Abgeben, einmal je Vorlader; leer, wenn nichts (mehr) abzugeben ist. */
    private synchronized List<String> rest() {
        if (closed || handOff == null || refused || handedOff) {
            return List.of();
        }
        handedOff = true;
        return order.stream().filter(key -> !uiOnly.test(key) && !loaded.contains(key) && !failed.contains(key))
                .toList();
    }

    private void run() {
        while (true) {
            String key = next();
            if (key == null) {
                List<String> rest = rest();
                if (!rest.isEmpty()) {
                    boolean accepted;
                    try {
                        accepted = Boolean.TRUE.equals(handOff.apply(rest));
                    } catch (RuntimeException e) {
                        accepted = false;
                    }
                    if (!accepted) {
                        // Nur ein Platz oder kein Teil möglich: wie bisher alles selbst laden.
                        synchronized (this) {
                            refused = true;
                        }
                    }
                    continue;
                }
                boolean report;
                synchronized (this) {
                    worker = null;
                    report = sinceBatch > 0 && !closed;
                    sinceBatch = 0;
                }
                source.idle();
                if (report) {
                    SwingUtilities.invokeLater(batch);
                }
                return;
            }
            try {
                byte[] bytes = source.load(key);
                List<Waiter> waiters;
                boolean report;
                synchronized (this) {
                    if (closed) {
                        source.idle();
                        return;
                    }
                    cache.put(key, bytes);
                    loaded.add(key);
                    waiters = waiting.remove(key);
                    report = ++sinceBatch >= batchSize;
                    if (report) {
                        sinceBatch = 0;
                    }
                }
                if (waiters != null) {
                    SwingUtilities.invokeLater(() -> waiters.forEach(waiter -> waiter.done().accept(bytes)));
                }
                if (report) {
                    SwingUtilities.invokeLater(batch);
                }
            } catch (Exception error) {
                List<Waiter> waiters;
                synchronized (this) {
                    failed.add(key);
                    waiters = waiting.remove(key);
                }
                if (waiters != null) {
                    String text = reason.apply(error);
                    SwingUtilities.invokeLater(() -> waiters.forEach(waiter -> waiter.failed().accept(text)));
                }
            }
        }
    }
}
