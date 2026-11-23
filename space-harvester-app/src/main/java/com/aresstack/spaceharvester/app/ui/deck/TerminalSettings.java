package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.controls.FunctionKeyBar;
import com.aresstack.spaceharvester.deck.terminal.LoginScript;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Die Einstellungen des Terminals aus MainframeMate (Tn3270SettingsPanel), gemerkt in {@code app-state.json}:
 * Aktionsverzögerung, Deckkraft der F-Tasten-Leiste, Sternenhimmel (an/aus, Zeitfaktor, deutsche Namen) und die
 * Mausbelegung. Änderungen gelten sofort für alle offenen Sitzungen (Beobachter, kein Abfragen).
 */
public final class TerminalSettings {

    static final String DELAY_KEY = "deck.terminal.action-delay-ms";
    static final String OPACITY_KEY = "deck.terminal.fkey-opacity";
    static final String SKY_KEY = "deck.terminal.sky";
    static final String SKY_FACTOR_KEY = "deck.terminal.sky-factor";
    static final String SKY_GERMAN_KEY = "deck.terminal.sky-german";
    static final String MOUSE_KEY = "deck.terminal.mouse";
    static final String RECONNECT_KEY = "deck.terminal.auto-reconnect";

    public static final int DEFAULT_DELAY_MS = (int) LoginScript.DEFAULT_DELAY.toMillis();
    public static final int DEFAULT_SKY_FACTOR = 120;

    private static TerminalSettings shared;

    private final AppState state;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    TerminalSettings(AppState state) {
        this.state = state;
    }

    /** Die Einstellungen der laufenden App. */
    public static synchronized TerminalSettings shared() {
        if (shared == null) {
            shared = new TerminalSettings(AppState.shared());
        }
        return shared;
    }

    /** Pause nach jedem Schritt bei Anmeldung und Makro (MainframeMate: tn3270ActionDelayMs, Vorgabe 1000 ms). */
    public int actionDelayMs() {
        return Math.max(0, Math.min(10_000, state.getInt(DELAY_KEY, DEFAULT_DELAY_MS)));
    }

    public Duration actionDelay() {
        return Duration.ofMillis(actionDelayMs());
    }

    /** Deckkraft der F-Tasten-Leiste in Prozent (0 unsichtbar … 100 deckend, Vorgabe 50). */
    public int functionKeyOpacity() {
        return Math.max(0, Math.min(100, state.getInt(OPACITY_KEY, FunctionKeyBar.DEFAULT_OPACITY)));
    }

    /** Sternenhimmel hinter dem Terminal; aus = klassisch schwarz. */
    public boolean sky() {
        return state.getBoolean(SKY_KEY, true);
    }

    /** Zeitfaktor des Himmels (1 = Echtzeit, 120 = 2 Minuten ≈ 4 Stunden). */
    public int skyFactor() {
        return Math.max(1, Math.min(10_000, state.getInt(SKY_FACTOR_KEY, DEFAULT_SKY_FACTOR)));
    }

    /** Sternbilder auf Deutsch. */
    public boolean skyGerman() {
        return state.getBoolean(SKY_GERMAN_KEY, true);
    }

    /** Abgerissene Verbindung automatisch neu aufbauen und anmelden (Vorgabe an: das Terminal soll nicht tot bleiben). */
    public boolean autoReconnect() {
        return state.getBoolean(RECONNECT_KEY, true);
    }

    /** Schaltet den Neuaufbau um und meldet die Änderung. */
    public void autoReconnect(boolean enabled) {
        state.putAll(Map.of(RECONNECT_KEY, String.valueOf(enabled)));
        listeners.forEach(Runnable::run);
    }

    /** Maus → F-Taste; ohne Eintrag die Vorgaben aus MainframeMate. */
    public List<MouseFkeyBinding> mouse() {
        return MouseFkeyBinding.decode(state.get(MOUSE_KEY));
    }

    /** Speichert alle Werte auf einmal und meldet die Änderung. */
    public void save(int delayMs, int opacity, boolean sky, int skyFactor, boolean skyGerman,
                     List<MouseFkeyBinding> mouse) {
        state.putAll(Map.of(DELAY_KEY, String.valueOf(delayMs), OPACITY_KEY, String.valueOf(opacity),
                SKY_KEY, String.valueOf(sky), SKY_FACTOR_KEY, String.valueOf(skyFactor),
                SKY_GERMAN_KEY, String.valueOf(skyGerman), MOUSE_KEY, MouseFkeyBinding.encode(mouse)));
        listeners.forEach(Runnable::run);
    }

    /** Meldet jede gespeicherte Änderung (auf dem Faden, der speichert). */
    public void observe(Runnable listener) {
        listeners.add(listener);
    }

    public void unobserve(Runnable listener) {
        listeners.remove(listener);
    }
}
