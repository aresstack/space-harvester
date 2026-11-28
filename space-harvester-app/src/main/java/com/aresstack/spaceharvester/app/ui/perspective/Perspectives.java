package com.aresstack.spaceharvester.app.ui.perspective;

import com.aresstack.spaceharvester.app.ui.controls.Starfield;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Die Perspektiven des Fensters in der Reihenfolge des Umschalters und die gerade gewählte. Ein Wechsel meldet sich
 * über {@link #onChange}; wer wechselt, ruft nur {@link #select} – das Hauptfenster tauscht daraufhin Navigation,
 * Arbeitsfläche und linke Leiste aus (#1141).
 */
public final class Perspectives {

    public static final String HARVESTER = "harvester";
    public static final String COMMAND_DECK = "command-deck";
    public static final String PROVING_GROUND = "proving-ground";
    public static final String MISSION_CONTROL = "mission-control";

    private final List<Perspective> all = new ArrayList<>();
    private final List<Consumer<Perspective>> listeners = new ArrayList<>();
    private Perspective current;

    public Perspectives add(Perspective perspective) {
        if (find(perspective.id()).isPresent()) {
            throw new IllegalArgumentException("Perspektive doppelt: " + perspective.id());
        }
        all.add(perspective);
        if (current == null) {
            // Die erste ist die Startperspektive; wer schon zuhört (etwa der Titel), erfährt sie.
            current = perspective;
            listeners.forEach(listener -> listener.accept(perspective));
        }
        return this;
    }

    public List<Perspective> all() {
        return List.copyOf(all);
    }

    public Perspective current() {
        return current;
    }

    public boolean isCurrent(String id) {
        return current != null && current.id().equals(id);
    }

    public Optional<Perspective> find(String id) {
        return all.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Wählt die Perspektive; dieselbe noch einmal zu wählen tut nichts. */
    public void select(String id) {
        Perspective next = find(id).orElseThrow(() -> new IllegalArgumentException("Unbekannte Perspektive: " + id));
        if (next == current) {
            return;
        }
        Perspective previous = current;
        current = next;
        listeners.forEach(listener -> listener.accept(next));
        previous.onHidden().run();
        next.onShown().run();
    }

    public void onChange(Consumer<Perspective> listener) {
        listeners.add(listener);
    }

    /**
     * Mission Control (früher Mission Protocol, #1141) ist zurückgestellt: keine Workflow-Engine, keine Bedienung, nur
     * ein Blick aus dem Cockpit auf vorbeiziehende Sterne. Die Animation läuft nur, solange die Fläche zu sehen ist.
     */
    public static Perspective missionControl() {
        Starfield stars = new Starfield(Starfield.Mode.WARP)
                .headline("MISSION CONTROL")
                .caption("In Vorbereitung · Workflows definieren, ausführen und überwachen")
                .cockpit(true);
        quietMouseNavigation(stars);
        return Perspective.of(MISSION_CONTROL, "Mission Control",
                "Workflows definieren, ausführen und überwachen", false, null, stars, null);
    }

    /**
     * Eine Perspektive ohne Verlauf nimmt die Maustasten Zurück/Vor trotzdem für sich, damit die verdeckte Leseansicht
     * des Space Harvesters nicht unsichtbar blättert.
     */
    private static void quietMouseNavigation(javax.swing.JComponent workspace) {
        com.aresstack.spaceharvester.app.ui.controls.MouseNavigation.install(workspace, () -> { }, () -> { });
    }
}
