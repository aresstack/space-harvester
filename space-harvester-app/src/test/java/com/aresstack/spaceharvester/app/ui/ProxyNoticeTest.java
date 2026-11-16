package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.source.network.ProxyRoutes.PacState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Der Hinweis in der Statuszeile: erscheint, sobald das Erneuern des Proxy-Skripts scheitert, nennt seit wann, trägt
 * den Grund als Tooltip und verschwindet, sobald das Erneuern wieder gelingt. Er hängt allein an den Meldungen der
 * Route, kein Abfragen.
 */
class ProxyNoticeTest {

    @Test
    void appearsWhileTheScriptIsStaleAndDisappearsWhenRefreshed() throws Exception {
        AtomicReference<PacState> state = new AtomicReference<>(PacState.NONE);
        List<Runnable> listeners = new ArrayList<>();
        ProxyNotice.Source source = new ProxyNotice.Source() {
            @Override
            public PacState pacState() {
                return state.get();
            }

            @Override
            public AutoCloseable onPacChange(Runnable listener) {
                listeners.add(listener);
                return () -> listeners.remove(listener);
            }
        };
        ProxyNotice[] notice = new ProxyNotice[1];
        SwingUtilities.invokeAndWait(() -> {
            notice[0] = new ProxyNotice(() -> source);
            new StatusLine(new JProgressBar(), notice[0], new JLabel("Seite 3"), new JLabel(" "), new JLabel(" "),
                    new JLabel("© 2026 AresStack"));
        });
        assertThat(notice[0].isVisible()).as("ohne veraltetes Skript unsichtbar").isFalse();
        assertThat(listeners).as("hört auf die Route").hasSize(1);

        Instant since = Instant.parse("2026-11-16T16:03:00Z");
        String reason = "Proxy-Skript http://wpad.example/wpad.dat geladen 16.11. 09:00, gültig bis 16.11. 21:00; "
                + "nicht erneuert seit 16.11. 17:03 (3 Versuche): Connect timed out";
        state.set(new PacState(true, since, reason));
        listeners.get(0).run();
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(notice[0].isVisible()).isTrue();
        assertThat(notice[0].text()).isEqualTo(ProxyNotice.PREFIX + Texts.time(since) + ProxyNotice.SUFFIX);
        assertThat(notice[0].label().getToolTipText()).isEqualTo(reason);

        state.set(new PacState(false, null, "Proxy-Skript http://wpad.example/wpad.dat geladen 16.11. 17:10"));
        listeners.get(0).run();
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(notice[0].isVisible()).isFalse();
    }
}
