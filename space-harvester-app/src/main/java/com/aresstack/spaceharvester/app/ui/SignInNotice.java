package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Der Hinweis in der Statuszeile des Hauptfensters, solange eine Quelle einen Einmalcode verlangt (ADR 0018): „Confluence
 * verlangt einen Einmalcode“, rot und klickbar; der Klick öffnet den Reiter „Aufträge“ mit dem Codefeld dieser
 * Anforderung. Gespeist allein aus {@link SignInRequests#onChange} und {@link SignInRequests#pending()}: erscheint mit
 * der ersten offenen Anforderung, nennt bei mehreren alle Quellen und verschwindet, sobald keine mehr offen ist
 * (angenommen, abgelehnt oder abgelaufen). Der Code selbst steht hier nie. Nur auf dem EDT.
 *
 * <p>Läuft eine Anforderung ab, auf die kein Auftrag wartet (Lesen auf Abruf), meldet der Core das nicht von sich aus:
 * er räumt sie erst beim nächsten Zugriff weg. Darum fragt der Hinweis genau einmal zum Ablauf der ältesten offenen
 * Anforderung nach ({@link #expiry}); verlängert ein weiterer Leser sie, frühestens eine Wartezeit später wieder.</p>
 */
final class SignInNotice extends JPanel {

    static final String SUFFIX_ONE = " verlangt einen Einmalcode";
    static final String SUFFIX_MANY = " verlangen einen Einmalcode";
    static final String TOOLTIP = "Zum Codefeld unter „Aufträge“";

    private final Supplier<SignInRequests> source;
    private final Function<String, String> sourceName;
    private final Consumer<SignInRequest> onClick;
    private final JLabel label = new JLabel();
    private final Font plain = Comic.semiBold(13f);
    private final Font underlined = plain.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
    private List<SignInRequest> shown = List.of();
    private SignInRequests watched;
    private AutoCloseable subscription;
    /** Fragt zum Ablauf der ältesten offenen Anforderung nach; ohne offene Anforderung steht er. */
    private final Timer expiry = new Timer(1, e -> refresh());

    /** @param onClick öffnet das Codefeld der ersten offenen Anforderung */
    SignInNotice(AppContext context, Consumer<SignInRequest> onClick) {
        this(() -> {
            AppRuntime runtime = context.runtime();
            return runtime == null ? null : runtime.workspace().signIns();
        }, sourceId -> {
            AppRuntime runtime = context.runtime();
            if (runtime != null) {
                for (HarvestSource source : runtime.workspace().sources()) {
                    if (source.id().equals(sourceId)) {
                        return source.title() == null || source.title().isBlank() ? sourceId : source.title();
                    }
                }
            }
            return sourceId;
        }, onClick);
        context.onChange(ignored -> refresh());
    }

    /**
     * @param source     die Anforderungen der aktuellen Laufzeit, {@code null} ohne Laufzeit
     * @param sourceName Anzeigename einer Quelle zu ihrer Kennung
     */
    SignInNotice(Supplier<SignInRequests> source, Function<String, String> sourceName,
                 Consumer<SignInRequest> onClick) {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.source = Objects.requireNonNull(source, "source");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.onClick = Objects.requireNonNull(onClick, "onClick");
        setOpaque(false);
        setVisible(false);
        label.setFont(plain);
        label.setForeground(Comic.RED);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText(TOOLTIP);
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
        expiry.setRepeats(false);
        refresh();
    }

    /** Zeigt die jetzt offenen Anforderungen; ohne eine ist der Hinweis unsichtbar. */
    void refresh() {
        SignInRequests requests = watch();
        List<SignInRequest> pending = requests == null ? List.of() : requests.pending();
        shown = pending;
        armExpiry(requests, pending);
        if (pending.isEmpty()) {
            if (isVisible()) {
                setVisible(false);
                revalidateParent();
            }
            return;
        }
        List<String> names = new ArrayList<>();
        for (SignInRequest request : pending) {
            String name = sourceName.apply(request.sourceId());
            if (name == null || name.isBlank()) {
                name = request.service().isBlank() ? request.sourceId() : request.service();
            }
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        label.setText(String.join(", ", names) + (names.size() == 1 ? SUFFIX_ONE : SUFFIX_MANY));
        if (!isVisible()) {
            setVisible(true);
            revalidateParent();
        }
    }

    /**
     * Stellt die Nachfrage auf den Ablauf der ältesten offenen Anforderung (seit + Wartezeit). Ist der schon
     * vorbei und die Anforderung trotzdem noch offen (ein weiterer Leser hat sie verlängert), eine Wartezeit später.
     */
    private void armExpiry(SignInRequests requests, List<SignInRequest> pending) {
        expiry.stop();
        if (requests == null || pending.isEmpty() || requests.waitTime().isZero()) {
            return;
        }
        Instant now = Instant.now();
        Instant earliest = pending.stream().map(SignInRequest::since).min(Instant::compareTo).orElse(now);
        Duration untilExpiry = Duration.between(now, earliest.plus(requests.waitTime()));
        if (untilExpiry.isNegative()) {
            untilExpiry = requests.waitTime();
        }
        expiry.setInitialDelay((int) Math.min(Integer.MAX_VALUE, untilExpiry.toMillis() + 1000));
        expiry.start();
    }

    /** Für Tests: die Nachfrage zum Ablauf. */
    Timer expiryTimer() {
        return expiry;
    }

    /** Öffnet das Codefeld der ersten offenen Anforderung. */
    void open() {
        if (!shown.isEmpty()) {
            onClick.accept(shown.get(0));
        }
    }

    String text() {
        return label.getText();
    }

    JLabel label() {
        return label;
    }

    /** Hört auf die Anforderungen der aktuellen Laufzeit; nach einem Neustart auf die der neuen. */
    private SignInRequests watch() {
        SignInRequests current = source.get();
        if (current != watched) {
            close(subscription);
            subscription = null;
            watched = current;
            if (current != null) {
                subscription = current.onChange(() -> SwingUtilities.invokeLater(this::refresh));
            }
        }
        return current;
    }

    @Override
    public void removeNotify() {
        expiry.stop();
        super.removeNotify();
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
