package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.source.network.ProxyRoutes;
import com.aresstack.spaceharvester.source.network.ProxyRoutes.PacState;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Der Hinweis in der Statuszeile des Hauptfensters, solange das Erneuern des Proxy-Skripts (PAC) scheitert und das
 * zuletzt gültige Skript weiterläuft (Angelo, 16.11.2026: „Wie erkenne ich, dass ich Probleme habe?“): „Proxy-Skript
 * seit 16.11.2026 17:03 nicht erneuert“, orange und klickbar; der Klick zeigt den Grund in einem Overlay, der Tooltip
 * nennt ihn ebenso. Gespeist allein aus {@link ProxyRoutes#pacState()} und {@link ProxyRoutes#onPacChange}, kein
 * Abfragen (ADR 0027). Nur auf dem EDT.
 */
final class ProxyNotice extends JPanel {

    static final String PREFIX = "Proxy-Skript seit ";
    static final String SUFFIX = " nicht erneuert";
    static final String TITLE = "Proxy-Skript nicht erneuert";

    /** Der PAC-Zustand und seine Änderungsmeldungen; produktiv {@link ProxyRoutes}, in Tests ein Fake. */
    interface Source {
        PacState pacState();

        AutoCloseable onPacChange(Runnable listener);

        static Source of(ProxyRoutes routes) {
            return new Source() {
                @Override
                public PacState pacState() {
                    return routes.pacState();
                }

                @Override
                public AutoCloseable onPacChange(Runnable listener) {
                    return routes.onPacChange(listener);
                }
            };
        }
    }

    private final Supplier<Source> source;
    private final JLabel label = new JLabel();
    private final Font plain = Comic.semiBold(13f);
    private final Font underlined = plain.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
    private PacState shown = PacState.NONE;
    private Source watched;
    private AutoCloseable subscription;

    ProxyNotice(AppContext context) {
        this(() -> {
            AppRuntime runtime = context.runtime();
            return runtime == null ? null : Source.of(runtime.workspace().proxyRoutes());
        });
        context.onChange(ignored -> refresh());
    }

    /** @param source der PAC-Zustand der aktuellen Laufzeit, {@code null} ohne Laufzeit */
    ProxyNotice(Supplier<Source> source) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.source = Objects.requireNonNull(source, "source");
        setOpaque(false);
        setVisible(false);
        label.setFont(plain);
        label.setForeground(Comic.ORANGE);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                open();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                label.setFont(underlined);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                label.setFont(plain);
            }
        });
        add(label);
        refresh();
    }

    /** Zeigt den jetzigen Zustand; solange das Skript nicht veraltet ist, ist der Hinweis unsichtbar. */
    void refresh() {
        Source current = watch();
        PacState state = current == null ? PacState.NONE : current.pacState();
        shown = state;
        if (!state.stale()) {
            if (isVisible()) {
                setVisible(false);
                revalidateParent();
            }
            return;
        }
        label.setText(PREFIX + (state.failingSince() == null ? "kurzem" : Texts.time(state.failingSince())) + SUFFIX);
        label.setToolTipText(state.description());
        if (!isVisible()) {
            setVisible(true);
            revalidateParent();
        }
    }

    /** Zeigt den Grund in einem Overlay über dem Hinweis. */
    void open() {
        if (!shown.stale() || !label.isShowing()) {
            return;
        }
        InfoPopup popup = new InfoPopup().text(TITLE, shown.description() == null ? "" : shown.description());
        popup.showAt(label, 0, -popup.sizeFor(InfoPopup.MAX_HEIGHT).height);
    }

    String text() {
        return label.getText();
    }

    JLabel label() {
        return label;
    }

    /** Hört auf die Route der aktuellen Laufzeit; nach einem Neustart auf die der neuen. */
    private Source watch() {
        Source current = source.get();
        if (current != watched) {
            close(subscription);
            subscription = null;
            watched = current;
            if (current != null) {
                subscription = current.onPacChange(() -> SwingUtilities.invokeLater(this::refresh));
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

    private static void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Eine bereits beendete Laufzeit hat nichts mehr zu melden.
        }
    }
}
