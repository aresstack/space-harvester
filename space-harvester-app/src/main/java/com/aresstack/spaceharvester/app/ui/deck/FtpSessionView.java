package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
    private final com.aresstack.spaceharvester.app.ui.IconButton reconnect;
    private final JPanel footTools;
    private String view = DATASETS;
    private boolean started;

    FtpSessionView(FtpSession session, Runnable showJobs,
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

        reconnect = new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.SYNC,
                "Erneut verbinden – die Verbindung zu diesem Server noch einmal aufbauen");
        reconnect.addActionListener(e -> later(session.connect(), home -> { }, error -> { }));
        // Die Aktionszeile enthält nur Knöpfe und beginnt links; Trennen und Schließen macht das ✕ am Reiter in einem
        // Schritt, der Stand steht in der Statuszeile der App (Angelo, 23.11.2026).
        footTools = DeckBars.tools(actions, reconnect);
        add(center, BorderLayout.CENTER);

        session.observe(changed -> SwingUtilities.invokeLater(this::stateChanged));
        stateChanged();
    }

    /** Die Aktionen dieser Sitzung für die Fußleiste des Command Deck neben der Lupe. */
    JComponent footTools() {
        return footTools;
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
        if (JOBS.equals(next)) datasets.revokeDiagnosis();
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
