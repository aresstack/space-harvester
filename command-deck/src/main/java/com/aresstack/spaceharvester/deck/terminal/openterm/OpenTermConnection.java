package com.aresstack.spaceharvester.deck.terminal.openterm;

import com.aresstack.spaceharvester.deck.terminal.TerminalConnection;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.ascert.open.term.core.Terminal;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * Eine OpenTerm-Verbindung. Ihre Darstellung ({@link OpenTermScreen}) ist zugleich der Empfänger der Rückmeldungen
 * des Terminals; Zustandswechsel kommen von dort, nie durch Abfragen.
 */
final class OpenTermConnection implements TerminalConnection {

    private final Terminal terminal;
    private final TerminalProfile profile;
    private final Listener listener;
    private final AtomicReference<OpenTermScreen> screen = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    OpenTermConnection(Terminal terminal, TerminalProfile profile, Listener listener) {
        this.terminal = terminal;
        this.profile = profile;
        this.listener = listener;
    }

    /**
     * Baut die Verbindung auf. Wurde vorher oder währenddessen getrennt, bleibt keine Verbindung zum Host stehen:
     * vor dem Aufbau wird abgebrochen, danach sofort wieder getrennt.
     */
    @Override
    public void connect() throws IOException {
        ensureScreen();
        if (closed.get()) {
            return;
        }
        terminal.connect();
        if (closed.get()) {
            quietlyDisconnect();
            return;
        }
        listener.connected();
    }

    @Override
    public void disconnect() {
        close();
        quietlyDisconnect();
    }

    /** Markiert die Verbindung als zu und gibt die Codepage genau einmal frei. */
    private void close() {
        if (closed.compareAndSet(false, true)) {
            CodePages.release();
        }
    }

    private void quietlyDisconnect() {
        try {
            terminal.disconnect();
        } catch (RuntimeException ignored) {
            // Schon zu oder nie verbunden.
        }
    }

    @Override
    public <T> Optional<T> view(Class<T> type) {
        // Die Anzeige ist eine Swing-Komponente und zugleich die TerminalView.
        if (type.isAssignableFrom(OpenTermScreen.class)) {
            return Optional.of(type.cast(screen()));
        }
        return Optional.empty();
    }

    /** Die Darstellung; einmal angelegt, auf dem EDT. */
    OpenTermScreen screen() {
        OpenTermScreen current = screen.get();
        if (current != null) {
            return current;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            ensureScreen();
            return screen.get();
        }
        OpenTermScreen created = new OpenTermScreen(terminal, profile.model(), this::statusChanged);
        return screen.compareAndSet(null, created) ? created : screen.get();
    }

    /** Vor dem Verbinden muss die Darstellung stehen: Sie nimmt die Rückmeldungen des Terminals entgegen. */
    private void ensureScreen() {
        if (screen.get() != null) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(this::screen);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen beim Anlegen der Terminalanzeige", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Terminalanzeige ließ sich nicht anlegen", e.getCause());
        }
    }

    /** Rückmeldung des Terminals (OpenTerm {@code TnAction.status}). */
    private void statusChanged(int status) {
        switch (status) {
            case com.ascert.open.term.core.TnAction.READY -> listener.connected();
            case com.ascert.open.term.core.TnAction.DISCONNECTED_BY_REMOTE_HOST -> {
                close();
                listener.closed(null);
            }
            case com.ascert.open.term.core.TnAction.CONNECTION_ERROR -> {
                close();
                listener.closed("Verbindungsfehler");
            }
            default -> {
                // CONNECTING, X_WAIT, DISCONNECTED: nichts zu melden; DISCONNECTED folgt dem eigenen Trennen.
            }
        }
    }

    Terminal terminal() {
        return terminal;
    }
}
