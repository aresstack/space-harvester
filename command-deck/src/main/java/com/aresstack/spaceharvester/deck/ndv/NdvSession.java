package com.aresstack.spaceharvester.deck.ndv;

import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Eine Sitzung am NDV-Server. Der Server spricht über eine einzige Verbindung ein Gespräch nach dem anderen
 * (MainframeMate {@code NdvService}); deshalb reiht die Sitzung alle Aufrufe hintereinander. Alle Aufrufe blockieren
 * und gehören nicht auf den Event Dispatch Thread.
 *
 * <p>Selbstheilend: Reißt die Verbindung ab, baut der nächste Aufruf sie einmal neu auf (Passwort neu aus KeePass) und
 * wiederholt sich; erst wenn auch das scheitert, ist die Sitzung {@link NdvState#FAILED} mit Grund. Nach „Trennen“
 * verbindet nichts von selbst.
 *
 * <p>Zustandswechsel meldet die Sitzung ihren Beobachtern (kein Abfragen), auf dem Thread, der den Wechsel auslöste.
 */
public final class NdvSession {

    private final String id;
    private final NdvProfile profile;
    private final String account;
    private final NdvConnector connector;
    private final NdvSessions.LoginSource login;
    private final List<Consumer<NdvSession>> observers = new CopyOnWriteArrayList<>();
    /** Reiht die Gespräche mit dem Server; fair, damit kein Aufruf verhungert. */
    private final ReentrantLock conversation = new ReentrantLock(true);
    private final ReentrantLock stateLock = new ReentrantLock();
    private NdvConnection connection;
    private NdvState state = NdvState.CONNECTING;
    private String failure;
    private String user = "";

    NdvSession(String id, NdvProfile profile, String account, NdvConnector connector, NdvSessions.LoginSource login) {
        this.id = Objects.requireNonNull(id, "id");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.account = account == null ? "" : account.trim();
        this.connector = Objects.requireNonNull(connector, "connector");
        this.login = Objects.requireNonNull(login, "login");
    }

    public String id() {
        return id;
    }

    public NdvProfile profile() {
        return profile;
    }

    /** Titel des KeePass-Eintrags, leer wenn keiner. */
    public String account() {
        return account;
    }

    /** Kurzname für Reiter: Eintrag oder {@code host:port}. */
    public String label() {
        return account.isEmpty() ? profile.label() : account;
    }

    /** Der angemeldete Benutzer, sobald verbunden. */
    public String user() {
        stateLock.lock();
        try {
            return user;
        } finally {
            stateLock.unlock();
        }
    }

    public NdvState state() {
        stateLock.lock();
        try {
            return state;
        } finally {
            stateLock.unlock();
        }
    }

    /** Der Grund, wenn die Verbindung scheiterte oder abriss. */
    public Optional<String> failure() {
        stateLock.lock();
        try {
            return Optional.ofNullable(failure);
        } finally {
            stateLock.unlock();
        }
    }

    public void observe(Consumer<NdvSession> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void unobserve(Consumer<NdvSession> observer) {
        observers.remove(observer);
    }

    /** Bibliotheken, deren Name zum Muster passt; leer oder {@code *} für alle. */
    public List<String> libraries(String filter) throws IOException {
        String pattern = filter == null || filter.isBlank() ? "*" : filter.trim().toUpperCase(java.util.Locale.ROOT);
        return converse(connection -> connection.libraries(pattern));
    }

    /**
     * Objekte einer Bibliothek, Seite für Seite (Teilergebnisse sofort). Beim Wiederholen nach Verbindungsabriss
     * beginnt die Auflistung von vorn; der Empfänger bekommt dann {@code totalSoFar} wieder ab der ersten Seite.
     */
    public int objects(String library, String filter, NdvConnection.Page page) throws IOException {
        Objects.requireNonNull(page, "page");
        String lib = requireLibrary(library);
        String pattern = filter == null || filter.isBlank() ? "*" : filter.trim().toUpperCase(java.util.Locale.ROOT);
        return converse(connection -> connection.objects(lib, pattern, page));
    }

    /** Quelltext eines Objekts. */
    public String read(NdvObject object) throws IOException {
        Objects.requireNonNull(object, "object");
        if (!object.source()) {
            throw new IOException("„" + object.displayName() + "“ hat keinen Quelltext auf dem Server");
        }
        return converse(connection -> connection.read(object));
    }

    /** Speichert den Quelltext; nur auf ausdrücklichen Wunsch (Speichern im Editor), nie beim Öffnen. */
    public void write(NdvObject object, String source) throws IOException {
        Objects.requireNonNull(object, "object");
        converse(connection -> {
            connection.write(object, source == null ? "" : source);
            return null;
        });
    }

    /** Baut die Verbindung (erneut) auf; blockiert. Für den ersten Aufbau und „Neu verbinden“. */
    public void connect() {
        conversation.lock();
        try {
            change(NdvState.CONNECTING, null);
            try {
                ensureConnection(true);
            } catch (IOException e) {
                change(NdvState.FAILED, NdvSessions.describe(e));
            }
        } finally {
            conversation.unlock();
        }
    }

    /**
     * Trennt; danach verbindet nichts von selbst. Wartet nicht auf ein laufendes Gespräch. Der Zustand wechselt
     * zugleich mit dem Abhängen der Verbindung, damit ein paralleles Gespräch keine neue einhängen kann.
     */
    public void disconnect() {
        NdvConnection current;
        boolean changed;
        stateLock.lock();
        try {
            current = connection;
            connection = null;
            changed = state != NdvState.DISCONNECTED;
            state = NdvState.DISCONNECTED;
            failure = null;
        } finally {
            stateLock.unlock();
        }
        if (current != null) {
            current.close();
        }
        if (changed) {
            observers.forEach(observer -> observer.accept(this));
        }
    }

    @FunctionalInterface
    private interface Conversation<T> {
        T with(NdvConnection connection) throws IOException;
    }

    private <T> T converse(Conversation<T> work) throws IOException {
        conversation.lock();
        try {
            if (state() == NdvState.DISCONNECTED) {
                throw new IOException("Die Sitzung ist getrennt – neu verbinden");
            }
            NdvConnection current;
            try {
                current = ensureConnection(false);
            } catch (IOException lost) {
                change(NdvState.FAILED, NdvSessions.describe(lost));
                throw lost;
            }
            try {
                return work.with(current);
            } catch (IOException first) {
                if (current.open()) {
                    throw first;
                }
                // Verbindung abgerissen: einmal neu aufbauen und wiederholen – außer, es wurde von Hand getrennt.
                current.close();
                if (state() == NdvState.DISCONNECTED) {
                    throw first;
                }
                NdvConnection fresh;
                try {
                    fresh = ensureConnection(true);
                } catch (IOException reconnect) {
                    reconnect.addSuppressed(first);
                    change(NdvState.FAILED, NdvSessions.describe(reconnect));
                    throw reconnect;
                }
                try {
                    return work.with(fresh);
                } catch (IOException second) {
                    if (!fresh.open()) {
                        change(NdvState.FAILED, NdvSessions.describe(second));
                    }
                    throw second;
                }
            }
        } finally {
            conversation.unlock();
        }
    }

    /** Die stehende Verbindung oder eine neue; nur unter {@link #conversation}. */
    private NdvConnection ensureConnection(boolean fresh) throws IOException {
        NdvConnection current;
        stateLock.lock();
        try {
            current = connection;
        } finally {
            stateLock.unlock();
        }
        if (!fresh && current != null && current.open()) {
            return current;
        }
        if (current != null) {
            current.close();
        }
        NdvConnection created;
        String name;
        try (ServerCredentials credentials = login.read()) {
            name = credentials.user();
            if (name.isBlank()) {
                throw new IOException("Der KeePass-Eintrag hat keinen Benutzer");
            }
            if (!user().isEmpty() && !user().equalsIgnoreCase(name)) {
                throw new IOException("NDV-Benutzer geändert – bitte eine neue Sitzung öffnen");
            }
            char[] password = credentials.copyPassword();
            try {
                created = connector.connect(profile, name, password);
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(NdvSessions.describe(e), e);
        }
        boolean keep;
        stateLock.lock();
        try {
            // Inzwischen von Hand getrennt: die neue Verbindung nicht stehen lassen.
            keep = state != NdvState.DISCONNECTED;
            if (keep) {
                connection = created;
                user = name.toUpperCase(java.util.Locale.ROOT);
            }
        } finally {
            stateLock.unlock();
        }
        if (!keep) {
            created.close();
            throw new IOException("Die Sitzung ist getrennt – neu verbinden");
        }
        change(NdvState.CONNECTED, null);
        return created;
    }

    private static String requireLibrary(String library) {
        if (library == null || library.isBlank()) {
            throw new IllegalArgumentException("Bibliothek fehlt");
        }
        return library.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Setzt den Zustand und meldet ihn, wenn er sich ändert. */
    void change(NdvState next, String reason) {
        stateLock.lock();
        try {
            String nextFailure = next == NdvState.FAILED ? reason : null;
            if (state == next && Objects.equals(failure, nextFailure)) {
                return;
            }
            // Nach „Trennen“ zählt nur ein neuer Aufbau; ein spät scheiterndes Gespräch ändert nichts mehr.
            if (state == NdvState.DISCONNECTED && next != NdvState.CONNECTING) {
                return;
            }
            state = next;
            failure = nextFailure;
        } finally {
            stateLock.unlock();
        }
        observers.forEach(observer -> observer.accept(this));
    }
}
