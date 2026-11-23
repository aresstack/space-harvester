package com.aresstack.spaceharvester.deck.ftp;

import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.ftp.jes.JesConnection;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Eine FTP-Sitzung zu einem Host: eine Verbindung für Datasets und, erst bei Bedarf, eine zweite im JES-Modus für Jobs
 * und Spool (wie in MainframeMate; der Modus gilt je Verbindung). Beide teilen Host und KeePass-Eintrag.
 *
 * <p>Jede Verbindung bedient einen Auftrag zur Zeit (FTP ist ein Zwiegespräch); Aufträge laufen auf dem
 * Hintergrund-Executor und liefern ein {@link CompletableFuture}. Bricht die Verbindung weg, wird beim nächsten
 * Auftrag neu verbunden; lesende Aufträge werden nach der {@link RetryPolicy} des Profils wiederholt (MainframeMate:
 * Versuche, Wartezeit, Backoff, Antwortcodes), schreibende nie, damit nichts doppelt geschieht. Zustandswechsel melden
 * die Beobachter, kein Abfragen.
 */
public final class FtpSession {

    /** Liest Benutzer und Passwort kurz vor dem Anmelden; das Ergebnis wird sofort geschlossen. */
    @FunctionalInterface
    public interface CredentialSource {
        ServerCredentials open() throws Exception;
    }

    /** Ein Auftrag an eine Verbindung. */
    @FunctionalInterface
    public interface Task<C, T> {
        T run(C connection) throws Exception;
    }

    @FunctionalInterface
    private interface Opener<C> {
        C open(FtpProfile profile, String user, char[] password) throws IOException;
    }

    private final String id;
    private final String label;
    private final FtpProfile profile;
    private final CredentialSource credentials;
    private final Executor background;
    private final FtpConnector connector;
    private final List<Consumer<FtpSession>> observers = new CopyOnWriteArrayList<>();
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Lane<FileConnection> files;
    private final Lane<JesConnection> jobs;
    private ConnectionState state = ConnectionState.CONNECTING;
    private String failure;
    private volatile String user = "";
    private volatile boolean mvs;

    FtpSession(String id, String label, FtpProfile profile, CredentialSource credentials, FtpConnector connector,
               Executor background) {
        this.id = Objects.requireNonNull(id, "id");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.label = label == null || label.isBlank() ? profile.label() : label;
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.background = Objects.requireNonNull(background, "background");
        this.connector = Objects.requireNonNull(connector, "connector");
        files = new Lane<>(connector::files, FileConnection::alive, FileConnection::close, true);
        jobs = new Lane<>(connector::jes, JesConnection::alive, JesConnection::close, false);
    }

    public String id() {
        return id;
    }

    /** Name für Pillen und Statuszeile: der KeePass-Eintrag, sonst der Host. */
    public String label() {
        return label;
    }

    public FtpProfile profile() {
        return profile;
    }

    /** Der angemeldete Benutzer, sobald verbunden. */
    public String user() {
        return user;
    }

    /** Ob der Host ein MVS ist, sobald verbunden. */
    public boolean mvs() {
        return mvs;
    }

    private volatile boolean loginRefused;

    public ConnectionState state() {
        stateLock.lock();
        try {
            return state;
        } finally {
            stateLock.unlock();
        }
    }

    /** Ist die Sitzung gescheitert, weil der Host die Anmeldung abgelehnt hat (nicht wegen Netz oder Zeitlimit)? */
    public boolean loginRefused() {
        return loginRefused && state() == ConnectionState.FAILED;
    }

    private static boolean refusal(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof LoginRefusedException) {
                return true;
            }
        }
        return false;
    }

    public Optional<String> failure() {
        stateLock.lock();
        try {
            return Optional.ofNullable(failure);
        } finally {
            stateLock.unlock();
        }
    }

    public void observe(Consumer<FtpSession> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void unobserve(Consumer<FtpSession> observer) {
        observers.remove(observer);
    }

    /** Baut die Dataset-Verbindung auf (beim Öffnen und für „Erneut verbinden“). */
    public CompletableFuture<String> connect() {
        if (state() == ConnectionState.FAILED) {
            change(ConnectionState.CONNECTING, null, true);
        }
        return files(FileConnection::home);
    }

    /** Ein lesender Auftrag an die Dataset-Verbindung; nach Verbindungsabbruch einmal wiederholt. */
    public <T> CompletableFuture<T> files(Task<FileConnection, T> task) {
        return submit(files, task, true);
    }

    /** Ein schreibender Auftrag an die Dataset-Verbindung; nie wiederholt. */
    public <T> CompletableFuture<T> filesOnce(Task<FileConnection, T> task) {
        return submit(files, task, false);
    }

    /** Ein lesender Auftrag an die JES-Verbindung (beim ersten Mal wird sie aufgebaut). */
    public <T> CompletableFuture<T> jobs(Task<JesConnection, T> task) {
        return submit(jobs, task, true);
    }

    /** Ein schreibender Auftrag an die JES-Verbindung (Einreichen, Löschen); nie wiederholt. */
    public <T> CompletableFuture<T> jobsOnce(Task<JesConnection, T> task) {
        return submit(jobs, task, false);
    }

    /**
     * Lädt die DD-Namen der Spool-Dateien {@code 1..count} einzeln nach, über so viele eigene JES-Verbindungen, wie
     * das Profil erlaubt (MainframeMate „Parallele Verbindungen“, Verfahren OFF und FAST mit Nachladen). Die
     * Verbindungen werden danach geschlossen; was nicht abrufbar war, fehlt in der Antwort.
     */
    /** Die Verbindungen, die gerade DD-Namen nachladen; „Trennen“ schließt sie mit. */
    private final java.util.Set<JesConnection> probes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CompletableFuture<java.util.Map<Integer, String>> probeSpoolNames(String jobId, int count) {
        java.util.Map<Integer, String> names = new java.util.concurrent.ConcurrentHashMap<>();
        if (state().ended() || count < 1) {
            return CompletableFuture.completedFuture(names);
        }
        int lanes = Math.max(1, Math.min(profile.spoolNaming().connections(), count));
        List<CompletableFuture<Void>> parts = new java.util.ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            int first = lane + 1;
            parts.add(CompletableFuture.runAsync(() -> {
                JesConnection jes;
                try {
                    jes = openJes();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
                probes.add(jes);
                try {
                    // Trennen kann zwischen Anmelden und Eintragen gekommen sein: dann sofort aufhören.
                    for (int id = first; id <= count && !state().ended(); id += lanes) {
                        try {
                            jes.probe(jobId, id).ifPresent(file -> names.put(file.id(), file.ddName()));
                        } catch (FtpReplyException missing) {
                            // nicht abrufbar: Name bleibt SPOOL#n
                        }
                    }
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                } finally {
                    probes.remove(jes);
                    jes.close();
                }
            }, background));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).handle((done, error) -> {
            if (error != null && names.isEmpty()) {
                throw error instanceof java.util.concurrent.CompletionException completion ? completion
                        : new java.util.concurrent.CompletionException(error);
            }
            return names;
        });
    }

    private JesConnection openJes() throws Exception {
        try (ServerCredentials login = credentials.open()) {
            char[] password = login.copyPassword();
            try {
                return connector.jes(profile, login.user(), password);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** Trennt beide Verbindungen; laufende Aufträge brechen ab. Danach nimmt die Sitzung nichts mehr an. */
    public void disconnect() {
        change(ConnectionState.DISCONNECTED, null, false);
        files.shutdown();
        jobs.shutdown();
        // Nachlade-Verbindungen gehören keiner Leitung: einzeln schließen, das bricht auch eine hängende Abfrage ab.
        for (JesConnection probe : List.copyOf(probes)) {
            probe.close();
        }
    }

    private <C, T> CompletableFuture<T> submit(Lane<C> lane, Task<C, T> task, boolean retry) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (state().ended()) {
            result.completeExceptionally(new IllegalStateException("Die Verbindung ist getrennt"));
            return result;
        }
        try {
            background.execute(() -> {
                try {
                    result.complete(lane.call(task, retry));
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RuntimeException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private void change(ConnectionState next, String reason, boolean force) {
        stateLock.lock();
        try {
            if ((state.ended() && !force) || (state == next && Objects.equals(failure, reason))) {
                return;
            }
            state = next;
            failure = next == ConnectionState.FAILED ? reason : null;
        } finally {
            stateLock.unlock();
        }
        observers.forEach(observer -> observer.accept(this));
    }

    /** Ein lesbarer Grund: Klasse und Meldung der eigentlichen Ursache, ohne Stacktrace. */
    public static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (cause instanceof FtpReplyException || cause instanceof IllegalStateException
                || cause instanceof IllegalArgumentException) {
            return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
        }
        String type = cause.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /** Eine Verbindung mit eigenem Schloss: ein Auftrag zur Zeit, Aufbau bei Bedarf. */
    private final class Lane<C> {

        private final Opener<C> opener;
        private final java.util.function.Predicate<C> alive;
        private final Consumer<C> closer;
        private final boolean primary;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile C connection;
        private volatile boolean shut;

        Lane(Opener<C> opener, java.util.function.Predicate<C> alive, Consumer<C> closer, boolean primary) {
            this.opener = opener;
            this.alive = alive;
            this.closer = closer;
            this.primary = primary;
        }

        <T> T call(Task<C, T> task, boolean retry) throws Exception {
            lock.lock();
            try {
                RetryPolicy policy = profile.retry();
                for (int attempt = 1; ; attempt++) {
                    C current = ensure();
                    try {
                        return task.run(current);
                    } catch (FtpReplyException refused) {
                        // Abgelehnt, die Verbindung steht: nur bei eingestellten Antwortcodes noch einmal.
                        if (!retry || shut || !policy.shouldRetry(refused, attempt)) {
                            throw refused;
                        }
                    } catch (IOException broken) {
                        // Kaputte Verbindung nie stehen lassen: der nächste Versuch oder Auftrag baut neu auf.
                        drop();
                        if (!retry || shut || !policy.shouldRetry(broken, attempt)) {
                            throw broken;
                        }
                    }
                    pause(policy.delayMillis(attempt));
                }
            } finally {
                lock.unlock();
            }
        }

        private void pause(long millis) throws java.io.InterruptedIOException {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Wiederholung abgebrochen");
            }
        }

        private C ensure() throws Exception {
            if (shut) {
                throw new IllegalStateException("Die Verbindung ist getrennt");
            }
            C current = connection;
            if (current != null && alive.test(current)) {
                return current;
            }
            if (current != null) {
                drop();
            }
            try {
                current = open();
            } catch (Exception e) {
                if (primary) {
                    loginRefused = refusal(e);
                    change(ConnectionState.FAILED, describe(e), false);
                }
                throw e;
            }
            if (shut) {
                closer.accept(current);
                throw new IllegalStateException("Die Verbindung ist getrennt");
            }
            connection = current;
            if (primary) {
                change(ConnectionState.CONNECTED, null, false);
            }
            return current;
        }

        private C open() throws Exception {
            try (ServerCredentials login = credentials.open()) {
                char[] password = login.copyPassword();
                try {
                    C opened = opener.open(profile, login.user(), password);
                    user = login.user();
                    if (opened instanceof FileConnection fileConnection) {
                        mvs = fileConnection.mvs();
                    }
                    return opened;
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        }

        private void drop() {
            C current = connection;
            connection = null;
            if (current != null) {
                closer.accept(current);
            }
        }

        /** Von außen, auch während ein Auftrag läuft: schließt die Verbindung, damit er abbricht. */
        void shutdown() {
            shut = true;
            drop();
        }
    }
}
