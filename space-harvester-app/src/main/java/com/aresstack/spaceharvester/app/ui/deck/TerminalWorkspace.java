package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.deck.sky.CosmicClockPanel;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Arbeitsbereich „Terminal“ des Command Deck: oben die Sitzungen als Pillen mit Zustandspunkt und rechts „Neue
 * Verbindung“, in der Mitte das 3270-Bild vor dem Sternenhimmel aus MainframeMate (kosmische Uhr), unten die Aktionen
 * der gewählten Sitzung über dem gemeinsamen Statusband. Die Sitzungen gehören dem Anwendungsfall
 * {@link TerminalSessions}; Wechsel des Bereichs oder der Perspektive trennen nichts.
 */
public final class TerminalWorkspace extends JPanel {

    private static final String NEW = "new";

    private final TerminalSessions sessions;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<TerminalSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final ComposerToggleButton newPill;
    private final CosmicClockPanel sky = new CosmicClockPanel(120);
    private final ConnectForm form;
    private final JLabel status = Comic.muted(" ");
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton copy;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton paste;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton disconnect;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton close;
    private TerminalSession selected;

    public TerminalWorkspace(TerminalSessions sessions) {
        super(new BorderLayout());
        this.sessions = sessions;
        setBackground(Comic.SURFACE);
        form = new ConnectForm(sessions::open);

        // Kopfzeile: Sitzungen links, „Neue Verbindung“ als letzte Pille.
        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung",
                "Neue TN3270-Verbindung öffnen");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> select(null));
        pillGroup.add(newPill);
        pills.add(newPill);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        head.add(pills, BorderLayout.CENTER);

        // Mitte: Sternenhimmel, darin zentriert das Terminal bzw. das Formular. Er läuft nur, solange er sichtbar ist.
        sky.setLayout(new GridBagLayout());
        sky.setUseGermanNames(true);
        sky.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (sky.isShowing()) {
                    sky.start();
                } else {
                    sky.stop();
                }
            }
        });
        sky.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                fitScreen();
            }
        });
        sky.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                view(TerminalView.class).ifPresent(TerminalView::focus);
            }
        });

        // Unten: Aktionen der gewählten Sitzung, wie die Aktionszeile des Space Harvesters.
        copy = Comic.button("Kopieren", "Auswahl oder ganzes Bild kopieren (Strg+C)");
        copy.addActionListener(e -> view(TerminalView.class).ifPresent(TerminalView::copy));
        paste = Comic.button("Einfügen", "Text der Zwischenablage an der Schreibmarke eintippen (Strg+V)");
        paste.addActionListener(e -> view(TerminalView.class).ifPresent(TerminalView::paste));
        disconnect = Comic.button("Trennen", "Verbindung dieser Sitzung trennen");
        disconnect.addActionListener(e -> {
            if (selected != null) {
                selected.disconnect();
            }
        });
        close = Comic.button("Schließen", "Getrennte Sitzung aus der Liste nehmen");
        close.addActionListener(e -> {
            if (selected != null) {
                closeSession(selected);
            }
        });
        JPanel actions = Comic.row(copy, paste, disconnect, close);
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        foot.add(status, BorderLayout.CENTER);
        foot.add(actions, BorderLayout.EAST);

        add(head, BorderLayout.NORTH);
        add(sky, BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);

        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        sessions.all().forEach(this::added);
        select(null);
    }

    private void added(TerminalSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        ComposerToggleButton pill = new ComposerToggleButton(dot(session.state()), session.profile().label(),
                session.state().label());
        pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        pill.getAccessibleContext().setAccessibleName(session.profile().label());
        pill.addActionListener(e -> select(session));
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        select(session);
        pills.revalidate();
        pills.repaint();
    }

    private void changed(TerminalSession session) {
        ComposerToggleButton pill = pillOf.get(session);
        if (pill != null) {
            pill.setIcon(dot(session.state()));
            pill.setToolTipText(session.failure().map(reason -> session.state().label() + ": " + reason)
                    .orElse(session.state().label()));
        }
        if (session == selected) {
            showState();
            if (session.state() == SessionState.CONNECTED) {
                view(TerminalView.class).ifPresent(TerminalView::focus);
            }
        }
    }

    /** Zeigt die Sitzung (oder mit {@code null} das Formular „Neue Verbindung“). */
    void select(TerminalSession session) {
        selected = session;
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        sky.removeAll();
        Component center = session == null ? form : session.view(JComponent.class).orElse(null);
        if (center != null) {
            sky.add(center);
        }
        sky.revalidate();
        sky.repaint();
        fitScreen();
        showState();
        if (session == null) {
            SwingUtilities.invokeLater(form::focusHost);
        } else {
            SwingUtilities.invokeLater(() -> view(TerminalView.class).ifPresent(TerminalView::focus));
        }
    }

    private void closeSession(TerminalSession session) {
        sessions.close(session);
        ComposerToggleButton pill = pillOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
    }

    private void showState() {
        TerminalSession session = selected;
        boolean live = session != null && !session.state().ended();
        copy.setEnabled(session != null && session.state() == SessionState.CONNECTED);
        paste.setEnabled(session != null && session.state() == SessionState.CONNECTED);
        disconnect.setEnabled(live);
        close.setEnabled(session != null && session.state().ended());
        if (session == null) {
            status.setText("Keine Sitzung gewählt – Host eintragen und verbinden");
            status.setForeground(Comic.MUTED);
            return;
        }
        String text = session.profile().label() + " · " + session.state().label()
                + session.failure().map(reason -> " – " + reason).orElse("");
        status.setText(text);
        status.setForeground(session.state() == SessionState.FAILED ? Comic.RED : Comic.MUTED);
    }

    private void fitScreen() {
        view(TerminalView.class).ifPresent(view -> view.fit(sky.getWidth() - 24, sky.getHeight() - 24));
    }

    private <T> Optional<T> view(Class<T> type) {
        return selected == null ? Optional.empty() : selected.view(type);
    }

    /** Die gewählte Sitzung (für Tests). */
    TerminalSession selected() {
        return selected;
    }

    ConnectForm form() {
        return form;
    }

    String statusText() {
        return status.getText();
    }

    /** Zustandspunkt: grün verbunden, blau im Aufbau, rot Fehler, grau getrennt (Ampel der App). */
    static Icon dot(SessionState state) {
        Color color = switch (state) {
            case CONNECTED -> Comic.GREEN;
            case CONNECTING -> Comic.BLUE;
            case FAILED -> Comic.RED;
            case DISCONNECTED -> Comic.MUTED;
        };
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x + 2, y + 2, 8, 8);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }
        };
    }
}
