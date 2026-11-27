package com.aresstack.spaceharvester.app.speech;

import com.aresstack.spaceharvester.app.ui.AppState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Die Spracheingabe im Chat: „erst komplett“ (Vorgabe, Text nach dem Stopp) oder „live mitschreiben“ (Wörter erscheinen
 * während des Sprechens, Angelo 26.11.2026 wie ChatGPT). Umgeschaltet per Klick auf die Wellen im Composer oder unter
 * Einstellungen › KI-Dienst › Modelle; dort stehen auch die Werte des gleitenden Fensters. Gespeichert im App-Zustand.
 *
 * <p>Whisper verarbeitet höchstens 30 Sekunden am Stück; das Live-Fenster bleibt deshalb immer darunter
 * ({@link #MAX_WINDOW_SECONDS}), siehe {@link LiveTranscript}.
 */
public final class LiveDictationSettings {

    static final String LIVE_KEY = "speech.live-dictation";
    static final String STEP_KEY = "speech.live-dictation.step-millis";
    static final String WINDOW_KEY = "speech.live-dictation.window-seconds";
    static final String FIRST_KEY = "speech.live-dictation.first-millis";

    public static final int DEFAULT_STEP_MILLIS = 1000;
    public static final int MIN_STEP_MILLIS = 200;
    public static final int MAX_STEP_MILLIS = 10_000;
    public static final int DEFAULT_WINDOW_SECONDS = 20;
    public static final int MIN_WINDOW_SECONDS = 5;
    /** Whisper sieht genau 30 Sekunden; ein längeres Fenster würde abgeschnitten. */
    public static final int MAX_WINDOW_SECONDS = 29;
    public static final int DEFAULT_FIRST_MILLIS = 1000;
    public static final int MIN_FIRST_MILLIS = 200;
    public static final int MAX_FIRST_MILLIS = 5_000;

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** Die Werte des gleitenden Fensters für eine Aufnahme. */
    public record Tuning(int stepMillis, int windowSeconds, int firstMillis) {
        public Tuning {
            stepMillis = clamp(stepMillis, MIN_STEP_MILLIS, MAX_STEP_MILLIS);
            windowSeconds = clamp(windowSeconds, MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS);
            firstMillis = clamp(firstMillis, MIN_FIRST_MILLIS, Math.min(MAX_FIRST_MILLIS, windowSeconds * 1000));
        }

        public static final Tuning DEFAULT = new Tuning(DEFAULT_STEP_MILLIS, DEFAULT_WINDOW_SECONDS,
                DEFAULT_FIRST_MILLIS);
    }

    private LiveDictationSettings() {
    }

    public static boolean live() {
        return AppState.shared().getBoolean(LIVE_KEY, false);
    }

    public static void live(boolean value) {
        AppState.shared().put(LIVE_KEY, value);
        LISTENERS.forEach(Runnable::run);
    }

    public static Tuning tuning() {
        AppState state = AppState.shared();
        return new Tuning(state.getInt(STEP_KEY, DEFAULT_STEP_MILLIS), state.getInt(WINDOW_KEY, DEFAULT_WINDOW_SECONDS),
                state.getInt(FIRST_KEY, DEFAULT_FIRST_MILLIS));
    }

    public static void tuning(Tuning value) {
        AppState.shared().putAll(java.util.Map.of(STEP_KEY, String.valueOf(value.stepMillis()),
                WINDOW_KEY, String.valueOf(value.windowSeconds()), FIRST_KEY, String.valueOf(value.firstMillis())));
    }

    /** Meldet jedes Umschalten (Wellen-Klick oder Einstellungen), damit beide Stellen denselben Stand zeigen. */
    public static void listen(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Hört nicht mehr zu. */
    public static void unlisten(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Wie {@link #listen(Runnable)}, aber nur, solange {@code owner} angezeigt werden kann (in einem Fenster steckt).
     * Ohne das hielt die feste Hörerliste jede je gebaute Einstellungsseite und jeden Chat mitsamt Fenster fest.
     * Kommt {@code owner} wieder, läuft {@code listener} einmal, um verpasste Umschaltungen nachzuholen.
     */
    public static void listenWhileDisplayable(java.awt.Component owner, Runnable listener) {
        java.util.Objects.requireNonNull(owner, "owner");
        java.util.Objects.requireNonNull(listener, "listener");
        if (owner.isDisplayable()) {
            listen(listener);
        }
        owner.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) {
                return;
            }
            unlisten(listener);
            if (owner.isDisplayable()) {
                listen(listener);
                listener.run();
            }
        });
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
