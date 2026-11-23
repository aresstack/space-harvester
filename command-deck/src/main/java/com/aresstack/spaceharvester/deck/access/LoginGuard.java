package com.aresstack.spaceharvester.deck.access;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Schutz vor der Kontosperre (Angelo, 23.11.2026): Schlägt eine Anmeldung mit einem Zugang aus dem Passwortspeicher
 * fehl, merkt sich das dieser Wächter JE EINTRAG, nicht je Verbindung oder Bereich. Derselbe Eintrag dient Terminal,
 * FTP, NDV, BetaView und SharePoint; ist sein Passwort abgelaufen, würde sonst jeder Bereich und jeder Reiter es
 * einmal versuchen, und das Konto wäre gesperrt.
 *
 * <p>Die Regel für jeden Bereich:
 * <ol>
 *   <li>Vor jedem Anmeldeversuch, den nicht der Mensch im Formular ausgelöst hat (Wechsel in den Bereich, neuer
 *       Reiter, „Neue Verbindung“ mit gemerkten Daten, Neuaufbau): {@link #mayTry(String)} fragen. Bei {@code false}
 *       NICHT verbinden, sondern das Formular zeigen, mit dem Grund aus {@link #failure(String)}.</li>
 *   <li>Lehnt der Host die Anmeldung ab: {@link #failed(String, String)}.</li>
 *   <li>Klappt eine Anmeldung: {@link #succeeded(String)}. Nur das löscht die Sperre.</li>
 * </ol>
 * Drückt der Mensch im Formular selbst „Verbinden“, darf der Bereich es versuchen, auch wenn die Sperre gesetzt ist:
 * Er hat die Daten geprüft. Das Ergebnis meldet der Bereich wieder über {@link #failed} oder {@link #succeeded}.
 *
 * <p>Die Sperre übersteht den Neustart ({@link Store}). Gespeichert werden nur der Titel des Eintrags, der Grund und
 * die Zeit, nie Benutzer oder Passwort. Threadsicher; Beobachter laufen im meldenden Thread.
 */
public final class LoginGuard {

    /** Wohin die Sperren geschrieben werden (in der App: ihr Zustandsspeicher). {@code null} als Wert entfernt. */
    public interface Store {

        String get(String key);

        void put(String key, String value);

        /** Nur im Speicher (Tests, oder kein Zustandsspeicher). */
        static Store memory() {
            java.util.Map<String, String> values = new java.util.concurrent.ConcurrentHashMap<>();
            return new Store() {
                @Override
                public String get(String key) {
                    return values.get(key);
                }

                @Override
                public void put(String key, String value) {
                    if (value == null) {
                        values.remove(key);
                    } else {
                        values.put(key, value);
                    }
                }
            };
        }
    }

    /**
     * Der letzte Fehlschlag eines Eintrags.
     *
     * @param entry  Titel des Eintrags im Passwortspeicher
     * @param reason was der Host gemeldet hat, für das Formular
     * @param at     wann
     */
    public record Failure(String entry, String reason, Instant at) {
    }

    static final String PREFIX = "deck.login.failed.";

    private final Store store;
    private final List<Consumer<String>> observers = new CopyOnWriteArrayList<>();

    public LoginGuard(Store store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Ob mit diesem Eintrag ohne Zutun des Menschen angemeldet werden darf: nein, solange seine letzte Anmeldung
     * fehlgeschlagen ist. Ohne Eintrag (leer, {@code null}) gibt es nichts zu sperren.
     */
    public boolean mayTry(String entry) {
        return failure(entry).isEmpty();
    }

    /** Der gemerkte Fehlschlag des Eintrags, leer wenn keiner. */
    public Optional<Failure> failure(String entry) {
        if (blank(entry)) {
            return Optional.empty();
        }
        String stored;
        synchronized (this) {
            stored = store.get(key(entry));
        }
        if (stored == null || stored.isEmpty()) {
            return Optional.empty();
        }
        int split = stored.indexOf('|');
        Instant at = Instant.EPOCH;
        String reason = stored;
        if (split > 0) {
            try {
                at = Instant.ofEpochMilli(Long.parseLong(stored.substring(0, split)));
                reason = stored.substring(split + 1);
            } catch (NumberFormatException ignored) {
                // Von Hand geändert: der ganze Text gilt als Grund, die Sperre bleibt.
            }
        }
        return Optional.of(new Failure(entry.trim(), reason, at));
    }

    /** Die Anmeldung mit diesem Eintrag ist fehlgeschlagen: ab jetzt kein automatischer Versuch mehr, nirgends. */
    public void failed(String entry, String reason) {
        failed(entry, reason, Instant.now());
    }

    void failed(String entry, String reason, Instant at) {
        if (blank(entry)) {
            return;
        }
        String text = reason == null || reason.isBlank() ? "Anmeldung abgelehnt" : reason.strip();
        synchronized (this) {
            store.put(key(entry), at.toEpochMilli() + "|" + text);
        }
        changed(entry);
    }

    /** Eine Anmeldung mit diesem Eintrag hat geklappt: Die Sperre ist weg. */
    public void succeeded(String entry) {
        if (blank(entry)) {
            return;
        }
        boolean was;
        synchronized (this) {
            was = store.get(key(entry)) != null;
            if (was) {
                store.put(key(entry), null);
            }
        }
        if (was) {
            changed(entry);
        }
    }

    /** Meldet jede Änderung mit dem Titel des Eintrags (Sperre gesetzt oder gelöst); kein Polling nötig. */
    public void onChange(Consumer<String> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    private void changed(String entry) {
        String title = entry.trim();
        observers.forEach(observer -> observer.accept(title));
    }

    private static boolean blank(String entry) {
        return entry == null || entry.isBlank();
    }

    /** Der Titel als Schlüssel: Groß- und Kleinschreibung zählt nicht, wie im Passwortspeicher gesucht wird. */
    static String key(String entry) {
        return PREFIX + java.net.URLEncoder.encode(entry.trim().toLowerCase(java.util.Locale.ROOT),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
