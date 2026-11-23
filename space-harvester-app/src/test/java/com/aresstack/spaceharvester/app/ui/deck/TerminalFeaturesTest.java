package com.aresstack.spaceharvester.app.ui.deck;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.terminal.AidKey;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalMacro;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.openterm.FakeTn3270Host;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Was das Terminal aus MainframeMate übernimmt: Scrollrad und Seitentasten lösen F-Tasten aus, der Eingabe-Verlauf
 * zeichnet auf, eine Sitzung lässt sich als Lesezeichen merken und mit „Verbinden“ neu öffnen.
 */
class TerminalFeaturesTest {

    private CommandDeck deck;
    private String bookmarkName;

    @AfterEach
    void shutdown() {
        if (bookmarkName != null) {
            new TerminalBookmarks(AppState.shared()).remove(bookmarkName);
        }
        if (deck != null) {
            deck.shutdown();
        }
    }

    @Test
    void mouseKeysHistoryBookmarkAndReconnect() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "LISTE SEITE 1", false),
                new FakeTn3270Host.Line(4, 2, "", true),
                new FakeTn3270Host.Line(23, 1, "F3=Ende  F7=Zurück  F8=Vor", false)), "Cp273")) {
            SwingUtilities.invokeAndWait(() -> {
                deck = new CommandDeck();
                ConnectForm form = deck.terminal().form();
                form.host.setText("127.0.0.1");
                form.port.setText(String.valueOf(host.port()));
                form.tls.setSelected(false); // der Schein-Host spricht kein TLS
                form.submit();
            });
            TerminalSession session = deck.sessions().all().get(0);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            waitFor(() -> session.state() == SessionState.CONNECTED, until);
            JComponent screen = session.view(JComponent.class).orElseThrow();
            ready(session);

            // Scrollrad runter = F8 (Vorgabe aus MainframeMate).
            SwingUtilities.invokeAndWait(() -> wheel(screen, 1, 0));
            waitFor(() -> host.aids().size() >= 1, until);
            assertThat(host.aids()).containsExactly(0xF8);
            Thread.sleep(TerminalWorkspace.MOUSE_DEBOUNCE_MS + 50);
            ready(session);
            // Shift+Scrollrad hoch = F10, Seitentaste „Zurück“ = F3.
            SwingUtilities.invokeAndWait(() -> wheel(screen, -1, InputEvent.SHIFT_DOWN_MASK));
            waitFor(() -> host.aids().size() >= 2, until);
            ready(session);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(TerminalWorkspace.sideButton(4))
                        .isEqualTo(com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding.MouseAction.BACK);
                // Ohne Bildschirm lässt sich kein Ereignis der Taste 4 bauen: Aktion direkt wie der Empfänger.
                assertThat(deck.terminal().mouseKey(TerminalWorkspace.sideButton(4), new MouseEvent(screen,
                        MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 10, 1, false,
                        MouseEvent.BUTTON1))).isTrue();
            });
            waitFor(() -> host.aids().size() >= 3, until);
            assertThat(host.aids()).containsExactly(0xF8, 0x7A, 0xF3);

            // Alles steht im Verlauf.
            assertThat(session.macro().steps()).containsExactly(TerminalMacro.Step.aid(AidKey.PF8),
                    TerminalMacro.Step.aid(AidKey.PF10), TerminalMacro.Step.aid(AidKey.PF3));
            SwingUtilities.invokeAndWait(() -> {
                JComponent history = deck.terminal().historyPanel(session, () -> { });
                assertThat(texts(history)).contains("Eingabe-Verlauf (3)", "F8", "F10", "F3");
            });

            // Als Lesezeichen merken: das Formular bietet es danach an.
            bookmarkName = "Testliste " + System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                deck.terminal().bookmark(session, bookmarkName);
                ConnectForm form = deck.terminal().form();
                assertThat(form.bookmarksShown()).isTrue();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < form.bookmark.getItemCount(); i++) {
                    names.add(form.bookmark.getItemAt(i).name());
                }
                assertThat(names).contains(bookmarkName);
            });
            TerminalBookmarks.Bookmark saved = new TerminalBookmarks(AppState.shared()).all().stream()
                    .filter(bookmark -> bookmark.name().equals(bookmarkName)).findFirst().orElseThrow();
            assertThat(saved.profile().port()).isEqualTo(host.port());
            assertThat(saved.steps()).hasSize(3);

            // Aus einem Lesezeichen geöffnet und neu gemerkt: dessen Makro steht davor.
            String second = bookmarkName + " B";
            TerminalSession replayed = deck.sessions().open(session.profile(), null,
                    List.of(TerminalMacro.Step.aid(AidKey.PF7)));
            SwingUtilities.invokeAndWait(() -> deck.terminal().bookmark(replayed, second));
            TerminalBookmarks store = new TerminalBookmarks(AppState.shared());
            assertThat(store.all().stream().filter(b -> b.name().equals(second)).findFirst().orElseThrow().steps())
                    .startsWith(TerminalMacro.Step.aid(AidKey.PF7));
            store.remove(second);
            replayed.disconnect();
            waitFor(() -> replayed.note().map(note -> note.startsWith("Makro ")).orElse(false)
                    || replayed.state().ended(), until);
            SwingUtilities.invokeAndWait(() -> deck.terminal().closeSessionForTest(replayed));

            // Getrennt: „Verbinden“ öffnet dieselbe Verbindung neu.
            session.disconnect();
            SwingUtilities.invokeAndWait(() -> deck.terminal().reconnect(session));
            waitFor(() -> deck.sessions().all().size() == 1 && deck.sessions().all().get(0) != session, until);
            TerminalSession fresh = deck.sessions().all().get(0);
            waitFor(() -> fresh.state() == SessionState.CONNECTED, until);
            assertThat(fresh.state()).isEqualTo(SessionState.CONNECTED);
            assertThat(fresh.profile()).isEqualTo(session.profile());
            SwingUtilities.invokeAndWait(() -> assertThat(deck.terminal().selected()).isSameAs(fresh));
        }
    }

    /** Wartet, bis der Host fertig geantwortet hat (sonst verwirft OpenTerm die nächste Taste still). */
    private static void ready(TerminalSession session) throws InterruptedException {
        assertThat(session.view(com.aresstack.spaceharvester.deck.terminal.TerminalKeyboard.class).orElseThrow()
                .awaitUnlocked(java.time.Duration.ofSeconds(10))).isTrue();
    }

    private static void wheel(JComponent screen, int rotation, int modifiers) {
        MouseWheelEvent event = new MouseWheelEvent(screen, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                modifiers, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
        for (var listener : screen.getMouseWheelListeners()) {
            listener.mouseWheelMoved(event);
        }
    }

    private static List<String> texts(Component component) {
        List<String> found = new ArrayList<>();
        if (component instanceof JLabel label && label.getText() != null) {
            found.add(label.getText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                found.addAll(texts(child));
            }
        }
        return found;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, long until) throws Exception {
        while (System.nanoTime() < until) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
