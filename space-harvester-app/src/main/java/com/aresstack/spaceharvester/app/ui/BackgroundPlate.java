package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.Acquisition;
import com.aresstack.spaceharvester.app.runtime.Acquisition.Pause;
import com.aresstack.spaceharvester.app.runtime.Acquisition.SourceCounts;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Die Hintergrundbefüllung (ADR 0016) unter Status: in Worten, ob und warum sie gerade lädt oder pausiert, dazu
 * Warteschlange, laufende Abrufe, Geladenes und Gescheitertes, gesamt und je Quelle. Die Zahlen kommen aus dem
 * Beschaffungsplaner des Core ({@code source}, außerhalb des EDT abgefragt); ohne Laufzeit ist die Tafel leer. Solange
 * sie sichtbar ist, fragt sie alle {@value #POLL_MILLIS} ms neu. Nur auf dem EDT.
 */
final class BackgroundPlate extends JPanel {

    static final int POLL_MILLIS = 2000;

    private final Supplier<Optional<Acquisition>> source;
    private final Facts facts = new Facts();
    private final Timer poll = new Timer(POLL_MILLIS, e -> refresh());
    private boolean loading;
    private Acquisition shown;

    /** @param source der Stand des Planers, leer ohne Laufzeit oder ohne Planer; blockiert, nie auf dem EDT gerufen */
    BackgroundPlate(Supplier<Optional<Acquisition>> source) {
        super(new BorderLayout());
        this.source = Objects.requireNonNull(source, "source");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        add(facts, BorderLayout.CENTER);
        poll.setRepeats(true);
        addHierarchyListener(e -> {
            if (isShowing()) {
                refresh();
                poll.start();
            } else {
                poll.stop();
            }
        });
        render(null);
    }

    /** Fragt den Stand im Hintergrund ab; nie zwei Abfragen zugleich. */
    void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        Background.run(source::get, current -> {
            loading = false;
            render(current.orElse(null));
        }, failure -> {
            loading = false;
            render(null);
        });
    }

    /** Zeigt den Stand; {@code null} heißt: kein Planer erreichbar. */
    void render(Acquisition status) {
        if (Objects.equals(status, shown) && status != null) {
            return;
        }
        shown = status;
        facts.reset();
        if (status == null) {
            facts.add("Zustand", "noch nicht gestartet", Comic.MUTED);
            revalidate();
            repaint();
            return;
        }
        facts.add("Zustand", stage(status), tone(status));
        facts.add("Warteschlange", Texts.count(status.queued(), "Seite", "Seiten")
                + (status.foregroundQueued() > 0 ? " · davon " + status.foregroundQueued() + " für Suche oder Aktion"
                : ""));
        facts.add("Lädt gerade", status.inFlight() == 0 ? "nichts"
                : status.foregroundRunning() + " für Suche oder Aktion · " + status.backgroundRunning()
                + " im Hintergrund (höchstens " + status.parallelism() + ")");
        facts.add("Geladen", status.loaded() + (status.promoted() > 0 ? " · " + status.promoted()
                + " vorgezogen, weil eine Suche sie brauchte" : ""));
        facts.add("Fehlgeschlagen", String.valueOf(status.failed()), status.failed() > 0 ? Comic.ORANGE : Comic.INK);
        facts.add("Letzte Prüfung des Katalogs", Texts.time(status.lastScan()));
        for (SourceCounts counts : status.sources()) {
            facts.add(counts.sourceId(), counts.queued() + " wartend · " + counts.loaded() + " geladen"
                    + (counts.failed() > 0 ? " · " + counts.failed() + " fehlgeschlagen" : ""),
                    counts.failed() > 0 ? Comic.ORANGE : Comic.INK);
        }
        revalidate();
        repaint();
    }

    /** Die Stufe in Worten. */
    static String stage(Acquisition status) {
        if (!status.enabled() || status.pause() == Pause.DISABLED) {
            return "abgeschaltet (Einstellungen › Cache)";
        }
        return switch (status.pause()) {
            case NONE -> status.backgroundRunning() > 0 ? "lädt im Hintergrund"
                    : status.queued() > 0 ? "bereit, Seiten warten" : "bereit";
            case FOREGROUND -> "pausiert: eine Suche lädt Seiten";
            case USER_ACTION -> "pausiert: eine Aktion hat Vorrang";
            case COOLDOWN -> "pausiert: wartet kurz nach der letzten Aktion";
            case IDLE -> "nichts zu laden: alle Seiten aktuell";
            case CLOSED -> "beendet";
            case DISABLED -> "abgeschaltet (Einstellungen › Cache)";
        };
    }

    private static Color tone(Acquisition status) {
        if (!status.enabled() || status.pause() == Pause.DISABLED || status.pause() == Pause.CLOSED) {
            return Comic.MUTED;
        }
        return status.pause() == Pause.NONE || status.pause() == Pause.IDLE ? Comic.GREEN : Comic.ORANGE;
    }

    // ---- für Tests

    Facts facts() {
        return facts;
    }
}
