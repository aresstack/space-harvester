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
    private final AtomicReference<OpenTermMask> mask = new AtomicReference<>();
    /** Brücke zum Proxy-Tunnel oder {@code null} (direkt). */
    private final java.io.Closeable relay;
    /** Warum der Tunnel scheiterte; ersetzt dann das allgemeine „Verbindungsfehler“. */
    private final AtomicReference<String> problem;

    OpenTermConnection(Terminal terminal, TerminalProfile profile, Listener listener) {
        this(terminal, profile, listener, null, new AtomicReference<>());
    }

    OpenTermConnection(Terminal terminal, TerminalProfile profile, Listener listener, java.io.Closeable relay,
                       AtomicReference<String> problem) {
        this.terminal = terminal;
        this.profile = profile;
        this.listener = listener;
        this.relay = relay;
        this.problem = problem;
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
        try {
            terminal.connect();
        } catch (IOException | RuntimeException e) {
            closeRelay();
            String reason = problem.get();
            if (reason != null) {
                throw new IOException(reason, e);
            }
            throw e;
        }
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
            closeRelay();
        }
    }

    private void closeRelay() {
        if (relay != null) {
            try {
                relay.close();
            } catch (IOException ignored) {
                // schon zu
            }
        }
    }

    /** Der Grund aus dem Tunnel, sonst {@code fallback}. */
    private String reason(String fallback) {
        String reason = problem.get();
        return reason == null ? fallback : reason;
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
        // Der Maskenassistent (#1151) bekommt nur die Maske, nie die Anzeige mit ihren Host-Tasten.
        if (type == com.aresstack.spaceharvester.deck.terminal.TerminalMask.class) {
            return Optional.of(type.cast(mask()));
        }
        if (type.isAssignableFrom(OpenTermScreen.class)) {
            return Optional.of(type.cast(screen()));
        }
        return Optional.empty();
    }

    /** Die Maske für den KI-Maskenassistenten; ohne Host-Tasten. */
    OpenTermMask mask() {
        OpenTermMask current = mask.get();
        if (current == null) {
            mask.compareAndSet(null, new OpenTermMask(terminal, () -> {
                OpenTermScreen view = screen.get();
                if (view != null) {
                    view.refresh();
                }
            }));
            current = mask.get();
        }
        return current;
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
                listener.closed(reason(null));
            }
            case com.ascert.open.term.core.TnAction.CONNECTION_ERROR -> {
                close();
                listener.closed(reason("Verbindungsfehler"));
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
