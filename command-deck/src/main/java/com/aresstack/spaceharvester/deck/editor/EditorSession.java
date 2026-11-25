package com.aresstack.spaceharvester.deck.editor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Ein im gemeinsamen Editor geöffnetes Dokument: laden, bearbeiten, speichern mit Konfliktprüfung, neu laden. Jede
 * Änderung des Zustands geht an die Beobachter (kein Abfragen), auf dem Thread, der sie ausgelöst hat. Gespeichert
 * wird nur auf ausdrücklichen Wunsch.
 */
public final class EditorSession {

    /** Zustand des Dokuments. */
    public enum State {
        LOADING("Wird geladen"),
        READY("Bereit"),
        SAVING("Wird gespeichert"),
        /** Die Datei wurde seit dem Laden geändert; Speichern braucht eine Entscheidung. */
        CONFLICT("Geändert von außen"),
        FAILED("Fehler");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final DocumentStore store;
    private final Executor background;
    private final VersionHistory history;
    private String location;
    private String name;
    private final List<Consumer<EditorSession>> observers = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private EditorDocument document;
    private String text;
    private State state = State.LOADING;
    private String problem;
    private String note;
    private long revision;

    EditorSession(DocumentStore store, String location, String name, Executor background, VersionHistory history) {
        this.store = Objects.requireNonNull(store, "store");
        this.location = Objects.requireNonNull(location, "location");
        this.name = Objects.requireNonNull(name, "name");
        this.background = Objects.requireNonNull(background, "background");
        this.history = Objects.requireNonNull(history, "history");
    }

    /** Die Adresse; ändert sich nur durch {@link #saveAs}. */
    public String location() {
        return read(() -> location);
    }

    public String name() {
        return read(() -> name);
    }

    /** {@code false} für reine Ansichten der Ablage. */
    public boolean writable() {
        return read(() -> store.writable() && (document == null || document.writeRestriction().isEmpty()));
    }

    /** Die lokal aufbewahrten Versionen dieses Dokuments, neueste zuerst (wie der Verlauf von MainframeMate). */
    public java.util.List<VersionHistory.Version> versions() {
        return history.versions(store.id(), location());
    }

    /** Text einer Version aus {@link #versions()}. */
    public Optional<String> version(String id) {
        return history.content(store.id(), location(), id);
    }

    public DocumentStore store() {
        return store;
    }

    public State state() {
        return read(() -> state);
    }

    /** Der zuletzt geladene oder gespeicherte Stand. */
    public Optional<EditorDocument> document() {
        return read(() -> Optional.ofNullable(document));
    }

    /** Der Text mit allen ungespeicherten Änderungen. */
    public Optional<String> text() {
        return read(() -> Optional.ofNullable(text));
    }

    /**
     * Zählt jedes abgeschlossene Laden (auch Neu laden). Die Oberfläche erkennt daran neuen Inhalt, selbst wenn sie
     * den Zwischenzustand „Wird geladen“ nie zu sehen bekam.
     */
    public long revision() {
        return read(() -> revision);
    }

    /** Ob es ungespeicherte Änderungen gibt. */
    public boolean dirty() {
        return read(() -> document != null && !document.text().equals(text));
    }

    /** Grund eines Fehlers oder Konflikts. */
    public Optional<String> problem() {
        return read(() -> Optional.ofNullable(problem));
    }

    /** Ergebnis der letzten Aktion, etwa „Gespeichert“. */
    public Optional<String> note() {
        return read(() -> Optional.ofNullable(note));
    }

    public void observe(Consumer<EditorSession> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    public void unobserve(Consumer<EditorSession> observer) {
        observers.remove(observer);
    }

    /** Übernimmt den Text aus dem Editor; meldet nur, wenn sich „ungespeichert“ dadurch ändert. */
    public void edit(String edited) {
        boolean before;
        boolean after;
        lock.lock();
        try {
            before = document != null && !document.text().equals(text);
            text = Objects.requireNonNull(edited, "edited");
            after = document != null && !document.text().equals(text);
            note = null;
        } finally {
            lock.unlock();
        }
        if (before != after) {
            changed();
        }
    }

    /** Lädt das Dokument (neu); ungespeicherte Änderungen gehen dabei verloren – Rückfragen stellt die Oberfläche. */
    public void load() {
        set(State.LOADING, null, null);
        background.execute(() -> {
            try {
                EditorDocument loaded = store.load(location());
                if (loaded.writeRestriction().isEmpty()) remember(loaded.text(), "geöffnet");
                lock.lock();
                try {
                    document = loaded;
                    text = loaded.text();
                    revision++;
                    state = State.READY;
                    problem = null;
                    note = null;
                } finally {
                    lock.unlock();
                }
                changed();
            } catch (DocumentException e) {
                set(State.FAILED, e.getMessage(), null);
            } catch (RuntimeException e) {
                set(State.FAILED, "Laden gescheitert (" + e.getClass().getSimpleName() + ")", null);
            }
        });
    }

    /** Speichert; hat jemand die Datei inzwischen geändert, wird nicht gespeichert, sondern {@link State#CONFLICT}. */
    public void save() {
        save(false);
    }

    /** Speichert trotz Konflikt (nach Rückfrage in der Oberfläche). */
    public void overwrite() {
        save(true);
    }

    private void save(boolean force) {
        EditorDocument base;
        String edited;
        lock.lock();
        try {
            if (document == null || state == State.SAVING || state == State.LOADING || !store.writable() || !document.writeRestriction().isEmpty()) {
                return;
            }
            base = document;
            edited = text;
            state = State.SAVING;
            note = null;
        } finally {
            lock.unlock();
        }
        changed();
        background.execute(() -> {
            try {
                remember(edited, "vor Speichern");
                SaveResult result = store.save(base, edited, force);
                if (result instanceof SaveResult.Saved saved) {
                    lock.lock();
                    try {
                        document = saved.document();
                        state = State.READY;
                        problem = null;
                        note = "Gespeichert";
                    } finally {
                        lock.unlock();
                    }
                    changed();
                } else if (result instanceof SaveResult.Conflict conflict) {
                    set(State.CONFLICT, conflict.reason(), null);
                }
            } catch (DocumentException e) {
                // Der Text bleibt im Editor; man kann es erneut versuchen.
                set(State.READY, null, "Nicht gespeichert: " + e.getMessage());
            } catch (RuntimeException e) {
                set(State.READY, null, "Nicht gespeichert (" + e.getClass().getSimpleName() + ")");
            }
        });
    }

    /**
     * Speichert den Text unter {@code target} (Ablage muss {@link DocumentStore#canSaveAs()} können); danach zeigt
     * die Sitzung auf die neue Adresse, wie der Reiter in MainframeMate.
     */
    public void saveAs(String target) {
        Objects.requireNonNull(target, "target");
        EditorDocument base;
        String edited;
        lock.lock();
        try {
            if (document == null || state == State.SAVING || state == State.LOADING || !store.canSaveAs()) {
                return;
            }
            base = document;
            edited = text;
            state = State.SAVING;
            note = null;
        } finally {
            lock.unlock();
        }
        changed();
        background.execute(() -> {
            try {
                EditorDocument saved = store.saveAs(base, edited, target);
                lock.lock();
                try {
                    document = saved;
                    location = saved.location();
                    name = saved.name();
                    state = State.READY;
                    problem = null;
                    note = "Gespeichert unter " + saved.name();
                } finally {
                    lock.unlock();
                }
                remember(edited, "gespeichert unter");
                changed();
            } catch (DocumentException e) {
                set(State.READY, null, "Nicht gespeichert: " + e.getMessage());
            } catch (RuntimeException e) {
                set(State.READY, null, "Nicht gespeichert (" + e.getClass().getSimpleName() + ")");
            }
        });
    }

    private void remember(String content, String label) {
        try {
            history.record(store.id(), location(), content, label);
        } catch (RuntimeException e) {
            // Der Verlauf ist eine Hilfe; Laden und Speichern scheitern nie an ihm.
        }
    }

    private void set(State next, String reason, String message) {
        lock.lock();
        try {
            state = next;
            problem = reason;
            note = message;
        } finally {
            lock.unlock();
        }
        changed();
    }

    private void changed() {
        observers.forEach(observer -> observer.accept(this));
    }

    private <T> T read(java.util.function.Supplier<T> reader) {
        lock.lock();
        try {
            return reader.get();
        } finally {
            lock.unlock();
        }
    }
}
