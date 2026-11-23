package com.aresstack.spaceharvester.deck.editor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Anwendungsfall „Dokumente im gemeinsamen Editor“: öffnen, auflisten, schließen. Dieselbe Adresse derselben Ablage
 * wird nur einmal geöffnet. Die Dokumente gehören nicht zur Oberfläche und überleben jeden Bereichs- und
 * Perspektivenwechsel (#1141).
 */
public final class EditorSessions {

    private final Executor background;
    private final VersionHistory history;
    private final List<EditorSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Consumer<EditorSession>> openedObservers = new CopyOnWriteArrayList<>();
    private final List<Consumer<EditorSession>> closedObservers = new CopyOnWriteArrayList<>();
    private final List<Consumer<EditorSession>> revealObservers = new CopyOnWriteArrayList<>();

    public EditorSessions(Executor background) {
        this(background, VersionHistory.NONE);
    }

    /** Mit lokalem Verlauf: Version beim Öffnen und vor jedem Speichern (wie MainframeMate). */
    public EditorSessions(Executor background, VersionHistory history) {
        this.background = Objects.requireNonNull(background, "background");
        this.history = Objects.requireNonNull(history, "history");
    }

    /** Öffnet ein Dokument und lädt es im Hintergrund; ist es schon offen, kommt das offene zurück. */
    public EditorSession open(DocumentStore store, String location, String name) {
        Optional<EditorSession> existing = find(store, location);
        if (existing.isPresent()) {
            return existing.get();
        }
        EditorSession session = new EditorSession(store, location, name, background, history);
        sessions.add(session);
        openedObservers.forEach(observer -> observer.accept(session));
        session.load();
        return session;
    }

    public Optional<EditorSession> find(DocumentStore store, String location) {
        return sessions.stream().filter(s -> s.store() == store && s.location().equals(location)).findFirst();
    }

    public List<EditorSession> all() {
        return List.copyOf(sessions);
    }

    /** Nimmt das Dokument aus der Liste; ungespeicherte Änderungen verwirft es – Rückfragen stellt die Oberfläche. */
    public void close(EditorSession session) {
        if (sessions.remove(session)) {
            closedObservers.forEach(observer -> observer.accept(session));
        }
    }

    public void onOpened(Consumer<EditorSession> observer) {
        openedObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void onClosed(Consumer<EditorSession> observer) {
        closedObservers.add(Objects.requireNonNull(observer, "observer"));
    }

    /**
     * Bittet die Oberfläche, ein offenes Dokument nach vorn zu holen (etwa nach einem Doppelklick auf ein Aufrufziel im
     * Diagramm); die Bereiche, die das Dokument zeigen, wählen es aus.
     */
    public void reveal(EditorSession session) {
        if (session != null && sessions.contains(session)) {
            revealObservers.forEach(observer -> observer.accept(session));
        }
    }

    public void onReveal(Consumer<EditorSession> observer) {
        revealObservers.add(Objects.requireNonNull(observer, "observer"));
    }
}
