package com.aresstack.spaceharvester.application.signin;

import com.aresstack.spaceharvester.application.sources.SignInChallenge;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Zusätzliche Anmeldung während eines Auftrags (ADR 0018, #185). Nach dem Vorbild des Enterprise AI Client
 * ({@code UnlockingDocumentReader} mit {@code OneTimeCodePrompt}): verlangt die Quelle einen Einmalcode, wartet der
 * Lesezugriff, bis der Benutzer ihn eingegeben hat, und liest dann neu. Anders als dort fragt hier keine
 * blockierende Oberfläche: der Auftrag wartet an derselben Stelle ({@link #await}), die Oberfläche sieht die offene
 * Anforderung ({@link #pending()}, {@link #onChange}) und sendet den Code ({@link #submit}).
 *
 * <p>Je Quelle eine Anforderung, weil die Sitzung für die ganze Quelle gilt: weitere Aufträge derselben Quelle warten
 * auf dieselbe Anforderung. Wartet niemand mehr (Abbruch), verschwindet sie. Der Code wird nur an die Abfrage der Quelle
 * weitergereicht und danach im übergebenen {@code char[]} überschrieben; er steht in keinem Feld, Log oder Ereignis.
 * Mit Wartezeit null (Server ohne Oberfläche) wartet niemand: die Datei gilt sofort als „Anmeldung erforderlich“.
 */
public class SignInRequests {

    private static final System.Logger LOG = System.getLogger(SignInRequests.class.getName());
    /** Standard: so lange wartet ein Auftrag auf den Code, danach fehlen die übrigen Dateien mit Grund. */
    public static final Duration DEFAULT_WAIT = Duration.ofMinutes(15);
    /** Eine Freischaltung so kurz vor dem Fehlschlag gilt schon für ihn (Anfrage lief noch ohne Sitzung). */
    private static final Duration RECENT = Duration.ofSeconds(30);
    private static final long POLL_MILLIS = 250;

    /** Wie ein Warten endet. */
    public enum Outcome {
        /** Die Quelle hat den Code angenommen; der Aufrufer lädt neu. */
        UNLOCKED,
        /** Abgelehnt, keine Oberfläche oder Wartezeit abgelaufen; die Datei fehlt mit Grund. */
        DECLINED
    }

    private final Duration wait;
    private final Clock clock;
    private final Map<String, Pending> bySource = new HashMap<>();
    private final Map<String, Instant> unlockedAt = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** @param wait wie lange ein Auftrag höchstens auf den Code wartet; {@link Duration#ZERO}: nie */
    public SignInRequests(Duration wait, Clock clock) {
        this.wait = Objects.requireNonNull(wait, "wait");
        if (wait.isNegative()) {
            throw new IllegalArgumentException("wait darf nicht negativ sein");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ohne Oberfläche: niemand wartet, jede Anforderung endet sofort mit {@link Outcome#DECLINED}. */
    public static SignInRequests none() {
        return new SignInRequests(Duration.ZERO, Clock.systemUTC());
    }

    /** Ob überhaupt gewartet wird. */
    public boolean interactive() {
        return !wait.isZero();
    }

    /**
     * Wartet, bis die Quelle freigeschaltet ist. Blockiert; ruft dabei regelmäßig {@code checkpoint} auf, das mit einer
     * Ausnahme abbricht (Abbruch des Auftrags) und die Anforderung für diesen Auftrag zurückzieht.
     *
     * @param failedAt  wann die Anfrage begann, die den Code verlangt hat; eine Freischaltung danach gilt schon
     * @param checkpoint wird beim Warten aufgerufen und darf mit einer Ausnahme abbrechen
     */
    public Outcome await(String sourceId, String jobId, SignInChallenge challenge, Instant failedAt,
                         Runnable checkpoint) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(challenge, "challenge");
        Pending pending;
        synchronized (this) {
            Instant unlocked = unlockedAt.get(sourceId);
            if (unlocked != null && failedAt != null && !unlocked.isBefore(failedAt.minus(RECENT))) {
                return Outcome.UNLOCKED;
            }
            if (!interactive()) {
                return Outcome.DECLINED;
            }
            pending = bySource.get(sourceId);
            if (pending == null) {
                pending = new Pending(UUID.randomUUID().toString(), sourceId, challenge, clock.instant());
                bySource.put(sourceId, pending);
                LOG.log(System.Logger.Level.INFO, "Quelle {0} verlangt eine Anmeldung (Einmalcode)", sourceId);
            }
            pending.jobs.add(jobId == null ? "" : jobId);
        }
        changed();
        Instant deadline = clock.instant().plus(wait);
        try {
            while (true) {
                synchronized (this) {
                    if (pending.state == State.UNLOCKED) {
                        return Outcome.UNLOCKED;
                    }
                    if (pending.state == State.DECLINED) {
                        return Outcome.DECLINED;
                    }
                    if (!clock.instant().isBefore(deadline)) {
                        return Outcome.DECLINED;
                    }
                    try {
                        wait(POLL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Outcome.DECLINED;
                    }
                }
                if (checkpoint != null) {
                    checkpoint.run();
                }
            }
        } finally {
            leave(pending, jobId == null ? "" : jobId);
        }
    }

    private void leave(Pending pending, String jobId) {
        boolean changed = false;
        synchronized (this) {
            pending.jobs.remove(jobId);
            if (pending.jobs.isEmpty() && bySource.get(pending.sourceId) == pending) {
                bySource.remove(pending.sourceId);
                changed = true;
            } else if (pending.state == State.OPEN) {
                changed = true;
            }
        }
        if (changed) {
            changed();
        }
    }

    /** Offene Anforderungen, älteste zuerst. */
    public synchronized List<SignInRequest> pending() {
        List<SignInRequest> list = new ArrayList<>();
        for (Pending pending : bySource.values()) {
            list.add(pending.view());
        }
        list.sort((a, b) -> a.since().compareTo(b.since()));
        return list;
    }

    /** Die offene Anforderung einer Quelle. */
    public synchronized Optional<SignInRequest> pendingFor(String sourceId) {
        return Optional.ofNullable(bySource.get(sourceId)).map(Pending::view);
    }

    /** Die Anforderung, auf die ein Auftrag gerade wartet („Wartet auf Anmeldung“). */
    public synchronized Optional<SignInRequest> waiting(String jobId) {
        return bySource.values().stream().filter(pending -> pending.jobs.contains(jobId)).findFirst()
                .map(Pending::view);
    }

    /** Die offene Anforderung mit dieser Kennung. */
    public synchronized Optional<SignInRequest> request(String requestId) {
        return find(requestId).map(Pending::view);
    }

    /**
     * Sendet den Code an die Quelle und überschreibt {@code code} danach. Blockiert (Netz), nicht auf dem EDT
     * aufrufen. Gleichzeitige Versuche für dieselbe Anforderung laufen nacheinander.
     */
    public SignInRequest.Answer submit(String requestId, char[] code) {
        Objects.requireNonNull(code, "code");
        try {
            Pending pending;
            synchronized (this) {
                pending = find(requestId).orElse(null);
            }
            if (pending == null) {
                return SignInRequest.Answer.GONE;
            }
            synchronized (pending.sending) {
                SignInChallenge challenge;
                synchronized (this) {
                    if (pending.state != State.OPEN) {
                        return pending.state == State.UNLOCKED ? SignInRequest.Answer.ACCEPTED
                                : SignInRequest.Answer.GONE;
                    }
                    challenge = pending.challenge;
                }
                SignInChallenge next;
                try {
                    next = challenge.submit(code);
                } catch (SourceAccessException e) {
                    LOG.log(System.Logger.Level.INFO, "Einmalcode für Quelle {0} nicht gesendet ({1})",
                            pending.sourceId, e.reason());
                    return SignInRequest.Answer.FAILED;
                }
                synchronized (this) {
                    if (next == null) {
                        pending.state = State.UNLOCKED;
                        unlockedAt.put(pending.sourceId, clock.instant());
                        if (bySource.get(pending.sourceId) == pending) {
                            bySource.remove(pending.sourceId);
                        }
                        notifyAll();
                    } else {
                        pending.challenge = next;
                    }
                }
                LOG.log(System.Logger.Level.INFO, "Einmalcode für Quelle {0} {1}", pending.sourceId,
                        next == null ? "angenommen" : "nicht angenommen");
                changed();
                return next == null ? SignInRequest.Answer.ACCEPTED : SignInRequest.Answer.REJECTED;
            }
        } finally {
            Arrays.fill(code, '\0');
        }
    }

    /**
     * Fordert bei der Quelle einen neuen Code an. Blockiert (Netz).
     *
     * @return die Anforderung danach; leer, wenn es sie nicht mehr gibt
     * @throws SourceAccessException wenn das Anfordern scheitert
     */
    public Optional<SignInRequest> resend(String requestId) {
        Pending pending;
        synchronized (this) {
            pending = find(requestId).orElse(null);
        }
        if (pending == null) {
            return Optional.empty();
        }
        synchronized (pending.sending) {
            SignInChallenge challenge;
            synchronized (this) {
                if (pending.state != State.OPEN) {
                    return Optional.empty();
                }
                challenge = pending.challenge;
            }
            SignInChallenge next = challenge.resend();
            synchronized (this) {
                if (next != null) {
                    pending.challenge = next;
                }
            }
        }
        changed();
        return request(requestId);
    }

    /** Lehnt ab: die wartenden Aufträge laden ohne diese Dateien weiter, sie fehlen mit Grund. */
    public boolean decline(String requestId) {
        synchronized (this) {
            Pending pending = find(requestId).orElse(null);
            if (pending == null) {
                return false;
            }
            pending.state = State.DECLINED;
            bySource.remove(pending.sourceId);
            notifyAll();
        }
        changed();
        return true;
    }

    /**
     * Meldet jede Änderung der offenen Anforderungen (neu, Code abgelehnt, erledigt). Der Aufruf kommt aus einem
     * Auftrags- oder Aufrufer-Thread; die Oberfläche liest danach {@link #pending()}.
     *
     * @return zum Abmelden
     */
    public AutoCloseable onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    private void changed() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Beobachter der Anmeldungen gescheitert ({0})",
                        e.getClass().getName());
            }
        }
    }

    private Optional<Pending> find(String requestId) {
        return bySource.values().stream().filter(pending -> pending.id.equals(requestId)).findFirst();
    }

    private enum State {
        OPEN, UNLOCKED, DECLINED
    }

    private static final class Pending {

        final String id;
        final String sourceId;
        final Instant since;
        final Set<String> jobs = new LinkedHashSet<>();
        final Object sending = new Object();
        SignInChallenge challenge;
        State state = State.OPEN;

        Pending(String id, String sourceId, SignInChallenge challenge, Instant since) {
            this.id = id;
            this.sourceId = sourceId;
            this.challenge = challenge;
            this.since = since;
        }

        SignInRequest view() {
            return new SignInRequest(id, sourceId, challenge.service(), challenge.title(), challenge.message(),
                    challenge.error(), challenge.resendAvailable(), List.copyOf(jobs), since);
        }
    }
}
