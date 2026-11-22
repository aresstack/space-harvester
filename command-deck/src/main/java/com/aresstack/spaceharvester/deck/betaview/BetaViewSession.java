package com.aresstack.spaceharvester.deck.betaview;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Eine Anmeldung an einem BetaView-Server und alles, was man darin tut. Jeder Vorgang läuft im Hintergrund und kommt
 * als {@link CompletableFuture} zurück; die Vorgänge einer Sitzung laufen streng nacheinander, denn der Server merkt
 * sich das zuletzt geöffnete Dokument. Fragt die Oberfläche eine Seite, den Download oder die Info eines anderen
 * Dokuments als des zuletzt geöffneten, öffnet die Sitzung es vorher erneut. Zustandswechsel gehen an die Beobachter
 * (kein Abfragen), auf dem Thread des Wechsels.
 */
public final class BetaViewSession {

    private final String id;
    private final BetaViewProfile profile;
    private final Executor background;
    private final List<Consumer<BetaViewSession>> observers = new CopyOnWriteArrayList<>();
    /** Schützt den Zustand. */
    private final ReentrantLock stateLock = new ReentrantLock();
    /** Reiht die Aufrufe an den Server nacheinander ein (ein Vorgang zur Zeit). */
    private final ReentrantLock serverLock = new ReentrantLock();
    /** Schützt {@link #tail}, das Ende der Warteschlange der Serveraufrufe. */
    private final ReentrantLock queueLock = new ReentrantLock();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private BetaViewConnection connection;
    private ConnectionState state = ConnectionState.CONNECTING;
    private String failure;
    /** Schlüssel des Dokuments, das der Server gerade offen hat. */
    private String currentDocument;

    BetaViewSession(String id, BetaViewProfile profile, Executor background) {
        this.id = Objects.requireNonNull(id, "id");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.background = Objects.requireNonNull(background, "background");
    }

    public String id() {
        return id;
    }

    public BetaViewProfile profile() {
        return profile;
    }

    public ConnectionState state() {
        stateLock.lock();
        try {
            return state;
        } finally {
            stateLock.unlock();
        }
    }

    /** Der Grund, wenn die Anmeldung scheiterte. */
    public Optional<String> failure() {
        stateLock.lock();
        try {
            return Optional.ofNullable(failure);
        } finally {
            stateLock.unlock();
        }
    }

    public void observe(Consumer<BetaViewSession> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void unobserve(Consumer<BetaViewSession> observer) {
        observers.remove(observer);
    }

    // ---- Suchen ----

    public CompletableFuture<ResultTable> search(SearchFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return call(connection -> connection.search(filter));
    }

    public CompletableFuture<List<SavedSearch>> savedSearches() {
        return call(BetaViewConnection::savedSearches);
    }

    public CompletableFuture<ResultTable> run(SavedSearch search) {
        Objects.requireNonNull(search, "search");
        return call(connection -> connection.run(search));
    }

    public CompletableFuture<Void> saveSearch(NameAndDescription search) {
        Objects.requireNonNull(search, "search");
        if (search.name().isEmpty()) {
            throw new IllegalArgumentException("Name der Suche fehlt");
        }
        return call(connection -> {
            connection.saveSearch(search);
            return null;
        });
    }

    // ---- Dokumente ----

    /** Öffnet ein Dokument über die Aktion eines Treffers oder Lesezeichens. */
    public CompletableFuture<OpenedDocument> open(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Aktion fehlt");
        }
        return call(connection -> remember(connection.open(action)));
    }

    /** Öffnet einen Reiter (erneut), etwa einen aus der letzten Sitzung. */
    public CompletableFuture<OpenedDocument> open(DocumentTab document) {
        Objects.requireNonNull(document, "document");
        return call(connection -> remember(connection.open(document.openAction())));
    }

    /** Text einer Seite (ab 1) des Dokuments. */
    public CompletableFuture<String> page(DocumentTab document, int page) {
        return onDocument(document, connection -> connection.page(page));
    }

    public CompletableFuture<DownloadChoices> downloadChoices(DocumentTab document) {
        return onDocument(document, BetaViewConnection::downloadChoices);
    }

    public CompletableFuture<DownloadedFile> download(DocumentTab document, DownloadOptions options) {
        Objects.requireNonNull(options, "options");
        return onDocument(document, connection -> connection.download(options));
    }

    public CompletableFuture<List<InfoLine>> properties(DocumentTab document) {
        return onDocument(document, BetaViewConnection::properties);
    }

    public CompletableFuture<NameAndDescription> bookmarkProposal(DocumentTab document) {
        return onDocument(document, BetaViewConnection::bookmarkProposal);
    }

    public CompletableFuture<Void> bookmark(DocumentTab document, NameAndDescription bookmark) {
        Objects.requireNonNull(bookmark, "bookmark");
        if (bookmark.name().isEmpty()) {
            throw new IllegalArgumentException("Name fehlt");
        }
        return onDocument(document, connection -> {
            connection.bookmark(bookmark);
            return null;
        });
    }

    public CompletableFuture<List<Bookmark>> bookmarks() {
        return call(BetaViewConnection::bookmarks);
    }

    public CompletableFuture<List<DocumentTab>> openTabs() {
        return call(BetaViewConnection::openTabs);
    }

    public CompletableFuture<Void> close(DocumentTab document) {
        Objects.requireNonNull(document, "document");
        return call(connection -> {
            connection.close(document);
            if (document.key().equals(currentDocument)) {
                currentDocument = null;
            }
            return null;
        });
    }

    public CompletableFuture<Void> closeAll() {
        return call(connection -> {
            connection.closeAll();
            currentDocument = null;
            return null;
        });
    }

    /** Meldet ab: vergisst die Verbindung. Die Cookies des Servers verfallen mit ihr. */
    public void disconnect() {
        stateLock.lock();
        try {
            connection = null;
        } finally {
            stateLock.unlock();
        }
        change(ConnectionState.DISCONNECTED, null);
    }

    // ---- intern ----

    void connected(BetaViewConnection created) {
        stateLock.lock();
        try {
            if (state.ended()) {
                return;
            }
            connection = created;
        } finally {
            stateLock.unlock();
        }
        change(ConnectionState.CONNECTED, null);
    }

    void change(ConnectionState next, String reason) {
        stateLock.lock();
        try {
            if (state.ended() || state == next) {
                return;
            }
            state = next;
            failure = next == ConnectionState.FAILED ? reason : null;
        } finally {
            stateLock.unlock();
        }
        observers.forEach(observer -> observer.accept(this));
    }

    private OpenedDocument remember(OpenedDocument opened) {
        currentDocument = opened.document().key();
        return opened;
    }

    /** Wie {@link #call}, öffnet aber vorher {@code document} erneut, wenn der Server ein anderes offen hat. */
    private <T> CompletableFuture<T> onDocument(DocumentTab document, ServerCall<T> work) {
        Objects.requireNonNull(document, "document");
        return call(connection -> {
            if (!document.key().equals(currentDocument)) {
                remember(connection.open(document.openAction()));
                currentDocument = document.key();
            }
            return work.on(connection);
        });
    }

    /**
     * Stellt einen Serveraufruf hinten an: die Aufrufe einer Sitzung laufen strikt in der Reihenfolge, in der sie
     * angefordert wurden (der Server merkt sich die letzte Suche und das aktuelle Dokument, „Suche speichern“ nach
     * „Suchen“ muss also auch danach laufen). Ein Lock allein garantiert diese Reihenfolge nicht.
     */
    private <T> CompletableFuture<T> call(ServerCall<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        queueLock.lock();
        try {
            tail = tail.handleAsync((ignored, previous) -> {
                run(work, result);
                return null;
            }, background);
            // Nimmt der Executor nichts mehr an (Programmende), bleibt der Aufruf nicht hängen.
            tail.whenComplete((ignored, rejected) -> {
                if (rejected != null) {
                    result.completeExceptionally(rejected);
                }
            });
        } finally {
            queueLock.unlock();
        }
        return result;
    }

    private <T> void run(ServerCall<T> work, CompletableFuture<T> result) {
        serverLock.lock();
        try {
            BetaViewConnection current;
            stateLock.lock();
            try {
                current = connection;
            } finally {
                stateLock.unlock();
            }
            if (current == null) {
                throw new IOException("Nicht angemeldet");
            }
            result.complete(work.on(current));
        } catch (Exception | Error e) {
            result.completeExceptionally(e);
        } finally {
            serverLock.unlock();
        }
    }

    private interface ServerCall<T> {
        T on(BetaViewConnection connection) throws IOException;
    }
}
