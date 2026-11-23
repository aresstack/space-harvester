package com.aresstack.spaceharvester.deck.terminal.openterm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.AidKey;
import com.aresstack.spaceharvester.deck.terminal.LoginScript;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalKeyboard;
import com.aresstack.spaceharvester.deck.terminal.TerminalMacro;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import org.junit.jupiter.api.Test;

/**
 * Makro-Aufzeichnung an der echten OpenTerm-Anzeige: sichtbare Eingaben und F-Tasten werden gemeldet, Eingaben in ein
 * verdecktes Feld (Passwort) nie; ein Lesezeichen-Makro wird nach dem Verbinden abgespielt.
 */
class MacroRecordingTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void visibleInputAndKeysAreRecordedHiddenFieldsNever() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "ANMELDUNG", false),
                FakeTn3270Host.Line.input(3, 2, 8, false, false),
                FakeTn3270Host.Line.input(4, 2, 8, false, true)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable));
            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()));
            TerminalKeyboard keyboard = session.view(TerminalKeyboard.class).orElseThrow();
            TerminalView view = session.view(TerminalView.class).orElseThrow();
            JComponent screen = session.view(JComponent.class).orElseThrow();
            TerminalMacro macro = new TerminalMacro();
            view.addInputListener(new TerminalView.InputListener() {
                @Override
                public void typed(String text) {
                    macro.typed(text);
                }

                @Override
                public void aid(AidKey key) {
                    macro.aid(key);
                }
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (session.state() != SessionState.CONNECTED && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(keyboard.awaitUnlocked(Duration.ofSeconds(10))).isTrue();

            assertThat(keyboard.firstInputField()).isTrue();
            type(screen, 'a');
            assertThat(keyboard.nextInputField()).isTrue();
            type(screen, 'g'); // verdecktes Feld: nie aufzeichnen
            key(screen, KeyEvent.VK_F3);

            assertThat(macro.steps()).containsExactly(TerminalMacro.Step.text("a"),
                    TerminalMacro.Step.aid(AidKey.PF3));
            session.disconnect();
        }
    }

    @Test
    void aBookmarkMacroIsPlayedAfterConnecting() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "MENUE", false),
                FakeTn3270Host.Line.input(3, 2, 8, false, false)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable), new LoginScript(Duration.ZERO));
            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()), null,
                    List.of(TerminalMacro.Step.aid(AidKey.PF8), TerminalMacro.Step.aid(AidKey.PF3)));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!session.note().map(note -> note.startsWith("Makro ")).orElse(false)
                    && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertThat(session.note()).contains("Makro abgespielt (2 Schritte)");
            assertThat(host.aids()).containsExactly(0xF8, 0xF3);
            session.disconnect();
        }
    }

    @Test
    void pulldownLinesAreRecognisedLikeMainframeMate() {
        assertThat(OpenTermScreen.looksLikePulldownLine("   | 3  Save        |   ")).isTrue();
        assertThat(OpenTermScreen.looksLikePulldownLine("   │ 10 Lang       │   ")).isTrue();
        assertThat(OpenTermScreen.looksLikePulldownLine("   | Save          |   ")).isFalse();
        assertThat(OpenTermScreen.looksLikePulldownLine("   3  Save            ")).isFalse();
        assertThat(OpenTermScreen.looksLikePulldownLine("")).isFalse();
    }

    /** Ohne Bildschirm gibt es keinen Fokus, der Tasten verteilt: die Empfänger direkt rufen, wie AWT es täte. */
    private static void type(JComponent screen, char c) {
        long now = System.currentTimeMillis();
        KeyEvent pressed = new KeyEvent(screen, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.getExtendedKeyCodeForChar(c), c);
        KeyEvent typed = new KeyEvent(screen, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c);
        for (java.awt.event.KeyListener listener : screen.getKeyListeners()) {
            listener.keyPressed(pressed);
        }
        for (java.awt.event.KeyListener listener : screen.getKeyListeners()) {
            listener.keyTyped(typed);
        }
    }

    private static void key(JComponent screen, int code) {
        KeyEvent pressed = new KeyEvent(screen, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code,
                KeyEvent.CHAR_UNDEFINED);
        for (java.awt.event.KeyListener listener : screen.getKeyListeners()) {
            listener.keyPressed(pressed);
        }
    }
}
