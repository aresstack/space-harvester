package com.aresstack.spaceharvester.deck.terminal.openterm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.AidKey;
import com.aresstack.spaceharvester.deck.terminal.FunctionKeyLegend;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalKeyboard;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** PF- und Sondertasten erreichen den Host; jedes neue Bild wird gemeldet, ohne abzufragen. */
class FunctionKeysTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void keysReachTheHostAndNewScreensAreAnnounced() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "MENUE", false),
                new FakeTn3270Host.Line(3, 2, "", true),
                new FakeTn3270Host.Line(23, 1, "F3=Ende  F7=Back  F8=Vor", false)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable));
            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()));
            TerminalKeyboard keyboard = session.view(TerminalKeyboard.class).orElseThrow();
            TerminalView view = session.view(TerminalView.class).orElseThrow();
            AtomicInteger screens = new AtomicInteger();
            view.addScreenListener(screens::incrementAndGet);

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (session.state() != SessionState.CONNECTED && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(keyboard.awaitUnlocked(java.time.Duration.ofSeconds(10))).isTrue();
            // Die Tastatur wird schon beim Lesen des Kopfs frei, gemeldet wird das Bild an seinem Ende.
            waitFor(() -> screens.get() > 0, until);
            assertThat(screens.get()).as("das erste Bild wurde gemeldet").isPositive();
            assertThat(FunctionKeyLegend.ofScreen(view.text()))
                    .containsEntry(3, "Ende").containsEntry(7, "Back").containsEntry(8, "Vor");

            int before = screens.get();
            keyboard.press(AidKey.PF3);
            waitFor(() -> host.aids().size() >= 1, until);
            assertThat(keyboard.awaitUnlocked(java.time.Duration.ofSeconds(10))).isTrue();
            keyboard.press(AidKey.PA1);
            waitFor(() -> host.aids().size() >= 2, until);
            assertThat(keyboard.awaitUnlocked(java.time.Duration.ofSeconds(10))).isTrue();
            keyboard.press(AidKey.pf(24));
            waitFor(() -> host.aids().size() >= 3, until);
            assertThat(host.aids()).containsExactly(0xF3, 0x6C, 0x4C);
            waitFor(() -> screens.get() > before, until);
            assertThat(screens.get()).as("die Antworten des Hosts wurden gemeldet").isGreaterThan(before);
            session.disconnect();
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long until) throws InterruptedException {
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.sleep(20);
        }
    }
}
