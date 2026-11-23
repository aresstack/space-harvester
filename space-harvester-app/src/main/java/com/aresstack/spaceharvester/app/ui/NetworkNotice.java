package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.network.NetworkState;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Der Hinweis in der Statuszeile des Hauptfensters, solange keine Netzverbindung besteht (Angelo 22.11.2026): „Keine
 * Netzverbindung – bitte Internetverbindung prüfen“, rot. Der Tooltip nennt seit wann, den Grund und die nächste
 * Prüfung; Aufträge über das Netz warten so lange und machen von selbst weiter. Gespeist allein aus dem
 * {@link NetworkState} des Core über seinen Beobachter, kein Abfragen (ADR 0027); verschwindet von selbst, sobald das
 * Netz zurück ist. Nur auf dem EDT.
 */
final class NetworkNotice extends JPanel {

    static final String TEXT = "Keine Netzverbindung – bitte Internetverbindung prüfen";

    private final Supplier<NetworkState> source;
    private final JLabel label = new JLabel(TEXT);
    private NetworkState watched;
    private Subscription subscription = Subscription.NONE;

    NetworkNotice(AppContext context) {
        this(() -> {
            AppRuntime runtime = context.runtime();
            return runtime == null ? null : runtime.workspace().network();
        });
        context.onChange(ignored -> refresh());
    }

    /** @param source der Netzzustand der aktuellen Laufzeit, {@code null} ohne Laufzeit */
    NetworkNotice(Supplier<NetworkState> source) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.source = Objects.requireNonNull(source, "source");
        setOpaque(false);
        setVisible(false);
        label.setFont(Comic.semiBold(13f));
        label.setForeground(Comic.RED);
        add(label);
        refresh();
    }

    /** Zeigt den jetzigen Zustand; online ist der Hinweis unsichtbar. */
    void refresh() {
        NetworkState current = watch();
        NetworkState.Status status = current == null ? NetworkState.Status.ONLINE : current.status();
        if (status.online()) {
            if (isVisible()) {
                setVisible(false);
                revalidateParent();
            }
            return;
        }
        label.setToolTipText(tip(status));
        if (!isVisible()) {
            setVisible(true);
            revalidateParent();
        }
    }

    /** „Seit 23:41 keine Netzverbindung (ConnectException). Aufträge über das Netz warten …; nächste Prüfung um ….“ */
    static String tip(NetworkState.Status status) {
        return "Seit " + (status.since() == null ? "kurzem" : Texts.time(status.since())) + " keine Netzverbindung"
                + (status.reason() == null ? "" : " (" + status.reason() + ")")
                + ". Aufträge über das Netz warten und machen von selbst weiter, sobald sie zurück ist"
                + (status.nextCheck() == null ? "." : "; nächste Prüfung um " + Texts.time(status.nextCheck()) + ".");
    }

    String text() {
        return label.getText();
    }

    JLabel label() {
        return label;
    }

    /** Hört auf den Netzzustand der aktuellen Laufzeit; nach einem Neustart auf den der neuen. */
    private NetworkState watch() {
        NetworkState current = source.get();
        if (current != watched) {
            subscription.close();
            subscription = Subscription.NONE;
            watched = current;
            if (current != null) {
                subscription = current.subscribe(status -> SwingUtilities.invokeLater(this::refresh));
            }
        }
        return current;
    }

    private void revalidateParent() {
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }
}
