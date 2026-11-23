package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Eine FTP-Sitzung im Command Deck: in der Mitte die gewählte Unteransicht (Datasets oder JES-Jobs, umgeschaltet oben
 * rechts in der Titelleiste), unten die Werkzeugleiste mit dem Stand links und den Aktionen der Unteransicht rechts,
 * dahinter Trennen und Schließen der Sitzung. Beide Unteransichten bleiben je Sitzung bestehen; der Wechsel lädt
 * nichts neu, nur das erste Zeigen der Jobliste fragt sie ab (lesend).
 */
final class FtpSessionView extends JPanel {

    static final String DATASETS = "Datasets";
    static final String JOBS = "JES-Jobs";

    private final FtpSession session;
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final CardLayout actionCards = new CardLayout();
    private final JPanel actions = new JPanel(actionCards);
    /** Der letzte Stand dieser Sitzung; die Statuszeile der App zeigt ihn, solange die Sitzung gewählt ist. */
    private StatusNote status = StatusNote.plain(" ");
    /** Wohin der Stand geht (die Statuszeile des FTP-Bereichs); bekommt ihn nur, solange die Sitzung gewählt ist. */
    private Consumer<StatusNote> statusSink = note -> { };
    private final FtpDatasetsView datasets;
    private final FtpJobsView jobs;
    private final ComposerButton reconnect;
    private final ComposerButton disconnect;
    private final ComposerButton close;
    private String view = DATASETS;
    private boolean started;

    FtpSessionView(FtpSession session, Consumer<FtpSession> onClose, Runnable showJobs,
                   com.aresstack.spaceharvester.deck.editor.EditorSessions editors) {
        super(new BorderLayout());
        this.session = session;
        setBackground(Comic.SURFACE);
        datasets = new FtpDatasetsView(this, showJobs, editors);
        jobs = new FtpJobsView(this);
        center.setOpaque(false);
        center.add(datasets, DATASETS);
        center.add(jobs, JOBS);
        actions.setOpaque(false);
        actions.add(datasets.actions(), DATASETS);
        actions.add(jobs.actions(), JOBS);

        reconnect = Comic.button("Erneut verbinden", "Die Verbindung zu diesem Server noch einmal aufbauen");
        reconnect.addActionListener(e -> later(session.connect(), home -> { }, error -> { }));
        disconnect = Comic.button("Trennen", "Beide Verbindungen dieser Sitzung trennen (Datasets und Jobs)");
        disconnect.addActionListener(e -> session.disconnect());
        close = Comic.button("Schließen", "Getrennte Sitzung aus der Liste nehmen");
        close.addActionListener(e -> {
            if (datasets.closeDocuments()) {
                onClose.accept(session);
            }
        });
        JPanel east = new JPanel(new BorderLayout(12, 0));
        east.setOpaque(false);
        east.add(actions, BorderLayout.CENTER);
        east.add(Comic.row(reconnect, disconnect, close), BorderLayout.EAST);
        // Die Aktionszeile enthält nur Knöpfe, eine Zeile wie die Aktionszeilen im Space Harvester; der Stand steht in
        // der Statuszeile der App.
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        foot.add(east, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);

        session.observe(changed -> SwingUtilities.invokeLater(this::stateChanged));
        stateChanged();
    }

    FtpSession session() {
        return session;
    }

    FtpDatasetsView datasets() {
        return datasets;
    }

    FtpJobsView jobs() {
        return jobs;
    }

    String view() {
        return view;
    }

    /** Zeigt eine Unteransicht; die Jobliste wird beim ersten Zeigen abgefragt. */
    void show(String next) {
        view = JOBS.equals(next) ? JOBS : DATASETS;
        centerCards.show(center, view);
        actionCards.show(actions, view);
        if (JOBS.equals(view) && session.state() == ConnectionState.CONNECTED) {
            jobs.startOnce();
        }
    }

    private void stateChanged() {
        ConnectionState state = session.state();
        reconnect.setVisible(state == ConnectionState.FAILED);
        disconnect.setEnabled(!state.ended());
        close.setEnabled(state.ended() || state == ConnectionState.FAILED);
        boolean connected = state == ConnectionState.CONNECTED;
        datasets.setConnected(connected);
        jobs.setConnected(connected);
        if (connected && !started) {
            started = true;
            datasets.start();
            if (JOBS.equals(view)) {
                jobs.startOnce();
            }
        }
        switch (state) {
            case CONNECTING -> status("Verbinde mit " + session.profile().label() + " …", false);
            case CONNECTED -> status(session.label() + " · verbunden als " + session.user()
                    + (session.mvs() ? " · z/OS" : ""), false);
            case FAILED -> status("Verbindung fehlgeschlagen – " + session.failure().orElse("ohne Grund"), true);
            case DISCONNECTED -> status("Getrennt", false);
        }
    }

    /** Der Stand für die Statuszeile; rot nur für Fehler. */
    void status(String text, boolean error) {
        status = new StatusNote(text, error ? StatusNote.Tone.ERROR : StatusNote.Tone.PLAIN, null, null);
        statusSink.accept(status);
    }

    /** Schließt die Statuszeile an (gewählt) oder ab ({@code null}); angeschlossen zeigt sie sofort den letzten Stand. */
    void statusTo(Consumer<StatusNote> sink) {
        statusSink = sink == null ? note -> { } : sink;
        statusSink.accept(status);
    }

    String statusText() {
        return status.text();
    }

    boolean statusError() {
        return status.tone() == StatusNote.Tone.ERROR;
    }

    /** Bringt das Ergebnis eines Auftrags auf den EDT. */
    static <T> void later(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        future.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                onFailure.accept(error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error);
            } else {
                onSuccess.accept(result);
            }
        }));
    }

    /** Ein Auftrag mit Fehlermeldung im Stand. */
    <T> void run(CompletableFuture<T> future, String failure, Consumer<T> onSuccess) {
        later(future, onSuccess, error -> status(failure + " – " + FtpWorkspace.describe(error), true));
    }

    static JComponent plain(JComponent component) {
        component.setOpaque(false);
        return component;
    }
}
