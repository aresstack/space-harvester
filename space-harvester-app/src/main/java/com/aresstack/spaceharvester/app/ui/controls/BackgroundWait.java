package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.observe.Subscription;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Wartet auf einen Hintergrundauftrag (Angelo 21.11.2026): zeigt den drehenden Ring eines {@link BusyOverlay}, bis die
 * abonnierte Hintergrundgruppe fertig ist, nimmt ihn dann weg und meldet das Ergebnis. Kein Zeitgeber, kein Abfragen:
 * die Meldung kommt vom Core ({@code Workspace#whenBackgroundFinished}). Alle Aufrufe auf dem Event Dispatch Thread.
 *
 * <pre>{@code
 * wait.follow(group.groupId(), "Nachbarseiten werden geladen …", done -> zeigeTreffer());
 * }</pre>
 */
public final class BackgroundWait {

    private final BusyOverlay overlay;
    private final BiFunction<String, Consumer<BackgroundGroup>, Subscription> subscribe;
    private Subscription current = Subscription.NONE;
    private String followed;
    /** Zählt jedes {@link #follow}; eine eingereihte Meldung eines ersetzten Wartens erkennt sich daran. */
    private long generation;

    /**
     * @param subscribe meldet einmal das Ende einer Gruppe auf dem EDT, etwa {@code workspace::whenBackgroundFinished}
     */
    public BackgroundWait(BusyOverlay overlay, BiFunction<String, Consumer<BackgroundGroup>, Subscription> subscribe) {
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.subscribe = Objects.requireNonNull(subscribe, "subscribe");
    }

    /**
     * Zeigt den Ring mit {@code text}, bis die Gruppe fertig ist; dann verschwindet er und {@code done} läuft. Ein
     * neuer Aufruf ersetzt den vorigen, dessen Ende dann nichts mehr auslöst.
     */
    public void follow(String groupId, String text, Consumer<BackgroundGroup> done) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(done, "done");
        stop();
        long mine = ++generation;
        followed = groupId;
        overlay.setText(text);
        overlay.setBusy(true);
        current = subscribe.apply(groupId, group -> {
            if (mine != generation || followed == null) {
                return;
            }
            followed = null;
            overlay.setBusy(false);
            done.accept(group);
        });
    }

    /** Hört auf zu warten und nimmt den Ring weg, ohne Meldung. */
    public void stop() {
        current.close();
        current = Subscription.NONE;
        generation++;
        followed = null;
        overlay.setBusy(false);
    }

    /** Ob gerade auf eine Gruppe gewartet wird. */
    public boolean waiting() {
        return followed != null;
    }
}
