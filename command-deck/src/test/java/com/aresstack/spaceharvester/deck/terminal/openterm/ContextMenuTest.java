package com.aresstack.spaceharvester.deck.terminal.openterm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.Test;

/** Rechtsklick ins Terminalbild: dieselben Punkte wie in jedem Textfeld der App (Angelo, 23.11.2026). */
class ContextMenuTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void copyPasteAndSelectAllLikeEveryTextField() throws Exception {
        try (FakeTn3270Host host = new FakeTn3270Host(List.of(
                new FakeTn3270Host.Line(1, 2, "MENUE", false)), "Cp273")) {
            TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(),
                    runnable -> Thread.ofVirtual().start(runnable));
            TerminalSession session = sessions.open(TerminalProfile.of("127.0.0.1", host.port()));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (session.state() != SessionState.CONNECTED && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            OpenTermScreen screen = (OpenTermScreen) session.view(JComponent.class).orElseThrow();
            JPopupMenu menu = screen.buildMenu();
            List<String> items = new ArrayList<>();
            for (java.awt.Component part : menu.getComponents()) {
                items.add(part instanceof JMenuItem item ? item.getText() : "—");
            }
            assertThat(items).containsExactly("Kopieren", "Einfügen", "—", "Alles markieren");
            session.disconnect();
        }
    }
}
