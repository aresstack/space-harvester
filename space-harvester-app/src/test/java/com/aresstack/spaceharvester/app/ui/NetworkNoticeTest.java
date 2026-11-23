package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.network.NetworkState;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Der Hinweis „Keine Netzverbindung“ in der Statuszeile (Angelo 22.11.2026): erscheint, sobald der Netzzustand offline
 * meldet, nennt Grund und nächste Prüfung als Tooltip und verschwindet von selbst, sobald das Netz zurück ist. Er hängt
 * allein an den Meldungen des Netzzustands, kein Abfragen; Fristen laufen hier nie ab.
 */
class NetworkNoticeTest {

    private static final Instant T0 = Instant.parse("2026-11-22T21:00:00Z");

    @Test
    void appearsWhileOfflineAndDisappearsWhenTheNetworkIsBack() throws Exception {
        NetworkState network = new NetworkState(Clock.fixed(T0, ZoneOffset.UTC), (delay, task) -> { });
        try {
            NetworkNotice[] notice = new NetworkNotice[1];
            SwingUtilities.invokeAndWait(() -> {
                notice[0] = new NetworkNotice(() -> network);
                new StatusLine(new JProgressBar(), notice[0], new JLabel("Seite 3"), new JLabel(" "),
                        new JLabel(" "), new JLabel("© 2026 AresStack"));
            });
            assertThat(notice[0].isVisible()).as("online unsichtbar").isFalse();

            network.failed(new IOException("Abruf von wiki.example scheiterte",
                    new ConnectException("Connection refused")));
            SwingUtilities.invokeAndWait(() -> { });

            assertThat(notice[0].isVisible()).isTrue();
            assertThat(notice[0].text()).isEqualTo("Keine Netzverbindung – bitte Internetverbindung prüfen");
            assertThat(notice[0].label().getToolTipText()).isEqualTo("Seit " + Texts.time(T0)
                    + " keine Netzverbindung (ConnectException). Aufträge über das Netz warten und machen von selbst "
                    + "weiter, sobald sie zurück ist; nächste Prüfung um " + Texts.time(T0.plusSeconds(5)) + ".");

            network.succeeded();
            SwingUtilities.invokeAndWait(() -> { });

            assertThat(notice[0].isVisible()).isFalse();
        } finally {
            network.close();
        }
    }

    @Test
    void afterARestartItListensToTheNewNetworkState() throws Exception {
        NetworkState first = new NetworkState(Clock.fixed(T0, ZoneOffset.UTC), (delay, task) -> { });
        NetworkState second = new NetworkState(Clock.fixed(T0, ZoneOffset.UTC), (delay, task) -> { });
        AtomicReference<NetworkState> current = new AtomicReference<>(first);
        try {
            NetworkNotice[] notice = new NetworkNotice[1];
            SwingUtilities.invokeAndWait(() -> notice[0] = new NetworkNotice(current::get));

            current.set(second);
            SwingUtilities.invokeAndWait(notice[0]::refresh);
            first.failed(new ConnectException("Connection refused"));
            SwingUtilities.invokeAndWait(() -> { });
            assertThat(notice[0].isVisible()).as("die alte Laufzeit meldet nichts mehr").isFalse();

            second.failed(new ConnectException("Connection refused"));
            SwingUtilities.invokeAndWait(() -> { });
            assertThat(notice[0].isVisible()).isTrue();

            current.set(null);
            SwingUtilities.invokeAndWait(notice[0]::refresh);
            assertThat(notice[0].isVisible()).as("ohne Laufzeit nichts").isFalse();
        } finally {
            first.close();
            second.close();
        }
    }
}
