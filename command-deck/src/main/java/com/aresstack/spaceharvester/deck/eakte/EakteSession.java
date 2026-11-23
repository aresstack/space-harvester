package com.aresstack.spaceharvester.deck.eakte;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Eine E-Akte-Sitzung des Command Deck: Zustand, Grund eines Fehlers und die Vorgänge (Mappe lesen, Dokument lesen),
 * alle im Hintergrund. Läuft die Sitzung ab, ohne dass sie sich ohne Kennwort erneuern lässt, wird sie
 * {@link EakteState#FAILED}; ein Kennwort sendet sie nie von sich aus erneut.
 */
public final class EakteSession {

    private final String id;
    private final EakteProfile profile;
    private final Executor background;
    private final List<Consumer<EakteSession>> observers = new CopyOnWriteArrayList<>();
    private volatile EakteState state = EakteState.CONNECTING;
    private volatile String failure;
    private volatile EakteConnection connection;

    EakteSession(String id, EakteProfile profile, Executor background) {
        this.id = id;
        this.profile = profile;
        this.background = background;
    }

    public String id() {
        return id;
    }

    public EakteProfile profile() {
        return profile;
    }

    public EakteState state() {
        return state;
    }

    /** Grund des letzten Fehlers, solange die Sitzung im Zustand FAILED ist. */
    public Optional<String> failure() {
        return state == EakteState.FAILED ? Optional.ofNullable(failure) : Optional.empty();
    }

    /** Pfad der WebDAV-Wurzel, sobald angemeldet. */
    public Optional<String> rootHref() {
        EakteConnection open = connection;
        return open == null ? Optional.empty() : Optional.of(open.rootHref());
    }

    /** Meldet jede Zustandsänderung, im Thread der Änderung. */
    public void observe(Consumer<EakteSession> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public CompletableFuture<List<EakteEntry>> list(String href) {
        return run(open -> open.list(href));
    }

    public CompletableFuture<byte[]> read(String href) {
        return run(open -> open.read(href));
    }

    /** Schreibt das Dokument gestreamt nach {@code target}; liefert die Zahl der Bytes. */
    public CompletableFuture<Long> download(String href, java.io.OutputStream target) {
        return run(open -> open.download(href, target));
    }

    /** Trennt; danach liefert jeder Vorgang einen Fehler. */
    public void disconnect() {
        EakteConnection open = connection;
        connection = null;
        if (open != null) {
            open.close();
        }
        if (state != EakteState.FAILED) {
            change(EakteState.DISCONNECTED, null);
        }
    }

    void connected(EakteConnection open) {
        connection = open;
        change(EakteState.CONNECTED, null);
    }

    void change(EakteState next, String reason) {
        state = next;
        failure = reason;
        observers.forEach(observer -> observer.accept(this));
    }

    private interface Work<T> {
        T apply(EakteConnection connection) throws Exception;
    }

    private <T> CompletableFuture<T> run(Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        background.execute(() -> {
            EakteConnection open = connection;
            if (open == null) {
                result.completeExceptionally(new IllegalStateException("Nicht angemeldet"));
                return;
            }
            try {
                result.complete(work.apply(open));
            } catch (SessionExpiredException e) {
                connection = null;
                change(EakteState.FAILED, e.getMessage());
                result.completeExceptionally(e);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }
}
